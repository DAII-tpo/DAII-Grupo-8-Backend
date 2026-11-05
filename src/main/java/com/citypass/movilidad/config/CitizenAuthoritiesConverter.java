package com.citypass.movilidad.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;

/**
 * Authorities de un ciudadano: {@code ROLE_CITIZEN} y nada más.
 *
 * <p>Deliberadamente <strong>no</strong> otorga {@code ROLE_USER}, que es el que recibe todo token
 * federado válido. Con eso las dos poblaciones quedan disjuntas en authorities, y los
 * {@code @PreAuthorize("hasRole('ADMIN')")} del personal siguen negando a un ciudadano sin tocar
 * una línea.
 *
 * <p>El rol sale del emisor del token y no de {@code users.role}: es la misma asimetría que ya
 * existe del lado federado, donde la autorización la decide el token y la columna es un snapshot.
 *
 * <p>No es un {@code @Component}: lo construye {@link JwtAuthenticationConfig}. Un bean que
 * implemente {@code Converter} entra en el slice de todos los {@code @WebMvcTest}, y acá no hace
 * falta.
 */
public class CitizenAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    public static final String ROLE_CITIZEN = "ROLE_CITIZEN";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        return List.of(new SimpleGrantedAuthority(ROLE_CITIZEN));
    }
}
