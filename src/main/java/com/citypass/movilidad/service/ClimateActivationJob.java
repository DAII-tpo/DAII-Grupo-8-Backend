package com.citypass.movilidad.service;

import com.citypass.movilidad.model.ClimateAlert;
import com.citypass.movilidad.repository.ClimateAlertRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@Slf4j
public class ClimateActivationJob {
    private final ClimateAlertRepository alerts;
    private final ClimateAlertService service;
    private final ClimateActivationFailureReporter failures;

    public ClimateActivationJob(ClimateAlertRepository alerts, ClimateAlertService service,
                                ClimateActivationFailureReporter failures) {
        this.alerts = alerts;
        this.service = service;
        this.failures = failures;
    }

    @Scheduled(fixedDelayString = "${movilidad.climate.activation-interval:30s}")
    public void activateDueAlerts() {
        var due = alerts.findByStatusAndValidFromLessThanEqual(ClimateAlert.Status.PROGRAMADA, Instant.now());
        for (ClimateAlert alert : due) {
            try {
                service.activateAlert(alert.getId());
            } catch (Exception error) {
                log.error("No se pudo activar la alerta climática {}", alert.getExternalAlertId(), error);
                try {
                    failures.report(alert.getId());
                } catch (Exception reportError) {
                    log.error("No se pudo registrar el fallo de activación {}", alert.getExternalAlertId(), reportError);
                }
            }
        }
    }
}
