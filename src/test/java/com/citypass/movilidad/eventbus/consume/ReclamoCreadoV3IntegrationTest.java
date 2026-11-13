package com.citypass.movilidad.eventbus.consume;

import com.citypass.movilidad.repository.EventInboxRepository;
import com.citypass.movilidad.service.CicloviaObstructionService;
import com.citypass.movilidad.service.DestinationRecommendationPublisher;
import com.citypass.movilidad.service.TrafficIncidentChanged;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.EncoderFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/** Avro → mapper → dispatcher/handlers reales → MySQL e inbox/outbox, sin publicar en EDA. */
@Testcontainers
@SpringBootTest(properties = {
        "citypass.eventbus.consumer.enabled=false", "citypass.eventbus.publisher.enabled=false",
        "citypass.eventbus.publisher.register-event-types=false", "movilidad.analytics.affected-stations.cron=-"
})
class ReclamoCreadoV3IntegrationTest {
    private static final String CREATED = "com.citypass.reclamos.ReclamoCreado.v3";
    private static final String UPDATED = "com.citypass.reclamos.ReclamoActualizado";
    private static final String AFFECTED = "com.citypass.movilidad.EstacionUrbanaAfectada";
    private static final String RESTORED = "com.citypass.movilidad.EstacionUrbanaRestaurada";
    private static final long CREATED_AT = 1793884500000L;

    private static final Schema CREATED_DATA = SchemaBuilder.record("ReclamoCreadoData")
            .namespace("com.citypass.reclamos").fields()
            .requiredString("reclamoId").requiredString("categoria").requiredBoolean("afectaCirculacion")
            .optionalDouble("latitud").optionalDouble("longitud").requiredLong("createdAt")
            .optionalString("correlationId").optionalString("causaId").optionalString("causaTipo")
            .endRecord();
    private static final Schema UPDATED_DATA = SchemaBuilder.record("ReclamoActualizadoData")
            .namespace("com.citypass.reclamos").fields()
            .requiredString("reclamoId").requiredString("estadoNuevo").endRecord();

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("movilidad").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired List<EventHandler> handlers;
    @Autowired EventInboxRepository inbox;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CicloviaObstructionService obstructionService;
    @MockitoSpyBean DestinationRecommendationPublisher recommendations;

    private final ObjectMapper mapper = new ObjectMapper();
    private EventDispatcher dispatcher;

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM station_affectations");
        jdbc.update("DELETE FROM event_outbox");
        jdbc.update("DELETE FROM event_inbox");
        jdbc.update("DELETE FROM city_incidents");
        jdbc.update("DELETE FROM inbound_entity_states");
        jdbc.update("DELETE FROM stations");
        dispatcher = new EventDispatcher(handlers, new EventEnvelopeMapper(mapper), inbox, Clock.systemUTC());
    }

    @Test
    void v3AvroAfectaSoloEstacionesActivasEnRadioYActualizadoLasRestaura() throws Exception {
        station("Cercana", "ACTIVE", false, -34.6001);
        station("Dentro de 300 m", "ACTIVE", false, -34.60269);
        station("Fuera de 300 m", "ACTIVE", false, -34.60271);
        station("Inactiva", "INACTIVE", false, -34.6);
        station("Eliminada", "ACTIVE", true, -34.6);
        GenericRecord created = created(true, -34.6, -58.4);

        dispatch(CREATED, created);
        dispatch(CREATED, created); // Reentrega del mismo eventId: no duplica los efectos.

        assertThat(dispatcher.topics()).contains(CREATED, UPDATED)
                .doesNotContain("com.citypass.reclamos.ReclamoCreado");
        assertThat(handlers).extracting(EventHandler::topic).doesNotHaveDuplicates();
        assertThat(count("city_incidents")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT reported_at FROM city_incidents", Timestamp.class).toInstant())
                .isEqualTo(Instant.ofEpochMilli(CREATED_AT));
        assertThat(payloads(AFFECTED)).hasSize(2);
        assertThat(jdbc.queryForList("SELECT s.name FROM station_affectations a JOIN stations s ON s.id=a.station_id",
                String.class)).containsExactlyInAnyOrder("Cercana", "Dentro de 300 m");
        assertThat(payloads(AFFECTED)).allSatisfy(data -> {
            assertThat(data.path("causaTipo").asText()).isEqualTo("RECLAMO");
            assertThat(data.path("moduloOrigen").asText()).isEqualTo("RECLAMOS");
            assertThat(data.path("causaId").asText()).isEqualTo("r-v3");
        });
        verify(recommendations).afterIncidentChanged(new TrafficIncidentChanged(-34.6, -58.4));

        GenericRecord updated = new GenericData.Record(UPDATED_DATA);
        updated.put("reclamoId", "r-v3");
        updated.put("estadoNuevo", "RESUELTO");
        dispatch(UPDATED, envelope("resolved-v3", UPDATED, updated));

        assertThat(jdbc.queryForObject("SELECT active FROM city_incidents", Boolean.class)).isFalse();
        assertThat(count("station_affectations")).isZero();
        assertThat(payloads(RESTORED)).extracting(data -> data.path("afectacionId").asText())
                .containsExactlyInAnyOrderElementsOf(payloads(AFFECTED).stream()
                        .map(data -> data.path("afectacionId").asText()).toList());
        assertThat(count("event_inbox")).isEqualTo(2);
    }

    @Test
    void v3SinAfectarCirculacionNoGeneraIncidentesNiAfectaciones() throws Exception {
        station("Cercana", "ACTIVE", false, -34.6);
        dispatch(CREATED, created(false, -34.6, -58.4));

        assertThat(count("city_incidents")).isZero();
        assertThat(payloads(AFFECTED)).isEmpty();
        assertThat(count("event_inbox")).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource(value = {"null,-58.4", "-34.6,null"}, nullValues = "null")
    void v3AceptaCoordenadasAvroNullableSinInventarAfectaciones(Double latitude, Double longitude) throws Exception {
        station("Cercana", "ACTIVE", false, -34.6);
        dispatch(CREATED, created(true, latitude, longitude));

        assertThat(count("city_incidents")).isZero();
        assertThat(payloads(AFFECTED)).isEmpty();
        assertThat(count("event_inbox")).isEqualTo(1);
    }

    private GenericRecord created(boolean affects, Double latitude, Double longitude) throws Exception {
        GenericRecord data = new GenericData.Record(CREATED_DATA);
        data.put("reclamoId", "r-v3");
        data.put("categoria", "TRANSITO");
        data.put("afectaCirculacion", affects);
        data.put("latitud", latitude);
        data.put("longitud", longitude);
        data.put("createdAt", CREATED_AT);
        data.put("correlationId", null);
        data.put("causaId", null);
        data.put("causaTipo", null);
        // El tópico es v3, pero metadata.eventType conserva el FQN original del gateway.
        return envelope("created-v3", "com.citypass.reclamos.ReclamoCreado", data);
    }

    private GenericRecord envelope(String eventId, String eventType, GenericRecord data) throws Exception {
        Schema schema = SchemaBuilder.record(data.getSchema().getName() + "Envelope")
                .namespace("com.citypass.reclamos").fields()
                .name("metadata").type(AvroEvents.METADATA).noDefault()
                .name("data").type(data.getSchema()).noDefault().endRecord();
        GenericRecord envelope = new GenericData.Record(schema);
        envelope.put("metadata", AvroEvents.envelope(eventId, eventType, false).get("metadata"));
        envelope.put("data", data);
        var bytes = new ByteArrayOutputStream();
        var encoder = EncoderFactory.get().binaryEncoder(bytes, null);
        new GenericDatumWriter<GenericRecord>(schema).write(envelope, encoder);
        encoder.flush();
        return new GenericDatumReader<GenericRecord>(schema)
                .read(null, DecoderFactory.get().binaryDecoder(bytes.toByteArray(), null));
    }

    private void dispatch(String topic, GenericRecord envelope) {
        transactions.executeWithoutResult(status -> dispatcher.dispatch(new ConsumerRecord<>(topic, 0, 0L, null, envelope)));
    }

    private void station(String name, String status, boolean deleted, double latitude) {
        jdbc.update("INSERT INTO stations (name,latitude,longitude,capacity,status,source,deleted_at) VALUES (?,?,?,20,?,'MANUAL',?)",
                name, latitude, -58.4, status, deleted ? Timestamp.from(Instant.now()) : null);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private List<JsonNode> payloads(String eventType) {
        return jdbc.queryForList("SELECT payload FROM event_outbox WHERE event_type=?", String.class, eventType)
                .stream().map(json -> {
                    try { return mapper.readTree(json); }
                    catch (Exception ex) { throw new IllegalStateException(ex); }
                }).toList();
    }
}
