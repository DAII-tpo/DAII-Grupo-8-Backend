package com.citypass.movilidad.support;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

/**
 * Emite tokens firmados como los del Login Federado, sin depender del IdP ni de ningún servidor.
 *
 * <p>{@code nimbus-jose-jwt} ya viene con spring-boot-starter-oauth2-resource-server, así que no
 * agrega dependencias (y el gate de Sonar bloquea, conviene no sumar WireMock para esto).
 */
public final class TestJwtFactory {

    public static final String ISSUER = "https://idp.citypass.local";
    public static final String AUDIENCE = "citypass-movilidad-api";
    public static final String KEY_ID = "test-kid";

    private final RSAKey key;

    public TestJwtFactory() {
        try {
            this.key = new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo generar el par RSA de prueba", ex);
        }
    }

    /** Clave pública con la que el test arma su propio decoder. */
    public RSAPublicKey publicKey() {
        try {
            return key.toRSAPublicKey();
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo exportar la clave pública de prueba", ex);
        }
    }

    /** Claims de un access token de persona válido para este módulo. */
    public JWTClaimsSet.Builder validClaims(String subject, List<String> groups) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .subject(subject)
                .claim("preferred_username", "jperez")
                .claim("module", "movilidad")
                .claim("groups", groups)
                .claim("token_use", "human")
                .claim("namespace", "com.citypass.movilidad")
                .claim("ver", 1)
                .jwtID("jti-" + subject)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(15, ChronoUnit.MINUTES)));
    }

    public String signedToken(String subject, List<String> groups) {
        return sign(validClaims(subject, groups).build());
    }

    /** Firma con la clave buena. El {@code typ} va a propósito: así emite un IdP RFC 9068. */
    public String sign(JWTClaimsSet claims) {
        return sign(claims, key);
    }

    /** Firma con otro par RSA, para el caso de firma que no valida contra el JWKS. */
    public String signWithForeignKey(JWTClaimsSet claims) {
        try {
            return sign(claims, new RSAKeyGenerator(2048).keyID(KEY_ID).generate());
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo generar el par RSA ajeno", ex);
        }
    }

    /** Token HS256, para verificar que el decoder rechaza la confusión de algoritmo. */
    public String signWithHmac(JWTClaimsSet claims) {
        try {
            byte[] secret = new byte[32];
            java.util.Arrays.fill(secret, (byte) 7);
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(KEY_ID).build(), claims);
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo firmar el token HS256 de prueba", ex);
        }
    }

    private static String sign(JWTClaimsSet claims, RSAKey signingKey) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(signingKey.getKeyID())
                            .type(new JOSEObjectType("at+jwt"))
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException("No se pudo firmar el token de prueba", ex);
        }
    }
}
