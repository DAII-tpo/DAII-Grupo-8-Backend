package com.citypass.movilidad.service;

import com.citypass.movilidad.model.ClimateAlert;
import com.citypass.movilidad.repository.ClimateAlertRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClimateActivationFailureReporterTest {
    private final ClimateAlertRepository alerts = mock(ClimateAlertRepository.class);
    private final ClimateAlertEffects effects = mock(ClimateAlertEffects.class);
    private final ClimateActivationFailureReporter reporter = new ClimateActivationFailureReporter(alerts, effects);

    @Test
    void reportsFailureForThePersistedAlertWithoutClaimingSuccess() {
        ClimateAlert alert = new ClimateAlert();
        alert.setId(7L);
        when(alerts.findById(7L)).thenReturn(Optional.of(alert));

        reporter.report(7L);

        verify(effects).failed(eq(alert), any(Instant.class), eq("No se pudo aplicar la alerta; se reintentará"));
    }

    @Test
    void missingAlertDoesNotPublishAnInventedFailure() {
        when(alerts.findById(7L)).thenReturn(Optional.empty());

        reporter.report(7L);

        verifyNoInteractions(effects);
    }
}
