package com.citypass.movilidad.dto;

import com.citypass.movilidad.model.enums.BikeStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;

@Schema(description = "Bicicleta registrada en el parque")
public record BikeResponse(
        @Schema(example = "1") Long id,
        @Schema(example = "BIKE-001") String code,
        @Schema(description = "ID de la estación actual; puede ser nulo", example = "1") Long stationId,
        @Schema(description = "Nombre de la estación actual; puede ser nulo", example = "Estación Obelisco") String stationName,
        @Schema(example = "AVAILABLE") BikeStatus status,
        @Schema(example = "City Bike 2026") String model,
        @Schema(example = "2026-03-15") LocalDate purchaseDate,
        @Schema(description = "Último mantenimiento completado", example = "2026-09-01T10:30:00Z") Instant lastMaintenanceAt,
        @Schema(example = "2026-09-01T09:00:00Z") Instant createdAt,
        @Schema(example = "2026-09-08T14:30:00Z") Instant updatedAt
) {
}
