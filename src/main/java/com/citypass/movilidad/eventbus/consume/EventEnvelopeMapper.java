package com.citypass.movilidad.eventbus.consume;

import com.citypass.movilidad.eventbus.EventMetadata;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.consumer.ConsumerRecord;

import java.time.Instant;

/** Convierte el record Avro del bus ({metadata, data}) en un IncomingEvent. */
public class EventEnvelopeMapper {

    private final ObjectMapper objectMapper;

    public EventEnvelopeMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public IncomingEvent map(ConsumerRecord<?, ?> consumerRecord) {
        if (!(consumerRecord.value() instanceof GenericRecord envelope)) {
            throw new InvalidEventException("El mensaje no es un record Avro: " + describe(consumerRecord));
        }
        if (!(field(envelope, "metadata") instanceof GenericRecord metadata)) {
            throw new InvalidEventException("El evento no tiene metadata: " + describe(consumerRecord));
        }

        EventMetadata eventMetadata = new EventMetadata(
                text(metadata, "eventId"),
                text(metadata, "eventType"),
                instant(field(metadata, "receivedAt")),
                text(metadata, "source"),
                text(metadata, "tokenId"),
                field(metadata, "schemaId") instanceof Number schemaId ? schemaId.intValue() : null,
                text(metadata, "payloadHash"),
                text(metadata, "gatewayVersion"),
                text(metadata, "instanceId"));

        if (eventMetadata.eventId() == null || eventMetadata.eventId().isBlank()) {
            throw new InvalidEventException("El evento no tiene eventId: " + describe(consumerRecord));
        }
        return new IncomingEvent(consumerRecord.topic(), eventMetadata, toJson(field(envelope, "data")));
    }

    private JsonNode toJson(Object data) {
        try {
            // GenericData arma el JSON respetando el schema (uniones, enums, mapas y records anidados).
            return objectMapper.readTree(GenericData.get().toString(data));
        } catch (JsonProcessingException ex) {
            throw new InvalidEventException("Los datos del evento no se pueden convertir a JSON", ex);
        }
    }

    private static Object field(GenericRecord avroRecord, String name) {
        return avroRecord.hasField(name) ? avroRecord.get(name) : null;
    }

    private static String text(GenericRecord avroRecord, String name) {
        Object value = field(avroRecord, name);
        return value == null ? null : value.toString();
    }

    // timestamp-millis llega como long, o como Instant si el deserializador usa conversiones lógicas.
    private static Instant instant(Object value) {
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof Number millis) {
            return Instant.ofEpochMilli(millis.longValue());
        }
        throw new InvalidEventException("metadata.receivedAt inválido: " + value);
    }

    private static String describe(ConsumerRecord<?, ?> consumerRecord) {
        return consumerRecord.topic() + "-" + consumerRecord.partition() + "@" + consumerRecord.offset();
    }
}
