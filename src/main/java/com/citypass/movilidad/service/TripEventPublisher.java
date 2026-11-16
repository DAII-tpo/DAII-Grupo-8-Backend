package com.citypass.movilidad.service;

import com.citypass.movilidad.eventbus.publish.EventPublisher;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.Trip;
import com.citypass.movilidad.model.enums.AggregateType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Registra el inicio y encola el viaje completo cuando finaliza. */
@Component
@Slf4j
public class TripEventPublisher {

    static final String EVENT_NAME = "ViajeFinalizado";
    /** Símbolo del enum CausaFinalizacionViaje del contrato para los viajes que cierra Emergencias. */
    static final String EMERGENCY_CAUSE = "EMERGENCIA";

    private final EventPublisher events;

    public TripEventPublisher(EventPublisher events) {
        this.events = events;
    }

    public void tripStarted(Trip trip) {
        log.info("Viaje iniciado: tripId={}, userId={}, bikeId={}, originStationId={}",
                trip.getId(), trip.getUser().getId(), trip.getBike().getId(), trip.getOriginStation().getId());
    }

    public void tripEnded(Trip trip) {
        Station destination = trip.getDestinationStation();
        // Una incidencia de bicicleta o un aviso SOS no identifican por sí solos un incidente vial.
        publish(trip, destination.getId().toString(), destination.getLatitude().doubleValue(),
                destination.getLongitude().doubleValue(), null, null);

        log.info("Viaje finalizado: tripId={}, userId={}, bikeId={}, destinationStationId={}, durationSeconds={}",
                trip.getId(), trip.getUser().getId(), trip.getBike().getId(),
                destination.getId(), trip.getDurationSeconds());
    }

    /**
     * Viaje que cerró Emergencias al resolver el pedido de ayuda (CLOSED_BY_EMERGENCY): causaTipo EMERGENCIA y,
     * como causaId, el código de la emergencia (EMG-…), el mismo que viaja en EmergenciaBicicleta.
     * <p>
     * El viaje termina donde se pidió ayuda, así que las coordenadas finales son las del SOS. estacionFin es la
     * estación a la que se llevó la bici, o vacía si no había ninguna con lugar (el contrato no la admite nula).
     */
    public void tripClosedByEmergency(Trip trip, String emergencyCode, double latitude, double longitude) {
        Station destination = trip.getDestinationStation();
        publish(trip, destination == null ? "" : destination.getId().toString(), latitude, longitude,
                emergencyCode, EMERGENCY_CAUSE);

        log.info("Viaje cerrado por emergencia: tripId={}, emergencia={}, destinationStationId={}, durationSeconds={}",
                trip.getId(), emergencyCode, destination == null ? null : destination.getId(),
                trip.getDurationSeconds());
    }

    private void publish(Trip trip, String endStationId, double endLatitude, double endLongitude,
                         String causeId, String causeType) {
        var origin = trip.getOriginStation();
        String tripId = trip.getId().toString();
        String endedAt = trip.getEndedAt().toString();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("viajeId", tripId);
        data.put("correlationId", "viaje-" + tripId);
        data.put("estacionInicio", origin.getId().toString());
        data.put("latitudInicio", origin.getLatitude().doubleValue());
        data.put("longitudInicio", origin.getLongitude().doubleValue());
        data.put("horaInicio", trip.getStartedAt().toString());
        data.put("estacionFin", endStationId);
        data.put("latitudFin", endLatitude);
        data.put("longitudFin", endLongitude);
        data.put("horaFinalizacion", endedAt);
        data.put("causaId", causeId);
        data.put("causaTipo", causeType);
        data.put("createdAt", endedAt);
        events.publish(EVENT_NAME, AggregateType.TRIP, tripId, data);
    }
}
