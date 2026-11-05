package com.citypass.movilidad.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Par de tokens y los datos de la cuenta, para que el frontend no tenga que decodificar el JWT.
 *
 * <p>El rol va acá y <strong>no</strong> como claim del access token: dentro del token sería un
 * snapshot que envejece y que alguien podría leer como autoritativo. Acá es informativo.
 */
@Schema(description = "Resultado de un login, registro o refresh")
public record AuthTokenResponse(

        @Schema(description = "Access token, para el header Authorization")
        String accessToken,

        @Schema(description = "Siempre \"Bearer\"", example = "Bearer")
        String tokenType,

        @Schema(description = "Segundos de vida del access token", example = "1800")
        long expiresIn,

        @Schema(description = "Refresh token. Rota en cada uso: hay que guardar siempre el último")
        String refreshToken,

        @Schema(description = "ID del usuario", example = "42")
        Long userId,

        @Schema(description = "Email de la cuenta", example = "ana.perez@example.com")
        String email,

        @Schema(description = "Nombre", example = "Ana")
        String firstName,

        @Schema(description = "Apellido", example = "Pérez")
        String lastName,

        @Schema(description = "Rol del usuario", example = "USER")
        String role) {
}
