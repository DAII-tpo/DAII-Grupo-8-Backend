package com.citypass.movilidad.config;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Traduce los grupos LDAP del claim {@code groups} a los roles del módulo.
 *
 * <p>Está separado de {@link FederatedAuthoritiesConverter} porque se necesita siempre: la
 * resolución del rol la usa {@code CurrentUserService} también en modo local, mientras que la
 * conversión a authorities de Spring Security solo tiene sentido en modo seguro. Además, un bean
 * que implemente {@code Converter} entra en el slice de todos los {@code @WebMvcTest}, y esta
 * clase no tiene por qué arrastrarlos.
 */
@Component
public class FederatedRoleResolver {

    private final AuthProperties properties;

    public FederatedRoleResolver(AuthProperties properties) {
        this.properties = properties;
    }

    /** Grupos crudos del token, normalizados. Puede faltar, venir vacío o traer entradas en blanco. */
    public List<String> groupsOf(Jwt jwt) {
        List<String> groups = jwt.getClaimAsStringList(FederatedJwtClaims.GROUPS);
        if (groups == null) {
            return List.of();
        }
        return groups.stream()
                .filter(group -> group != null && !group.isBlank())
                .map(group -> group.trim().toLowerCase(Locale.ROOT))
                .toList();
    }

    /** Todos los roles que los grupos del token mapean, sin colapsar. */
    public Set<String> rolesOf(Jwt jwt) {
        Set<String> roles = new LinkedHashSet<>();
        for (String group : groupsOf(jwt)) {
            String role = properties.roleForGroup(group);
            if (role != null) {
                roles.add(role);
            }
        }
        return roles;
    }

    /**
     * Rol que queda en {@code users.role}, que es de un solo valor: gana el primero de
     * {@code role-precedence} que el token tenga, y si no hay ninguno, el rol por defecto.
     */
    public String resolveRole(Jwt jwt) {
        Set<String> rolesDelToken = rolesOf(jwt);
        for (String candidate : properties.rolePrecedence()) {
            if (rolesDelToken.contains(candidate)) {
                return candidate;
            }
        }
        return properties.defaultRole();
    }
}
