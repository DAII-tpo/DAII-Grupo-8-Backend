package com.citypass.movilidad.client;

import com.citypass.movilidad.config.IdpClientConfig;
import com.citypass.movilidad.config.IdpProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Único test que arma el {@link IdpClientConfig} real: {@code MockRestServiceServer} no pasa por
 * la capa de transporte, así que ignora los timeouts y no puede cubrir este caso.
 *
 * <p>El riesgo concreto es que la consulta del perfil ocurre dentro del request de la persona: un
 * IdP que acepta la conexión y no contesta dejaría el request colgado reteniendo una conexión de
 * la base.
 */
class HttpFederatedUserInfoClientTimeoutTest {

    @Test
    void cortaLaEsperaCuandoElIdpNoContestaATiempo() throws IOException {
        // El IdP de mentira retiene la respuesta hasta que el test lo libera (o 5 s como tope).
        CountDownLatch liberar = new CountDownLatch(1);
        HttpServer lento = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        lento.createContext("/me", exchange -> {
            try {
                if (!liberar.await(5, TimeUnit.SECONDS)) {
                    return;
                }
                exchange.sendResponseHeaders(204, -1);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        lento.start();

        try {
            IdpProperties properties = new IdpProperties(
                    "http://127.0.0.1:" + lento.getAddress().getPort() + "/me",
                    true, 1000, 200, Duration.ofMinutes(10));
            HttpFederatedUserInfoClient client = new HttpFederatedUserInfoClient(
                    new IdpClientConfig().idpRestClient(properties), properties, Clock.systemUTC());

            long comienzo = System.nanoTime();
            // Un timeout es una falla más: devuelve vacío, no propaga.
            assertThat(client.fetch("el-access-token", "U000042")).isEmpty();
            long transcurrido = Duration.ofNanos(System.nanoTime() - comienzo).toMillis();

            assertThat(transcurrido).isLessThan(1_000);
        } finally {
            liberar.countDown();
            lento.stop(0);
        }
    }
}
