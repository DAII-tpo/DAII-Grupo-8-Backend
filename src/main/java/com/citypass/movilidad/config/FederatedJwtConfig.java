package com.citypass.movilidad.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Decoder y converter del Login Federado (Grupo 2). Solo se activa en modo seguro, igual que la
 * cadena de seguridad que los usa.
 */
@Configuration
@ConditionalOnProperty(name = "security.local.enabled", havingValue = "false")
public class FederatedJwtConfig {

    @Bean
    public OAuth2TokenValidator<Jwt> federatedJwtValidator(AuthProperties properties) {
        return FederatedJwtValidators.of(properties);
    }

    /**
     * Restringir el algoritmo en el decoder bloquea {@code alg: none} y la confusión con HS256
     * antes de que corra cualquier validador. La clave se busca por {@code kid} contra el JWKS,
     * que Nimbus cachea y refetchea solo ante un kid desconocido.
     *
     * <p>{@code ConditionalOnMissingBean} deja que un test aporte un decoder armado con su propia
     * clave pública, reutilizando los mismos validadores de producción.
     */
    @Bean
    @ConditionalOnMissingBean(JwtDecoder.class)
    public JwtDecoder jwtDecoder(AuthProperties properties, OAuth2TokenValidator<Jwt> validator) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .jwtProcessorCustomizer(FederatedJwtDecoders::acceptAccessTokenTypes)
                .build();
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    public FederatedAuthoritiesConverter federatedAuthoritiesConverter(
            AuthProperties properties, FederatedRoleResolver roleResolver) {
        return new FederatedAuthoritiesConverter(properties, roleResolver);
    }

    @Bean
    public Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter(
            FederatedAuthoritiesConverter authoritiesConverter) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return converter;
    }
}
