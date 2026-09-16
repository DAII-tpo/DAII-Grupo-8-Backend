package com.citypass.movilidad.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Devolución de la bicicleta al finalizar un viaje")
public record TripEndRequest(
        @Schema(description = "ID de la estación destino; debe estar habilitada y con anclajes libres", example = "2")
        @NotNull Long destinationStationId
) {
}
