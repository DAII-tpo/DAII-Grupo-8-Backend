package com.citypass.movilidad.service;

import com.citypass.movilidad.model.ClimateAlert;
import com.citypass.movilidad.model.Neighborhood;
import com.citypass.movilidad.repository.ClimateAlertRepository;
import com.citypass.movilidad.repository.ClimateTripNotificationRepository;
import com.citypass.movilidad.repository.NeighborhoodRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClimateAlertServiceTest {
    private final ClimateAlertRepository repository = mock(ClimateAlertRepository.class);
    private final NeighborhoodRepository neighborhoods = mock(NeighborhoodRepository.class);
    private final ClimateAlertEffects effects = mock(ClimateAlertEffects.class);
    private final ClimateAlertService service = new ClimateAlertService(repository, neighborhoods, effects,
            mock(ClimateTripNotificationRepository.class));
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void confirmedEventNormalizesNeighborhoodAndActivatesWhenAlreadyDue() throws Exception {
        Neighborhood palermo = new Neighborhood();
        palermo.setId("AR-CABA-PALERMO");
        palermo.setName("Palermo");
        when(neighborhoods.findByNormalizedName("PALERMO")).thenReturn(Optional.of(palermo));
        long from = Instant.now().minusSeconds(60).toEpochMilli();
        long until = Instant.now().plusSeconds(3600).toEpochMilli();
        var data = mapper.readTree("""
                {"alertaId":"weather-1","nivelCriticidad":"MEDIA","fenomeno":"VIENTO_FUERTE",
                "barrios":["palérmo"],"vigenciaDesde":%d,"vigenciaHasta":%d}
                """.formatted(from, until));

        service.confirm(data, Instant.now());

        ArgumentCaptor<ClimateAlert> saved = ArgumentCaptor.forClass(ClimateAlert.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getExternalAlertId()).isEqualTo("weather-1");
        assertThat(saved.getValue().getSeverity()).isEqualTo(ClimateAlert.Severity.MEDIA);
        assertThat(saved.getValue().getNeighborhoods()).containsExactly(palermo);
        assertThat(saved.getValue().getStatus()).isEqualTo(ClimateAlert.Status.ACTIVA);
        verify(effects).applied(any(ClimateAlert.class), any(Instant.class));
    }

    @Test
    void futureAlertIsScheduledAndDuplicateBusinessIdIsIgnored() throws Exception {
        long from = Instant.now().plusSeconds(3600).toEpochMilli();
        long until = Instant.now().plusSeconds(7200).toEpochMilli();
        var data = mapper.readTree("""
                {"alertaId":"weather-2","nivelCriticidad":"ALTA","fenomeno":"GRANIZO",
                "barrios":[],"vigenciaDesde":%d,"vigenciaHasta":%d}
                """.formatted(from, until));

        service.confirm(data, Instant.now());
        ArgumentCaptor<ClimateAlert> saved = ArgumentCaptor.forClass(ClimateAlert.class);
        verify(repository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(ClimateAlert.Status.PROGRAMADA);
        verify(effects, never()).applied(any(), any());

        when(repository.findByExternalAlertId("weather-2")).thenReturn(Optional.of(saved.getValue()));
        service.confirm(data, Instant.now());
        verify(repository).saveAndFlush(any());
    }

    @Test
    void scheduledAlertActivatesAndActiveAlertCanBeFinalized() throws Exception {
        ClimateAlert alert = new ClimateAlert();
        alert.setExternalAlertId("weather-3");
        alert.setStatus(ClimateAlert.Status.PROGRAMADA);
        alert.setId(3L);
        alert.setValidFrom(Instant.now().minusSeconds(30));
        when(repository.findByIdForUpdate(3L)).thenReturn(Optional.of(alert));

        service.activateAlert(3L);

        assertThat(alert.getStatus()).isEqualTo(ClimateAlert.Status.ACTIVA);
        verify(effects).applied(any(ClimateAlert.class), any(Instant.class));

        when(repository.findByExternalAlertId("weather-3")).thenReturn(Optional.of(alert));
        service.finish(mapper.readTree("""
                {"alertaId":"weather-3","finalizadaEn":1794011463871}
                """));

        assertThat(alert.getStatus()).isEqualTo(ClimateAlert.Status.FINALIZADA);
        verify(effects).lifted(any(ClimateAlert.class), any(Instant.class));
    }
}
