package com.citypass.movilidad.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * La persona autenticada, tal como la ve el backend.
 *
 * <p>Es la fuente única del rol para el frontend: el mapeo grupo LDAP → rol vive solo en
 * {@code application.properties}, así que el front se lo pregunta al back en lugar de calcularlo.
 * El rol sale del token y no de {@code users.role}, que es un snapshot.
 */
@Schema(description = "Usuario autenticado: tipo, rol efectivo y datos de perfil")
public record CurrentUserResponse(

        @Schema(description = "ID local del usuario (users.id)", example = "42")
        Long id,

        @Schema(description = "Población a la que pertenece: ciudadano (login propio) o personal "
                + "municipal (IdP del Grupo 2)", allowableValues = {"CITIZEN", "STAFF"},
                example = "STAFF")
        String type,

        @Schema(description = "Rol efectivo del token", allowableValues = {"CITIZEN", "ADMIN", "USER"},
                example = "ADMIN")
        String role,

        @Schema(description = "Nombre", example = "Ana")
        String firstName,

        @Schema(description = "Apellido", example = "Pérez")
        String lastName,

        @Schema(description = "Email", example = "ana.perez@example.com")
        String email,

        @Schema(description = "Grupos LDAP del token, normalizados (trim y minúsculas). Vacío para "
                + "ciudadanos", example = "[\"admin\"]")
        List<String> groups) {

    public static final String TYPE_CITIZEN = "CITIZEN";
    public static final String TYPE_STAFF = "STAFF";
    public static final String ROLE_CITIZEN = "CITIZEN";
}
