package com.citypass.movilidad.config;

import com.citypass.movilidad.service.CitizenTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Enruta cada token al validador de su emisor: el IdP del Grupo 2 para el personal municipal, y el
 * nuestro para los ciudadanos.
 *
 * <p>No se puede resolver con dos {@code SecurityFilterChain} y {@code securityMatcher}, y eso
 * cierra la discusión: {@code securityMatcher} discrimina por <em>ruta</em>, y un ciudadano y un
 * empleado pegan a las mismas ({@code /api/v1/trips/**}, {@code POST /api/v1/incidents}). La
 * distinción tiene que ser por el contenido del token, dentro de una sola cadena.
 *
 * <p>Precio de usar el resolver: es mutuamente excluyente con {@code .jwt(...)} en el DSL, así que
 * el converter de authorities se setea dentro de cada {@code JwtAuthenticationProvider}. Que es
 * donde corresponde, porque cada emisor necesita el suyo.
 *
 * <p>No es condicional al modo: el resolver se necesita en los dos. {@link FederatedJwtConfig} sí
 * sigue siendo de modo seguro, de ahí el {@code ObjectProvider}.
 */
@Configuration
public class JwtAuthenticationConfig {

    private static final Logger LOG = LoggerFactory.getLogger(JwtAuthenticationConfig.class);

    @Bean
    public AuthenticationManagerResolver<HttpServletRequest> jwtIssuerAuthenticationManagerResolver(
            AuthProperties federatedProperties,
            CitizenAuthProperties citizenProperties,
            ObjectProvider<JwtDecoder> federatedDecoder,
            ObjectProvider<Converter<Jwt, AbstractAuthenticationToken>> federatedConverter,
            CitizenTokenService citizenTokens) {

        Map<String, AuthenticationManager> byIssuer = new LinkedHashMap<>();

        JwtDecoder decoder = federatedDecoder.getIfAvailable();
        Converter<Jwt, AbstractAuthenticationToken> converter = federatedConverter.getIfAvailable();
        if (decoder != null && converter != null) {
            byIssuer.put(federatedProperties.issuer(), managerOf(decoder, converter));
        } else {
            // Modo local: FederatedJwtConfig no está, así que un token del IdP da 401. Es correcto,
            // porque en ese modo no hay nada contra lo que validarlo y su JWKS puede ser inalcanzable.
            LOG.info("Sin el lado federado presente: solo se aceptan tokens de ciudadano");
        }

        byIssuer.put(citizenProperties.issuer(),
                managerOf(citizenTokens.decoder(), citizenConverter()));

        // Un iss desconocido o ausente devuelve null del map, y el resolver responde
        // InvalidBearerTokenException -> 401. El iss se lee sin verificar la firma, que es
        // inevitable y correcto: es solo ruteo, y la firma la comprueba después el manager
        // elegido, que solo acepta su propia clave.
        return new JwtIssuerAuthenticationManagerResolver(iss -> managerFor(byIssuer, iss));
    }

    /**
     * El manager de ese emisor, avisando en WARN si no hay ninguno: es la forma más rápida de
     * detectar que el Grupo 2 cambió el issuer y todo el personal empezó a recibir 401.
     *
     * <p>Se loguea solo el {@code iss} recibido, nunca el token. Un token sin {@code iss} no llega
     * acá: el resolver lo rechaza antes.
     */
    static AuthenticationManager managerFor(Map<String, AuthenticationManager> byIssuer, String iss) {
        AuthenticationManager manager = byIssuer.get(iss);
        if (manager == null && LOG.isWarnEnabled()) {
            LOG.warn("Token con un issuer desconocido: '{}'. Aceptados: {}",
                    sanitize(iss), byIssuer.keySet());
        }
        return manager;
    }

    /** El iss viene sin verificar: se recortan los saltos de línea para que no falsee el log. */
    private static String sanitize(String iss) {
        return iss == null ? null : iss.replaceAll("[\\r\\n\\t]", "_");
    }

    private static AuthenticationManager managerOf(
            JwtDecoder decoder, Converter<Jwt, AbstractAuthenticationToken> converter) {
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(converter);
        return provider::authenticate;
    }

    private static Converter<Jwt, AbstractAuthenticationToken> citizenConverter() {
        return converterOf(new CitizenAuthoritiesConverter());
    }

    private static Converter<Jwt, AbstractAuthenticationToken> converterOf(
            Converter<Jwt, Collection<GrantedAuthority>> authorities) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
