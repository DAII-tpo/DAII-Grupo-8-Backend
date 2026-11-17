package com.citypass.movilidad.service;

import com.citypass.movilidad.model.ClimateAlert;
import com.citypass.movilidad.repository.ClimateAlertRepository;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClimateActivationJobTest {
    private final ClimateAlertRepository alerts = mock(ClimateAlertRepository.class);
    private final ClimateAlertService service = mock(ClimateAlertService.class);
    private final ClimateActivationFailureReporter failures = mock(ClimateActivationFailureReporter.class);
    private final ClimateActivationJob job = new ClimateActivationJob(alerts, service, failures);

    @Test
    void activatesDueAlertsIndividuallyAndContinuesAfterFailure() {
        ClimateAlert failed = alert(1L);
        ClimateAlert successful = alert(2L);
        when(alerts.findByStatusAndValidFromLessThanEqual(eq(ClimateAlert.Status.PROGRAMADA), any(Instant.class)))
                .thenReturn(List.of(failed, successful));
        doThrow(new IllegalStateException("temporary failure")).when(service).activateAlert(1L);

        job.activateDueAlerts();

        verify(failures).report(1L);
        verify(service).activateAlert(2L);
    }

    @Test
    void tickExpiresBeforeActivating() {
        when(alerts.findByStatusInAndValidUntilLessThanEqual(
                eq(List.of(ClimateAlert.Status.PROGRAMADA, ClimateAlert.Status.ACTIVA)), any(Instant.class)))
                .thenReturn(List.of(alert(1L)));
        when(alerts.findByStatusAndValidFromLessThanEqual(eq(ClimateAlert.Status.PROGRAMADA), any(Instant.class)))
                .thenReturn(List.of(alert(2L)));

        job.tick();

        InOrder order = inOrder(service);
        order.verify(service).expireAlert(1L);
        order.verify(service).activateAlert(2L);
    }

    @Test
    void expirationFailureDoesNotStopOtherAlertsNorActivation() {
        when(alerts.findByStatusInAndValidUntilLessThanEqual(anyList(), any(Instant.class)))
                .thenReturn(List.of(alert(1L), alert(2L)));
        when(alerts.findByStatusAndValidFromLessThanEqual(eq(ClimateAlert.Status.PROGRAMADA), any(Instant.class)))
                .thenReturn(List.of(alert(3L)));
        doThrow(new IllegalStateException("temporary failure")).when(service).expireAlert(1L);

        job.tick();

        verify(service).expireAlert(2L);
        verify(service).activateAlert(3L);
        // Un cierre fallido se reintenta en el próximo tick; no se publica FALLIDA.
        verifyNoInteractions(failures);
    }

    private static ClimateAlert alert(Long id) {
        ClimateAlert alert = new ClimateAlert();
        alert.setId(id);
        alert.setExternalAlertId("weather-" + id);
        return alert;
    }
}
