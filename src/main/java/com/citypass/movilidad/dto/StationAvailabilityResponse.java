package com.citypass.movilidad.dto;

import com.citypass.movilidad.model.enums.StationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Disponibilidad actual de una estación, calculada al momento de la consulta")
public record StationAvailabilityResponse(

        @Schema(description = "ID de la estación", example = "1")
        Long stationId,

        @Schema(description = "Nombre de la estación", example = "Estación 9 de Julio")
        String stationName,

        @Schema(description = "Estado operativo de la estación", example = "ACTIVE")
        StationStatus status,

        @Schema(description = "Cantidad total de anclajes de la estación", example = "20")
        Integer capacity,

        @Schema(description = "Bicicletas en estado AVAILABLE que pueden retirarse", example = "7")
        Integer availableBikes,

        @Schema(description = "Anclajes libres para devolver una bicicleta", example = "13")
        Integer availableSlots,

        @Schema(description = "Momento en el que se calculó la disponibilidad", example = "2026-09-08T14:30:00Z")
        Instant checkedAt
) {
}
