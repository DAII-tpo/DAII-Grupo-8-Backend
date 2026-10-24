package com.citypass.movilidad.service;

import com.citypass.movilidad.model.EventOutbox;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.Trip;
import com.citypass.movilidad.repository.EventOutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TripEventPublisherTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final EventOutboxRepository outboxRepository = mock(EventOutboxRepository.class);
    private final TripEventPublisher publisher = new TripEventPublisher(outboxRepository, mapper);

    @Test
    void elInicioDeViajeLlevaLosCamposQueEsperaAnalitica() throws Exception {
        Trip trip = mock(Trip.class);
        Station origin = station("Estación Obelisco", "-34.6037", "-58.3816");
        org.mockito.Mockito.when(trip.getId()).thenReturn(42L);
        org.mockito.Mockito.when(trip.getOriginStation()).thenReturn(origin);
        org.mockito.Mockito.when(trip.getStartedAt()).thenReturn(Instant.parse("2026-11-01T15:00:00Z"));

        publisher.tripStarted(trip);

        JsonNode data = savedPayload("ViajeIniciado");
        assertThat(data.path("id").asText()).isEqualTo("42");
        assertThat(data.path("estacion").asText()).isEqualTo("Estación Obelisco");
        assertThat(data.path("latitud").asDouble()).isEqualTo(-34.6037);
        assertThat(data.path("horaInicio").asText()).isEqualTo("2026-11-01T15:00:00Z");
        assertThat(data.has("barrioInicio")).isTrue();
        assertThat(data.path("correlationId").asText()).isEqualTo("viaje-42");
    }

    @Test
    void elFinDeViajeUsaElMismoIdYCorrelationId() throws Exception {
        Trip trip = mock(Trip.class);
        Station destination = station("Estación Retiro", "-34.5915", "-58.3745");
        org.mockito.Mockito.when(trip.getId()).thenReturn(42L);
        org.mockito.Mockito.when(trip.getDestinationStation()).thenReturn(destination);
        org.mockito.Mockito.when(trip.getEndedAt()).thenReturn(Instant.parse("2026-11-01T15:20:00Z"));

        publisher.tripEnded(trip);

        JsonNode data = savedPayload("ViajeTerminado");
        assertThat(data.path("id").asText()).isEqualTo("42");
        assertThat(data.path("horaFinalizacion").asText()).isEqualTo("2026-11-01T15:20:00Z");
        assertThat(data.path("correlationId").asText()).isEqualTo("viaje-42");
    }

    private JsonNode savedPayload(String expectedType) throws Exception {
        ArgumentCaptor<EventOutbox> saved = ArgumentCaptor.forClass(EventOutbox.class);
        verify(outboxRepository).save(saved.capture());
        assertThat(saved.getValue().getEventType()).isEqualTo(expectedType);
        return mapper.readTree(saved.getValue().getPayload());
    }

    private static Station station(String name, String lat, String lng) {
        Station station = mock(Station.class);
        org.mockito.Mockito.when(station.getName()).thenReturn(name);
        org.mockito.Mockito.when(station.getLatitude()).thenReturn(new BigDecimal(lat));
        org.mockito.Mockito.when(station.getLongitude()).thenReturn(new BigDecimal(lng));
        return station;
    }
}
