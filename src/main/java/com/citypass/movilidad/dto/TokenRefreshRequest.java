package com.citypass.movilidad.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Refresh token con el que se pide un par nuevo")
public record TokenRefreshRequest(

        @Schema(description = "El refresh token recibido en el login o en el refresh anterior",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 128) String refreshToken) {
}
