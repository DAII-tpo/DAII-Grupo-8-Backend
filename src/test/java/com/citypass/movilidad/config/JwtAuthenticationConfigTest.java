package com.citypass.movilidad.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Ruteo por issuer: el iss conocido elige su manager y el desconocido no elige ninguno (401). */
class JwtAuthenticationConfigTest {

    private final AuthenticationManager federado = authentication -> authentication;
    private final Map<String, AuthenticationManager> byIssuer =
            Map.of("https://idp.citypass.local", federado);

    @Test
    void eligeElManagerDelIssuerConocido() {
        assertThat(JwtAuthenticationConfig.managerFor(byIssuer, "https://idp.citypass.local"))
                .isSameAs(federado);
    }

    @Test
    void unIssuerDesconocidoNoTieneManager() {
        assertThat(JwtAuthenticationConfig.managerFor(byIssuer, "https://idp.otro.local\nfalso"))
                .isNull();
    }
}
