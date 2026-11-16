package com.citypass.movilidad.analytics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyticsEmbedClientTest {
    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private String responseType = "text/html; charset=utf-8";
    private String responseBody = "<!doctype html><html><body>Dashboard</body></html>";
    private int responseStatus = 200;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/embed/movilidad", this::respond);
        server.start();
    }

    private void respond(HttpExchange exchange) throws java.io.IOException {
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        exchange.getResponseHeaders().add("Content-Type", responseType);
        exchange.getResponseHeaders().add("Cache-Control", "private, max-age=10");
        exchange.getResponseHeaders().add("Content-Security-Policy", "default-src 'self'");
        exchange.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().add("Referrer-Policy", "no-referrer");
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(responseStatus, body.length);
        try (var output = exchange.getResponseBody()) { output.write(body); }
    }

    private AnalyticsEmbedClient client() {
        return new AnalyticsEmbedClient(new AnalyticsProperties(baseUrl, 1000, 2000));
    }

    @AfterEach void stop() { server.stop(0); }

    @Test void reenviaTokenHumanoYDevuelveHtmlYCabecerasDeBi() {
        var embed = client().fetch("human-access-token");
        assertThat(authorization).hasValue("Bearer human-access-token");
        assertThat(embed.type().toString()).startsWith("text/html");
        assertThat(new String(embed.body(), StandardCharsets.UTF_8)).contains("Dashboard");
        assertThat(embed.headers().getFirst("Content-Security-Policy")).isEqualTo("default-src 'self'");
        assertThat(embed.headers().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(embed.headers().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(embed.headers().getFirst("Cache-Control")).contains("no-store");
    }

    @Test void rechazaRespuestasQueNoSeanHtml() {
        responseType = "application/json";
        assertThatThrownBy(() -> client().fetch("human-access-token"))
                .isInstanceOfSatisfying(AnalyticsException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
    }

    @Test void traduceErroresDeBiSinExponerElBodyRemoto() {
        responseStatus = 503;
        responseBody = "private upstream error";
        assertThatThrownBy(() -> client().fetch("human-access-token"))
                .isInstanceOfSatisfying(AnalyticsException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(ex.getMessage()).doesNotContain("private upstream error");
                });
    }

    @Test void validaUrlHhtpsOLoopbackHttp() {
        assertThatThrownBy(() -> new AnalyticsEmbedClient(new AnalyticsProperties("http://example.com", 1000, 1000)))
                .isInstanceOf(AnalyticsException.class);
    }
}
