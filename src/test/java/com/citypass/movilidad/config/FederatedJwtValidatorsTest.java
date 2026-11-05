package com.citypass.movilidad.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Las cuatro validaciones de claims que el spec del Login Federado exige (iss, exp, aud,
 * token_use). Sin contexto de Spring ni contenedor: son milisegundos y cubren el núcleo de
 * seguridad caso por caso.
 */
class FederatedJwtValidatorsTest {

    private static final String ISSUER = "https://idp.citypass.local";
    private static final String AUDIENCE = "citypass-movilidad-api";

    private final OAuth2TokenValidator<Jwt> validator = FederatedJwtValidators.of(authProperties());

    private static AuthProperties authProperties() {
        return new AuthProperties(
                "https://api.citypass-login.pp.ua/.well-known/jwks.json",
                ISSUER, AUDIENCE, "human",
                Map.of(), "USER", List.of("ADMIN", "OPERATOR", "USER"),
                List.of(), List.of(), 1L);
    }

    /** Token válido de referencia; cada test lo degrada en un solo claim. */
    private static Jwt.Builder validToken() {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .header("kid", "test-kid")
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject("U000042")
                .claim("token_use", "human")
                .issuedAt(now)
                .expiresAt(now.plus(15, ChronoUnit.MINUTES));
    }

    @Test
    void aceptaUnTokenQueCumpleLasCincoValidaciones() {
        assertThat(validate(validToken())).isFalse();
    }

    @Test
    void rechazaUnTokenDeOtroIssuer() {
        assertThat(validate(validToken().issuer("https://idp.atacante.local"))).isTrue();
    }

    @Test
    void rechazaUnTokenVencido() {
        Instant pasado = Instant.now().minus(1, ChronoUnit.HOURS);
        assertThat(validate(validToken().issuedAt(pasado).expiresAt(pasado.plus(15, ChronoUnit.MINUTES))))
                .isTrue();
    }

    @Test
    void rechazaUnTokenCuyoAudienceEsDeOtroModulo() {
        assertThat(validate(validToken().audience(List.of("citypass-reclamos-api")))).isTrue();
    }

    @Test
    void rechazaUnTokenSinAudience() {
        assertThat(validate(validToken().claim("aud", null))).isTrue();
    }

    /** El aud siempre es lista y puede traer varios: basta con que esté el nuestro. */
    @Test
    void aceptaUnAudienceConVariosValoresSiIncluyeElDelModulo() {
        assertThat(validate(validToken().audience(List.of("citypass-reclamos-api", AUDIENCE)))).isFalse();
    }

    @Test
    void rechazaUnTokenDeServicioEnLugarDePersona() {
        assertThat(validate(validToken().claim("token_use", "service"))).isTrue();
    }

    @Test
    void rechazaUnTokenSinElClaimTokenUse() {
        assertThat(validate(validToken().claim("token_use", null))).isTrue();
    }

    private boolean validate(Jwt.Builder token) {
        OAuth2TokenValidatorResult result = validator.validate(token.build());
        return result.hasErrors();
    }
}
