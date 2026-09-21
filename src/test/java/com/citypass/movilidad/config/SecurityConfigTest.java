package com.citypass.movilidad.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigTest {

    private SecurityConfig securityConfig;

    @BeforeEach
    void setUp() {
        securityConfig = new SecurityConfig();
    }

    @Test
    void corsConfigurationSourceAplicaParaCualquierRuta() {
        CorsConfigurationSource source = securityConfig.corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ping");

        CorsConfiguration config = source.getCorsConfiguration(request);

        assertThat(config).isNotNull();
    }

    @Test
    void corsConfigurationSourceContieneOrigenesPermitidos() {
        CorsConfigurationSource source = securityConfig.corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bicicletas");

        CorsConfiguration config = source.getCorsConfiguration(request);

        assertThat(config).isNotNull();
        assertThat(config.getAllowedOrigins()).containsExactlyInAnyOrder("http://localhost:5173", "http://localhost:3000");
    }

    @Test
    void corsConfigurationSourceValidaOrigenesAutorizadosYRechazaNoAutorizados() {
        CorsConfigurationSource source = securityConfig.corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/estaciones");

        CorsConfiguration config = source.getCorsConfiguration(request);

        assertThat(config).isNotNull();
        assertThat(config.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
        assertThat(config.checkOrigin("http://localhost:3000")).isEqualTo("http://localhost:3000");
        assertThat(config.checkOrigin("http://sitio-malicioso.com")).isNull();
    }

    @Test
    void corsConfigurationSourcePermiteMetodosHttpEsperados() {
        CorsConfigurationSource source = securityConfig.corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/viajes");

        CorsConfiguration config = source.getCorsConfiguration(request);

        assertThat(config).isNotNull();
        assertThat(config.getAllowedMethods()).containsExactlyInAnyOrder(
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
        );
    }

    @Test
    void corsConfigurationSourceHabilitaCredencialesYHeadersGlobales() {
        CorsConfigurationSource source = securityConfig.corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/incidencias");

        CorsConfiguration config = source.getCorsConfiguration(request);

        assertThat(config).isNotNull();
        assertThat(config.getAllowCredentials()).isTrue();
        assertThat(config.getAllowedHeaders()).isEqualTo(List.of("*"));
    }
}
