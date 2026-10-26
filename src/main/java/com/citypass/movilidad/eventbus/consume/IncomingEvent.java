package com.citypass.movilidad.eventbus.consume;

import com.citypass.movilidad.eventbus.EventMetadata;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Evento recibido del bus.
 *
 * @param data campos de negocio como JSON; un handler los convierte a su DTO con
 *             objectMapper.treeToValue(event.data(), MiEvento.class)
 */
public record IncomingEvent(String topic, EventMetadata metadata, JsonNode data) {
}
