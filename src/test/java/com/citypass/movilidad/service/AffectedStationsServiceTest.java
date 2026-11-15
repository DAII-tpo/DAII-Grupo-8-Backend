package com.citypass.movilidad.service;

import com.citypass.movilidad.eventbus.publish.EventPublisher;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.enums.AggregateType;
import com.citypass.movilidad.repository.CityIncidentRepository;
import com.citypass.movilidad.repository.StationAffectationRepository;
import com.citypass.movilidad.repository.StationAffectationRepository.Affectation;
import com.citypass.movilidad.repository.StationAffectationRepository.FestivalTransfer;
import com.citypass.movilidad.repository.StationRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AffectedStationsServiceTest {
    private final StationAffectationRepository affectations = mock(StationAffectationRepository.class);
    private final EventPublisher events = mock(EventPublisher.class);
    private final AffectedStationsService service = new AffectedStationsService(mock(StationRepository.class),
            mock(CityIncidentRepository.class), affectations, events, 300);

    @Test
    void announcesRestrictionFromTheScheduledStartAfterAnEventTransfer() {
        var station = station();
        var start = Instant.ofEpochMilli(Instant.now().plusSeconds(3600).toEpochMilli());
        var end = start.plusSeconds(7200);
        when(affectations.findFestivalTransfer(eq(3L), eq(7L), anyLong()))
                .thenReturn(Optional.of(new FestivalTransfer("festival", "corr-1", start.toEpochMilli(), end.toEpochMilli())));

        service.registerFestivalTransfer(3L, station);

        var stored = ArgumentCaptor.forClass(Affectation.class);
        verify(affectations).save(stored.capture());
        var payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq("EstacionUrbanaAfectada"), eq(AggregateType.INCIDENT),
                eq(stored.getValue().id()), payload.capture());
        var fields = (Map<?, ?>) payload.getValue();
        assertThat(fields.get("causaId")).isEqualTo("festival");
        assertThat(fields.get("causaTipo")).isEqualTo("EVENTO_PUBLICO");
        assertThat(fields.get("moduloOrigen")).isEqualTo("MOVILIDAD");
        assertThat(fields.get("tipoAfectacion")).isEqualTo("RECOMENDACION_RESTRINGIDA");
        assertThat(fields.get("correlationId")).isEqualTo("corr-1");
        assertThat(fields.get("estacionId")).isEqualTo("7");
        assertThat(fields.get("afectacionDesde")).isEqualTo(start.toString());
        assertThat(fields.get("afectacionHastaEstimada")).isEqualTo(end.toString());
        assertThat(Instant.parse((String) fields.get("occurredAt"))).isBefore(start);
    }

    @Test
    void avoidsPublishingAnotherRestrictionForTheSameEventAndStation() {
        when(affectations.findFestivalTransfer(eq(3L), eq(7L), anyLong())).thenReturn(Optional.of(
                new FestivalTransfer("festival", "corr-1", null, Instant.now().plusSeconds(3600).toEpochMilli())));
        when(affectations.hasFestivalAffectation(7L, "festival")).thenReturn(true);

        service.registerFestivalTransfer(3L, station());

        verifyNoInteractions(events);
    }

    @Test
    void restoresOnlyFestivalAffectationsThatAreNoLongerOpen() {
        var closed = new Affectation("cerrada", 7L, "festival-a", "EVENTO_PUBLICO", "MOVILIDAD", "corr-a");
        var open = new Affectation("abierta", 7L, "festival-b", "EVENTO_PUBLICO", "MOVILIDAD", "corr-b");
        when(affectations.findAll()).thenReturn(List.of(closed, open));
        when(affectations.openFestivalAffectations(anyLong())).thenReturn(Set.of(open.id()));

        service.synchronize();

        var payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq("EstacionUrbanaRestaurada"), eq(AggregateType.INCIDENT), eq(closed.id()),
                payload.capture());
        assertThat(((Map<?, ?>) payload.getValue()).get("correlationId")).isEqualTo("corr-a");
        verify(affectations).delete(closed.id());
    }

    private Station station() {
        return Station.builder().id(7L).latitude(new BigDecimal("-34.6"))
                .longitude(new BigDecimal("-58.4")).build();
    }
}
