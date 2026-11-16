package com.citypass.movilidad.service;

import com.citypass.movilidad.eventbus.publish.EventPublisher;
import com.citypass.movilidad.model.ClimateAlert;
import com.citypass.movilidad.model.ClimateTripNotification;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.Trip;
import com.citypass.movilidad.model.enums.AggregateType;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.model.enums.TripStatus;
import com.citypass.movilidad.repository.ClimateTripNotificationRepository;
import com.citypass.movilidad.repository.StationRepository;
import com.citypass.movilidad.repository.TripRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.stream.Collectors;

/** Cambios transaccionales y eventos de salida al activar/finalizar una alerta. */
@Service
public class ClimateAlertEffects {
    private final StationRepository stations;
    private final TripRepository trips;
    private final ClimateTripNotificationRepository notifications;
    private final ClimatePolicyService policy;
    private final EventPublisher events;
    private final StationAvailabilityService availability;

    public ClimateAlertEffects(StationRepository stations, TripRepository trips,
                               ClimateTripNotificationRepository notifications,
                               ClimatePolicyService policy, EventPublisher events,
                               StationAvailabilityService availability) {
        this.stations = stations;
        this.trips = trips;
        this.notifications = notifications;
        this.policy = policy;
        this.events = events;
        this.availability = availability;
    }

    public void applied(ClimateAlert alert, Instant now) {
        List<Station> affected = affectedStations(alert);
        int notified = alert.getSeverity() == ClimateAlert.Severity.CRITICA
                ? notifyActiveTrips(alert, affected, now) : 0;
        boolean blocked = alert.getSeverity() == ClimateAlert.Severity.ALTA
                || alert.getSeverity() == ClimateAlert.Severity.CRITICA;
        List<AffectedStation> blockedStations = blocked ? affected.stream()
                .filter(station -> station.getStatus() == StationStatus.ACTIVE)
                .map(ClimateAlertEffects::toEventStation).toList()
                : List.of();
        events.publish("AlertaClimaticaAplicada", AggregateType.CLIMATE_ALERT, alert.getId(),
                new AppliedEvent(alert.getExternalAlertId(), alert.getSeverity().name(), "APLICADA",
                        actions(alert.getSeverity()), blockedStations, blockedStations.size(), notified,
                        now.toEpochMilli(), null));
    }

    public void lifted(ClimateAlert alert, Instant now) {
        List<AffectedStation> restored = affectedStations(alert).stream()
                .filter(station -> station.getStatus() == StationStatus.ACTIVE)
                .filter(station -> policy.effectiveAlert(station)
                        .map(active -> active.getSeverity().ordinal() < ClimateAlert.Severity.ALTA.ordinal())
                        .orElse(true))
                .map(ClimateAlertEffects::toEventStation)
                .toList();
        events.publish("AlertaClimaticaLevantada", AggregateType.CLIMATE_ALERT, alert.getId(),
                new LiftedEvent(alert.getExternalAlertId(), restored, now.toEpochMilli()));
    }

    public void failed(ClimateAlert alert, Instant now, String detail) {
        events.publish("AlertaClimaticaAplicada", AggregateType.CLIMATE_ALERT, alert.getId(),
                new AppliedEvent(alert.getExternalAlertId(), alert.getSeverity().name(), "FALLIDA",
                        List.of(), List.of(), 0, 0, now.toEpochMilli(), detail));
    }

    private List<Station> affectedStations(ClimateAlert alert) {
        Set<String> ids = alert.getNeighborhoods().stream().map(neighborhood -> neighborhood.getId())
                .collect(Collectors.toSet());
        return ids.isEmpty() ? List.of() : stations.findByNeighborhood_IdInAndDeletedAtIsNull(ids);
    }

    private int notifyActiveTrips(ClimateAlert alert, List<Station> affected, Instant now) {
        if (affected.isEmpty()) {
            return 0;
        }
        Set<String> affectedNeighborhoods = alert.getNeighborhoods().stream()
                .map(neighborhood -> neighborhood.getId()).collect(Collectors.toSet());
        List<Station> candidates = stations.findByStatusAndDeletedAtIsNull(StationStatus.ACTIVE);
        Map<Long, Integer> capacityByStation = candidates.stream()
                .collect(Collectors.toMap(Station::getId, Station::getCapacity));
        Map<Long, StationAvailability> freeSlots = availability.availabilityFor(capacityByStation);
        List<Station> returnable = candidates.stream()
                .filter(station -> freeSlots.get(station.getId()).availableSlots() > 0).toList();
        List<Long> originIds = affected.stream().map(Station::getId).toList();
        List<Trip> activeTrips = trips.findByStatusAndOriginStation_IdIn(TripStatus.ACTIVE, originIds);
        for (Trip trip : activeTrips) {
            ClimateTripNotification notification = new ClimateTripNotification();
            notification.setAlert(alert);
            notification.setTrip(trip);
            notification.setSuggestedStation(suggestedStation(trip.getOriginStation(), returnable, affectedNeighborhoods));
            notification.setNotifiedAt(now);
            notifications.save(notification);
        }
        return activeTrips.size();
    }

    private Station suggestedStation(Station origin, List<Station> candidates, Set<String> affectedNeighborhoods) {
        List<Station> clear = candidates.stream()
                .filter(station -> station.getNeighborhood() != null
                        && !affectedNeighborhoods.contains(station.getNeighborhood().getId()))
                .toList();
        List<Station> pool = clear.isEmpty() ? candidates : clear;
        return pool.stream().min(Comparator.comparingDouble(station -> GeoDistance.meters(
                origin.getLatitude().doubleValue(), origin.getLongitude().doubleValue(),
                station.getLatitude().doubleValue(), station.getLongitude().doubleValue()))).orElse(null);
    }

    private static List<String> actions(ClimateAlert.Severity severity) {
        return switch (severity) {
            case BAJA -> List.of("AVISO_USUARIOS");
            case MEDIA -> List.of("AVISO_USUARIOS", "CONFIRMACION_RIESGO_AL_ALQUILAR");
            case ALTA -> List.of("AVISO_USUARIOS", "BLOQUEO_ALQUILER");
            case CRITICA -> List.of("AVISO_USUARIOS", "BLOQUEO_ALQUILER", "AVISO_DEVOLUCION_VIAJES_ACTIVOS");
        };
    }

    private static AffectedStation toEventStation(Station station) {
        String id = station.getExternalId() == null ? station.getId().toString() : station.getExternalId();
        return new AffectedStation(id, station.getName(), station.getNeighborhood().getId(),
                station.getLatitude().doubleValue(), station.getLongitude().doubleValue());
    }

    private record AffectedStation(String id, String nombre, String barrio, double latitud, double longitud) { }
    private record AppliedEvent(String alertaId, String nivelCriticidad, String estado,
                                List<String> accionesAplicadas, List<AffectedStation> estacionesBloqueadas,
                                int cantidadEstacionesBloqueadas, int viajesActivosNotificados,
                                long aplicadaEn, String detalleError) { }
    private record LiftedEvent(String alertaId, List<AffectedStation> estacionesRestablecidas,
                               long levantadaEn) { }
}
