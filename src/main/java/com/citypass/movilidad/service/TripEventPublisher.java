package com.citypass.movilidad.service;

import com.citypass.movilidad.model.EventOutbox;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.Trip;
import com.citypass.movilidad.model.enums.AggregateType;
import com.citypass.movilidad.repository.EventOutboxRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publica el inicio y el fin de cada viaje (CU-INT-01, paso 9).
 *
 * Escribe en el outbox dentro de la misma transacción que el viaje: si el gateway
 * está caído el viaje se registra igual y el evento sale después (OutboxDispatcher).
 * Los nombres y campos son los que espera Analítica en su capa silver:
 * ViajeIniciado y ViajeTerminado, unidos por {@code id}.
 */
@Component
@Slf4j
public class TripEventPublisher {

    public static final String TRIP_STARTED = "ViajeIniciado";
    public static final String TRIP_ENDED = "ViajeTerminado";

    private final EventOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public TripEventPublisher(EventOutboxRepository outboxRepository, ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    public void tripStarted(Trip trip) {
        Station origin = trip.getOriginStation();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", String.valueOf(trip.getId()));
        data.put("estacion", origin.getName());
        data.put("barrioInicio", null);
        data.put("latitud", origin.getLatitude().doubleValue());
        data.put("longitud", origin.getLongitude().doubleValue());
        data.put("horaInicio", trip.getStartedAt().toString());
        data.put("correlationId", correlationId(trip));
        enqueue(trip, TRIP_STARTED, data);
    }

    public void tripEnded(Trip trip) {
        Station destination = trip.getDestinationStation();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", String.valueOf(trip.getId()));
        data.put("estacion", destination.getName());
        data.put("latitud", destination.getLatitude().doubleValue());
        data.put("longitud", destination.getLongitude().doubleValue());
        data.put("horaFinalizacion", trip.getEndedAt().toString());
        data.put("correlationId", correlationId(trip));
        enqueue(trip, TRIP_ENDED, data);
    }

    // Mismo valor en el inicio y el fin: permite seguir el viaje completo entre módulos.
    private String correlationId(Trip trip) {
        return "viaje-" + trip.getId();
    }

    private void enqueue(Trip trip, String eventType, Map<String, Object> data) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException ex) {
            // No puede pasar con un Map de tipos simples; si pasa, que se vea.
            throw new IllegalStateException("No se pudo serializar " + eventType, ex);
        }

        outboxRepository.save(EventOutbox.builder()
                .aggregateType(AggregateType.TRIP)
                .aggregateId(String.valueOf(trip.getId()))
                .eventType(eventType)
                .payload(payload)
                .build());

        log.info("{} encolado: tripId={}", eventType, trip.getId());
    }
}
