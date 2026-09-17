package com.citypass.movilidad.dto;

import com.citypass.movilidad.validation.EntityId;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Traslado administrativo entre estaciones")
public record BikeTransferRequest(
        @Schema(description = "ID de la estación de destino", example = "2", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @EntityId Long stationId
) {
}
