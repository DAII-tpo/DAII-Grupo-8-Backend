package com.citypass.movilidad.dto;

import com.citypass.movilidad.model.enums.BikeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

@Schema(description = "Datos para registrar una bicicleta")
public record BikeCreateRequest(
        @Schema(description = "Código único de la bicicleta", example = "BIKE-001", maxLength = 50)
        @NotBlank @Size(max = 50) String code,
        @Schema(description = "ID de la estación inicial. Obligatorio cuando el estado es AVAILABLE", example = "1")
        Long stationId,
        @Schema(description = "Estado inicial. Si se omite se utiliza AVAILABLE; IN_USE no está permitido",
                example = "AVAILABLE")
        BikeStatus status,
        @Schema(description = "Modelo de la bicicleta", example = "City Bike 2026", maxLength = 100)
        @Size(max = 100) String model,
        @Schema(description = "Fecha de compra", example = "2026-03-15")
        LocalDate purchaseDate
) {
}
