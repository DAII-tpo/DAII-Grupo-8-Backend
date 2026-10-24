package com.citypass.movilidad.eventbus.consume;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnvelopeMapperTest {

    private static final String TOPIC = "com.citypass.test.EventoDePrueba";

    private final EventEnvelopeMapper mapper = new EventEnvelopeMapper(new ObjectMapper());

    @Test
    void separaLaMetadataDelGatewayDeLosDatosDeNegocio() {
        GenericRecord envelope = AvroEvents.envelope("e-1", TOPIC, false);

        IncomingEvent event = mapper.map(new ConsumerRecord<>(TOPIC, 0, 42L, "clave", envelope));

        assertThat(event.topic()).isEqualTo(TOPIC);
        assertThat(event.metadata().eventId()).isEqualTo("e-1");
        assertThat(event.metadata().eventType()).isEqualTo(TOPIC);
        assertThat(event.metadata().source()).isEqualTo("grupo5");
        assertThat(event.metadata().schemaId()).isEqualTo(7);
        assertThat(event.metadata().receivedAt()).isEqualTo(Instant.ofEpochMilli(1786547143000L));
        assertThat(event.data().get("estacionId").asText()).isEqualTo("est-003");
        assertThat(event.data().get("cantidad").asInt()).isEqualTo(4);
        assertThat(event.data().get("comentario").isNull()).isTrue();
    }

    @Test
    void aceptaReceivedAtComoInstant() {
        GenericRecord envelope = AvroEvents.envelope("e-1", TOPIC, false);
        ((GenericRecord) envelope.get("metadata")).put("receivedAt", Instant.ofEpochMilli(5L));

        IncomingEvent event = mapper.map(new ConsumerRecord<>(TOPIC, 0, 0L, null, envelope));

        assertThat(event.metadata().receivedAt()).isEqualTo(Instant.ofEpochMilli(5L));
    }

    @Test
    void rechazaMensajesQueNoSonEventosDelBus() {
        ConsumerRecord<String, Object> texto = new ConsumerRecord<>(TOPIC, 0, 0L, null, "texto");
        ConsumerRecord<String, Object> vacio = new ConsumerRecord<>(TOPIC, 0, 0L, null, null);
        ConsumerRecord<String, Object> sinEventId =
                new ConsumerRecord<>(TOPIC, 0, 0L, null, AvroEvents.envelope("", TOPIC, false));

        assertThatThrownBy(() -> mapper.map(texto)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> mapper.map(vacio)).isInstanceOf(InvalidEventException.class);
        assertThatThrownBy(() -> mapper.map(sinEventId)).isInstanceOf(InvalidEventException.class);
    }
}
