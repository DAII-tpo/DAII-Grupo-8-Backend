package com.citypass.movilidad.service;

import com.citypass.movilidad.eventbus.publish.EventPublisher;
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
    private final EventPublisher events;

    public TripEventPublisher(EventPublisher events) {
        this.events = events;
    }

    public void tripStarted(Trip trip) {
        log.info("Viaje iniciado: tripId={}, userId={}, bikeId={}, originStationId={}",
                trip.getId(), trip.getUser().getId(), trip.getBike().getId(), trip.getOriginStation().getId());
    }

    public void tripEnded(Trip trip) {
        var origin = trip.getOriginStation();
        var destination = trip.getDestinationStation();
        String tripId = trip.getId().toString();
        String endedAt = trip.getEndedAt().toString();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("viajeId", tripId);
        data.put("correlationId", "viaje-" + tripId);
        data.put("estacionInicio", origin.getId().toString());
        data.put("latitudInicio", origin.getLatitude().doubleValue());
        data.put("longitudInicio", origin.getLongitude().doubleValue());
        data.put("horaInicio", trip.getStartedAt().toString());
        data.put("estacionFin", destination.getId().toString());
        data.put("latitudFin", destination.getLatitude().doubleValue());
        data.put("longitudFin", destination.getLongitude().doubleValue());
        data.put("horaFinalizacion", endedAt);
        // Una incidencia de bicicleta o un aviso SOS no identifican por sí solos un incidente vial.
        data.put("causaId", null);
        data.put("causaTipo", null);
        data.put("createdAt", endedAt);
        events.publish("ViajeFinalizado", AggregateType.TRIP, tripId, data);

        log.info("Viaje finalizado: tripId={}, userId={}, bikeId={}, destinationStationId={}, durationSeconds={}",
                trip.getId(), trip.getUser().getId(), trip.getBike().getId(),
                destination.getId(), trip.getDurationSeconds());
    }
}
