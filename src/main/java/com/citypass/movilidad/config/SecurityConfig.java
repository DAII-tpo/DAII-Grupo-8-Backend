package com.citypass.movilidad.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/** Seguridad: modo local (todo abierto) o modo seguro (JWT federado), y configuración de CORS. */
@Configuration
@EnableWebSecurity
@SuppressWarnings("java:S4502")
public class SecurityConfig {

    /** Endpoints de infraestructura y documentación, abiertos en cualquier modo. */
    private static final String[] INFRA_PATHS = {
            "/api/v1/ping",
            "/actuator/health",
            "/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    /**
     * Puerta de entrada del login de ciudadanos: por definición no puede exigir token.
     *
     * <p>Enumerados uno por uno y no {@code /api/v1/auth/**}: si mañana alguien agrega
     * {@code /api/v1/auth/me}, el comodín lo dejaría abierto por accidente.
     */
    private static final String[] AUTH_PATHS = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh"
    };

    // Orígenes CORS (CORS_ALLOWED_ORIGINS, separados por coma). Admiten comodines para los previews de Vercel.
    @Value("${app.cors.allowed-origins}")
    private List<String> allowedOriginPatterns;

    /**
     * Modo local (default): todos los endpoints abiertos, pero el bearer se valida si viene.
     *
     * <p>Montar el resource server acá es lo que hace que el login de ciudadanos funcione de verdad
     * en modo local. Sin esto, un token se ignora en silencio: no queda nada en el
     * {@code SecurityContext}, {@code @CurrentUserId} cae al usuario demo, y un ciudadano vería y
     * crearía los viajes de ese usuario. Sin error, con datos cruzados entre personas.
     *
     * <p>El bearer es <em>opcional</em>: sin header el request entra anónimo y todo sigue abierto
     * como antes; con un header inválido da 401, que es lo correcto.
     *
     * <p>El resolver llega como {@code ObjectProvider} porque los {@code @WebMvcTest} incluyen los
     * beans {@code SecurityFilterChain} pero no las {@code @Configuration} comunes: si fuera un
     * parámetro obligatorio, esos slices no arrancarían. Ausente, esta cadena se comporta como
     * antes del login de ciudadanos.
     */
    @Bean
    @ConditionalOnProperty(name = "security.local.enabled", havingValue = "true", matchIfMissing = true)
    public SecurityFilterChain localSecurityFilterChain(
            HttpSecurity http,
            ObjectProvider<AuthenticationManagerResolver<HttpServletRequest>> resolver)
            throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        AuthenticationManagerResolver<HttpServletRequest> disponible = resolver.getIfAvailable();
        if (disponible != null) {
            http.oauth2ResourceServer(oauth2 -> oauth2.authenticationManagerResolver(disponible));
        }
        return http.build();
    }

    /**
     * Modo seguro: exige el JWT del Login Federado salvo en los endpoints públicos.
     *
     * <p>Las dependencias llegan como parámetros del método y no por constructor a propósito:
     * {@code SecurityConfigCorsTest} instancia esta clase con {@code new SecurityConfig()}.
     */
    @Bean
    @ConditionalOnProperty(name = "security.local.enabled", havingValue = "false")
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthProperties authProperties,
            AuthenticationManagerResolver<HttpServletRequest> authenticationManagerResolver)
            throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> {
                    permitIfAny(auth, HttpMethod.GET, authProperties.publicGetPaths());
                    auth.requestMatchers(INFRA_PATHS).permitAll();
                    auth.requestMatchers(AUTH_PATHS).permitAll();
                    // Quedó sin uso: ahora un ciudadano tiene token propio, así que
                    // anyRequest().authenticated() ya le da acceso. Ver el comentario de la lista.
                    permitIfAny(auth, null, authProperties.citizenOpenPaths());
                    auth.anyRequest().authenticated();
                })
                // El resolver y no .jwt(...): son mutuamente excluyentes, y hacen falta dos
                // emisores. Ver JwtAuthenticationConfig por qué no se separa en dos cadenas.
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationManagerResolver(authenticationManagerResolver));
        return http.build();
    }

    /**
     * Abre esas rutas, y ninguna si la lista viene vacía.
     *
     * <p>El chequeo de longitud no es cosmético: {@code requestMatchers(metodo)} sin patrones
     * matchea <em>todas</em> las rutas de ese método, así que una property vacía dejaría abierta
     * la API entera. Con la lista vacía no se registra ningún matcher y todo cae en
     * {@code anyRequest().authenticated()}.
     */
    private static void permitIfAny(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry auth,
            HttpMethod method, List<String> configured) {
        String[] paths = pathsOf(configured);
        if (paths.length == 0) {
            return;
        }
        if (method == null) {
            auth.requestMatchers(paths).permitAll();
        } else {
            auth.requestMatchers(method, paths).permitAll();
        }
    }

    private static String[] pathsOf(List<String> configured) {
        if (configured == null) {
            return new String[0];
        }
        return configured.stream().filter(path -> path != null && !path.isBlank()).map(String::trim)
                .toArray(String[]::new);
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
