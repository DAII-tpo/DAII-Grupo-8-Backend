package com.citypass.movilidad.client;

import com.citypass.movilidad.client.dto.RecommendationApiRequest;
import com.citypass.movilidad.client.dto.RecommendationApiResponse;
import com.citypass.movilidad.config.RecommendationClientConfig;
import com.citypass.movilidad.config.RecommendationProperties;
import com.citypass.movilidad.model.enums.ExplanationCode;
import com.citypass.movilidad.model.enums.RecommendationPurpose;
import com.citypass.movilidad.model.enums.RecommendationStatus;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * El cliente tiene un solo contrato hacia afuera: o devuelve una respuesta utilizable, o lanza
 * RecommendationUnavailableException. Estos tests recorren las formas en que el servicio puede
 * fallar y verifican que ninguna se escape con otra excepción.
 */
class RecommendationClientTest {

    private static final String RECOMMENDATIONS_URL = "http://recomendacion.test/v1/recommendations";

    private static final String OK_BODY = """
            {
              "status": "RECOMMENDED",
              "reason": null,
              "purpose": "PICKUP",
              "modelVersion": "logreg-synthetic-v1",
              "recommendation": {"stationId": 77, "score": 0.768498, "distanceMeters": 236,
                                 "availableBikes": 13, "availableDocks": 17},
              "alternatives": [{"stationId": 44, "score": 0.151128, "distanceMeters": 193,
                                "availableBikes": 1, "availableDocks": 17}],
              "ranking": [{"stationId": 77, "score": 0.768498, "rank": 1, "distanceMeters": 236}],
              "explanation": {
                "code": "NEAREST_LOW_AVAILABILITY",
                "comparedTo": {"stationId": 44, "distanceMeters": 193, "availableBikes": 1,
                               "availableDocks": 17},
                "factors": [{"feature": "secure_units", "impact": 2.759}]
              }
            }
            """;

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private RecommendationClient client;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder().baseUrl("http://recomendacion.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new RecommendationClient(builder.build(), properties("http://recomendacion.test"));
    }

    @AfterEach
    void tearDown() {
        server.reset();
    }

    @Test
    void devuelveLaRecomendacionQueMandaElServicio() {
        server.expect(requestTo(RECOMMENDATIONS_URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(jsonPath("$.purpose").value("PICKUP"))
                .andExpect(jsonPath("$.user.lat").value(-34.6037))
                .andExpect(jsonPath("$.stations[0].stationId").value(44))
                .andRespond(withSuccess(OK_BODY, MediaType.APPLICATION_JSON));

        RecommendationApiResponse response = client.recommend(request());

        assertThat(response.status()).isEqualTo(RecommendationStatus.RECOMMENDED);
        assertThat(response.purpose()).isEqualTo(RecommendationPurpose.PICKUP);
        assertThat(response.modelVersion()).isEqualTo("logreg-synthetic-v1");
        assertThat(response.recommendation().stationId()).isEqualTo(77L);
        assertThat(response.alternatives()).hasSize(1);
        assertThat(response.explanation().code()).isEqualTo(ExplanationCode.NEAREST_LOW_AVAILABILITY);
        assertThat(response.explanation().comparedTo().stationId()).isEqualTo(44L);
        assertThat(response.explanation().factors().getFirst().feature()).isEqualTo("secure_units");
        server.verify();
    }

    @Test
    void traduceUnErrorDelServicioAServicioNoDisponible() {
        server.expect(requestTo(RECOMMENDATIONS_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.recommend(request()))
                .isInstanceOf(RecommendationUnavailableException.class);
    }

    @Test
    void traduceUnCuerpoIlegibleAServicioNoDisponible() {
        server.expect(requestTo(RECOMMENDATIONS_URL))
                .andRespond(withSuccess("{esto no es json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.recommend(request()))
                .isInstanceOf(RecommendationUnavailableException.class);
    }

    @Test
    void traduceUnCodigoDeExplicacionDesconocidoAServicioNoDisponible() {
        String body = OK_BODY.replace("NEAREST_LOW_AVAILABILITY", "NEAREST_USUALLY_EMPTY_AT_THIS_HOUR");
        server.expect(requestTo(RECOMMENDATIONS_URL))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.recommend(request()))
                .isInstanceOf(RecommendationUnavailableException.class);
    }

    @Test
    void traduceUnCuerpoVacioAServicioNoDisponible() {
        server.expect(requestTo(RECOMMENDATIONS_URL))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.recommend(request()))
                .isInstanceOf(RecommendationUnavailableException.class)
                .hasMessageContaining("vacía");
    }

    @Test
    void traduceUnaRespuestaSinStatusAServicioNoDisponible() {
        server.expect(requestTo(RECOMMENDATIONS_URL))
                .andRespond(withSuccess("{\"purpose\": \"PICKUP\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.recommend(request()))
                .isInstanceOf(RecommendationUnavailableException.class);
    }

    @Test
    void despiertaAlServicioConUnPingAHealth() {
        server.expect(requestTo("http://recomendacion.test/health"))
                .andRespond(withSuccess("{\"status\":\"UP\"}", MediaType.APPLICATION_JSON));

        client.warmUp();

        server.verify(Duration.ofSeconds(5));
    }

    @Test
    void noPingueaSiElCalentamientoEstaDeshabilitado() {
        RecommendationClient deshabilitado = new RecommendationClient(builder.build(),
                new RecommendationProperties("http://recomendacion.test", 1000, 2000, 1000, 15, false));

        deshabilitado.warmUp();

        // Ninguna expectativa registrada: cualquier request habría fallado el test.
        server.verify();
    }

    /**
     * Un servicio que acepta la conexión pero no contesta a tiempo es el caso peligroso: sin
     * read timeout la petición se queda colgada y el usuario espera con ella.
     */
    @Test
    void cortaLaEsperaCuandoElServicioNoContestaATiempo() throws IOException {
        HttpServer lento = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        lento.createContext("/v1/recommendations", exchange -> {
            try {
                TimeUnit.MILLISECONDS.sleep(1_500);
                byte[] body = OK_BODY.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        lento.start();

        try {
            String baseUrl = "http://127.0.0.1:" + lento.getAddress().getPort();
            RecommendationProperties properties =
                    new RecommendationProperties(baseUrl, 1000, 200, 1000, 15, true);
            RecommendationClient conTimeoutCorto = new RecommendationClient(
                    new RecommendationClientConfig().recommendationRestClient(properties), properties);

            long comienzo = System.nanoTime();
            assertThatThrownBy(() -> conTimeoutCorto.recommend(request()))
                    .isInstanceOf(RecommendationUnavailableException.class);
            long transcurrido = Duration.ofNanos(System.nanoTime() - comienzo).toMillis();

            assertThat(transcurrido).isLessThan(1_000);
        } finally {
            lento.stop(0);
        }
    }

    private RecommendationApiRequest request() {
        return new RecommendationApiRequest(RecommendationPurpose.PICKUP,
                new RecommendationApiRequest.UserLocation(-34.6037, -58.3816),
                List.of(new RecommendationApiRequest.StationSnapshot(44L, -34.6046, -58.3798, 18, 1, 17),
                        new RecommendationApiRequest.StationSnapshot(77L, -34.6026, -58.3838, 30, 13, 17)));
    }

    private RecommendationProperties properties(String baseUrl) {
        return new RecommendationProperties(baseUrl, 1000, 2000, 1000, 15, true);
    }
}
