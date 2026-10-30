package com.citypass.movilidad.service;

import com.citypass.movilidad.config.RecommendationProperties;
import com.citypass.movilidad.dto.NearbyStationResponse;
import com.citypass.movilidad.dto.StationRecommendationResponse;
import com.citypass.movilidad.model.CulturalDestination;
import com.citypass.movilidad.model.enums.RecommendationPurpose;
import com.citypass.movilidad.model.enums.RecommendationStatus;
import com.citypass.movilidad.repository.CulturalDestinationRepository;
import com.citypass.movilidad.repository.DestinationRecommendationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CU-INT-01: publica la recomendación inicial, cambios por incidentes, invalidaciones
 * por cancelación y recálculos cada 15 minutos para los eventos de las próximas 24 h.
 * El envío real es asíncrono a través del outbox EDA oficial.
 */
@Service
public class DestinationRecommendationPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(DestinationRecommendationPublisher.class);

    private final CulturalDestinationRepository destinations;
    private final StationRecommendationService recommendations;
    private final NearbyStationService nearbyStations;
    private final RecommendationProperties recommendationProperties;
    private final IncidentProximityFilter incidentFilter;
    private final DestinationRecommendationEventWriter outbox;

    private final DestinationRecommendationJobRepository jobs;

    public DestinationRecommendationPublisher(CulturalDestinationRepository destinations,
                                              StationRecommendationService recommendations,
                                              NearbyStationService nearbyStations,
                                              RecommendationProperties recommendationProperties,
                                              IncidentProximityFilter incidentFilter,
                                              DestinationRecommendationEventWriter outbox,
                                              DestinationRecommendationJobRepository jobs) {
        this.destinations = destinations;
        this.recommendations = recommendations;
        this.nearbyStations = nearbyStations;
        this.recommendationProperties = recommendationProperties;
        this.incidentFilter = incidentFilter;
        this.outbox = outbox;
        this.jobs = jobs;
    }

    /** No recalcular antes de confirmar la transacción del inbox y de negocio. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterDestinationChanged(CulturalDestinationChanged change) {
        safelyPublish(change.eventId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterIncidentChanged(TrafficIncidentChanged change) {
        Instant now = Instant.now();
        int radius = recommendationProperties.candidateRadiusMeters() + incidentFilter.radiusMeters();
        for (CulturalDestination destination : upcoming(now)) {
            if (destination.getLatitude() == null || destination.getLongitude() == null) continue;
            if (GeoDistance.meters(change.latitude(), change.longitude(),
                    destination.getLatitude().doubleValue(), destination.getLongitude().doubleValue()) <= radius) {
                safelyPublish(destination.getEventId());
            }
        }
    }

    /** Recálculo regular, aun si no hubo eventos nuevos; también sirve de recuperación ante fallos. */
    @Scheduled(cron = "0 */15 * * * *")
    public void recalculateUpcoming() {
        java.util.stream.Stream.concat(jobs.pendingIds().stream(),
                upcoming(Instant.now()).stream().map(CulturalDestination::getEventId))
                .distinct().forEach(this::safelyPublish);
    }

    private List<CulturalDestination> upcoming(Instant now) {
        // Incluye eventos que ya comenzaron pero todavía no terminaron.
        // Excluye los que empiezan dentro de más de 24 horas.
        Instant horizon = now.plusSeconds(24 * 3600);
        return destinations.findByEndsAtAfterOrderByStartsAtAsc(now).stream()
                .filter(CulturalDestination::isRoutable)
                .filter(destination -> destination.getStartsAt() != null
                        && !destination.getStartsAt().isAfter(horizon))
                .toList();
    }

    private void safelyPublish(String eventId) {
        try {
            publish(eventId);
        } catch (RuntimeException ex) {
            // Un evento fallido no bloquea los demás, ni impide confirmar el offset del inbox.
            // El scheduler reintentará al recalcular los eventos próximos.
            LOG.error("No se pudo actualizar la recomendación del evento {}", eventId, ex);
        }
    }

    /** Permite ejecutar el recálculo en pruebas sin esperar al scheduler. */
    public void publish(String eventId) {
        String requestToken = jobs.token(eventId);
        CulturalDestination destination = destinations.findById(eventId).orElse(null);
        if (destination == null) return;

        String reason = invalidationReason(destination);

        if (reason != null) {
            outbox.publish(eventId, payload(eventId, null, 0, reason), requestToken);
            return;
        }

        double lat = destination.getLatitude().doubleValue();
        double lng = destination.getLongitude().doubleValue();
        List<NearbyStationResponse> candidates = nearbyStations.findNearby(
                lat, lng, recommendationProperties.candidateRadiusMeters(),
                recommendationProperties.maxCandidates());
        IncidentProximityFilter.FilterResult filtered = incidentFilter.filter(candidates);

        if (filtered.safe().isEmpty() && filtered.discarded() == 0) {
            outbox.publish(eventId, payload(eventId, null, 0,
                    "No hay estaciones cercanas disponibles para recomendar"), requestToken);
            return;
        }

        if (filtered.safe().isEmpty() && filtered.discarded() > 0) {
            outbox.publish(eventId, payload(eventId, null, filtered.discarded(),
                    "No hay estaciones seguras cerca del evento: todas están afectadas por incidentes"), requestToken);
            return;
        }

        StationRecommendationResponse response = recommendations.recommend(lat, lng, RecommendationPurpose.DROPOFF);
        String message = response.message();
        outbox.publish(eventId, payload(eventId, response, filtered.discarded(), message), requestToken);
    }

    private String invalidationReason(CulturalDestination destination) {
        if (destination.isCancelled()) return "El evento fue cancelado";
        if (!destination.isSpaceEnabled()) return "El espacio no está disponible";
        if (destination.getLatitude() == null || destination.getLongitude() == null) {
            return "El evento no tiene una ubicación disponible";
        }
        if (destination.getEndsAt() == null || !destination.getEndsAt().isAfter(Instant.now())) {
            return "El evento ya no está vigente";
        }
        return null;
    }

    private Map<String, Object> payload(String eventId, StationRecommendationResponse result,
                                        int discarded, String message) {
        boolean recommended = result != null
                && result.status() == RecommendationStatus.RECOMMENDED
                && result.station() != null;
        StationRecommendationResponse.RecommendedStation station = recommended ? result.station() : null;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventoId", eventId);
        data.put("estado", recommended ? "RECOMMENDED" : "NO_RECOMMENDATION");
        data.put("estacionId", null);
        data.put("estacion", null);
        data.put("latitud", null);
        data.put("longitud", null);
        data.put("lugaresLibres", null);
        data.put("distanciaMetros", null);
        if (station != null) {
            data.put("estacionId", station.stationId());
            data.put("estacion", station.stationName());
            data.put("latitud", station.latitude().doubleValue());
            data.put("longitud", station.longitude().doubleValue());
            data.put("lugaresLibres", station.availableSlots());
            data.put("distanciaMetros", station.distanceMeters());
        }
        data.put("mensaje", message == null ? "No hay estaciones disponibles para recomendar" : message);
        data.put("descartadasPorIncidentes", discarded);
        data.put("calculadaEn", Instant.now().toString());
        data.put("correlationId", "evento-" + eventId);
        return data;
    }
}
