package com.citypass.movilidad.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Valida el JSON que publica el backend contra el contrato registrado en el bus, como hace el gateway: si no
 * cumple, el gateway lo rechaza con 400 y el evento queda FAILED en el outbox.
 */
final class AvroContract {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AvroContract() {
    }

    /** Schema Avro de data a partir del mismo archivo que se registra en el bus. */
    static Schema load(String eventName) {
        try (InputStream definition = AvroContract.class
                .getResourceAsStream("/eventbus/event-types/" + eventName + ".json")) {
            ObjectNode json = (ObjectNode) MAPPER.readTree(definition);
            if (!eventName.equals(json.get("name").asText())) {
                throw new IllegalStateException("El contrato no se llama " + eventName);
            }
            json.put("type", "record");
            json.put("namespace", "com.citypass.movilidad");
            return new Schema.Parser().parse(json.toString());
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    /** Errores de conversión del evento contra el schema; vacío si lo cumple. */
    static List<String> violations(Schema schema, Object event) {
        JsonNode payload = MAPPER.valueToTree(event);
        List<String> errors = new ArrayList<>();
        Object converted = convert(schema, payload, "data", errors);
        if (errors.isEmpty() && !GenericData.get().validate(schema, converted)) {
            errors.add("data: el record convertido no valida contra el schema");
        }
        return errors;
    }

    /** JSON plano a Avro siguiendo el schema (uniones con null sin envolver, como acepta el gateway). */
    private static Object convert(Schema schema, JsonNode value, String path, List<String> errors) {
        switch (schema.getType()) {
            case RECORD -> {
                if (value == null || !value.isObject()) {
                    errors.add(path + ": se esperaba un objeto");
                    return null;
                }
                GenericRecord avroRecord = new GenericData.Record(schema);
                value.fieldNames().forEachRemaining(name -> {
                    if (schema.getField(name) == null) {
                        errors.add(path + "." + name + ": no existe en el contrato");
                    }
                });
                for (Schema.Field field : schema.getFields()) {
                    avroRecord.put(field.name(), convert(field.schema(), value.get(field.name()),
                            path + "." + field.name(), errors));
                }
                return avroRecord;
            }
            case UNION -> {
                if (value == null || value.isNull()) {
                    return null;
                }
                Schema nonNull = schema.getTypes().stream()
                        .filter(type -> type.getType() != Schema.Type.NULL).findFirst().orElseThrow();
                return convert(nonNull, value, path, errors);
            }
            case ENUM -> {
                return new GenericData.EnumSymbol(schema, value.asText());
            }
            case STRING -> {
                return expect(value != null && value.isTextual(), "un texto", path, errors) ? value.asText() : null;
            }
            case LONG -> {
                return expect(value != null && value.isNumber(), "un número", path, errors) ? value.asLong() : null;
            }
            case DOUBLE -> {
                return expect(value != null && value.isNumber(), "un número", path, errors) ? value.asDouble() : null;
            }
            case BOOLEAN -> {
                return expect(value != null && value.isBoolean(), "un booleano", path, errors) ? value.asBoolean() : null;
            }
            default -> {
                errors.add(path + ": tipo no contemplado en el test " + schema.getType());
                return null;
            }
        }
    }

    private static boolean expect(boolean ok, String what, String path, List<String> errors) {
        if (!ok) {
            errors.add(path + ": se esperaba " + what);
        }
        return ok;
    }
}
