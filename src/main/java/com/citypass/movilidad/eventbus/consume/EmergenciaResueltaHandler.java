package com.citypass.movilidad.eventbus.consume;

import com.citypass.movilidad.config.EmergencyProperties;
import com.citypass.movilidad.dto.event.EmergenciaActualizadaData;
import com.citypass.movilidad.service.EmergencyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Consumidor de com.citypass.emergencias.EmergenciaActualizada (Emergencias): cuando resuelven un pedido de ayuda
 * nuestro (estadoNuevo RESUELTA), se cierra el viaje sin cargo. El resto de las emergencias y de los estados se
 * ignora. El tópico es configurable con TOPIC_EMERGENCIA_ESTADO_ACTUALIZADO.
 * <p>
 * El mismo tópico lo consume CU-INT-01 para los incidentes urbanos; EventDispatcher entrega a los dos.
 */
@Component
public class EmergenciaResueltaHandler implements EventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(EmergenciaResueltaHandler.class);

    static final String RESOLVED_STATE = "RESUELTA";

    private final EmergencyService emergencyService;
    private final ObjectMapper objectMapper;
    private final String topic;

    public EmergenciaResueltaHandler(EmergencyService emergencyService, ObjectMapper objectMapper,
                                     EmergencyProperties properties) {
        this.emergencyService = emergencyService;
        this.objectMapper = objectMapper;
        this.topic = properties.resolutionTopic();
    }

    @Override
    public String topic() {
        return topic;
    }

    @Override
    public void handle(IncomingEvent event) {
        EmergenciaActualizadaData data;
        try {
            data = objectMapper.treeToValue(event.data(), EmergenciaActualizadaData.class);
        } catch (JsonProcessingException ex) {
            throw new InvalidEventException("Error al deserializar payload de " + topic, ex);
        }
        if (data.estadoNuevo() == null || !RESOLVED_STATE.equals(data.estadoNuevo().trim().toUpperCase(Locale.ROOT))) {
            return;
        }
        Instant resolvedAt = firstInstant(data.resueltoAt(), data.updatedAt(), event.metadata().receivedAt());
        // El código EMG-… es nuestro: si no está en ninguno de los dos campos, la emergencia no es de Movilidad.
        boolean resolved = Stream.of(data.emergenciaId(), data.correlationId())
                .anyMatch(code -> emergencyService.resolveByEmergencias(code, resolvedAt));
        if (resolved) {
            LOG.info("Emergencia {} resuelta por Emergencias (eventId={})",
                    data.emergenciaId(), event.metadata().eventId());
        }
    }

    /** Primera fecha que se pueda leer; si ninguna, cuándo la recibió el bus. */
    private static Instant firstInstant(String resueltoAt, String updatedAt, Instant receivedAt) {
        for (String value : new String[]{resueltoAt, updatedAt}) {
            Instant parsed = parseInstant(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return receivedAt != null ? receivedAt : Instant.now();
    }

    // Emergencias no fija el formato: se aceptan ISO con zona, ISO sin zona (UTC) y epoch millis.
    static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException ignored) {
            // probar el siguiente formato
        }
        try {
            return LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // probar el siguiente formato
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(text));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
