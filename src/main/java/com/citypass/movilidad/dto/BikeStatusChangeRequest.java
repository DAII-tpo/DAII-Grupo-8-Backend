package com.citypass.movilidad.dto;

import com.citypass.movilidad.model.enums.BikeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Cambio administrativo de estado")
public record BikeStatusChangeRequest(
        @Schema(description = "Nuevo estado administrativo", example = "MAINTENANCE", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull BikeStatus status,
        @Schema(description = "Motivo del cambio", example = "Mantenimiento preventivo", maxLength = 255)
        @Size(max = 255) String reason
) {
}
