package com.citypass.movilidad.analytics;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** HTTP local: verifica el proxy y el reenvío del access token humano sin servicios externos. */
class AnalyticsHttpIntegrationTest {
    private HttpServer server;
    private String base;
    private final AtomicInteger embedCalls = new AtomicInteger();
    private final AtomicInteger redirectedCalls = new AtomicInteger();
    private boolean redirect;
    private String receivedAuthorization;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/embed/movilidad", e -> {
            embedCalls.incrementAndGet();
            receivedAuthorization = e.getRequestHeaders().getFirst("Authorization");
            if (redirect) {
                e.getResponseHeaders().add("Location", base + "/other");
                send(e, 302, "redirect");
            } else {
                e.getResponseHeaders().add("Content-Security-Policy", "default-src 'self'");
                e.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
                e.getResponseHeaders().add("Referrer-Policy", "no-referrer");
                e.getResponseHeaders().add("Cache-Control", "private, max-age=10");
                e.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                send(e, 200, "<html>Live BI</html>");
            }
        });
        server.createContext("/other", e -> { redirectedCalls.incrementAndGet(); send(e, 200, "bad"); });
        server.start();
    }

    private static void send(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    @AfterEach void stop() { server.stop(0); }

    private AnalyticsEmbedClient client() {
        return new AnalyticsEmbedClient(new AnalyticsProperties(base, 1000, 2000));
    }

    @Test void mandaBearerHumanoYDevuelveHtml() {
        var embed = client().fetch("human-token");
        assertThat(receivedAuthorization).isEqualTo("Bearer human-token");
        assertThat(new String(embed.body(), StandardCharsets.UTF_8)).isEqualTo("<html>Live BI</html>");
        assertThat(embed.headers().getFirst("Content-Security-Policy")).isEqualTo("default-src 'self'");
        assertThat(embedCalls).hasValue(1);
    }

    @Test void nuncaSigueRedireccionesConElBearer() {
        redirect = true;
        assertThatThrownBy(() -> client().fetch("human-token"))
                .isInstanceOfSatisfying(AnalyticsException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY));
        assertThat(redirectedCalls).hasValue(0);
    }
}
