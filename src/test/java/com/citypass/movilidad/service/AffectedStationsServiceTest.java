package com.citypass.movilidad.service;

import com.citypass.movilidad.eventbus.publish.EventPublisher;
import com.citypass.movilidad.model.ClimateAlert;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.enums.AggregateType;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.repository.CityIncidentRepository;
import com.citypass.movilidad.repository.StationAffectationRepository;
import com.citypass.movilidad.repository.StationAffectationRepository.Affectation;
import com.citypass.movilidad.repository.StationAffectationRepository.FestivalTransfer;
import com.citypass.movilidad.repository.StationAffectationRepository.Key;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    @Test
    void climateAlertBlocksEachActiveStationWithTheBiContract() {
        var alert = climateAlert();
        var first = station(7L, StationStatus.ACTIVE);
        var inactive = station(8L, StationStatus.INACTIVE);
        var second = station(9L, StationStatus.ACTIVE);
        Instant now = Instant.parse("2026-11-15T12:00:00Z");

        assertThat(service.blockForClimateAlert(alert, List.of(first, inactive, second), now)).isEqualTo(2);

        var stored = ArgumentCaptor.forClass(Affectation.class);
        verify(affectations, times(2)).save(stored.capture());
        var payload = ArgumentCaptor.forClass(Object.class);
        verify(events, times(2)).publish(eq("EstacionUrbanaAfectada"), eq(AggregateType.CLIMATE_ALERT),
                any(), payload.capture());
        @SuppressWarnings("unchecked")
        var fields = (Map<String, Object>) payload.getAllValues().getFirst();
        assertThat(fields.keySet()).containsExactly("afectacionId", "estacionId", "latitud", "longitud",
                "tipoAfectacion", "afectacionDesde", "afectacionHastaEstimada", "causaId", "causaTipo",
                "moduloOrigen", "correlationId", "occurredAt");
        assertThat(fields.get("afectacionId")).isEqualTo(stored.getAllValues().getFirst().id());
        assertThat(fields.get("estacionId")).isEqualTo("7");
        assertThat(fields.get("latitud")).isEqualTo(-34.6);
        assertThat(fields.get("longitud")).isEqualTo(-58.4);
        assertThat(fields.get("tipoAfectacion")).isEqualTo("BLOQUEADA");
        assertThat(fields.get("afectacionDesde")).isEqualTo(alert.getValidFrom().toString());
        assertThat(fields.get("afectacionHastaEstimada")).isEqualTo(alert.getValidUntil().toString());
        assertThat(fields.get("causaId")).isEqualTo("alerta-7");
        assertThat(fields.get("causaTipo")).isEqualTo("EVENTO_CLIMATICO");
        assertThat(fields.get("moduloOrigen")).isEqualTo("MOVILIDAD");
        assertThat(fields.get("correlationId")).isEqualTo("alerta-7");
        assertThat(fields.get("occurredAt")).isEqualTo(now.toString());
        assertThat(((Map<?, ?>) payload.getAllValues().get(1)).get("estacionId")).isEqualTo("9");
        verify(affectations, never()).exists(new Key(8L, "EVENTO_CLIMATICO", "alerta-7"));
    }

    @Test
    void climateBlockIsIdempotentPerStationAndAlert() {
        when(affectations.exists(new Key(7L, "EVENTO_CLIMATICO", "alerta-7"))).thenReturn(true);

        assertThat(service.blockForClimateAlert(climateAlert(), List.of(station(7L, StationStatus.ACTIVE)),
                Instant.now())).isZero();

        verify(affectations, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void climateRestoreClosesEveryAffectationOfTheAlertWithItsOwnId() {
        var first = new Affectation("afectacion-1", 7L, "alerta-7", "EVENTO_CLIMATICO", "MOVILIDAD", "alerta-7");
        var second = new Affectation("afectacion-2", 9L, "alerta-7", "EVENTO_CLIMATICO", "MOVILIDAD", "alerta-7");
        when(affectations.findByCause("EVENTO_CLIMATICO", "alerta-7")).thenReturn(List.of(first, second));
        Instant finishedAt = Instant.parse("2026-11-15T14:00:00Z");

        assertThat(service.restoreClimateAlert("alerta-7", finishedAt)).isEqualTo(2);

        var payload = ArgumentCaptor.forClass(Object.class);
        verify(events).publish(eq("EstacionUrbanaRestaurada"), eq(AggregateType.CLIMATE_ALERT),
                eq("afectacion-1"), payload.capture());
        @SuppressWarnings("unchecked")
        var restored = (Map<String, Object>) payload.getValue();
        assertThat(restored).containsEntry("afectacionId", "afectacion-1")
                .containsEntry("estacionId", "7").containsEntry("correlationId", "alerta-7")
                .containsEntry("restauradaAt", finishedAt.toString());
        verify(events).publish(eq("EstacionUrbanaRestaurada"), eq(AggregateType.CLIMATE_ALERT),
                eq("afectacion-2"), any());
        verify(affectations).delete("afectacion-1");
        verify(affectations).delete("afectacion-2");
    }

    @Test
    void reconcilerKeepsClimateAffectationsOfOpenAlertsAndRestoresFinishedOnes() {
        var open = new Affectation("abierta", 7L, "alerta-activa", "EVENTO_CLIMATICO", "MOVILIDAD", "alerta-activa");
        var orphan = new Affectation("huerfana", 7L, "alerta-finalizada", "EVENTO_CLIMATICO", "MOVILIDAD",
                "alerta-finalizada");
        when(affectations.findAll()).thenReturn(List.of(open, orphan));
        when(affectations.openClimateAffectations()).thenReturn(Set.of(open.id()));

        service.synchronize();

        verify(events).publish(eq("EstacionUrbanaRestaurada"), eq(AggregateType.CLIMATE_ALERT), eq("huerfana"),
                any());
        verify(events, never()).publish(any(), any(), eq("abierta"), any());
        verify(affectations).delete("huerfana");
        verify(affectations, never()).delete("abierta");
    }

    private static ClimateAlert climateAlert() {
        var alert = new ClimateAlert();
        alert.setId(70L);
        alert.setExternalAlertId("alerta-7");
        alert.setValidFrom(Instant.parse("2026-11-15T13:00:00Z"));
        alert.setValidUntil(Instant.parse("2026-11-15T18:00:00Z"));
        return alert;
    }

    private Station station() {
        return Station.builder().id(7L).latitude(new BigDecimal("-34.6"))
                .longitude(new BigDecimal("-58.4")).build();
    }

    private static Station station(long id, StationStatus status) {
        return Station.builder().id(id).status(status).latitude(new BigDecimal("-34.6"))
                .longitude(new BigDecimal("-58.4")).build();
    }
}
