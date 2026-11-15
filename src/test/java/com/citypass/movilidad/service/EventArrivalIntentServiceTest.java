package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.EventArrivalIntentRequest;
import com.citypass.movilidad.exception.ResourceNotFoundException;
import com.citypass.movilidad.model.CulturalDestination;
import com.citypass.movilidad.model.EventArrivalIntent;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.repository.CulturalDestinationRepository;
import com.citypass.movilidad.repository.EventArrivalIntentRepository;
import com.citypass.movilidad.repository.StationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EventArrivalIntentServiceTest {
    private EventArrivalIntentRepository intentions;
    private CulturalDestinationRepository events;
    private StationRepository stations;
    private EventArrivalIntentService service;
    private CulturalDestination event;
    private Station station;

    @BeforeEach
    void setUp() {
        intentions = mock(EventArrivalIntentRepository.class);
        events = mock(CulturalDestinationRepository.class);
        stations = mock(StationRepository.class);
        service = new EventArrivalIntentService(intentions, events, stations);
        event = CulturalDestination.builder().eventId("ev-1").spaceId("space-1").title("Feria")
                .latitude(BigDecimal.valueOf(-34.6)).longitude(BigDecimal.valueOf(-58.4))
                .startsAt(Instant.parse("2030-01-01T12:00:00Z"))
                .endsAt(Instant.parse("2030-01-01T15:00:00Z"))
                .maxCapacity(100).eventStatus("ACTIVE").spaceEnabled(true).build();
        station = mock(Station.class);
        when(station.getId()).thenReturn(7L);
        when(station.getStatus()).thenReturn(StationStatus.ACTIVE);
        when(intentions.save(any(EventArrivalIntent.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void registraYActualizaLaIntencionExistente() {
        when(events.findById("ev-1")).thenReturn(Optional.of(event));
        when(stations.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(station));
        var existing = new EventArrivalIntent();
        existing.setEventId("ev-1"); existing.setStationId(3L);
        when(intentions.findByUserIdAndEventId(9L, "ev-1")).thenReturn(Optional.of(existing));

        var response = service.register(9L, "ev-1", new EventArrivalIntentRequest(7L, Instant.parse("2030-01-01T13:00:00Z")));

        assertThat(response.stationId()).isEqualTo(7L);
        assertThat(response.status()).isEqualTo("ACTIVE");
        verify(intentions).save(existing);
    }

    @Test
    void rechazaEventoFinalizadoEstacionInactivaYFranjaInvalida() {
        event.setEndsAt(Instant.parse("2020-01-01T15:00:00Z"));
        when(events.findById("ev-1")).thenReturn(Optional.of(event));
        assertThatThrownBy(() -> service.register(9L, "ev-1", new EventArrivalIntentRequest(7L, Instant.now())))
                .isInstanceOf(ResourceNotFoundException.class);

        event.setEndsAt(Instant.parse("2030-01-01T15:00:00Z"));
        when(stations.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.register(9L, "ev-1", new EventArrivalIntentRequest(7L, Instant.parse("2030-01-01T13:00:00Z"))))
                .isInstanceOf(ResourceNotFoundException.class);

        when(stations.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(station));
        assertThatThrownBy(() -> service.register(9L, "ev-1", new EventArrivalIntentRequest(7L, Instant.parse("2030-01-01T08:00:00Z"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cancelaConsultaYProyectaIntenciones() {
        var intent = new EventArrivalIntent(); intent.setEventId("ev-1"); intent.setStationId(7L); intent.setStatus("ACTIVE");
        when(intentions.findByUserIdAndEventId(9L, "ev-1")).thenReturn(Optional.of(intent));
        service.cancel(9L, "ev-1");
        assertThat(intent.getStatus()).isEqualTo("CANCELLED");
        assertThat(service.current(9L, "ev-1")).isPresent();

        var count = mock(EventArrivalIntentRepository.ArrivalCount.class);
        when(count.getStationId()).thenReturn(7L); when(count.getArrivals()).thenReturn(3L);
        when(intentions.countActiveByStation(eq("ev-1"), any(), any())).thenReturn(List.of(count));
        assertThat(service.projectedArrivals("ev-1", Instant.MIN, Instant.MAX)).containsEntry(7L, 3);
    }

    @Test
    void ignoraCancelacionYConsultaCuandoNoHayIntencionYRechazaEstacionInactiva() {
        when(intentions.findByUserIdAndEventId(9L, "missing")).thenReturn(Optional.empty());
        service.cancel(9L, "missing");
        assertThat(service.current(9L, "missing")).isEmpty();

        when(events.findById("ev-1")).thenReturn(Optional.of(event));
        when(stations.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(station));
        when(station.getStatus()).thenReturn(StationStatus.INACTIVE);
        assertThatThrownBy(() -> service.register(9L, "ev-1", new EventArrivalIntentRequest(7L, Instant.parse("2030-01-01T13:00:00Z"))))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
