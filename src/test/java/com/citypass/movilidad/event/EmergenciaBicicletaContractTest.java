package com.citypass.movilidad.event;

import com.citypass.movilidad.model.enums.EmergencyPriority;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El JSON que publica el backend tiene que cumplir el contrato registrado en el bus; si no, el gateway lo
 * rechaza con 400 y el evento queda FAILED en el outbox. Este test arma el schema Avro desde el mismo archivo
 * que se registra y convierte el payload real, como hace el gateway.
 */
class EmergenciaBicicletaContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static Schema schema;

    @BeforeAll
    static void cargarContrato() throws IOException {
        try (InputStream definition = EmergenciaBicicletaContractTest.class
                .getResourceAsStream("/eventbus/event-types/EmergenciaBicicleta.json")) {
            ObjectNode json = (ObjectNode) MAPPER.readTree(definition);
            assertThat(json.get("name").asText()).isEqualTo("EmergenciaBicicleta");
            json.put("type", "record");
            json.put("namespace", "com.citypass.movilidad");
            schema = new Schema.Parser().parse(json.toString());
        }
    }

    @Test
    void unEventoConCuestionarioCumpleElContrato() {
        assertCumpleElContrato(evento(new EmergenciaBicicletaEvent.Triage(
                true, false, false, false, EmergencyPriority.P2), 12.5, "sub-123"));
    }

    @Test
    void unEventoSinRespuestasNiPrecisionCumpleElContrato() {
        assertCumpleElContrato(evento(new EmergenciaBicicletaEvent.Triage(
                false, null, null, null, EmergencyPriority.P1), null, null));
    }

    @Test
    void todasLasPrioridadesExistenEnElContrato() {
        Schema prioridad = schema.getField("triage").schema().getField("prioridadSugerida").schema();
        for (EmergencyPriority priority : EmergencyPriority.values()) {
            assertThat(prioridad.getEnumSymbols()).contains(priority.name());
        }
    }

    private static void assertCumpleElContrato(EmergenciaBicicletaEvent event) {
        JsonNode payload = MAPPER.valueToTree(event);
        List<String> errores = new ArrayList<>();
        Object record = convertir(schema, payload, "data", errores);
        assertThat(errores).isEmpty();
        assertThat(GenericData.get().validate(schema, record)).isTrue();
    }

    /** Convierte JSON plano a Avro siguiendo el schema (uniones con null sin envolver, como acepta el gateway). */
    private static Object convertir(Schema schema, JsonNode value, String path, List<String> errores) {
        switch (schema.getType()) {
            case RECORD -> {
                if (value == null || !value.isObject()) {
                    errores.add(path + ": se esperaba un objeto");
                    return null;
                }
                GenericRecord record = new GenericData.Record(schema);
                value.fieldNames().forEachRemaining(name -> {
                    if (schema.getField(name) == null) {
                        errores.add(path + "." + name + ": no existe en el contrato");
                    }
                });
                for (Schema.Field field : schema.getFields()) {
                    record.put(field.name(), convertir(field.schema(), value.get(field.name()),
                            path + "." + field.name(), errores));
                }
                return record;
            }
            case UNION -> {
                if (value == null || value.isNull()) {
                    return null;
                }
                Schema noNulo = schema.getTypes().stream()
                        .filter(type -> type.getType() != Schema.Type.NULL).findFirst().orElseThrow();
                return convertir(noNulo, value, path, errores);
            }
            case ENUM -> {
                return new GenericData.EnumSymbol(schema, value.asText());
            }
            case STRING -> {
                return textual(value, path, errores) ? value.asText() : null;
            }
            case LONG -> {
                return numero(value, path, errores) ? value.asLong() : null;
            }
            case DOUBLE -> {
                return numero(value, path, errores) ? value.asDouble() : null;
            }
            case BOOLEAN -> {
                if (value == null || !value.isBoolean()) {
                    errores.add(path + ": se esperaba un booleano");
                    return null;
                }
                return value.asBoolean();
            }
            default -> {
                errores.add(path + ": tipo no contemplado en el test " + schema.getType());
                return null;
            }
        }
    }

    private static boolean textual(JsonNode value, String path, List<String> errores) {
        if (value == null || !value.isTextual()) {
            errores.add(path + ": se esperaba un texto");
            return false;
        }
        return true;
    }

    private static boolean numero(JsonNode value, String path, List<String> errores) {
        if (value == null || !value.isNumber()) {
            errores.add(path + ": se esperaba un número");
            return false;
        }
        return true;
    }

    private static EmergenciaBicicletaEvent evento(EmergenciaBicicletaEvent.Triage triage, Double precision,
                                                   String actorSub) {
        return new EmergenciaBicicletaEvent("EMG-20261029-0091", actorSub, 1793300000000L,
                new EmergenciaBicicletaEvent.Ubicacion(-34.6037, -58.3816, precision),
                triage,
                new EmergenciaBicicletaEvent.Viaje(450021L, 1793299280000L, 10L, "11 de Septiembre", 5L, "BIKE-03520"),
                new EmergenciaBicicletaEvent.Usuario(1L, "Ana", "Pérez", "ana.perez@example.com"));
    }
}
