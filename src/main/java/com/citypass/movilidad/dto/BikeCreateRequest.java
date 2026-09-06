package com.citypass.movilidad.dto;

import com.citypass.movilidad.model.enums.BikeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

@Schema(description = "Datos para registrar una bicicleta")
public record BikeCreateRequest(
        @NotBlank @Size(max = 50) String code,
        Long stationId,
        BikeStatus status,
        @Size(max = 100) String model,
        LocalDate purchaseDate
) {
}
