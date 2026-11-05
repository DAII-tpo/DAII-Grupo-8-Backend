package com.citypass.movilidad.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Configuración del Login Federado de CityPass+ (Grupo 2).
 *
 * <p>El backend es un resource server: nunca habla LDAP, solo valida el JWT contra el JWKS del
 * IdP. Los "grupos LDAP" llegan en el claim {@code groups} y son los roles del módulo.
 */
@ConfigurationProperties(prefix = "movilidad.auth")
public record AuthProperties(

        /** JWKS del IdP. Es la única forma de obtener la clave: el issuer no resuelve por DNS. */
        String jwkSetUri,

        /** String exacto que el IdP pone en {@code iss}. No es la URL del servicio. */
        @DefaultValue("https://idp.citypass.local") String issuer,

        /** Audience propio del módulo; el claim {@code aud} tiene que contenerlo. */
        @DefaultValue("citypass-movilidad-api") String audience,

        /** Valor exigido en {@code token_use}: distingue persona (human) de servicio (service). */
        @DefaultValue("human") String requiredTokenUse,

        /** Grupo LDAP crudo -> nombre de rol en la tabla {@code roles}. */
        Map<String, String> groupRoles,

        /** Rol para un token cuyo claim {@code groups} viene vacío o sin coincidencias. */
        @DefaultValue("USER") String defaultRole,

        /** {@code users.role} es de un solo valor: con varios grupos gana el primero de la lista. */
        @DefaultValue({"ADMIN", "OPERATOR", "USER"}) List<String> rolePrecedence,

        /** GET abiertos sin token. */
        @DefaultValue({}) List<String> publicGetPaths,

        /** Rutas abiertas a ciudadanos. Vacío = todo lo demás exige token federado. */
        @DefaultValue({}) List<String> citizenOpenPaths,

        /** Usuario usado solo en modo local (security.local.enabled=true), donde no hay token. */
        @DefaultValue("1") Long localUserId) {

    public AuthProperties {
        groupRoles = normalizeGroupRoles(groupRoles);
    }

    /**
     * Normaliza las claves a minúsculas: el casing que entrega LDAP no es confiable, así que el
     * mapeo se compara siempre en minúsculas contra los grupos del token.
     */
    private static Map<String, String> normalizeGroupRoles(Map<String, String> configured) {
        if (configured == null || configured.isEmpty()) {
            return Map.of();
        }
        return configured.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                entry -> entry.getKey().toLowerCase(Locale.ROOT),
                Map.Entry::getValue));
    }

    /** Rol configurado para un grupo LDAP, o {@code null} si ese grupo no está mapeado. */
    public String roleForGroup(String group) {
        return group == null ? null : groupRoles.get(group.toLowerCase(Locale.ROOT));
    }
}
