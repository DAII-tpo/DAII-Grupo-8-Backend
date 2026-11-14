package com.citypass.movilidad.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

/** Decisión de navegación del ciudadano. No crea ni retiene reservas de bicicletas. */
public record EventMobilityDecisionRequest(
        boolean attending,
        @DecimalMin("-90") @DecimalMax("90") Double originLatitude,
        @DecimalMin("-180") @DecimalMax("180") Double originLongitude) {
}
