package com.citypass.movilidad.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Credenciales de ingreso.
 *
 * <p>La contraseña no lleva {@code @Password} a propósito: al entrar no se valida la política, solo
 * se compara. Si la política endureciera mañana, las cuentas viejas tienen que poder seguir
 * entrando. El máximo de 72 está igual porque es el límite real de BCrypt.
 */
@Schema(description = "Credenciales de un ciudadano")
public record CitizenLoginRequest(

        @Schema(description = "Email de la cuenta", example = "ana.perez@example.com",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Email @Size(max = 255) String email,

        @Schema(description = "Contraseña", example = "Bicicleta2026",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 72) String password) {
}
