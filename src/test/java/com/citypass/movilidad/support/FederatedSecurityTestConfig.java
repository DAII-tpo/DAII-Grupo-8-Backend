package com.citypass.movilidad.support;

import com.citypass.movilidad.client.FederatedUserInfo;
import com.citypass.movilidad.client.FederatedUserInfoClient;
import com.citypass.movilidad.config.AuthProperties;
import com.citypass.movilidad.config.FederatedJwtDecoders;
import com.citypass.movilidad.config.FederatedJwtValidators;
import com.citypass.movilidad.model.User;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Permite probar el modo seguro sin el IdP: el decoder se arma con la clave pública del
 * {@link TestJwtFactory} pero reutiliza los validadores y el procesador de producción, así que
 * los tokens pasan por la misma cadena de validación que en serio.
 *
 * <p>A diferencia del postprocesador {@code jwt()} de spring-security-test, que saltea el decoder
 * por completo, acá los tokens van firmados de verdad en el header Authorization.
 */
@TestConfiguration
public class FederatedSecurityTestConfig {

    public static final TestJwtFactory TOKENS = new TestJwtFactory();

    @Bean
    @Primary
    public JwtDecoder testJwtDecoder(AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(TOKENS.publicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .jwtProcessorCustomizer(FederatedJwtDecoders::acceptAccessTokenTypes)
                .build();
        decoder.setJwtValidator(FederatedJwtValidators.of(properties));
        return decoder;
    }

    /**
     * Doble del {@code GET /me} del IdP, para que ningún test de integración salga a la red.
     *
     * <p>Devuelve datos derivados del {@code sub}, así se puede afirmar que el perfil llegó de
     * verdad a la fila local en lugar de comprobar solo que no explotó.
     */
    @Bean
    @Primary
    public FederatedUserInfoClient testUserInfoClient() {
        return (accessToken, subject) -> subject == null || subject.isBlank()
                ? Optional.empty()
                : Optional.of(new FederatedUserInfo(
                        subject.toLowerCase(Locale.ROOT) + "@citypass.local", "Persona", subject));
    }

    /** Token de la persona dueña de esa fila, con los grupos LDAP indicados. */
    public static RequestPostProcessor bearer(User user, String... groups) {
        return bearer(user.getExternalUserId(), groups);
    }

    public static RequestPostProcessor bearer(String subject, String... groups) {
        String token = TOKENS.signedToken(subject, List.of(groups));
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }
}
