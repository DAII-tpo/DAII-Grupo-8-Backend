package com.citypass.movilidad.service;

import com.citypass.movilidad.model.ClimateAlert;
import com.citypass.movilidad.repository.ClimateAlertRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

    private static ClimateAlert alert(Long id) {
        ClimateAlert alert = new ClimateAlert();
        alert.setId(id);
        alert.setExternalAlertId("weather-" + id);
        return alert;
    }
}
