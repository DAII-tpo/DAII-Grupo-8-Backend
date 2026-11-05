package com.citypass.movilidad.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Convierte el token en authorities de Spring Security.
 *
 * <p>Emite un {@code ROLE_*} por cada rol mapeado, más un {@code GROUP_*} por cada grupo crudo,
 * para poder escribir reglas sobre un grupo puntual sin tocar el mapeo de roles.
 *
 * <p>No es un {@code @Component}: lo declara {@link FederatedJwtConfig}, que solo está activo en
 * modo seguro. Un bean que implemente {@code Converter} entraría en el slice de todos los
 * {@code @WebMvcTest}, y acá no hace falta.
 */
public class FederatedAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String ROLE_PREFIX = "ROLE_";
    private static final String GROUP_PREFIX = "GROUP_";

    private final AuthProperties properties;
    private final FederatedRoleResolver roleResolver;

    public FederatedAuthoritiesConverter(AuthProperties properties, FederatedRoleResolver roleResolver) {
        this.properties = properties;
        this.roleResolver = roleResolver;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        // El rol por defecto va siempre: un token con groups vacío sigue siendo válido y
        // autenticado, y tiene que poder llegar a los endpoints que no son de admin.
        authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + properties.defaultRole()));

        for (String group : roleResolver.groupsOf(jwt)) {
            authorities.add(new SimpleGrantedAuthority(GROUP_PREFIX + group));
            String role = properties.roleForGroup(group);
            if (role != null) {
                authorities.add(new SimpleGrantedAuthority(ROLE_PREFIX + role));
            }
        }
        return authorities;
    }
}
