package com.citypass.movilidad.service;

import com.citypass.movilidad.repository.CulturalDestinationRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contrato de ReservaConfirmada/Cancelada/EventoFinalizado contra MySQL y Flyway. */
@Testcontainers
@SpringBootTest(properties = {"citypass.eventbus.consumer.enabled=false", "citypass.eventbus.publisher.enabled=false",
        "citypass.eventbus.publisher.register-event-types=false", "movilidad.analytics.affected-stations.cron=-"})
class EspaciosContractIntegrationTest {
    @Container static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("movilidad").withUsername("test").withPassword("test");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }
    @Autowired InboundEventService inbound;
    @Autowired CulturalDestinationRepository destinations;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String NS = "com.citypass.espacios.";
    private static final Instant RECEIVED_AT = Instant.parse("2026-11-08T12:00:00Z");

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM event_outbox");
        jdbc.update("DELETE FROM destination_recommendation_jobs");
        jdbc.update("DELETE FROM cultural_destinations");
        jdbc.update("DELETE FROM inbound_entity_states");
        jdbc.update("DELETE FROM espacios_event_cursors WHERE entity_type <> 'LOCK'");
    }
    private String confirmation() {
        return """
                {"reservaId":"res-1001","ciudadanoId":"ciu-42","correlationId":"res-1001",
                 "espacioId":"plaza-07","nombreEvento":"Feria de barrio","asistentesEstimados":300,
                 "inicio":1786547143000,"fin":1786558000000,
                 "ubicacion":{"lat":-34.6096,"lon":-58.3925}}
                """;
    }
    private void send(String event, String payload, Instant receivedAt) {
        transactions.executeWithoutResult(ignored -> {
            try { inbound.handle(NS + event, mapper.readTree(payload), receivedAt); }
            catch (Exception exception) { throw new IllegalArgumentException(exception); }
        });
    }
    @Test void confirmacionPersisteUbicacionAnidada() {
        send("ReservaConfirmada", confirmation(), RECEIVED_AT);
        var event = destinations.findById("res-1001").orElseThrow();
        assertThat(event.getCitizenId()).isEqualTo("ciu-42");
        assertThat(event.getLatitude()).isEqualByComparingTo("-34.6096");
        assertThat(event.getLongitude()).isEqualByComparingTo("-58.3925");
        assertThat(event.getStartsAt()).isEqualTo(Instant.ofEpochMilli(1786547143000L));
    }
    @Test void duplicadoEsIdempotentePorReservaId() {
        send("ReservaConfirmada", confirmation(), RECEIVED_AT);
        send("ReservaConfirmada", confirmation(), RECEIVED_AT.plusSeconds(1));
        assertThat(destinations.count()).isOne();
    }
    @Test void cancelacionAnteriorImpideConfirmacionTardia() {
        send("ReservaCancelada", """
                {"reservaId":"res-1001","ciudadanoId":"ciu-42","correlationId":"res-1001",
                 "motivo":"Cancelada","origen":"CIUDADANO"}
                """, RECEIVED_AT);
        send("ReservaConfirmada", confirmation(), RECEIVED_AT.plusSeconds(1));
        assertThat(destinations.findById("res-1001")).isEmpty();
    }
    @Test void finalizacionCierraLaProyeccion() {
        send("ReservaConfirmada", confirmation(), RECEIVED_AT);
        send("EventoFinalizado", """
                {"reservaId":"res-1001","ciudadanoId":"ciu-42","correlationId":"res-1001",
                 "fin":1786558000000,"asistentesReales":280}
                """, RECEIVED_AT.plusSeconds(1));
        assertThat(destinations.findById("res-1001").orElseThrow().getEventStatus()).isEqualTo("FINISHED");
    }
    @Test void sinUbicacionNoPersiste() {
        String invalid = confirmation().replace("\"ubicacion\":{\"lat\":-34.6096,\"lon\":-58.3925}", "\"otro\":null");
        assertThatThrownBy(() -> send("ReservaConfirmada", invalid, RECEIVED_AT)).isInstanceOf(IllegalArgumentException.class);
        assertThat(destinations.count()).isZero();
    }
}
