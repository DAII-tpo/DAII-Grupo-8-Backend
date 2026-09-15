package com.citypass.movilidad.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@SuppressWarnings("java:S4502")
public class SecurityConfig {

    @Value("${security.jwt.jwk-set-uri:http://localhost:9000/.well-known/jwks.json}")
    private String jwkSetUri;

    // Origenes habilitados para CORS: se configuran con CORS_ALLOWED_ORIGINS (separados por coma).
    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOrigins;

    // Modo local (security.local.enabled=true o por defecto): endpoints abiertos y CORS habilitado
    @Bean
    @ConditionalOnProperty(name = "security.local.enabled", havingValue = "true", matchIfMissing = true)
    public SecurityFilterChain localSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    // Modo seguro / producción (security.local.enabled=false): requiere JWT y valida endpoints
    @Bean
    @ConditionalOnProperty(name = "security.local.enabled", havingValue = "false")
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // TODO (dependencia externa - squad Login Federado): la consulta de disponibilidad
                        // queda pública de forma temporal para que el frontend (MOV-019/MOV-020) pueda
                        // consumirla mientras no exista el authorization server del proyecto. Cuando esté
                        // disponible hay que revisar si estas operaciones requieren usuario autenticado.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/stations/availability",
                                "/api/v1/stations/*/availability",
                                "/api/v1/stations/nearby"
                        ).permitAll()
                        .requestMatchers(
                                "/api/v1/ping",
                                "/actuator/health",
                                "/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder())));
        return http.build();
    }

    @Bean
    @ConditionalOnProperty(name = "security.local.enabled", havingValue = "false")
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // No se puede usar "*" porque allowCredentials esta en true: hay que listar los origenes.
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
