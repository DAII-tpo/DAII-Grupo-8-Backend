package com.citypass.movilidad.service;

import com.citypass.movilidad.client.RecommendationClient;
import com.citypass.movilidad.client.RecommendationUnavailableException;
import com.citypass.movilidad.eventbus.EventMetadata;
import com.citypass.movilidad.eventbus.consume.CuInt01Handlers;
import com.citypass.movilidad.eventbus.consume.IncomingEvent;
import com.citypass.movilidad.repository.CulturalDestinationRepository;
import com.citypass.movilidad.repository.DestinationRecommendationJobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Contratos reales de Espacios contra handlers, MySQL/Flyway, API y outbox; sin gateway externo. */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "citypass.eventbus.consumer.enabled=false", "citypass.eventbus.publisher.enabled=false",
        "citypass.eventbus.publisher.register-event-types=false", "movilidad.analytics.affected-stations.cron=-"
})
class EspaciosContractIntegrationTest {
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
    @Autowired CulturalDestinationRepository destinations;
    @Autowired DestinationRecommendationJobRepository jobs;
    @Autowired DestinationRecommendationEventWriter writer;
    @Autowired MockMvc http;
    @MockitoBean RecommendationClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String ESPACIOS = "com.citypass.espacios.";
    private static final Instant TIME = Instant.parse("2026-11-05T15:00:00Z");

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM space_reservations");
        jdbc.update("DELETE FROM espacios_event_cursors WHERE entity_type <> 'LOCK'");
        jdbc.update("DELETE FROM event_outbox");
        jdbc.update("DELETE FROM destination_recommendation_jobs");
        jdbc.update("DELETE FROM cultural_destinations");
        jdbc.update("DELETE FROM inbound_entity_states");
        jdbc.update("DELETE FROM stations");
        jdbc.update("INSERT INTO stations (name,latitude,longitude,capacity,status,source) VALUES ('Prueba',-34.6,-58.4,20,'ACTIVE','MANUAL')");
        when(client.recommend(any())).thenThrow(new RecommendationUnavailableException("modelo no disponible"));
    }
    private String snapshot(String state, int registrations) {
        return """
                {"eventoId":"evento-1","espacioId":"espacio-1","titulo":"Festival",
                 "zona":"Barrio","direccion":null,"latitud":-34.6,"longitud":-58.4,
                 "inicio":"2099-11-01T15:00:00Z","fin":"2099-11-01T18:00:00Z",
                 "capacidad":20,"cupoMaximo":20,"inscriptos":%d,"estado":"%s",
                 "categoria":"CULTURA","correlationId":"evento-evento-1"}
                """.formatted(registrations, state);
    }
    private String reservation(String type, String resource) {
        return """
                {"reservaId":"reserva-1","tipoReserva":"%s","recursoId":"%s",
                 "espacioId":"espacio-1","cantidadPersonas":1,
                 "inicio":"2099-11-01T15:00:00Z","fin":"2099-11-01T18:00:00Z"}
                """.formatted(type, resource);
    }
    private void send(String name, String payload, Instant receivedAt) {
        try {
            JsonNode data = mapper.readTree(payload);
            var config = new CuInt01Handlers();
            var handler = switch (name) {
                case "EventoComunitarioPublicado" -> config.cuInt01Handler1(inbound);
                case "EventoCancelado" -> config.cuInt01Handler2(inbound);
                case "EspacioEstadoCambiado" -> config.cuInt01Handler3(inbound);
                case "ReservaConfirmada" -> config.cuInt01Handler4(inbound);
                default -> throw new IllegalArgumentException(name);
            };
            var metadata = new EventMetadata(UUID.randomUUID().toString(), ESPACIOS + name,
                    receivedAt, "grupo7", null, null, null, null, null);
            transactions.executeWithoutResult(tx -> handler.handle(new IncomingEvent(ESPACIOS + name, metadata, data)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalArgumentException(ex);
        }
    }
    private List<JsonNode> output() {
        return jdbc.queryForList("SELECT payload FROM event_outbox WHERE event_type='com.citypass.movilidad.RecomendacionDestinoActualizada' ORDER BY created_at, id",
                String.class).stream().map(json -> {
            try { return mapper.readTree(json); }
            catch (Exception ex) { throw new IllegalStateException(ex); }
        }).sorted(java.util.Comparator.comparing(data -> Instant.parse(data.path("calculadaEn").asText()))).toList();
    }
    private int outputCount() { return output().size(); }

    @Test void creacionInscripcionSuspensionReanudacionYCancelacion() throws Exception {
        send("EventoComunitarioPublicado", snapshot("ACTIVE", 0), TIME);
        assertThat(output().getLast().path("estado").asText()).isEqualTo("RECOMMENDED");
        send("ReservaConfirmada", reservation("EVENTO", "evento-1"), TIME.plusSeconds(1));
        assertThat(destinations.findById("evento-1").orElseThrow().getRegistrations()).isZero();
        send("EventoComunitarioPublicado", snapshot("ACTIVE", 1), TIME.plusSeconds(2));
        send("EventoComunitarioPublicado", snapshot("SUSPENDED", 1), TIME.plusSeconds(3));
        assertThat(output().getLast().path("estado").asText()).isEqualTo("NO_RECOMMENDATION");
        assertThat(output().getLast().path("estacionId").isNull()).isTrue();
        http.perform(get("/api/v1/destinations/events")).andExpect(status().isOk()).andExpect(content().json("[]"));
        http.perform(get("/api/v1/destinations/events/evento-1/recommendation")).andExpect(status().isNotFound());
        send("EventoComunitarioPublicado", snapshot("ACTIVE_FULL", 20), TIME.plusSeconds(4));
        assertThat(output().getLast().path("estado").asText()).isEqualTo("RECOMMENDED");
        http.perform(get("/api/v1/destinations/events")).andExpect(jsonPath("$[0].expectedDemand").value(20));
        send("EventoCancelado", "{\"eventoId\":\"evento-1\",\"espacioId\":\"espacio-1\"}", TIME.plusSeconds(5));
        assertThat(output().getLast().path("mensaje").asText()).contains("cancelado");
        send("EventoComunitarioPublicado", snapshot("ACTIVE", 0), TIME.plusSeconds(6));
        assertThat(destinations.findById("evento-1").orElseThrow().isCancelled()).isTrue();
        assertThat(output().getLast().path("estado").asText()).isEqualTo("NO_RECOMMENDATION");
    }

    @Test void snapshotAtrasadoNoRevierteSuspensionNiDemandaConPrecisionDeNanosegundos() {
        send("EventoComunitarioPublicado", snapshot("SUSPENDED", 12), TIME.plusNanos(2));
        int count = outputCount();
        send("EventoComunitarioPublicado", snapshot("ACTIVE", 50), TIME.plusNanos(1));
        var event = destinations.findById("evento-1").orElseThrow();
        assertThat(event.getEventStatus()).isEqualTo("SUSPENDED");
        assertThat(event.getRegistrations()).isEqualTo(12);
        assertThat(outputCount()).isEqualTo(count);
        send("EventoComunitarioPublicado", snapshot("ACTIVE", 3), TIME.plusNanos(3));
        assertThat(destinations.findById("evento-1").orElseThrow().getRegistrations()).isEqualTo(3);
        assertThat(output().getLast().path("estado").asText()).isEqualTo("RECOMMENDED");
    }

    @Test void estadoDesconocidoNoSeRecomiendaYPuedeRecuperarse() {
        send("EventoComunitarioPublicado", snapshot("ESTADO_FUTURO", 2), TIME);
        assertThat(output().getLast().path("estado").asText()).isEqualTo("NO_RECOMMENDATION");
        assertThat(destinations.findById("evento-1").orElseThrow().getEventStatus()).isEqualTo("UNKNOWN");
        send("EventoComunitarioPublicado", snapshot("ACTIVE", 2), TIME.plusSeconds(1));
        assertThat(output().getLast().path("estado").asText()).isEqualTo("RECOMMENDED");
    }

    @Test void cancelacionTerminalGanaAunqueLlegueAntesDeLaCreacionYConFechaMenor() {
        send("EventoCancelado", "{\"eventoId\":\"evento-1\"}", TIME);
        send("EventoComunitarioPublicado", snapshot("ACTIVE", 0), TIME.plusSeconds(1));
        assertThat(destinations.findById("evento-1").orElseThrow().isRoutable()).isFalse();
        assertThat(output().getLast().path("estado").asText()).isEqualTo("NO_RECOMMENDATION");
        send("EventoCancelado", "{\"eventoId\":\"evento-1\"}", TIME.minusSeconds(1));
        assertThat(destinations.findById("evento-1").orElseThrow().isCancelled()).isTrue();
    }

    @Test void reservaAntesDelSnapshotYDuplicadosNoInventanDestinoNiDuplicanInscriptos() {
        send("ReservaConfirmada", reservation("EVENTO", "evento-1"), TIME);
        send("ReservaConfirmada", reservation("EVENTO", "evento-1"), TIME.plusSeconds(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM space_reservations", Integer.class)).isEqualTo(1);
        assertThat(destinations.findAll()).isEmpty();
        assertThat(output()).isEmpty();
        send("EventoComunitarioPublicado", snapshot("ACTIVE", 42), TIME.plusSeconds(2));
        send("ReservaConfirmada", reservation("EVENTO", "evento-1"), TIME.plusSeconds(3));
        assertThat(destinations.findById("evento-1").orElseThrow().getRegistrations()).isEqualTo(42);
    }

    @Test void reservaDeEspacioConservaDemandaHorarioSinCrearEventoCultural() {
        send("ReservaConfirmada", reservation("ESPACIO", "espacio-1"), TIME);
        send("ReservaConfirmada", reservation("ESPACIO", "espacio-1").replace("cantidadPersonas\":1", "cantidadPersonas\":9"), TIME.minusSeconds(1));
        assertThat(jdbc.queryForObject("SELECT people FROM space_reservations", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reservation_type FROM space_reservations", String.class)).isEqualTo("ESPACIO");
        assertThat(jdbc.queryForObject("SELECT starts_at IS NOT NULL AND ends_at IS NOT NULL FROM space_reservations", Boolean.class)).isTrue();
        assertThat(destinations.findAll()).isEmpty();
        assertThat(output()).isEmpty();
    }

    @Test void estadoDeEspacioAtrasadoNoHabilitaLoDeshabilitado() {
        send("EspacioEstadoCambiado", "{\"espacioId\":\"espacio-1\",\"estadoNuevo\":\"DISABLED\"}", TIME.plusSeconds(2));
        send("EspacioEstadoCambiado", "{\"espacioId\":\"espacio-1\",\"estadoNuevo\":\"ENABLED\"}", TIME);
        send("EventoComunitarioPublicado", snapshot("ACTIVE", 2), TIME.plusSeconds(1));
        assertThat(destinations.findById("evento-1").orElseThrow().isSpaceEnabled()).isFalse();
        assertThat(output().getLast().path("estado").asText()).isEqualTo("NO_RECOMMENDATION");
        send("EspacioEstadoCambiado", "{\"espacioId\":\"espacio-1\",\"estadoNuevo\":\"ENABLED\"}", TIME.plusSeconds(3));
        assertThat(output().getLast().path("estado").asText()).isEqualTo("RECOMMENDED");
    }

    @Test void rollbackRevierteReservaCursorYOutbox() throws Exception {
        JsonNode data = mapper.readTree(reservation("ESPACIO", "espacio-1"));
        transactions.executeWithoutResult(tx -> {
            inbound.handle(ESPACIOS + "ReservaConfirmada", data, TIME);
            tx.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM space_reservations", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM espacios_event_cursors WHERE entity_type='RESERVATION'", Integer.class)).isZero();
        assertThat(output()).isEmpty();
    }

    @Test void concurrenciaConSnapshotAntiguoNoPierdeLaVersionMasNueva() throws Exception {
        CompletableFuture.allOf(
                CompletableFuture.runAsync(() -> send("EventoComunitarioPublicado", snapshot("ACTIVE", 99), TIME)),
                CompletableFuture.runAsync(() -> send("EventoComunitarioPublicado", snapshot("SUSPENDED", 4), TIME.plusSeconds(1)))
        ).get(30, TimeUnit.SECONDS);
        var event = destinations.findById("evento-1").orElseThrow();
        assertThat(event.getEventStatus()).isEqualTo("SUSPENDED");
        assertThat(event.getRegistrations()).isEqualTo(4);
    }

    @Test void unCalculoViejoNoSePublicaDespuesDeSuspenderAunqueElNuevoYaSeConfirmo() {
        send("EventoComunitarioPublicado", snapshot("ACTIVE", 0), TIME);
        String previous = jobs.token("evento-1");
        send("EventoComunitarioPublicado", snapshot("SUSPENDED", 0), TIME.plusSeconds(1));
        int count = outputCount();
        writer.publish("evento-1", Map.of("eventoId", "evento-1", "estado", "RECOMMENDED"), previous);
        assertThat(outputCount()).isEqualTo(count);
        assertThat(jobs.pendingIds()).isEmpty();
    }
    @Test void reservaInvalidaNoDejaDatosNiCursorParcial() {
        String valid = reservation("ESPACIO", "espacio-1");
        for (String invalid : List.of(
                reservation("OTRO", "espacio-1"), reservation("ESPACIO", "otro-espacio"),
                valid.replace("cantidadPersonas\":1", "cantidadPersonas\":-1"),
                valid.replace("2099-11-01T18:00:00Z", "2099-11-01T14:00:00Z"))) {
            assertThatThrownBy(() -> send("ReservaConfirmada", invalid, TIME)).isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM space_reservations", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM espacios_event_cursors WHERE entity_type='RESERVATION'", Integer.class)).isZero();
        }
    }
    @Test void snapshotInvalidoNoAvanzaElCursorNiBloqueaUnoValido() {
        String invalid = snapshot("ACTIVE", 2).replace("2099-11-01T18:00:00Z", "2099-11-01T14:00:00Z");
        assertThatThrownBy(() -> send("EventoComunitarioPublicado", invalid, TIME.plusSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM espacios_event_cursors WHERE entity_type='CULTURAL_EVENT'", Integer.class)).isZero();
        assertThat(destinations.findAll()).isEmpty();
        send("EventoComunitarioPublicado", snapshot("ACTIVE", 2), TIME);
        assertThat(destinations.findById("evento-1").orElseThrow().getRegistrations()).isEqualTo(2);
        assertThat(output().getLast().path("estado").asText()).isEqualTo("RECOMMENDED");
    }
    @Test void cancelacionProcesadaAntesQueUnSnapshotConReceivedAtAnteriorDevuelveInvalidacion() {
        send("EventoCancelado", "{\"eventoId\":\"evento-1\"}", TIME.plusSeconds(1));
        send("EventoComunitarioPublicado", snapshot("ACTIVE", 0), TIME);
        var event = destinations.findById("evento-1").orElseThrow();
        assertThat(event.isCancelled()).isTrue();
        assertThat(event.getEventStatus()).isEqualTo("CANCELLED");
        assertThat(event.isRoutable()).isFalse();
        assertThat(output().getLast().path("estado").asText()).isEqualTo("NO_RECOMMENDATION");
    }
}
