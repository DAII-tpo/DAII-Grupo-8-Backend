package com.citypass.movilidad.dto;

import com.citypass.movilidad.validation.EntityId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Inicio de viaje a partir de una bicicleta disponible")
public record TripStartRequest(
        @Schema(description = "ID de la bicicleta a retirar; debe estar AVAILABLE", example = "1")
        @NotNull @EntityId Long bikeId
) {
}
