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

/** Seguridad: modo local (todo abierto) o modo seguro (JWT), y configuración de CORS. */
@Configuration
@EnableWebSecurity
@SuppressWarnings("java:S4502")
public class SecurityConfig {

    @Value("${security.jwt.jwk-set-uri:http://localhost:9000/.well-known/jwks.json}")
    private String jwkSetUri;

    // Orígenes CORS (CORS_ALLOWED_ORIGINS, separados por coma). Admiten comodines para los previews de Vercel.
    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOriginPatterns;

    // Modo local (default): todos los endpoints abiertos.
    @Bean
    @ConditionalOnProperty(name = "security.local.enabled", havingValue = "true", matchIfMissing = true)
    public SecurityFilterChain localSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    // Modo seguro: exige JWT salvo en los endpoints públicos.
    @Bean
    @ConditionalOnProperty(name = "security.local.enabled", havingValue = "false")
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        // Públicos por ahora; revisar cuando esté el Login Federado.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/stations/availability",
                                "/api/v1/stations/*/availability",
                                "/api/v1/stations/nearby",
                                "/api/v1/stations/recommendation"
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
        // Patterns en vez de origins para admitir comodines. No usar "https://*.vercel.app": habilita a cualquiera.
        configuration.setAllowedOriginPatterns(allowedOriginPatterns);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
