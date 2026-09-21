package com.citypass.movilidad;

import com.citypass.movilidad.model.Bike;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.enums.BikeStatus;
import com.citypass.movilidad.model.enums.StationSource;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.repository.BikeRepository;
import com.citypass.movilidad.repository.StationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Recorrido completo de MOV-042 contra una base real y un servicio de recomendación simulado.
 *
 * Cubre las dos garantías que ningún test unitario puede dar por sí solo: que el snapshot que
 * sale hacia el modelo solo contiene estaciones habilitadas (lo decide la consulta SQL de
 * MOV-017) y que una caída del servicio devuelve 200 igual.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest
class StationRecommendationIntegrationTest {

    private static final String URL = "/api/v1/stations/recommendation";
    private static final String LAT = "-34.6037";
    private static final String LNG = "-58.3816";

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("movilidad")
            .withUsername("test")
            .withPassword("test");

    /** Servicio de recomendación simulado: guarda el snapshot recibido y responde como el real. */
    static HttpServer fakeRecommendationService;
    static final AtomicReference<String> lastSnapshot = new AtomicReference<>();
    static final AtomicBoolean serviceDown = new AtomicBoolean(false);

    @BeforeAll
    static void startFakeService() throws IOException {
        fakeRecommendationService = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeRecommendationService.createContext("/v1/recommendations", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastSnapshot.set(body);
            if (serviceDown.get()) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            respond(exchange, recommendationFor(body));
        });
        fakeRecommendationService.createContext("/health", exchange -> respond(exchange,
                "{\"status\":\"UP\",\"modelVersion\":\"logreg-synthetic-v1\"}"));
        fakeRecommendationService.start();
    }

    @AfterAll
    static void stopFakeService() {
        fakeRecommendationService.stop(0);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("movilidad.recommendation.base-url",
                () -> "http://127.0.0.1:" + fakeRecommendationService.getAddress().getPort());
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private StationRepository stationRepository;
    @Autowired
    private BikeRepository bikeRepository;

    @BeforeEach
    void resetService() {
        serviceDown.set(false);
        lastSnapshot.set(null);
    }

    @Test
    void recomiendaLaEstacionConStockAunqueNoSeaLaMasCercana() throws Exception {
        bikeRepository.deleteAll();
        stationRepository.deleteAll();
        Station cercanaConUnaBici = station("DIAGONAL NORTE", "-34.6046", "-58.3798", 18, StationStatus.ACTIVE);
        Station lejanaConStock = station("CERRITO", "-34.6026", "-58.3838", 30, StationStatus.ACTIVE);
        bikes(cercanaConUnaBici, 1);
        bikes(lejanaConStock, 13);

        mockMvc.perform(get(URL + "?lat=" + LAT + "&lng=" + LNG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOMMENDED"))
                .andExpect(jsonPath("$.source").value("MODEL"))
                .andExpect(jsonPath("$.station.stationId").value(lejanaConStock.getId()))
                .andExpect(jsonPath("$.station.stationName").value("CERRITO"))
                .andExpect(jsonPath("$.station.availableBikes").value(13))
                .andExpect(jsonPath("$.explanation.comparedTo.stationName").value("DIAGONAL NORTE"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("CERRITO")))
                .andExpect(jsonPath("$.modelVersion").value("logreg-synthetic-v1"));
    }

    @Test
    void nuncaMandaNiRecomiendaUnaEstacionDeshabilitada() throws Exception {
        bikeRepository.deleteAll();
        stationRepository.deleteAll();
        // La más cercana, con el mayor stock, pero fuera de servicio: no puede aparecer en ningún lado.
        Station enMantenimiento = station("OBELISCO", LAT, LNG, 40, StationStatus.MAINTENANCE);
        Station inactiva = station("LAVALLE", "-34.6040", "-58.3820", 40, StationStatus.INACTIVE);
        Station habilitada = station("CERRITO", "-34.6026", "-58.3838", 30, StationStatus.ACTIVE);
        bikes(enMantenimiento, 30);
        bikes(inactiva, 30);
        bikes(habilitada, 5);

        String body = mockMvc.perform(get(URL + "?lat=" + LAT + "&lng=" + LNG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.station.stationId").value(habilitada.getId()))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("OBELISCO").doesNotContain("LAVALLE");

        JsonNode snapshot = objectMapper.readTree(lastSnapshot.get()).get("stations");
        assertThat(snapshot).hasSize(1);
        assertThat(snapshot.get(0).get("stationId").asLong()).isEqualTo(habilitada.getId());
    }

    @Test
    void sigueRespondiendoCuandoElServicioDeRecomendacionFalla() throws Exception {
        bikeRepository.deleteAll();
        stationRepository.deleteAll();
        Station cercana = station("DIAGONAL NORTE", "-34.6046", "-58.3798", 18, StationStatus.ACTIVE);
        Station lejana = station("CERRITO", "-34.6026", "-58.3838", 30, StationStatus.ACTIVE);
        bikes(cercana, 2);
        bikes(lejana, 13);
        serviceDown.set(true);

        mockMvc.perform(get(URL + "?lat=" + LAT + "&lng=" + LNG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOMMENDED"))
                .andExpect(jsonPath("$.source").value("FALLBACK"))
                .andExpect(jsonPath("$.station.stationId").value(cercana.getId()))
                .andExpect(jsonPath("$.explanation").doesNotExist())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("DIAGONAL NORTE")));
    }

    @Test
    void avisaCuandoNoHayEstacionesCerca() throws Exception {
        bikeRepository.deleteAll();
        stationRepository.deleteAll();

        mockMvc.perform(get(URL + "?lat=" + LAT + "&lng=" + LNG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_RECOMMENDATION"))
                .andExpect(jsonPath("$.reason").value("NO_CANDIDATES"));

        assertThat(lastSnapshot.get()).isNull();
    }

    @Test
    void publicaElContratoEnOpenApiParaQueElFrontendLoConsuma() throws Exception {
        String contrato = mockMvc.perform(get("/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode endpoint = objectMapper.readTree(contrato)
                .get("paths").get(URL).get("get");

        assertThat(endpoint.get("parameters").findValuesAsText("name"))
                .containsExactlyInAnyOrder("lat", "lng", "purpose");
        assertThat(endpoint.get("responses").has("200")).isTrue();
        assertThat(endpoint.get("responses").has("400")).isTrue();
        assertThat(endpoint.get("responses").get("200").toString())
                .contains("StationRecommendationResponse");
    }

    private Station station(String name, String latitude, String longitude, int capacity,
                            StationStatus status) {
        Station station = new Station();
        station.setName(name);
        station.setAddress("Dirección de " + name);
        station.setLatitude(new BigDecimal(latitude));
        station.setLongitude(new BigDecimal(longitude));
        station.setCapacity(capacity);
        station.setStatus(status);
        station.setSource(StationSource.MANUAL);
        return stationRepository.save(station);
    }

    private void bikes(Station station, int amount) {
        for (int i = 0; i < amount; i++) {
            Bike bike = new Bike();
            bike.setCode("REC-" + station.getId() + "-" + i);
            bike.setStation(station);
            bike.setStatus(BikeStatus.AVAILABLE);
            bikeRepository.save(bike);
        }
    }

    /** Responde como el servicio real: elige la candidata con más bicicletas del snapshot recibido. */
    private static String recommendationFor(String requestBody) {
        try {
            JsonNode stations = new ObjectMapper().readTree(requestBody).get("stations");
            List<JsonNode> candidates = new java.util.ArrayList<>();
            stations.forEach(candidates::add);
            JsonNode best = candidates.stream()
                    .max(java.util.Comparator.comparingInt(node -> node.get("availableBikes").asInt()))
                    .orElseThrow();
            JsonNode nearest = candidates.getFirst();
            boolean bestIsNearest = best == nearest;

            String explanation = bestIsNearest
                    ? "{\"code\": \"NEAREST_IS_BEST\", \"comparedTo\": null, \"factors\": []}"
                    : "{\"code\": \"NEAREST_LOW_AVAILABILITY\", \"comparedTo\": {\"stationId\": "
                    + nearest.get("stationId").asLong() + ", \"distanceMeters\": 193, \"availableBikes\": "
                    + nearest.get("availableBikes").asInt() + ", \"availableDocks\": 17}, "
                    + "\"factors\": [{\"feature\": \"secure_units\", \"impact\": 2.759}]}";

            return "{\"status\": \"RECOMMENDED\", \"reason\": null, \"purpose\": \"PICKUP\","
                    + "\"modelVersion\": \"logreg-synthetic-v1\","
                    + "\"recommendation\": {\"stationId\": " + best.get("stationId").asLong()
                    + ", \"score\": 0.768498, \"distanceMeters\": 236, \"availableBikes\": "
                    + best.get("availableBikes").asInt() + ", \"availableDocks\": 17},"
                    + "\"alternatives\": [], \"ranking\": [], \"explanation\": " + explanation + "}";
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
