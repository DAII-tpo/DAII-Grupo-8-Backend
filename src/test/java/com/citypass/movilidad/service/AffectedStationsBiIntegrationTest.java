package com.citypass.movilidad.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

/** Proyección → afectación persistida → outbox real; no requiere el gateway externo. */
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
    @Autowired AffectedStationsService affectations;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String AFECTADA = "com.citypass.movilidad.EstacionUrbanaAfectada";
    private static final String RESTAURADA = "com.citypass.movilidad.EstacionUrbanaRestaurada";

    @BeforeEach void reset() {
        jdbc.update("DELETE FROM station_affectations");
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
    private List<JsonNode> payloads(String type) {
        return jdbc.queryForList("SELECT payload FROM event_outbox WHERE event_type = ?", String.class, type)
                .stream().map(json -> {
                    try { return mapper.readTree(json); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                }).toList();
    }
    private String incident(String id) {
        return """
                {"reclamoId":"%s","categoria":"TRANSITO","afectaCirculacion":true,
                 "latitud":-34.6,"longitud":-58.4,"createdAt":1000,"correlationId":"c-1"}
                """.formatted(id);
    }
    private void claim(String id) throws Exception {
        handle("com.citypass.reclamos.ReclamoCreado", incident(id));
    }
    private void close(String id) throws Exception {
        handle("com.citypass.reclamos.ReclamoActualizado", "{\"reclamoId\":\"" + id + "\",\"estadoNuevo\":\"RESUELTO\"}");
    }

    @Test void publicaContratoConOrigenRealYNoDuplicaEnRefrescos() throws Exception {
        station("Activa", "ACTIVE", false, -34.6001);
        station("Inactiva", "INACTIVE", false, -34.6002);
        station("Eliminada", "ACTIVE", true, -34.6003);
        station("Lejana", "ACTIVE", false, -34.7);
        claim("r-1");
        publisher.refresh();
        claim("r-1");
        assertThat(payloads(AFECTADA)).hasSize(1);
        JsonNode data = payloads(AFECTADA).getFirst();
        assertThat(data.size()).isEqualTo(12);
        assertThat(UUID.fromString(data.path("afectacionId").asText())).isNotNull();
        assertThat(data.path("estacionId").asText()).isEqualTo(jdbc.queryForObject(
                "SELECT CAST(id AS CHAR) FROM stations WHERE name='Activa'", String.class));
        assertThat(data.path("causaTipo").asText()).isEqualTo("RECLAMO");
        assertThat(data.path("causaId").asText()).isEqualTo("r-1");
        assertThat(data.path("moduloOrigen").asText()).isEqualTo("RECLAMOS");
        assertThat(data.path("correlationId").asText()).isEqualTo("c-1");
        assertThat(data.path("tipoAfectacion").asText()).isEqualTo("RECOMENDACION_RESTRINGIDA");
        assertThat(data.path("afectacionHastaEstimada").isNull()).isTrue();
        assertThat(Instant.parse(data.path("afectacionDesde").asText())).isBeforeOrEqualTo(Instant.now());
        assertThat(data.path("latitud").doubleValue()).isEqualTo(-34.6001);
        assertThat(payloads(RESTAURADA)).isEmpty();
    }

    @Test void restauraLaCausaResueltaSinCerrarLaOtraYConservaElId() throws Exception {
        station("Activa", "ACTIVE", false, -34.6001);
        claim("r-1");
        String id = payloads(AFECTADA).getFirst().path("afectacionId").asText();
        claim("r-2");
        close("r-1");
        assertThat(payloads(RESTAURADA)).hasSize(1);
        assertThat(payloads(RESTAURADA).getFirst().path("afectacionId").asText()).isEqualTo(id);
        assertThat(payloads(RESTAURADA).getFirst().path("correlationId").asText()).isEqualTo("c-1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM station_affectations", Integer.class)).isEqualTo(1);
        close("r-2");
        publisher.refresh();
        assertThat(payloads(AFECTADA)).hasSize(2);
        assertThat(payloads(RESTAURADA)).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM station_affectations", Integer.class)).isZero();
    }

    @Test void identificaEmergenciaYGeneraCorrelacionEstableCuandoNoLaRecibe() throws Exception {
        station("Activa", "ACTIVE", false, -34.6);
        handle("com.citypass.emergencias.EmergenciaCreada", """
                {"emergenciaId":"e-1","categoria":"ACCIDENTE","afectaCirculacion":true,
                 "latitud":-34.6,"longitud":-58.4,"createdAt":"2026-11-02T15:00:00Z"}
                """);
        var data = payloads(AFECTADA).getFirst();
        assertThat(data.path("causaTipo").asText()).isEqualTo("EMERGENCIA");
        assertThat(data.path("moduloOrigen").asText()).isEqualTo("EMERGENCIAS");
        assertThat(data.path("causaId").asText()).isEqualTo("e-1");
        assertThat(data.path("correlationId").asText()).isEqualTo("EMERGENCIAS-e-1");
        handle("com.citypass.emergencias.EmergenciaActualizada", "{\"emergenciaId\":\"e-1\",\"estadoNuevo\":\"RESUELTA\"}");
        assertThat(payloads(RESTAURADA)).hasSize(1);
    }

    @Test void conservaUnaAfectacionVialHastaCerrarTodosSusReportes() throws Exception {
        station("Activa", "ACTIVE", false, -34.6);
        for (String id : List.of("r-1", "r-2")) {
            handle("com.citypass.reclamos.ReclamoCreado", incident(id).replace(
                    "\"categoria\"", "\"causaTipo\":\"INCIDENTE_VIAL\",\"causaId\":\"vial-1\",\"categoria\""));
        }
        assertThat(payloads(AFECTADA)).hasSize(1);
        var data = payloads(AFECTADA).getFirst();
        assertThat(data.path("causaTipo").asText()).isEqualTo("INCIDENTE_VIAL");
        assertThat(data.path("causaId").asText()).isEqualTo("vial-1");
        assertThat(data.path("moduloOrigen").asText()).isEqualTo("RECLAMOS");
        close("r-1");
        assertThat(payloads(RESTAURADA)).isEmpty();
        close("r-2");
        assertThat(payloads(RESTAURADA)).hasSize(1);
        assertThat(payloads(RESTAURADA).getFirst().path("afectacionId").asText())
                .isEqualTo(data.path("afectacionId").asText());
    }

    @ParameterizedTest
    @CsvSource({
            "reclamos,ReclamoCreado,reclamoId,RECLAMOS",
            "emergencias,EmergenciaCreada,emergenciaId,EMERGENCIAS"
    })
    void publicaReferenciaVialExplicitaEnOutboxSinCambiarElOrigen(String namespace, String event, String idField,
                                                               String origin) throws Exception {
        station("Activa", "ACTIVE", false, -34.6);
        String payload = """
                {"%s":"reporte-1","categoria":"TRANSITO","afectaCirculacion":true,
                 "latitud":-34.6,"longitud":-58.4,"createdAt":"2026-11-02T15:00:00Z",
                 "causaTipo":"INCIDENTE_VIAL","causaId":"vial-original","correlationId":"c-vial"}
                """.formatted(idField);
        handle("com.citypass." + namespace + "." + event, payload);
        handle("com.citypass." + namespace + "." + event, payload);
        publisher.refresh();
        assertThat(payloads(AFECTADA)).hasSize(1);
        var data = payloads(AFECTADA).getFirst();
        assertThat(data.size()).isEqualTo(12);
        assertThat(data.path("causaTipo").asText()).isEqualTo("INCIDENTE_VIAL");
        assertThat(data.path("causaId").asText()).isEqualTo("vial-original");
        assertThat(data.path("moduloOrigen").asText()).isEqualTo(origin);
        assertThat(data.path("correlationId").asText()).isEqualTo("c-vial");
        assertThat(data.path("tipoAfectacion").asText()).isEqualTo("RECOMENDACION_RESTRINGIDA");
        var schema = avroSchema("EstacionUrbanaAfectada");
        var decoded = new org.apache.avro.generic.GenericDatumReader<org.apache.avro.generic.GenericRecord>(schema)
                .read(null, org.apache.avro.io.DecoderFactory.get().jsonDecoder(schema, data.toString()));
        assertThat(decoded.get("causaTipo").toString()).isEqualTo("INCIDENTE_VIAL");
        assertThat(decoded.get("moduloOrigen").toString()).isEqualTo(origin);
    }

    @ParameterizedTest
    @CsvSource({
            "reclamos,ReclamoCreado,reclamoId,RECLAMO,RECLAMOS",
            "emergencias,EmergenciaCreada,emergenciaId,EMERGENCIA,EMERGENCIAS"
    })
    void noInfiereCausaVialPorCategoriaNiPorIdentificadorSuelto(String namespace, String event, String idField,
                                                             String cause, String origin) throws Exception {
        station("Activa", "ACTIVE", false, -34.6);
        String payload = """
                {"%s":"reporte-1","categoria":"INCIDENTE_VIAL","afectaCirculacion":true,
                 "latitud":-34.6,"longitud":-58.4,"createdAt":"2026-11-02T15:00:00Z","causaId":"referencia-suelta"}
                """.formatted(idField);
        handle("com.citypass." + namespace + "." + event, payload);
        assertThat(payloads(AFECTADA)).hasSize(1);
        var data = payloads(AFECTADA).getFirst();
        assertThat(data.path("causaTipo").asText()).isEqualTo(cause);
        assertThat(data.path("causaId").asText()).isEqualTo("reporte-1");
        assertThat(data.path("moduloOrigen").asText()).isEqualTo(origin);
    }

    @ParameterizedTest
    @CsvSource({"reclamos,ReclamoCreado,reclamoId", "emergencias,EmergenciaCreada,emergenciaId"})
    void rechazaReferenciaVialSinIdSinPublicarNiPersistir(String namespace, String event, String idField) {
        station("Activa", "ACTIVE", false, -34.6);
        String payload = """
                {"%s":"reporte-1","categoria":"TRANSITO","afectaCirculacion":true,
                 "latitud":-34.6,"longitud":-58.4,"createdAt":"2026-11-02T15:00:00Z",
                 "causaTipo":"INCIDENTE_VIAL","causaId":" "}
                """.formatted(idField);
        assertThatThrownBy(() -> handle("com.citypass." + namespace + "." + event, payload))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("causaId");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM city_incidents", Integer.class)).isZero();
        assertThat(payloads(AFECTADA)).isEmpty();
    }

    @Test void contratoAvroAdmiteTodosLosSimbolosDeBiYConservaLosDoceCampos() throws Exception {
        station("Activa", "ACTIVE", false, -34.6);
        claim("r-1");
        var schema = avroSchema("EstacionUrbanaAfectada");
        assertThat(schema.getFields().stream().map(org.apache.avro.Schema.Field::name)).containsExactly(
                "afectacionId", "estacionId", "latitud", "longitud", "tipoAfectacion", "afectacionDesde",
                "afectacionHastaEstimada", "causaId", "causaTipo", "moduloOrigen", "correlationId", "occurredAt");
        var types = List.of("BLOQUEADA", "CAPACIDAD_REDUCIDA", "FUERA_DE_SERVICIO");
        var causes = List.of("EVENTO_CLIMATICO", "RECLAMO", "INCIDENTE_VIAL", "EVENTO_PUBLICO", "RESERVA");
        assertThat(schema.getField("tipoAfectacion").schema().getEnumSymbols()).containsAll(types);
        assertThat(schema.getField("causaTipo").schema().getEnumSymbols()).containsAll(causes);
        var data = (com.fasterxml.jackson.databind.node.ObjectNode) payloads(AFECTADA).getFirst();
        for (String type : types) {
            for (String cause : causes) {
                data.put("tipoAfectacion", type).put("causaTipo", cause);
                var decoded = new org.apache.avro.generic.GenericDatumReader<org.apache.avro.generic.GenericRecord>(schema)
                        .read(null, org.apache.avro.io.DecoderFactory.get().jsonDecoder(schema, data.toString()));
                assertThat(decoded.get("tipoAfectacion").toString()).isEqualTo(type);
                assertThat(decoded.get("causaTipo").toString()).isEqualTo(cause);
            }
        }
    }

    private org.apache.avro.Schema avroSchema(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/eventbus/event-types/" + name + ".json")) {
            var definition = mapper.createObjectNode();
            definition.put("type", "record").put("name", name).put("namespace", "com.citypass.movilidad");
            definition.set("fields", mapper.readTree(stream).path("fields"));
            return new org.apache.avro.Schema.Parser().parse(definition.toString());
        }
    }

    @Test void noPublicaAlHacerRollbackYRecuperaCambiosDeCatalogo() throws Exception {
        station("Activa", "ACTIVE", false, -34.6);
        JsonNode data = mapper.readTree(incident("rollback"));
        transactions.executeWithoutResult(status -> {
            inbound.handle("com.citypass.reclamos.ReclamoCreado", data);
            assertThat(payloads(AFECTADA)).isEmpty();
            status.setRollbackOnly();
        });
        publisher.refresh();
        assertThat(payloads(AFECTADA)).isEmpty();
        claim("r-1");
        jdbc.update("UPDATE stations SET status='INACTIVE'");
        publisher.refresh();
        assertThat(payloads(RESTAURADA)).hasSize(1);
        jdbc.update("UPDATE stations SET status='ACTIVE'");
        publisher.refresh();
        assertThat(payloads(AFECTADA)).hasSize(2);
        assertThat(payloads(AFECTADA).get(0).path("afectacionId").asText())
                .isNotEqualTo(payloads(AFECTADA).get(1).path("afectacionId").asText());
    }

    @Test void dosReconciliacionesConcurrentesNoDuplicanElOutbox() throws Exception {
        station("Activa", "ACTIVE", false, -34.6);
        // Inserción directa simula una proyección confirmada antes de ejecutar el refresco.
        jdbc.update("""
                INSERT INTO city_incidents (source,external_id,category,latitude,longitude,active,reported_at,updated_at)
                VALUES ('RECLAMOS','r-1','TRANSITO',-34.6,-58.4,true,NOW(),NOW())
                """);
        CompletableFuture.allOf(CompletableFuture.runAsync(affectations::synchronize),
                CompletableFuture.runAsync(affectations::synchronize)).get(30, TimeUnit.SECONDS);
        assertThat(payloads(AFECTADA)).hasSize(1);
    }

    @Test void falloDeOutboxRevierteLaAfectacionYPermiteReintentar() throws Exception {
        station("Activa", "ACTIVE", false, -34.6);
        // Un fallo real de INSERT en el outbox debe revertir también el registro de afectación.
        jdbc.update("""
                INSERT INTO city_incidents (source,external_id,category,latitude,longitude,active,reported_at,updated_at)
                VALUES ('RECLAMOS','r-1','TRANSITO',-34.6,-58.4,true,NOW(),NOW())
                """);
        jdbc.execute("ALTER TABLE event_outbox ADD CONSTRAINT chk_test_no_bi CHECK (event_type <> 'com.citypass.movilidad.EstacionUrbanaAfectada')");
        try {
            assertThatThrownBy(affectations::synchronize).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM station_affectations", Integer.class)).isZero();
        } finally {
            jdbc.execute("ALTER TABLE event_outbox DROP CHECK chk_test_no_bi");
        }
        affectations.synchronize();
        assertThat(payloads(AFECTADA)).hasSize(1);
    }

    @Test void schemasCoincidenConLosPayloadsPublicados() throws Exception {
        station("Activa", "ACTIVE", false, -34.6);
        claim("r-1");
        close("r-1");
        for (String type : List.of(AFECTADA, RESTAURADA)) {
            String name = type.substring(type.lastIndexOf('.') + 1);
            try (var stream = getClass().getResourceAsStream("/eventbus/event-types/" + name + ".json")) {
                JsonNode schema = mapper.readTree(stream);
                var names = new java.util.ArrayList<String>();
                schema.path("fields").forEach(field -> names.add(field.path("name").asText()));
                var recordDefinition = mapper.createObjectNode();
                recordDefinition.put("type", "record");
                recordDefinition.put("name", name);
                recordDefinition.put("namespace", "com.citypass.movilidad");
                recordDefinition.set("fields", schema.path("fields"));
                var avro = new org.apache.avro.Schema.Parser().parse(recordDefinition.toString());
                JsonNode payload = payloads(type).getFirst();
                var reader = new org.apache.avro.generic.GenericDatumReader<org.apache.avro.generic.GenericRecord>(avro);
                var decoded = reader.read(null, org.apache.avro.io.DecoderFactory.get().jsonDecoder(avro, payload.toString()));
                assertThat(decoded.get("afectacionId").toString()).isEqualTo(payload.path("afectacionId").asText());
                var actual = new java.util.ArrayList<String>();
                payload.fieldNames().forEachRemaining(actual::add);
                assertThat(actual).containsExactlyInAnyOrderElementsOf(names);
                schema.path("fields").forEach(field -> {
                    JsonNode symbols = field.path("type").path("symbols");
                    if (symbols.isArray()) {
                        assertThat(symbols.toString()).contains("\"" + payload.path(field.path("name").asText()).asText() + "\"");
                    }
                });
            }
        }
    }
}
