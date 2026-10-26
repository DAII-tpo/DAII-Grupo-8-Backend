package com.citypass.movilidad.service;

import com.citypass.movilidad.eventbus.publish.EventPublisher;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.Trip;
import com.citypass.movilidad.model.enums.AggregateType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.argThat;

class TripEventPublisherTest {

    private final EventPublisher eventPublisher = mock(EventPublisher.class);
    private final TripEventPublisher publisher = new TripEventPublisher(eventPublisher);

    @Test
    void publicaInicioConContratoBI() {
        Trip trip = trip();
        publisher.tripStarted(trip);
        verify(eventPublisher).publish(eq("ViajeIniciado"), eq(AggregateType.TRIP), eq(42L),
                argThat(value -> value instanceof Map<?, ?> data
                        && "42".equals(data.get("id"))
                        && "Obelisco".equals(data.get("estacion"))
                        && "2026-11-01T14:30:00Z".equals(data.get("horaInicio"))
                        && "viaje-42".equals(data.get("correlationId"))
                        && data.containsKey("barrioInicio")));
    }

    @Test
    void publicaFinConMismoCorrelationId() {
        Trip trip = trip();
        publisher.tripEnded(trip);
        verify(eventPublisher).publish(eq("ViajeTerminado"), eq(AggregateType.TRIP), eq(42L),
                argThat(value -> value instanceof Map<?, ?> data
                        && "42".equals(data.get("id"))
                        && "Destino".equals(data.get("estacion"))
                        && "2026-11-01T14:52:00Z".equals(data.get("horaFinalizacion"))
                        && "viaje-42".equals(data.get("correlationId"))));
    }

    private Trip trip() {
        Station origin = new Station();
        origin.setName("Obelisco");
        origin.setLatitude(new BigDecimal("-34.6037000"));
        origin.setLongitude(new BigDecimal("-58.3816000"));
        Station destination = new Station();
        destination.setName("Destino");
        destination.setLatitude(new BigDecimal("-34.5809000"));
        destination.setLongitude(new BigDecimal("-58.4205000"));
        Trip trip = new Trip();
        trip.setId(42L);
        trip.setOriginStation(origin);
        trip.setDestinationStation(destination);
        trip.setStartedAt(Instant.parse("2026-11-01T14:30:00Z"));
        trip.setEndedAt(Instant.parse("2026-11-01T14:52:00Z"));
        return trip;
    }
}
