package com.citypass.movilidad.config;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

import java.util.List;

/**
 * Las validaciones de claims que el spec del Login Federado exige rechazar con 401: {@code iss}
 * exacto, {@code exp} vigente, {@code aud} que contenga el audience del módulo y
 * {@code token_use} de persona. La firma RS256 la verifica el decoder, no estos validadores.
 */
public final class FederatedJwtValidators {

    private FederatedJwtValidators() {
    }

    /**
     * Se componen a mano a propósito. {@code JwtValidators.createDefaultWithIssuer(...)} suma en
     * Spring Security 6.5 un {@code JwtTypeValidator} que exige {@code typ} ausente o
     * {@code JWT}: si el IdP emite {@code typ: at+jwt} (lo que manda la RFC 9068 para access
     * tokens), rechazaría todos los tokens con un error opaco. No cambiar por esa factory.
     */
    public static OAuth2TokenValidator<Jwt> of(AuthProperties properties) {
        return of(properties.issuer(), properties.audience(), properties.requiredTokenUse());
    }

    /**
     * La misma cadena para cualquier emisor. La usa también el login local de ciudadanos, que
     * firma sus propios tokens: las reglas son idénticas y conviene que no se dupliquen, para que
     * un cambio no quede aplicado a la mitad de los emisores.
     */
    public static OAuth2TokenValidator<Jwt> of(String issuer, String audience, String tokenUse) {
        return new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                // Compara iss como string plano, que es lo que necesitamos: el issuer no es una URL alcanzable.
                new JwtIssuerValidator(issuer),
                audienceValidator(audience),
                tokenUseValidator(tokenUse));
    }

    /**
     * El claim {@code aud} siempre es lista. Se lee vía {@code JwtClaimValidator} porque
     * {@code MappedJwtClaimSetConverter} normaliza un aud escalar a lista, así que aguanta
     * también un string suelto.
     */
    private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        return new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(audience));
    }

    private static OAuth2TokenValidator<Jwt> tokenUseValidator(String requiredTokenUse) {
        return new JwtClaimValidator<String>(FederatedJwtClaims.TOKEN_USE, requiredTokenUse::equals);
    }
}
