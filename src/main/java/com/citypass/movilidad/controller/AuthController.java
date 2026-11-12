package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.AuthTokenResponse;
import com.citypass.movilidad.dto.CitizenLoginRequest;
import com.citypass.movilidad.dto.CitizenRegisterRequest;
import com.citypass.movilidad.dto.TokenRefreshRequest;
import com.citypass.movilidad.exception.ErrorResponse;
import com.citypass.movilidad.service.CitizenAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registro e ingreso de ciudadanos.
 *
 * <p>El personal municipal no entra por acá: se autentica contra el IdP del Grupo 2 y manda ese
 * token. Estos endpoints son la puerta de entrada de los usuarios finales.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Validated
@Tag(name = "Autenticación de ciudadanos",
        description = "Registro, ingreso y renovación de tokens de los usuarios finales")
// Sin esto, springdoc les aplicaría el bearerAuth global de OpenApiConfig y la documentación
// pediría un token para poder loguearse, que es circular.
@SecurityRequirements
public class AuthController {

    private final CitizenAuthService citizenAuthService;

    public AuthController(CitizenAuthService citizenAuthService) {
        this.citizenAuthService = citizenAuthService;
    }

    /** POST /auth/register: crea la cuenta y devuelve el primer par de tokens. */
    @PostMapping("/register")
    @Operation(summary = "Registrar una cuenta de ciudadano",
            description = "Crea la cuenta y devuelve el par de tokens, así quien se registra queda "
                    + "logueado sin un segundo paso. No requiere verificación por email")
    @ApiResponse(responseCode = "201", description = "Cuenta creada",
            content = @Content(schema = @Schema(implementation = AuthTokenResponse.class)))
    @ApiResponse(responseCode = "400", description = "El email o la contraseña no cumplen el formato",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "409", description = "Ya existe una cuenta con ese email",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public ResponseEntity<AuthTokenResponse> register(
            @Valid @RequestBody CitizenRegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(citizenAuthService.register(request));
    }

    /** POST /auth/login: valida las credenciales y devuelve el par de tokens. */
    @PostMapping("/login")
    @Operation(summary = "Ingresar con email y contraseña",
            description = "Cinco intentos fallidos en 15 minutos bloquean la cuenta 10 minutos")
    @ApiResponse(responseCode = "200", description = "Ingreso correcto",
            content = @Content(schema = @Schema(implementation = AuthTokenResponse.class)))
    @ApiResponse(responseCode = "401",
            description = "Credenciales incorrectas (INVALID_CREDENTIALS) o cuenta bloqueada por "
                    + "intentos fallidos (ACCOUNT_LOCKED)",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "La cuenta no está habilitada",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public AuthTokenResponse login(@Valid @RequestBody CitizenLoginRequest request) {
        return citizenAuthService.login(request);
    }

    /** POST /auth/refresh: rota el refresh token y devuelve un par nuevo. */
    @PostMapping("/refresh")
    @Operation(summary = "Renovar el par de tokens",
            description = "El refresh token rota: hay que guardar siempre el último. Reusar uno ya "
                    + "gastado corta la sesión entera, porque no se puede distinguir un replay de "
                    + "un cliente desincronizado")
    @ApiResponse(responseCode = "200", description = "Par nuevo emitido",
            content = @Content(schema = @Schema(implementation = AuthTokenResponse.class)))
    @ApiResponse(responseCode = "401",
            description = "El refresh token es desconocido, venció, ya se usó o fue revocado",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "La cuenta no está habilitada",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public AuthTokenResponse refresh(@Valid @RequestBody TokenRefreshRequest request) {
        return citizenAuthService.refresh(request);
    }
}
