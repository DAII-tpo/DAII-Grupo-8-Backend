package com.citypass.movilidad.dto;

import com.citypass.movilidad.validation.Password;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Datos para registrar una cuenta de ciudadano")
public record CitizenRegisterRequest(

        @Schema(description = "Email, que es el usuario con el que se ingresa",
                example = "ana.perez@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Email @Size(max = 255) String email,

        @Schema(description = "Contraseña: 10 a 72 caracteres, con minúscula, mayúscula y número",
                example = "Bicicleta2026", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Password String password,

        @Schema(description = "Nombre", example = "Ana", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 100) String firstName,

        @Schema(description = "Apellido", example = "Pérez", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 100) String lastName) {
}
