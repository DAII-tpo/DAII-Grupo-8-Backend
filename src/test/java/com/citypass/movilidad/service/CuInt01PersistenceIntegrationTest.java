package com.citypass.movilidad.service;

import com.citypass.movilidad.repository.CulturalDestinationRepository;
import com.citypass.movilidad.repository.InboundEntityStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Real MySQL/Flyway and Spring transactions, with local input instead of Kafka producers. */
@Testcontainers
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@SpringBootTest(properties = {
        "citypass.eventbus.consumer.enabled=false",
        "citypass.eventbus.publisher.enabled=false",
        "citypass.eventbus.publisher.register-event-types=false"
})
class CuInt01PersistenceIntegrationTest {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("movilidad").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired org.springframework.test.web.servlet.MockMvc http;
    @Autowired InboundEventService inbound;
    @Autowired InboundEntityStateRepository states;
    @Autowired CulturalDestinationRepository destinations;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.citypass.movilidad.repository.DestinationRecommendationJobRepository jobs;
    @Autowired DestinationRecommendationPublisher publisher;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    DestinationRecommendationEventWriter writer;
    private final ObjectMapper mapper = new ObjectMapper();

    private void handle(String topic, String payload) {
        try {
            inbound.handle(topic, mapper.readTree(payload));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    private String event(String id) {
        return """
                {"eventoId":"%s","espacioId":"space-%s","titulo":"Festival",
                 "inicio":"2099-11-01T15:00:00Z","fin":"2099-11-01T18:00:00Z",
                 "latitud":-34.6,"longitud":-58.4,"cupoMaximo":100,"estado":"PUBLISHED"}
                """.formatted(id, id);
    }

    private int outboxCount(String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM event_outbox WHERE aggregate_id = ?",
                Integer.class, id);
    }

    @Test void outboxSePersisteDespuesDelCommitEnOtraTransaccion() {
        String id = UUID.randomUUID().toString();
        transactions.executeWithoutResult(status -> {
            handle("com.citypass.espacios.EventoComunitarioPublicado", event(id));
            assertThat(outboxCount(id)).isZero();
        });
        assertThat(outboxCount(id)).isEqualTo(1);
        String payload = jdbc.queryForObject("SELECT payload FROM event_outbox WHERE aggregate_id = ?",
                String.class, id);
        assertThat(payload).contains("NO_RECOMMENDATION").contains("calculadaEn");
    }

    @Test void rollbackNoGuardaDestinoEstadoNiOutbox() {
        String id = UUID.randomUUID().toString();
        transactions.executeWithoutResult(status -> {
            handle("com.citypass.espacios.EventoCancelado", "{\"eventoId\":\"" + id + "\"}");
            handle("com.citypass.espacios.EventoComunitarioPublicado", event(id));
            status.setRollbackOnly();
        });
        assertThat(destinations.findById(id)).isEmpty();
        assertThat(states.hasState("CULTURAL_EVENT", id, "CANCELLED")).isFalse();
        assertThat(outboxCount(id)).isZero();
    }

    @Test void cancelacionAnteriorAPublicacionNoResucitaElEvento() {
        String id = UUID.randomUUID().toString();
        transactions.executeWithoutResult(status -> handle("com.citypass.espacios.EventoCancelado",
                "{\"eventoId\":\"" + id + "\"}"));
        transactions.executeWithoutResult(status -> handle("com.citypass.espacios.EventoComunitarioPublicado", event(id)));
        assertThat(destinations.findById(id).orElseThrow().isCancelled()).isTrue();
        assertThat(jdbc.queryForObject("SELECT payload FROM event_outbox WHERE aggregate_id = ?", String.class, id))
                .contains("El evento fue cancelado");
    }

    @Test void espacioDeshabilitadoAntesDelDestinoSeConserva() {
        String id = UUID.randomUUID().toString();
        transactions.executeWithoutResult(status -> handle("com.citypass.espacios.EspacioEstadoCambiado",
                "{\"espacioId\":\"space-" + id + "\",\"estadoNuevo\":\"DISABLED\"}"));
        transactions.executeWithoutResult(status -> handle("com.citypass.espacios.EventoComunitarioPublicado", event(id)));
        assertThat(destinations.findById(id).orElseThrow().isSpaceEnabled()).isFalse();
        transactions.executeWithoutResult(status -> handle("com.citypass.espacios.EspacioEstadoCambiado",
                "{\"espacioId\":\"space-" + id + "\",\"estadoNuevo\":\"ENABLED\"}"));
        assertThat(destinations.findById(id).orElseThrow().isSpaceEnabled()).isTrue();
    }

    @Test void resolucionAnteriorACreacionNoAbreUnIncidenteViejo() {
        String id = UUID.randomUUID().toString();
        transactions.executeWithoutResult(status -> handle("com.citypass.reclamos.ReclamoActualizado",
                "{\"reclamoId\":\"" + id + "\",\"estadoNuevo\":\"RESUELTO\"}"));
        transactions.executeWithoutResult(status -> handle("com.citypass.reclamos.ReclamoCreado", """
                {"reclamoId":"%s","categoria":"CALLE","afectaCirculacion":true,
                 "latitud":-34.6,"longitud":-58.4,"createdAt":1000}
                """.formatted(id)));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM city_incidents WHERE external_id = ?", Integer.class, id))
                .isZero();
    }
    @Test void invalidacionFallidaQuedaPendienteYSeRecuperaAunqueElEventoNoSeaVigente() {
        String id = UUID.randomUUID().toString();
        transactions.executeWithoutResult(status -> handle("com.citypass.espacios.EventoComunitarioPublicado", event(id)));
        org.mockito.Mockito.doThrow(new IllegalStateException("transient failure")).when(writer)
                .publish(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.anyMap(),
                        org.mockito.ArgumentMatchers.anyString());
        transactions.executeWithoutResult(status -> handle("com.citypass.espacios.EventoCancelado",
                "{\"eventoId\":\"" + id + "\"}"));
        assertThat(jobs.pendingIds()).contains(id);
        assertThat(outboxCount(id)).isEqualTo(1);
        org.mockito.Mockito.doCallRealMethod().when(writer)
                .publish(org.mockito.ArgumentMatchers.eq(id), org.mockito.ArgumentMatchers.anyMap(),
                        org.mockito.ArgumentMatchers.anyString());
        publisher.recalculateUpcoming();
        assertThat(outboxCount(id)).isEqualTo(2);
        assertThat(jobs.pendingIds()).doesNotContain(id);
        publisher.recalculateUpcoming();
        assertThat(outboxCount(id)).isEqualTo(2);
    }

    @Test void unaConfirmacionViejaNoBorraUnaSolicitudNueva() {
        String id = UUID.randomUUID().toString();
        jobs.request(id);
        String oldToken = jobs.token(id);
        jobs.request(id);
        String currentToken = jobs.token(id);
        assertThat(currentToken).isNotEqualTo(oldToken);
        jobs.complete(id, oldToken);
        assertThat(jobs.token(id)).isEqualTo(currentToken);
        jobs.complete(id, currentToken);
        assertThat(jobs.token(id)).isNull();
    }

    @Test void apiExponeDemandaMaximaYDejaDeRecomendarTrasCancelacion() throws Exception {
        String id = UUID.randomUUID().toString();
        String full = event(id).replace("PUBLISHED", "ACTIVE_FULL");
        transactions.executeWithoutResult(status -> handle("com.citypass.espacios.EventoComunitarioPublicado", full));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/destinations/events"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                        "$[?(@.eventId == '" + id + "')].expectedDemand").value(org.hamcrest.Matchers.hasItem(100)));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/destinations/events/{id}/recommendation", id))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.purpose").value("DROPOFF"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("NO_RECOMMENDATION"));
        transactions.executeWithoutResult(status -> handle("com.citypass.espacios.EventoCancelado",
                "{\"eventoId\":\"" + id + "\"}"));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/destinations/events/{id}/recommendation", id))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }

}
