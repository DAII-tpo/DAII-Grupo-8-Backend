package com.citypass.movilidad.service;

import com.citypass.movilidad.eventbus.publish.EventPublisher;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.Trip;
import com.citypass.movilidad.model.enums.AggregateType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publica los dos eventos contratados con BI por el bus del Grupo 1.
 * Se invoca dentro de las transacciones startTrip/endTrip de TripService.
 * EventPublisher escribe en el outbox; OutboxRelay realiza el HTTP al gateway.
 */
@Component
public class TripEventPublisher {

    private final EventPublisher eventPublisher;

    public TripEventPublisher(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    public void tripStarted(Trip trip) {
        Station station = trip.getOriginStation();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", trip.getId().toString());
        data.put("estacion", station.getName());
        data.put("barrioInicio", null);
        data.put("latitud", station.getLatitude().doubleValue());
        data.put("longitud", station.getLongitude().doubleValue());
        data.put("horaInicio", trip.getStartedAt().toString());
        data.put("correlationId", "viaje-" + trip.getId());
        eventPublisher.publish("ViajeIniciado", AggregateType.TRIP, trip.getId(), data);
    }

    public void tripEnded(Trip trip) {
        Station station = trip.getDestinationStation();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", trip.getId().toString());
        data.put("estacion", station.getName());
        data.put("latitud", station.getLatitude().doubleValue());
        data.put("longitud", station.getLongitude().doubleValue());
        data.put("horaFinalizacion", trip.getEndedAt().toString());
        data.put("correlationId", "viaje-" + trip.getId());
        eventPublisher.publish("ViajeTerminado", AggregateType.TRIP, trip.getId(), data);
    }
}
