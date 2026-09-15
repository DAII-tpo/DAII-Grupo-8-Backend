package com.citypass.movilidad.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "Estación cercana a una ubicación, con su distancia y disponibilidad actual")
public record NearbyStationResponse(

        @Schema(description = "ID de la estación", example = "1")
        Long stationId,

        @Schema(description = "Nombre de la estación", example = "Estación Obelisco")
        String stationName,

        @Schema(description = "Dirección de la estación", example = "Av. 9 de Julio 1000")
        String address,

        @Schema(description = "Latitud de la estación", example = "-34.6037")
        BigDecimal latitude,

        @Schema(description = "Longitud de la estación", example = "-58.3816")
        BigDecimal longitude,

        @Schema(description = "Distancia en metros desde la ubicación consultada", example = "320")
        Integer distanceMeters,

        @Schema(description = "Cantidad total de anclajes de la estación", example = "20")
        Integer capacity,

        @Schema(description = "Bicicletas disponibles para retirar", example = "7")
        Integer availableBikes,

        @Schema(description = "Anclajes libres para devolver", example = "13")
        Integer availableSlots
) {
}
