package com.citypass.movilidad.service;

import com.citypass.movilidad.config.CitizenAuthProperties;
import com.citypass.movilidad.config.FederatedJwtClaims;
import com.citypass.movilidad.config.FederatedJwtValidators;
import com.citypass.movilidad.config.LocalSigningKey;
import com.citypass.movilidad.model.User;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Emite y valida los access tokens de los ciudadanos.
 *
 * <p>Es la única clase que conoce el algoritmo de firma, así que una migración a RS256 queda
 * contenida acá.
 */
@Service
public class CitizenTokenService {

    /**
     * Distingue nuestros tokens de los del IdP, que usan {@code token_use=human}.
     *
     * <p>Es defensa en profundidad: aun si alguien cruzara las claves, un token nuestro nunca
     * pasaría el validador federado ni al revés.
     */
    public static final String TOKEN_USE = "citizen";

    private final CitizenAuthProperties properties;
    private final Clock clock;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    public CitizenTokenService(CitizenAuthProperties properties, Clock clock,
                               @Value("${security.local.enabled:true}") boolean localMode) {
        this.properties = properties;
        this.clock = clock;

        SecretKey key = LocalSigningKey.resolve(properties.secret(), localMode);
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));

        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        // Las mismas reglas que el lado federado, con nuestro issuer y nuestro token_use.
        nimbus.setJwtValidator(FederatedJwtValidators.of(
                properties.issuer(), properties.audience(), TOKEN_USE));
        this.decoder = nimbus;
    }

    /**
     * Access token de un ciudadano.
     *
     * <p>El {@code sub} es el id local en decimal: es inmutable, ya está indexado como clave
     * primaria y no es un dato personal. No lleva claim de rol a propósito: sería un snapshot que
     * envejece y que alguien podría leer como autoritativo, cuando la autorización la decide la
     * authority que otorga el converter.
     */
    public String issueAccessToken(User user) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(properties.audience()))
                .subject(String.valueOf(user.getId()))
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .claim(FederatedJwtClaims.TOKEN_USE, TOKEN_USE)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public Duration accessTokenTtl() {
        return properties.accessTokenTtl();
    }

    /**
     * Decoder de nuestros tokens, para el resolver de issuers.
     *
     * <p>Queda encapsulado y no se expone como bean {@code JwtDecoder} a propósito: habría dos
     * candidatos de ese tipo sin {@code @Primary}, y en los tests el decoder de prueba del lado
     * federado secuestraría uno de los dos según el orden de registro.
     */
    public JwtDecoder decoder() {
        return decoder;
    }
}
