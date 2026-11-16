package com.citypass.movilidad.service;

import com.citypass.movilidad.eventbus.publish.EventPublisher;
import com.citypass.movilidad.model.Bike;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.Trip;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.model.enums.AggregateType;
import com.citypass.movilidad.model.enums.TripStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TripEventPublisherTest {

    private static final Instant STARTED = Instant.parse("2026-11-15T19:00:00Z");
    private static final Instant ENDED = Instant.parse("2026-11-15T19:25:00Z");

    private EventPublisher events;
    private TripEventPublisher publisher;
    private Trip trip;

    @BeforeEach
    void setUp() {
        events = mock(EventPublisher.class);
        publisher = new TripEventPublisher(events);
        Station origin = station(10L, "-34.600000", "-58.380000");
        trip = Trip.builder().id(7L).user(User.builder().id(1L).build()).bike(Bike.builder().id(5L).build())
                .originStation(origin).startedAt(STARTED).endedAt(ENDED).durationSeconds(1500).build();
    }

    @Test
    void unViajeTerminadoNormalmenteNoLlevaCausaYTerminaEnLaEstacionDeDevolucion() {
        trip.setStatus(TripStatus.COMPLETED);
        trip.setDestinationStation(station(20L, "-34.610000", "-58.390000"));

        publisher.tripEnded(trip);

        Map<String, Object> data = published();
        assertThat(data).containsEntry("viajeId", "7").containsEntry("correlationId", "viaje-7")
                .containsEntry("estacionInicio", "10").containsEntry("horaInicio", STARTED.toString())
                .containsEntry("estacionFin", "20").containsEntry("latitudFin", -34.61)
                .containsEntry("longitudFin", -58.39).containsEntry("horaFinalizacion", ENDED.toString())
                .containsEntry("causaId", null).containsEntry("causaTipo", null)
                .containsEntry("createdAt", ENDED.toString());
    }

    @Test
    void unViajeCerradoPorEmergenciaLlevaElCodigoComoCausaYTerminaDondeSePidioAyuda() {
        trip.setStatus(TripStatus.CLOSED_BY_EMERGENCY);
        trip.setDestinationStation(station(30L, "-34.607000", "-58.384000"));

        publisher.tripClosedByEmergency(trip, "EMG-20261115-60002", -34.6037, -58.3816);

        Map<String, Object> data = published();
        assertThat(data).containsEntry("viajeId", "7").containsEntry("correlationId", "viaje-7")
                .containsEntry("latitudInicio", -34.6).containsEntry("longitudInicio", -58.38)
                .containsEntry("estacionFin", "30").containsEntry("latitudFin", -34.6037)
                .containsEntry("longitudFin", -58.3816).containsEntry("horaFinalizacion", ENDED.toString())
                .containsEntry("causaId", "EMG-20261115-60002").containsEntry("causaTipo", "EMERGENCIA");
        assertThat(data).hasSize(13);
    }

    @Test
    void sinEstacionConLugarLaEstacionFinalVaVaciaPorqueElContratoNoLaAdmiteNula() {
        trip.setStatus(TripStatus.CLOSED_BY_EMERGENCY);

        publisher.tripClosedByEmergency(trip, "EMG-20261115-60002", -34.6037, -58.3816);

        assertThat(published()).containsEntry("estacionFin", "")
                .containsEntry("latitudFin", -34.6037).containsEntry("causaTipo", "EMERGENCIA");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> published() {
        ArgumentCaptor<Object> data = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq("ViajeFinalizado"), eq(AggregateType.TRIP), eq("7"), data.capture());
        return (Map<String, Object>) data.getValue();
    }

    private static Station station(long id, String latitude, String longitude) {
        return Station.builder().id(id).name("Estación " + id)
                .latitude(new BigDecimal(latitude)).longitude(new BigDecimal(longitude)).build();
    }
}
