package com.citypass.movilidad.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cablea los beans del modo seguro sin levantar Spring.
 *
 * <p>Cubre el decoder de producción, que en los tests de integración queda tapado por el de
 * prueba. No llama al IdP: {@code withJwkSetUri} es lazy y no descarga nada al construirse.
 */
class FederatedJwtConfigTest {

    private final FederatedJwtConfig config = new FederatedJwtConfig();
    private final AuthProperties properties = new AuthProperties(
            "https://api.citypass-login.pp.ua/.well-known/jwks.json",
            "https://idp.citypass.local", "citypass-movilidad-api", "human",
            Map.of("admin", "ADMIN"), "USER", List.of("ADMIN", "OPERATOR", "USER"),
            List.of(), List.of(), 1L);

    @Test
    void armaElDecoderConElJwksConfigurado() {
        OAuth2TokenValidator<Jwt> validator = config.federatedJwtValidator(properties);
        assertThat(config.jwtDecoder(properties, validator)).isInstanceOf(NimbusJwtDecoder.class);
    }

    @Test
    void elValidadorRechazaUnTokenQueNoCumpleElContrato() {
        OAuth2TokenValidator<Jwt> validator = config.federatedJwtValidator(properties);
        Jwt ajeno = Jwt.withTokenValue("token").header("alg", "RS256")
                .issuer("https://idp.atacante.local")
                .subject("U000042")
                .build();
        assertThat(validator.validate(ajeno).hasErrors()).isTrue();
    }

    /** El converter enchufado es el que traduce los grupos LDAP a authorities. */
    @Test
    void elConverterDeAutenticacionUsaLosGruposDelToken() {
        FederatedAuthoritiesConverter authorities =
                config.federatedAuthoritiesConverter(properties, new FederatedRoleResolver(properties));
        AbstractAuthenticationToken token = config.jwtAuthenticationConverter(authorities)
                .convert(Jwt.withTokenValue("token").header("alg", "RS256")
                        .subject("U000042")
                        .claim("groups", List.of("admin"))
                        .build());

        assertThat(token).isNotNull();
        assertThat(token.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_ADMIN", "GROUP_admin", "ROLE_USER");
    }

    @Test
    void sinGruposSoloOtorgaElRolPorDefecto() {
        FederatedAuthoritiesConverter authorities =
                config.federatedAuthoritiesConverter(properties, new FederatedRoleResolver(properties));
        AbstractAuthenticationToken token = config.jwtAuthenticationConverter(authorities)
                .convert(Jwt.withTokenValue("token").header("alg", "RS256").subject("U000042").build());

        assertThat(token).isNotNull();
        assertThat(token.getAuthorities())
                .containsExactly(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
