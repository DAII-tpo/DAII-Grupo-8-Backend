package com.citypass.movilidad.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Respuestas del cuestionario de triage")
public record EmergencyTriageRequest(
        @Schema(description = "¿Podés moverte?", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Boolean canMove,
        @Schema(description = "¿Estás sangrando?", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Boolean bleeding,
        @Schema(description = "¿Hay un vehículo involucrado?", example = "false",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull Boolean vehicleInvolved
) {
}
