package com.citypass.movilidad.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Traslado administrativo entre estaciones")
public record BikeTransferRequest(@NotNull Long stationId) {
}
