package com.citypass.movilidad.analytics;

import com.citypass.movilidad.config.FederatedJwtDecoders;
import com.citypass.movilidad.config.FederatedJwtValidators;
import com.citypass.movilidad.eventbus.publish.ServiceTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.net.HttpURLConnection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

/** Solo consulta el embed de Movilidad; sin reenvío de headers ni identidad del navegador. */
@Service
public class AnalyticsEmbedClient {
    static final String NAMESPACE = "com.citypass.movilidad";
    static final String EMBED_PATH = "/embed/movilidad";
    static final String NOT_CONFIGURED = "ANALYTICS_NOT_CONFIGURED";
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private final AnalyticsProperties properties;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private Resources configured;

    @Autowired
    public AnalyticsEmbedClient(AnalyticsProperties properties) {
        this.properties = properties;
        this.clock = Clock.systemUTC();
    }

    AnalyticsEmbedClient(AnalyticsProperties properties, RestClient client, JwtDecoder decoder, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.configured = resources(client, decoder);
    }

    public Embed fetch() {
        Resources resources = configured();
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                String token = validatedToken(resources);
                Raw raw = resources.client().get().uri(baseUrl() + EMBED_PATH)
                        .headers(h -> h.setBearerAuth(token))
                        .accept(MediaType.TEXT_HTML, MediaType.APPLICATION_JSON)
                        .exchange((request, response) -> new Raw(response.getStatusCode().value(),
                                response.getHeaders().getContentType(),
                                response.getHeaders().getFirst("Content-Security-Policy"),
                                response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1)));
                if (raw.status() == 401 && attempt == 0) {
                    resources.tokens().invalidate();
                    continue;
                }
                return translate(raw, token);
            }
            throw failure(HttpStatus.BAD_GATEWAY, "ANALYTICS_UPSTREAM", "Analítica rechazó la conexión del módulo.");
        } catch (AnalyticsException ex) {
            throw ex;
        } catch (Exception ex) {
            // RestClient/JOSE pueden incluir el body remoto en su excepción. No se propaga ni se loguea.
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "ANALYTICS_UNAVAILABLE", "No se pudo consultar Analítica. Intentá nuevamente.");
        }
    }

    private String validatedToken(Resources resources) {
        String token = resources.tokens().token();
        try {
            Jwt jwt = resources.decoder().decode(token);
            if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(clock.instant())) {
                throw new IllegalArgumentException("Token de servicio vencido");
            }
            return token;
        } catch (RuntimeException ex) {
            resources.tokens().invalidate();
            throw failure(HttpStatus.BAD_GATEWAY, "ANALYTICS_SERVICE_TOKEN", "Identidad no entregó un token válido para Analítica.");
        }
    }

    private Embed translate(Raw raw, String token) {
        if (raw.status() < 200 || raw.status() >= 300) {
            HttpStatus status = raw.status() >= 500 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
            throw failure(status, "ANALYTICS_UPSTREAM", "Analítica no pudo entregar el tablero (HTTP " + raw.status() + ").");
        }
        if (raw.body().length > MAX_RESPONSE_BYTES || raw.type() == null) throw invalidResponse();
        String body = new String(raw.body(), StandardCharsets.UTF_8);
        if (MediaType.APPLICATION_JSON.isCompatibleWith(raw.type())) {
            try {
                var json = mapper.readTree(body);
                if (json == null || !"ok".equals(json.path("status").asText())) throw invalidResponse();
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw invalidResponse();
            }
            return new Embed(MediaType.APPLICATION_JSON, "{\"status\":\"ok\"}");
        }
        if (MediaType.TEXT_HTML.isCompatibleWith(raw.type())) {
            String html = AnalyticsHtml.isolate(body, raw.policy());
            if (html == null || html.contains(token)) throw invalidResponse();
            return new Embed(MediaType.parseMediaType("text/html;charset=UTF-8"), html);
        }
        throw invalidResponse();
    }

    private synchronized Resources configured() {
        if (configured != null) return configured;
        if (blank(properties.url()) || blank(properties.tokenUrl()) || blank(properties.issuer())
                || blank(properties.jwkSetUri()) || blank(properties.clientId()) || blank(properties.clientSecret())) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED, "La conexión con Analítica todavía no está configurada.");
        }
        for (String url : List.of(properties.url(), properties.tokenUrl(), properties.jwkSetUri())) validateUrl(url);
        if (properties.connectTimeoutMillis() <= 0 || properties.readTimeoutMillis() <= 0) {
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED, "Los tiempos de conexión de Analítica no son válidos.");
        }
        var factory = new NoRedirectRequestFactory();
        factory.setConnectTimeout(properties.connectTimeoutMillis());
        factory.setReadTimeout(properties.readTimeoutMillis());
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256).restOperations(new RestTemplate(factory))
                .jwtProcessorCustomizer(FederatedJwtDecoders::acceptAccessTokenTypes).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                FederatedJwtValidators.of(properties.issuer(), "citypass", "service"),
                jwt -> validServiceClaims(jwt, clock)));
        configured = resources(RestClient.builder().requestFactory(factory).build(), decoder);
        return configured;
    }

    static OAuth2TokenValidatorResult validServiceClaims(Jwt jwt, Clock clock) {
        Object version = jwt.getClaims().get("ver");
        boolean integerOne = (version instanceof Integer || version instanceof Long) && ((Number) version).longValue() == 1;
        if (integerOne && NAMESPACE.equals(jwt.getClaims().get("namespace"))
                && jwt.getExpiresAt() != null && jwt.getExpiresAt().isAfter(clock.instant())) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Claims de servicio incompatibles con Analítica", null));
    }

    private Resources resources(RestClient client, JwtDecoder decoder) {
        return new Resources(client, new ServiceTokenProvider(client, properties.tokenUrl(),
                properties.clientId(), properties.clientSecret(), clock), decoder);
    }

    private String baseUrl() { return properties.url().replaceAll("/+$", ""); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void validateUrl(String value) {
        try {
            URI uri = URI.create(value);
            boolean local = uri.getHost() != null && List.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
            if (uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    && ("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme())))) return;
        } catch (IllegalArgumentException ignored) { /* Mensaje seguro, sin imprimir la URL configurada. */ }
        throw failure(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED, "Las URLs de Analítica deben ser HTTPS (HTTP solo en loopback local).");
    }

    private static AnalyticsException invalidResponse() {
        return failure(HttpStatus.BAD_GATEWAY, "ANALYTICS_RESPONSE", "Analítica todavía no entregó una respuesta de embed compatible.");
    }
    private static AnalyticsException failure(HttpStatus status, String code, String message) {
        return new AnalyticsException(status, code, message);
    }

    public record Embed(MediaType type, String body) { }
    private record Raw(int status, MediaType type, String policy, byte[] body) { }
    private record Resources(RestClient client, ServiceTokenProvider tokens, JwtDecoder decoder) { }

    /** El timeout cubre también la lectura del body; una redirección nunca recibe credenciales. */
    private static final class NoRedirectRequestFactory extends SimpleClientHttpRequestFactory {
        @Override
        protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
            super.prepareConnection(connection, method);
            connection.setInstanceFollowRedirects(false);
        }
    }
}
