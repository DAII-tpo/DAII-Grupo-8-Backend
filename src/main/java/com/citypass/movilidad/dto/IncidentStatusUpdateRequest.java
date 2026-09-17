package com.citypass.movilidad.dto;

import com.citypass.movilidad.model.enums.BikeIncidentStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Cambio de estado administrativo de una incidencia")
public record IncidentStatusUpdateRequest(
        @Schema(example = "UNDER_REVIEW") @NotNull BikeIncidentStatus status
) {
}
