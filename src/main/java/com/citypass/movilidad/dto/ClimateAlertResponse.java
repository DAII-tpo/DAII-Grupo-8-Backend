package com.citypass.movilidad.dto;

import com.citypass.movilidad.model.ClimateAlert;

import java.time.Instant;
import java.util.List;

/** Datos climáticos que la UI puede mostrar sin acceder al evento original. */
public record ClimateAlertResponse(
        String alertaId,
        ClimateAlert.Severity nivelCriticidad,
        String fenomeno,
        List<String> barrios,
        Instant vigenciaDesde,
        Instant vigenciaHasta,
        boolean activa
) {
    public static ClimateAlertResponse from(ClimateAlert alert) {
        return new ClimateAlertResponse(alert.getExternalAlertId(), alert.getSeverity(),
                alert.getPhenomenon(),
                alert.getNeighborhoods().stream().map(neighborhood -> neighborhood.getName()).sorted().toList(),
                alert.getValidFrom(), alert.getValidUntil(),
                alert.getStatus() == ClimateAlert.Status.ACTIVA);
    }
}
