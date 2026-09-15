package com.citypass.movilidad.dto;

import com.citypass.movilidad.model.enums.BikeStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Registro histórico de un cambio de estado")
public record BikeStatusHistoryResponse(
        @Schema(example = "15") Long id,
        @Schema(description = "Estado anterior; es nulo para el alta inicial", example = "AVAILABLE") BikeStatus previousStatus,
        @Schema(example = "MAINTENANCE") BikeStatus newStatus,
        @Schema(description = "Usuario que realizó el cambio; puede ser nulo hasta integrar Login Federado",
                example = "42") Long changedByUserId,
        @Schema(example = "Mantenimiento preventivo") String reason,
        @Schema(example = "2026-09-08T14:30:00Z") Instant changedAt
) {
}
