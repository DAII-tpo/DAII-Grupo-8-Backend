package com.citypass.movilidad.service;

import com.citypass.movilidad.repository.ClimateAlertRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class ClimateActivationFailureReporter {
    private final ClimateAlertRepository alerts;
    private final ClimateAlertEffects effects;

    public ClimateActivationFailureReporter(ClimateAlertRepository alerts, ClimateAlertEffects effects) {
        this.alerts = alerts;
        this.effects = effects;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void report(Long alertId) {
        alerts.findById(alertId).ifPresent(alert ->
                effects.failed(alert, Instant.now(), "No se pudo aplicar la alerta; se reintentará"));
    }
}
