package com.citypass.movilidad.dto;

import com.citypass.movilidad.model.ClimateAlert;
import com.citypass.movilidad.service.ClimateResponse;

import java.time.Instant;
import java.util.List;

/**
 * Datos climáticos que la UI puede mostrar sin acceder al evento original. {@code accion} es la respuesta que
 * resulta de la matriz fenómeno × criticidad; la UI arma los textos con ella, no con el nivel.
 */
public record ClimateAlertResponse(
        String alertaId,
        ClimateAlert.Severity nivelCriticidad,
        String fenomeno,
        List<String> barrios,
        Instant vigenciaDesde,
        Instant vigenciaHasta,
        boolean activa,
        String accion,
        boolean bloqueaAlquiler
) {
    public static ClimateAlertResponse from(ClimateAlert alert, ClimateResponse response) {
        return new ClimateAlertResponse(alert.getExternalAlertId(), alert.getSeverity(),
                alert.getPhenomenon(),
                alert.getNeighborhoods().stream().map(neighborhood -> neighborhood.getName()).sorted().toList(),
                alert.getValidFrom(), alert.getValidUntil(),
                alert.getStatus() == ClimateAlert.Status.ACTIVA,
                response.name(), response.blocksRental());
    }
}
