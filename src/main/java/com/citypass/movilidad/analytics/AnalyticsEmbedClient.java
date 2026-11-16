package com.citypass.movilidad.analytics;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.HttpURLConnection;
import java.net.URI;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Proxy del embed de Movilidad: reenvía el token humano autenticado, sin credenciales de servicio. */
@Service
public class AnalyticsEmbedClient {
    static final String EMBED_PATH = "/embed/movilidad";
    static final String NOT_CONFIGURED = "ANALYTICS_NOT_CONFIGURED";
    private final AnalyticsProperties properties;
    private final RestClient client;

    public AnalyticsEmbedClient(AnalyticsProperties properties) {
        this.properties = properties;
        validateUrl(properties.url());
        var factory = new NoRedirectRequestFactory();
        factory.setConnectTimeout(properties.connectTimeoutMillis());
        factory.setReadTimeout(properties.readTimeoutMillis());
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public Embed fetch(String humanAccessToken) {
        try {
            return client.get().uri(properties.url().replaceAll("/+$", "") + EMBED_PATH)
                    .headers(h -> h.setBearerAuth(humanAccessToken))
                    .accept(MediaType.TEXT_HTML)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            HttpStatus status = response.getStatusCode().is5xxServerError()
                                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
                            throw new AnalyticsException(status, "ANALYTICS_UPSTREAM",
                                    "Analítica no pudo entregar el tablero (HTTP " + response.getStatusCode().value() + ").");
                        }
                        MediaType type = response.getHeaders().getContentType();
                        if (type == null || !MediaType.TEXT_HTML.isCompatibleWith(type)) {
                            throw new AnalyticsException(HttpStatus.BAD_GATEWAY, "ANALYTICS_RESPONSE",
                                    "Analítica no devolvió el HTML esperado.");
                        }
                        byte[] body = response.getBody().readNBytes(2 * 1024 * 1024 + 1);
                        if (body.length > 2 * 1024 * 1024) {
                            throw new AnalyticsException(HttpStatus.BAD_GATEWAY, "ANALYTICS_RESPONSE",
                                    "El tablero supera el tamaño máximo admitido.");
                        }
                        HttpHeaders headers = new HttpHeaders();
                        for (String name : List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.CACHE_CONTROL,
                                "Content-Security-Policy", "X-Content-Type-Options", "Referrer-Policy")) {
                            List<String> values = response.getHeaders().get(name);
                            if (values != null) headers.put(name, values);
                        }
                        headers.setCacheControl("no-store");
                        headers.setVary(List.of(HttpHeaders.AUTHORIZATION));
                        return new Embed(type, body, headers);
                    });
        } catch (AnalyticsException ex) {
            throw ex;
        } catch (RestClientResponseException ex) {
            HttpStatus status = ex.getStatusCode().is5xxServerError()
                    ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
            throw new AnalyticsException(status, "ANALYTICS_UPSTREAM",
                    "Analítica no pudo entregar el tablero (HTTP " + ex.getStatusCode().value() + ").");
        } catch (Exception ex) {
            throw new AnalyticsException(HttpStatus.SERVICE_UNAVAILABLE, "ANALYTICS_UNAVAILABLE",
                    "No se pudo consultar Analítica. Intentá nuevamente.");
        }
    }

    private static void validateUrl(String value) {
        try {
            URI uri = URI.create(value);
            boolean loopback = uri.getHost() != null && List.of("localhost", "127.0.0.1", "::1", "[::1]")
                    .contains(uri.getHost());
            boolean secure = "https".equals(uri.getScheme()) || (loopback && "http".equals(uri.getScheme()));
            if (uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null
                    && uri.getFragment() == null && secure) return;
        } catch (RuntimeException ignored) { /* Se informa el error sin exponer configuración. */ }
        throw new AnalyticsException(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED,
                "La URL de Analítica debe ser HTTPS (HTTP solo en loopback) y no incluir credenciales ni parámetros.");
    }

    public record Embed(MediaType type, byte[] body, HttpHeaders headers) {
        public Embed {
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Embed embed)) return false;
            return Objects.equals(type, embed.type)
                    && Arrays.equals(body, embed.body)
                    && Objects.equals(headers, embed.headers);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(type, headers) + Arrays.hashCode(body);
        }

        @Override
        public String toString() {
            return "Embed[type=" + type + ", bodyLength=" + body.length
                    + ", bodyHash=" + Arrays.hashCode(body) + ", headers=" + headers + "]";
        }
    }

    private static final class NoRedirectRequestFactory extends SimpleClientHttpRequestFactory {
        @Override
        protected void prepareConnection(HttpURLConnection connection, String method) throws IOException {
            super.prepareConnection(connection, method);
            connection.setInstanceFollowRedirects(false);
        }
    }
}
