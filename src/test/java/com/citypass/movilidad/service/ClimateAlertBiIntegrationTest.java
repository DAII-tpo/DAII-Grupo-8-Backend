package com.citypass.movilidad.service;

import com.citypass.movilidad.event.AvroContract;
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

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Alerta climática → afectaciones BI + confirmado a Espacios en el outbox real, validados contra el contrato. */
@Testcontainers
@SpringBootTest(properties = {
        "citypass.eventbus.consumer.enabled=false", "citypass.eventbus.publisher.enabled=false",
        "citypass.eventbus.publisher.register-event-types=false", "movilidad.analytics.affected-stations.cron=-",
        "movilidad.climate.activation-interval=1h"
})
class ClimateAlertBiIntegrationTest {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("movilidad").withUsername("test").withPassword("test");
    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    private static final String NS = "com.citypass.movilidad.";
    @Autowired ClimateAlertService climate;
    @Autowired AffectedStationsService affectations;
    @Autowired ClimateActivationJob job;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM station_affectations");
        jdbc.update("DELETE FROM event_outbox");
        jdbc.update("DELETE FROM climate_alert_trip_notifications");
        jdbc.update("DELETE FROM climate_alert_neighborhoods");
        jdbc.update("DELETE FROM climate_alerts");
        jdbc.update("DELETE FROM city_incidents");
        jdbc.update("DELETE FROM stations");
        station("Palermo 1", "AR-CABA-PALERMO", "ACTIVE");
        station("Palermo 2", "AR-CABA-PALERMO", "ACTIVE");
        station("Palermo inactiva", "AR-CABA-PALERMO", "INACTIVE");
        station("Recoleta 1", "AR-CABA-RECOLETA", "ACTIVE");
        station("Retiro 1", "AR-CABA-RETIRO", "ACTIVE");
    }

    @Test void currentStormBlocksEachStationForBiAndRestoresThemOneToOne() throws Exception {
        confirm("alerta-1", "TORMENTA_ELECTRICA", "ALTA", Instant.now().minusSeconds(60));

        List<JsonNode> affected = payloads("EstacionUrbanaAfectada");
        assertThat(affected).hasSize(3);
        assertThat(payloads("AlertaClimaticaAplicada")).singleElement()
                .satisfies(applied -> assertThat(applied.path("cantidadEstacionesBloqueadas").asInt()).isEqualTo(3));
        assertThat(affected).allSatisfy(data -> {
            assertThat(data.path("tipoAfectacion").asText()).isEqualTo("BLOQUEADA");
            assertThat(data.path("causaTipo").asText()).isEqualTo("EVENTO_CLIMATICO");
            assertThat(data.path("causaId").asText()).isEqualTo("alerta-1");
            assertThat(data.path("correlationId").asText()).isEqualTo("alerta-1");
            assertThat(data.path("moduloOrigen").asText()).isEqualTo("MOVILIDAD");
            assertThat(data.path("afectacionHastaEstimada").isTextual()).isTrue();
        });
        assertThat(affected.stream().map(data -> data.path("estacionId").asText()).collect(Collectors.toSet()))
                .isEqualTo(stationIds("Palermo 1", "Palermo 2", "Recoleta 1"));

        affectations.synchronize();
        assertThat(payloads("EstacionUrbanaRestaurada")).isEmpty();

        long finishedAt = Instant.parse("2026-11-15T18:00:00Z").toEpochMilli();
        finish("alerta-1", finishedAt);

        List<JsonNode> restored = payloads("EstacionUrbanaRestaurada");
        assertThat(ids(restored)).isEqualTo(ids(affected));
        assertThat(restored).allSatisfy(data -> assertThat(data.path("restauradaAt").asText())
                .isEqualTo(Instant.ofEpochMilli(finishedAt).toString()));
        assertThat(payloads("AlertaClimaticaLevantada")).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM station_affectations", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT finalization_reason FROM climate_alerts WHERE external_alert_id = 'alerta-1'",
                String.class)).isEqualTo("ESPACIOS");
        assertEveryPayloadMatchesItsContract();
    }

    @Test void scheduledAlertIsAnnouncedPreventivelyButNotAppliedNorLifted() throws Exception {
        // El evento viaja en epoch millis: comparar con la misma precisión.
        Instant from = Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        confirm("alerta-2", "TORMENTA_FUERTE", "ALTA", from);

        assertThat(payloads("EstacionUrbanaAfectada")).hasSize(3).allSatisfy(data ->
                assertThat(data.path("afectacionDesde").asText()).isEqualTo(from.toString()));
        assertThat(payloads("AlertaClimaticaAplicada")).isEmpty();

        affectations.synchronize();
        assertThat(payloads("EstacionUrbanaRestaurada")).isEmpty();

        finish("alerta-2", Instant.now().toEpochMilli());
        assertThat(payloads("EstacionUrbanaRestaurada")).hasSize(3);
        assertThat(payloads("AlertaClimaticaLevantada")).isEmpty();
        assertEveryPayloadMatchesItsContract();
    }

    @Test void activeAlertExpiresAtItsValidUntilWithTheSameEffectsAsEspaciosFinish() throws Exception {
        Instant validUntil = soon();
        confirm("alerta-5", "TORMENTA_ELECTRICA", "ALTA", Instant.now().minusSeconds(60), validUntil);
        List<JsonNode> affected = payloads("EstacionUrbanaAfectada");
        assertThat(affected).hasSize(3);
        assertThat(payloads("AlertaClimaticaAplicada")).hasSize(1);

        // Pasa la vigencia sin que llegue la Finalizada de Espacios; el job corre después (tarde).
        waitUntilPast(validUntil);
        job.expireDueAlerts();

        List<JsonNode> restored = payloads("EstacionUrbanaRestaurada");
        assertThat(ids(restored)).isEqualTo(ids(affected));
        assertThat(restored).allSatisfy(data ->
                assertThat(data.path("restauradaAt").asText()).isEqualTo(validUntil.toString()));
        assertThat(payloads("AlertaClimaticaLevantada")).singleElement().satisfies(lifted ->
                assertThat(lifted.path("levantadaEn").asLong()).isEqualTo(validUntil.toEpochMilli()));
        assertThat(jdbc.queryForMap(
                "SELECT status, finalization_reason FROM climate_alerts WHERE external_alert_id = 'alerta-5'"))
                .containsEntry("status", "FINALIZADA").containsEntry("finalization_reason", "VENCIMIENTO");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM station_affectations", Integer.class)).isZero();
        assertEveryPayloadMatchesItsContract();

        // La Finalizada de Espacios que llega después no publica nada nuevo.
        int published = outboxSize();
        finish("alerta-5", Instant.now().toEpochMilli());
        assertThat(outboxSize()).isEqualTo(published);
        assertThat(jdbc.queryForObject("SELECT finalization_reason FROM climate_alerts WHERE external_alert_id = 'alerta-5'",
                String.class)).isEqualTo("VENCIMIENTO");
    }

    @Test void scheduledAlertThatExpiresWithoutActivatingOnlyRestoresItsPreventiveAffectations() throws Exception {
        Instant validUntil = soon();
        confirm("alerta-6", "TORMENTA_FUERTE", "ALTA", validUntil.minusMillis(500), validUntil);
        assertThat(payloads("EstacionUrbanaAfectada")).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT status FROM climate_alerts WHERE external_alert_id = 'alerta-6'",
                String.class)).isEqualTo("PROGRAMADA");

        // Vence sin que el job llegue a activarla: en el tick se vence primero y ya no se activa.
        waitUntilPast(validUntil);
        job.tick();

        assertThat(payloads("EstacionUrbanaRestaurada")).hasSize(3).allSatisfy(data ->
                assertThat(data.path("restauradaAt").asText()).isEqualTo(validUntil.toString()));
        assertThat(payloads("AlertaClimaticaAplicada")).isEmpty();
        assertThat(payloads("AlertaClimaticaLevantada")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM climate_alerts WHERE external_alert_id = 'alerta-6'",
                String.class)).isEqualTo("FINALIZADA");
        assertEveryPayloadMatchesItsContract();
    }

    @Test void warningOrConfirmationAlertsDoNotOpenBiAffectations() throws Exception {
        confirm("alerta-3", "LLUVIA_INTENSA", "ALTA", Instant.now().minusSeconds(60));

        assertThat(payloads("EstacionUrbanaAfectada")).isEmpty();
        assertThat(payloads("AlertaClimaticaAplicada")).singleElement().satisfies(applied ->
                assertThat(applied.path("accionesAplicadas").toString())
                        .isEqualTo("[\"AVISO_USUARIOS\",\"CONFIRMACION_RIESGO_AL_ALQUILAR\"]"));
    }

    @Test void reconcilerRestoresClimateAffectationsLeftOpenByAFinishedAlert() throws Exception {
        confirm("alerta-4", "NIEVE", "MEDIA", Instant.now().minusSeconds(60));
        // Simula una alerta que quedó FINALIZADA sin que se restauraran sus estaciones.
        jdbc.update("UPDATE climate_alerts SET status = 'FINALIZADA' WHERE external_alert_id = 'alerta-4'");

        affectations.synchronize();

        assertThat(ids(payloads("EstacionUrbanaRestaurada"))).isEqualTo(ids(payloads("EstacionUrbanaAfectada")));
    }

    private void confirm(String id, String phenomenon, String severity, Instant from) throws Exception {
        confirm(id, phenomenon, severity, from, from.plusSeconds(7200));
    }

    private void confirm(String id, String phenomenon, String severity, Instant from, Instant until)
            throws Exception {
        JsonNode data = mapper.readTree("""
                {"alertaId":"%s","fenomeno":"%s","nivelCriticidad":"%s","barrios":["Palermo","Recoleta"],
                 "vigenciaDesde":%d,"vigenciaHasta":%d}
                """.formatted(id, phenomenon, severity, from.toEpochMilli(), until.toEpochMilli()));
        transactions.executeWithoutResult(status -> climate.confirm(data, Instant.now()));
    }

    private void finish(String id, long finishedAt) throws Exception {
        JsonNode data = mapper.readTree("{\"alertaId\":\"%s\",\"finalizadaEn\":%d}".formatted(id, finishedAt));
        transactions.executeWithoutResult(status -> climate.finish(data));
    }

    /** Fin de vigencia cercano; el evento viaja en epoch millis, así que se compara con esa precisión. */
    private static Instant soon() {
        return Instant.now().plusMillis(1500).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

    private static void waitUntilPast(Instant instant) throws InterruptedException {
        while (!Instant.now().isAfter(instant)) {
            Thread.sleep(50);
        }
    }

    private int outboxSize() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox", Integer.class);
    }

    private void station(String name, String neighborhood, String status) {
        jdbc.update("""
                INSERT INTO stations (name,latitude,longitude,capacity,status,source,neighborhood_id)
                VALUES (?,-34.58,-58.42,20,?,'MANUAL',?)
                """, name, status, neighborhood);
    }

    private Set<String> stationIds(String... names) {
        return Set.of(names).stream().map(name -> jdbc.queryForObject(
                "SELECT CAST(id AS CHAR) FROM stations WHERE name = ?", String.class, name))
                .collect(Collectors.toSet());
    }

    private static Set<String> ids(List<JsonNode> payloads) {
        return payloads.stream().map(data -> data.path("afectacionId").asText()).collect(Collectors.toSet());
    }

    private List<JsonNode> payloads(String event) {
        return jdbc.queryForList("SELECT payload FROM event_outbox WHERE event_type = ?", String.class, NS + event)
                .stream().map(json -> {
                    try { return mapper.readTree(json); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                }).toList();
    }

    private void assertEveryPayloadMatchesItsContract() {
        for (String event : List.of("EstacionUrbanaAfectada", "EstacionUrbanaRestaurada",
                "AlertaClimaticaAplicada", "AlertaClimaticaLevantada")) {
            var schema = AvroContract.load(event);
            for (JsonNode payload : payloads(event)) {
                assertThat(AvroContract.violations(schema, payload)).as(event).isEmpty();
            }
        }
    }
}
