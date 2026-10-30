package com.citypass.movilidad.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Incidentes locales → proyección real → métrica → outbox existente; sin gateway externo. */
@Testcontainers
@SpringBootTest(properties = {
        "citypass.eventbus.consumer.enabled=false", "citypass.eventbus.publisher.enabled=false",
        "citypass.eventbus.publisher.register-event-types=false", "movilidad.analytics.affected-stations.cron=-"
})
class AffectedStationsBiIntegrationTest {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("movilidad").withUsername("test").withPassword("test");
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }
    @Autowired InboundEventService inbound;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired AffectedStationsMetricsPublisher publisher;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM event_outbox");
        jdbc.update("DELETE FROM city_incidents");
        jdbc.update("DELETE FROM inbound_entity_states");
        jdbc.update("DELETE FROM stations");
    }
    private void station(String name, String status, boolean deleted, double lat) {
        jdbc.update("INSERT INTO stations (name,latitude,longitude,capacity,status,source,deleted_at) VALUES (?,?,?,20,?,'MANUAL',?)",
                name, lat, -58.4, status, deleted ? Timestamp.from(Instant.now()) : null);
    }
    private void handle(String type, String json) throws Exception {
        JsonNode data = mapper.readTree(json);
        transactions.executeWithoutResult(status -> inbound.handle(type, data));
    }
    private List<Integer> counts() throws Exception {
        List<Integer> values = new ArrayList<>();
        for (String json : jdbc.queryForList("SELECT payload FROM event_outbox WHERE event_type = ?",
                String.class, "com.citypass.movilidad.EstacionesAfectadasActualizadas")) {
            JsonNode data = mapper.readTree(json);
            List<String> fields = new ArrayList<>();
            data.fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("cantidadEstacionesAfectadas", "calculadaEn");
            assertThat(Instant.parse(data.path("calculadaEn").asText())).isBeforeOrEqualTo(Instant.now());
            values.add(data.path("cantidadEstacionesAfectadas").intValue());
        }
        return values;
    }
    private String incident(String id) {
        return """
                {"reclamoId":"%s","categoria":"TRANSITO","afectaCirculacion":true,
                 "latitud":-34.6,"longitud":-58.4,"createdAt":1000}
                """.formatted(id);
    }
    @Test void cuentaEstacionesUnicasActivasYPublicaCeroAlResolverElUltimoIncidente() throws Exception {
        station("Activa", "ACTIVE", false, -34.6001);
        station("Inactiva", "INACTIVE", false, -34.6002);
        station("Eliminada", "ACTIVE", true, -34.6003);
        station("Lejana", "ACTIVE", false, -34.7);
        handle("com.citypass.reclamos.ReclamoCreado", incident("r-1"));
        assertThat(counts()).containsExactly(1);
        handle("com.citypass.reclamos.ReclamoCreado", incident("r-2"));
        assertThat(counts()).hasSize(2).containsOnly(1);
        handle("com.citypass.reclamos.ReclamoActualizado", "{\"reclamoId\":\"r-1\",\"estadoNuevo\":\"RESUELTO\"}");
        assertThat(counts()).hasSize(3).containsOnly(1);
        handle("com.citypass.reclamos.ReclamoActualizado", "{\"reclamoId\":\"r-2\",\"estadoNuevo\":\"RESUELTO\"}");
        assertThat(counts()).containsExactlyInAnyOrder(1, 1, 1, 0);
    }
    @Test void rollbackNoEmiteMetricaYRefrescoPeriodicoUsaElOutboxReal() throws Exception {
        JsonNode data = mapper.readTree(incident("rollback"));
        transactions.executeWithoutResult(status -> {
            inbound.handle("com.citypass.reclamos.ReclamoCreado", data);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox", Integer.class)).isZero();
            status.setRollbackOnly();
        });
        assertThat(counts()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM city_incidents", Integer.class)).isZero();
        publisher.refresh();
        assertThat(counts()).containsExactly(0);
    }
    @Test void contratoBiTieneSoloLaMetricaAcordadaYFecha() throws Exception {
        try (var definition = getClass().getResourceAsStream("/eventbus/event-types/EstacionesAfectadasActualizadas.json")) {
            JsonNode schema = mapper.readTree(definition);
            assertThat(schema.path("name").asText()).isEqualTo("EstacionesAfectadasActualizadas");
            List<String> fields = new ArrayList<>();
            schema.path("fields").forEach(field -> fields.add(field.path("name").asText()));
            assertThat(fields).containsExactly("cantidadEstacionesAfectadas", "calculadaEn");
        }
    }
}
