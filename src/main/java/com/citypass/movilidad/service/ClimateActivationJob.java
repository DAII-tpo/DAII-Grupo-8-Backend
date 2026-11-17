package com.citypass.movilidad.service;

import com.citypass.movilidad.model.ClimateAlert;
import com.citypass.movilidad.repository.ClimateAlertRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Ciclo de vida programado de las alertas climáticas: en cada tick primero vence las alertas cuya vigenciaHasta
 * ya pasó y después activa las PROGRAMADA cuya vigenciaDesde llegó. Correr las dos cosas en el mismo tick, y en ese
 * orden, evita que se intercalen. Cada alerta va en su propia transacción y con lock de fila, así que varias
 * instancias del backend no la procesan dos veces.
 */
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
    public void tick() {
        expireDueAlerts();
        activateDueAlerts();
    }

    public void expireDueAlerts() {
        var due = alerts.findByStatusInAndValidUntilLessThanEqual(
                List.of(ClimateAlert.Status.PROGRAMADA, ClimateAlert.Status.ACTIVA), Instant.now());
        for (ClimateAlert alert : due) {
            try {
                service.expireAlert(alert.getId());
            } catch (Exception error) {
                // Se reintenta en el próximo tick; no se publica FALLIDA (no aplica a un cierre).
                log.error("No se pudo vencer la alerta climática {}", alert.getExternalAlertId(), error);
            }
        }
    }

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
