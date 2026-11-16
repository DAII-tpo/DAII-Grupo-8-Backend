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
    private final ClimateAffectedStations affectedStations;
    private final ClimatePolicyMatrix matrix;

    public ClimateAlertEffects(StationRepository stations, TripRepository trips,
                               ClimateTripNotificationRepository notifications,
                               ClimatePolicyService policy, EventPublisher events,
                               StationAvailabilityService availability, ClimateAffectedStations affectedStations,
                               ClimatePolicyMatrix matrix) {
        this.stations = stations;
        this.trips = trips;
        this.notifications = notifications;
        this.policy = policy;
        this.events = events;
        this.availability = availability;
        this.affectedStations = affectedStations;
        this.matrix = matrix;
    }

    public void applied(ClimateAlert alert, Instant now) {
        ClimateResponse response = matrix.responseFor(alert);
        List<Station> affected = affectedStations.of(alert);
        int notified = response.notifiesActiveTrips() ? notifyActiveTrips(alert, affected, now) : 0;
        List<AffectedStation> blockedStations = response.blocksRental() ? affected.stream()
                .filter(station -> station.getStatus() == StationStatus.ACTIVE)
                .map(ClimateAlertEffects::toEventStation).toList()
                : List.of();
        events.publish("AlertaClimaticaAplicada", AggregateType.CLIMATE_ALERT, alert.getId(),
                new AppliedEvent(alert.getExternalAlertId(), alert.getSeverity().name(), "APLICADA",
                        response.publishedActions(), blockedStations, blockedStations.size(), notified,
                        now.toEpochMilli(), null));
    }

    public void lifted(ClimateAlert alert, Instant now) {
        // Solo se declara restablecida si ninguna otra alerta activa sigue bloqueando la estación.
        List<AffectedStation> restored = affectedStations.of(alert).stream()
                .filter(station -> station.getStatus() == StationStatus.ACTIVE)
                .filter(station -> policy.effective(station)
                        .map(active -> !active.response().blocksRental())
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

    private int notifyActiveTrips(ClimateAlert alert, List<Station> affected, Instant now) {
        if (affected.isEmpty()) {
            return 0;
        }
        Set<String> affectedNeighborhoods = ClimateAffectedStations.neighborhoodIds(alert);
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

    /** Estación más cercana fuera de los barrios afectados; null si no hay ninguna (nunca una afectada). */
    private Station suggestedStation(Station origin, List<Station> candidates, Set<String> affectedNeighborhoods) {
        return candidates.stream()
                .filter(station -> station.getNeighborhood() != null
                        && !affectedNeighborhoods.contains(station.getNeighborhood().getId()))
                .min(Comparator.comparingDouble(station -> GeoDistance.meters(
                        origin.getLatitude().doubleValue(), origin.getLongitude().doubleValue(),
                        station.getLatitude().doubleValue(), station.getLongitude().doubleValue())))
                .orElse(null);
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
