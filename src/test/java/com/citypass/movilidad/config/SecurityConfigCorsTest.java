package com.citypass.movilidad.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cubre el matcheo de origenes de CORS, que es lo unico que decide si el navegador puede
 * consumir la API desde el frontend: un origen que no matchea recibe 403 "Invalid CORS request"
 * antes de llegar al controller, y en modo local (permitAll) no hay ninguna otra regla que
 * lo explique.
 *
 * Se prueba el bean directamente en lugar de levantar el contexto porque el matcheo vive
 * entero en CorsConfiguration#checkOrigin y asi el test no necesita base ni Testcontainers.
 */
class SecurityConfigCorsTest {

    private static final String PROD_ORIGIN = "https://daii-grupo-8-frontend.vercel.app";

    private CorsConfiguration configuration;

    @BeforeEach
    void setUp() {
        SecurityConfig securityConfig = new SecurityConfig();
        ReflectionTestUtils.setField(securityConfig, "allowedOriginPatterns", List.of(
                PROD_ORIGIN,
                "https://daii-grupo-8-frontend-*.vercel.app",
                "http://localhost:5173"
        ));

        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) securityConfig.corsConfigurationSource();
        configuration = source.getCorsConfigurations().get("/**");
    }

    @Test
    void habilitaElDominioDeProduccionDelFrontend() {
        assertThat(configuration.checkOrigin(PROD_ORIGIN)).isEqualTo(PROD_ORIGIN);
    }

    @Test
    void habilitaLosDeployDePreviewQueVercelGeneraPorRama() {
        String branchPreview = "https://daii-grupo-8-frontend-git-feat-login-daii.vercel.app";
        String deployPreview = "https://daii-grupo-8-frontend-a1b2c3d4.vercel.app";

        assertThat(configuration.checkOrigin(branchPreview)).isEqualTo(branchPreview);
        assertThat(configuration.checkOrigin(deployPreview)).isEqualTo(deployPreview);
    }

    @Test
    void habilitaElFrontendLevantadoEnLocal() {
        assertThat(configuration.checkOrigin("http://localhost:5173")).isEqualTo("http://localhost:5173");
    }

    @Test
    void rechazaOtrosProyectosDeVercelYDominiosAjenos() {
        // El comodin esta acotado al nombre del proyecto: sin esto, cualquier front alojado en
        // Vercel podria llamar a la API con credenciales.
        assertThat(configuration.checkOrigin("https://proyecto-ajeno.vercel.app")).isNull();
        assertThat(configuration.checkOrigin("https://daii-grupo-8-frontend.vercel.app.atacante.com")).isNull();
        assertThat(configuration.checkOrigin("http://daii-grupo-8-frontend.vercel.app")).isNull();
    }

    @Test
    void mantieneLasCredencialesHabilitadas() {
        // setAllowedOriginPatterns es justamente lo que permite combinar comodin con credenciales.
        assertThat(configuration.getAllowCredentials()).isTrue();
    }
}
