package com.citypass.movilidad.config;

import com.citypass.movilidad.support.TestJwtFactory;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Las cinco validaciones contra el decoder real, con tokens firmados de verdad y sin servidor
 * HTTP: el decoder se arma con la clave pública del test pero reutiliza los validadores de
 * producción, así que lo que se prueba es la cadena que corre en serio.
 */
class FederatedJwtDecoderTest {

    private final TestJwtFactory tokens = new TestJwtFactory();
    private final NimbusJwtDecoder decoder = decoder();

    private static AuthProperties authProperties() {
        return new AuthProperties(
                "https://api.citypass-login.pp.ua/.well-known/jwks.json",
                TestJwtFactory.ISSUER, TestJwtFactory.AUDIENCE, "human",
                Map.of("admin", "ADMIN"), "USER", List.of("ADMIN", "USER"),
                List.of(), List.of(), 1L);
    }

    private NimbusJwtDecoder decoder() {
        NimbusJwtDecoder built = NimbusJwtDecoder.withPublicKey(tokens.publicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .jwtProcessorCustomizer(FederatedJwtDecoders::acceptAccessTokenTypes)
                .build();
        built.setJwtValidator(FederatedJwtValidators.of(authProperties()));
        return built;
    }

    @Test
    void aceptaUnAccessTokenDePersonaDelModulo() {
        Jwt jwt = decoder.decode(tokens.signedToken("U000042", List.of("admin")));
        assertThat(jwt.getSubject()).isEqualTo("U000042");
        assertThat(jwt.getClaimAsStringList("groups")).containsExactly("admin");
    }

    /**
     * El IdP del Grupo 2 hoy manda el header sin {@code typ}, pero la RFC 9068 pide {@code at+jwt}
     * para access tokens, así que el decoder tiene que aceptarlo si algún día lo agregan.
     * Este test falla si se saca el jwtProcessorCustomizer del decoder.
     */
    @Test
    void aceptaElTypAtJwtPorSiElIdpLoAgrega() {
        assertThat(decoder.decode(tokens.signedToken("U000042", List.of()))).isNotNull();
    }

    @Test
    void rechazaUnTokenFirmadoConOtraClave() {
        String token = tokens.signWithForeignKey(tokens.validClaims("U000042", List.of()).build());
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    /** Restringir el algoritmo en el decoder es lo que bloquea la confusión RS256/HS256. */
    @Test
    void rechazaUnTokenFirmadoConHmac() {
        String token = tokens.signWithHmac(tokens.validClaims("U000042", List.of()).build());
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rechazaUnTokenDeOtroIssuer() {
        assertRechazado(tokens.validClaims("U000042", List.of()).issuer("https://idp.atacante.local"));
    }

    @Test
    void rechazaUnTokenVencido() {
        Instant pasado = Instant.now().minus(1, ChronoUnit.HOURS);
        assertRechazado(tokens.validClaims("U000042", List.of())
                .issueTime(Date.from(pasado))
                .expirationTime(Date.from(pasado.plus(15, ChronoUnit.MINUTES))));
    }

    @Test
    void rechazaUnTokenDeOtroModulo() {
        assertRechazado(tokens.validClaims("U000042", List.of())
                .audience(List.of("citypass-reclamos-api")));
    }

    @Test
    void rechazaUnTokenDeServicio() {
        assertRechazado(tokens.validClaims("U000042", List.of()).claim("token_use", "service"));
    }

    private void assertRechazado(JWTClaimsSet.Builder claims) {
        String token = tokens.sign(claims.build());
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
}
