package com.citypass.movilidad.exception;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Formato común de error de la API")
public record ErrorResponse(
        @Schema(example = "2026-09-08T14:30:00Z") Instant timestamp,
        @Schema(example = "404") int status,
        @Schema(example = "Not Found") String error,
        @Schema(example = "Estación no encontrada: 99") String message,
        @Schema(example = "/api/v1/stations/99") String path
) {
}
