package com.citypass.movilidad.eventbus;

import com.citypass.movilidad.eventbus.consume.EventHandler;
import com.citypass.movilidad.eventbus.consume.IncomingEvent;
import com.citypass.movilidad.eventbus.publish.EventGatewayClient;
import com.citypass.movilidad.eventbus.publish.EventGatewayException;
import com.citypass.movilidad.eventbus.publish.EventPublisher;
import com.citypass.movilidad.eventbus.publish.OutboxRelay;
import com.citypass.movilidad.model.EventInbox;
import com.citypass.movilidad.model.EventOutbox;
import com.citypass.movilidad.model.enums.AggregateType;
import com.citypass.movilidad.model.enums.EventInboxStatus;
import com.citypass.movilidad.model.enums.EventOutboxStatus;
import com.citypass.movilidad.repository.EventInboxRepository;
import com.citypass.movilidad.repository.EventOutboxRepository;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Integración con el bus contra un Kafka embebido y MySQL real. El broker no exige SASL y el registry de
 * schemas es el mock en memoria de Confluent; lo demás (deserialización Avro, commit después de procesar,
 * deduplicación por inbox, outbox con lock y reintentos) es el código de producción.
 */
@Testcontainers
@SpringBootTest(properties = {
        "movilidad.analytics.affected-stations.cron=-",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "citypass.eventbus.consumer.enabled=true",
        "citypass.eventbus.consumer.security-protocol=PLAINTEXT",
        "citypass.eventbus.consumer.schema-registry-url=" + EventBusIntegrationTest.REGISTRY,
        "citypass.eventbus.consumer.retry-interval=50ms",
        "citypass.eventbus.publisher.enabled=true",
        "citypass.eventbus.publisher.register-event-types=false",
        // El relay se ejecuta a mano desde el test.
        "citypass.eventbus.publisher.poll-interval=1h",
        "citypass.eventbus.client-id=grupo3",
        "citypass.eventbus.client-secret=secreto-de-prueba"
})
@EmbeddedKafka(partitions = 1, topics = {EventBusIntegrationTest.TOPIC})
class EventBusIntegrationTest {

    static final String TOPIC = "com.citypass.emergencias.EmergenciaReportada";
    static final String REGISTRY = "mock://event-bus-integration";

    private static final Schema METADATA = SchemaBuilder.record("EventMetadata").namespace("com.citypass.gateway")
            .fields()
            .requiredString("eventId")
            .requiredString("eventType")
            .name("receivedAt").type(LogicalTypes.timestampMillis().addToSchema(Schema.create(Schema.Type.LONG)))
            .noDefault()
            .requiredString("source")
            .requiredString("tokenId")
            .requiredInt("schemaId")
            .requiredString("payloadHash")
            .requiredString("gatewayVersion")
            .requiredString("instanceId")
            .endRecord();

    private static final Schema DATA = SchemaBuilder.record("EmergenciaReportadaData")
            .namespace("com.citypass.emergencias").fields()
            .requiredString("estacionId")
            .requiredBoolean("falla")
            .endRecord();

    private static final Schema ENVELOPE = SchemaBuilder.record("EmergenciaReportada")
            .namespace("com.citypass.emergencias").fields()
            .name("metadata").type(METADATA).noDefault()
            .name("data").type(DATA).noDefault()
            .endRecord();

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("movilidad")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configuracionDinamica(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    private static KafkaProducer<String, Object> producer;

    @Autowired
    private RecordingHandler handler;

    @Autowired
    private EventInboxRepository inboxRepository;

    @Autowired
    private EventOutboxRepository outboxRepository;

    @Autowired
    private EventPublisher eventPublisher;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private EventGatewayClient gatewayClient;

    @AfterAll
    static void cerrarProductor() {
        if (producer != null) {
            producer.close();
        }
    }

    @BeforeEach
    void limpiar() {
        handler.received.clear();
        outboxRepository.deleteAll();
    }

    @Test
    void consumeUnEventoDeOtroGrupoYDescartaLaReentrega() {
        String eventId = UUID.randomUUID().toString();

        send(envelope(eventId, false));
        send(envelope(eventId, false));
        String siguiente = UUID.randomUUID().toString();
        send(envelope(siguiente, false));

        await().atMost(Duration.ofSeconds(30)).until(() -> inboxRepository.existsById(siguiente));
        assertThat(handler.received).extracting(event -> event.metadata().eventId())
                .containsExactly(eventId, siguiente);

        IncomingEvent recibido = handler.received.get(0);
        assertThat(recibido.metadata().source()).isEqualTo("grupo5");
        assertThat(recibido.data().get("estacionId").asText()).isEqualTo("est-003");

        EventInbox inbox = inboxRepository.findById(eventId).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(EventInboxStatus.PROCESSED);
        assertThat(inbox.getTopic()).isEqualTo(TOPIC);
        assertThat(inbox.getPayload()).isNull();
    }

    @Test
    void unEventoQueFallaQuedaRegistradoComoFailedYNoFrenaAlSiguiente() {
        String fallido = UUID.randomUUID().toString();
        String siguiente = UUID.randomUUID().toString();

        send(envelope(fallido, true));
        send(envelope(siguiente, false));

        await().atMost(Duration.ofSeconds(30)).until(() -> inboxRepository.existsById(siguiente));
        EventInbox inbox = inboxRepository.findById(fallido).orElseThrow();
        assertThat(inbox.getStatus()).isEqualTo(EventInboxStatus.FAILED);
        assertThat(inbox.getLastError()).contains("Falla simulada");
        assertThat(inbox.getPayload()).contains("est-003");
    }

    @Test
    void elOutboxPublicaLosEventosConfirmadosConSuFqn() {
        when(gatewayClient.publish(eq("com.citypass.movilidad.ViajeFinalizado"), any())).thenReturn("e-123");

        transactionTemplate.executeWithoutResult(status -> eventPublisher.publish(
                "ViajeFinalizado", AggregateType.TRIP, 7L, Map.of("tripId", 7, "duracionMin", 35)));
        outboxRelay.publishPending();

        EventOutbox publicado = outboxRepository.findAll().get(0);
        assertThat(publicado.getStatus()).isEqualTo(EventOutboxStatus.PUBLISHED);
        assertThat(publicado.getBusEventId()).isEqualTo("e-123");
        assertThat(publicado.getPublishedAt()).isNotNull();
    }

    @Test
    void elOutboxReprogramaUnEventoQueNoSePudoPublicarYNoLoReintentaAntesDeTiempo() {
        when(gatewayClient.publish(any(), any())).thenThrow(new EventGatewayException("HTTP 503", 503, true, null));

        transactionTemplate.executeWithoutResult(status -> eventPublisher.publish(
                "ViajeFinalizado", AggregateType.TRIP, 7L, Map.of("tripId", 7)));
        outboxRelay.publishPending();
        outboxRelay.publishPending();

        EventOutbox pendiente = outboxRepository.findAll().get(0);
        assertThat(pendiente.getStatus()).isEqualTo(EventOutboxStatus.PENDING);
        assertThat(pendiente.getAttempts()).isEqualTo(1);
        assertThat(pendiente.getNextAttemptAt()).isAfter(Instant.now());
        assertThat(pendiente.getLastError()).isEqualTo("HTTP 503");
    }

    @Test
    void publicarExigeLaTransaccionDelCambioDeDominio() {
        Map<String, Integer> data = Map.of("tripId", 7);

        assertThatThrownBy(() -> eventPublisher.publish("ViajeFinalizado", AggregateType.TRIP, 7L, data))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outboxRepository.count()).isZero();
    }

    // Hace de gateway: publica en Kafka con el formato Confluent, igual que el bus.
    private void send(GenericRecord envelope) {
        if (producer == null) {
            producer = new KafkaProducer<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, System.getProperty("spring.embedded.kafka.brokers"),
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class,
                    "schema.registry.url", REGISTRY));
        }
        producer.send(new ProducerRecord<>(TOPIC, "clave", envelope));
        producer.flush();
    }

    private static GenericRecord envelope(String eventId, boolean falla) {
        GenericRecord metadata = new GenericData.Record(METADATA);
        metadata.put("eventId", eventId);
        metadata.put("eventType", TOPIC);
        metadata.put("receivedAt", 1786547143000L);
        metadata.put("source", "grupo5");
        metadata.put("tokenId", "af2480cc-487f-474f-ac06-f396ad3f403d");
        metadata.put("schemaId", 1);
        metadata.put("payloadHash", "ae9c4096bd582ac95a75c115b5b87ec26bd8df44391a11e7ea6de5243a4cd801");
        metadata.put("gatewayVersion", "0.0.1-SNAPSHOT");
        metadata.put("instanceId", "gw-test");

        GenericRecord data = new GenericData.Record(DATA);
        data.put("estacionId", "est-003");
        data.put("falla", falla);

        GenericRecord envelope = new GenericData.Record(ENVELOPE);
        envelope.put("metadata", metadata);
        envelope.put("data", data);
        return envelope;
    }

    @TestConfiguration
    static class HandlerDePrueba {

        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }
    }

    static class RecordingHandler implements EventHandler {

        final List<IncomingEvent> received = new CopyOnWriteArrayList<>();

        @Override
        public String topic() {
            return TOPIC;
        }

        @Override
        public void handle(IncomingEvent event) {
            if (event.data().get("falla").asBoolean()) {
                throw new IllegalStateException("Falla simulada del handler");
            }
            received.add(event);
        }
    }
}
