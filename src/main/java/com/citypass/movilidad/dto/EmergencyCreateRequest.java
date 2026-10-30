package com.citypass.movilidad.dto;

import com.citypass.movilidad.validation.Latitude;
import com.citypass.movilidad.validation.Longitude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Pedido de ayuda (SOS) con la ubicación actual del usuario, tomada del navegador")
public record EmergencyCreateRequest(
        @Schema(description = "Latitud actual del usuario", example = "-34.6037",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Latitude Double latitude,
        @Schema(description = "Longitud actual del usuario", example = "-58.3816",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull @Longitude Double longitude,
        @Schema(description = "Precisión de la ubicación en metros (coords.accuracy del navegador)", example = "12.5")
        @DecimalMin("0.0") Double accuracyMeters
) {
}
