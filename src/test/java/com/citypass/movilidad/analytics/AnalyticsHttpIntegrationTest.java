package com.citypass.movilidad.analytics;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/** HTTP local y JWKS real: ninguna petición sale a BI o Identidad de producción. */
class AnalyticsHttpIntegrationTest {
    private static RSAKey key;
    private HttpServer server;
    private ExecutorService executor;
    private String base;
    private String token;
    private boolean slowBody;
    private boolean redirect;
    private AtomicInteger tokenCalls;
    private AtomicInteger embedCalls;
    private AtomicInteger otherCalls;

    @BeforeAll static void key() throws Exception { key = new RSAKeyGenerator(2048).keyID("local").generate(); }
    @BeforeEach void start() throws Exception {
        tokenCalls = new AtomicInteger(); embedCalls = new AtomicInteger(); otherCalls = new AtomicInteger();
        var claims = new JWTClaimsSet.Builder().issuer("identity-local").subject("service-movilidad")
                .audience("citypass").expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("token_use", "service").claim("namespace", AnalyticsEmbedClient.NAMESPACE).claim("ver", 1).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("local").build(), claims);
        jwt.sign(new RSASSASigner(key)); token = jwt.serialize();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/token", e -> {
            tokenCalls.incrementAndGet();
            send(e, 200, "{\"access_token\":\"" + token + "\",\"expires_in\":900}");
        });
        server.createContext("/jwks", e -> send(e, 200, new JWKSet(key.toPublicJWK()).toString()));
        server.createContext("/other", e -> { otherCalls.incrementAndGet(); send(e, 200, "{}"); });
        server.createContext("/embed/movilidad", e -> {
            embedCalls.incrementAndGet();
            if (!e.getRequestHeaders().getFirst("Authorization").equals("Bearer " + token)
                    || e.getRequestHeaders().containsKey("X-Usuario-Token") || e.getRequestHeaders().containsKey("X-User-Token")) {
                send(e, 403, "{}"); return;
            }
            if (redirect) { e.getResponseHeaders().add("Location", base + "/other"); send(e, 302, "{}"); return; }
            if (slowBody) {
                e.getResponseHeaders().add("Content-Type", "application/json");
                e.sendResponseHeaders(200, 15);
                try { Thread.sleep(1500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                e.close(); return;
            }
            send(e, 200, "{\"status\":\"ok\"}");
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
    private static void send(HttpExchange e, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().add("Content-Type", "application/json");
        e.sendResponseHeaders(status, bytes.length);
        try (var output = e.getResponseBody()) { output.write(bytes); }
    }
    private AnalyticsEmbedClient client(int timeout) {
        return new AnalyticsEmbedClient(new AnalyticsProperties(base, base + "/token", "identity-local",
                base + "/jwks", "client-test", UUID.randomUUID().toString(), 1000, timeout));
    }
    @Test void autenticaValidaRs256ConJwksYReutilizaTokenPorHttp() {
        var client = client(2000);
        assertThat(client.fetch().body()).isEqualTo("{\"status\":\"ok\"}");
        client.fetch();
        assertThat(tokenCalls).hasValue(1);
        assertThat(embedCalls).hasValue(2);
    }
    @Test void unaRedireccionNoRecibeElBearer() {
        redirect = true;
        var client = client(2000);
        assertThatThrownBy(client::fetch).isInstanceOf(AnalyticsException.class);
        assertThat(otherCalls).hasValue(0);
    }
    @Test void elTimeoutTambienCubreUnBodyDetenidoDespuesDeLosHeaders() {
        slowBody = true;
        var client = client(500);
        assertThatThrownBy(client::fetch).isInstanceOfSatisfying(AnalyticsException.class,
                ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        assertThat(embedCalls).hasValue(1);
    }
}
