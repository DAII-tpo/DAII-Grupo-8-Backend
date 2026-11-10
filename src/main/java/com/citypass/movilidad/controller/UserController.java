package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.CurrentUserResponse;
import com.citypass.movilidad.exception.ErrorResponse;
import com.citypass.movilidad.service.CurrentUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Datos de la persona autenticada.
 *
 * <p>Va bajo {@code /users} y no bajo {@code /auth}: las rutas de auth son públicas, y
 * {@code SecurityConfig} advierte justamente que un {@code /auth/me} quedaría abierto por
 * accidente si alguien usara un comodín. Este endpoint exige token.
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Usuarios", description = "Identidad y rol de la persona autenticada")
public class UserController {

    private final CurrentUserService currentUserService;

    public UserController(CurrentUserService currentUserService) {
        this.currentUserService = currentUserService;
    }

    /** GET /users/me: quién es la persona autenticada y con qué rol. */
    @GetMapping("/me")
    @Operation(summary = "Consultar el usuario autenticado",
            description = "Devuelve el tipo (CITIZEN o STAFF), el rol efectivo y el perfil. Es la "
                    + "fuente única del rol para el frontend: para el personal municipal se calcula "
                    + "con los grupos LDAP del token, igual que la autorización del resto de la API. "
                    + "En el primer request de un empleado da de alta su fila local")
    @ApiResponse(responseCode = "200", description = "Usuario autenticado",
            content = @Content(schema = @Schema(implementation = CurrentUserResponse.class)))
    @ApiResponse(responseCode = "401", description = "Token ausente o inválido",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @ApiResponse(responseCode = "403", description = "La cuenta no está habilitada",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public CurrentUserResponse me() {
        return currentUserService.describeCurrentUser();
    }
}
