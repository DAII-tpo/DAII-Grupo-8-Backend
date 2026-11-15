package com.citypass.movilidad.dto.event;

import com.citypass.movilidad.eventbus.EventMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sobre estándar despachado por el Gateway o Webhook Dispatcher de EDA (Grupo 1).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EdaWebhookEnvelope(
        EventMetadata metadata,
        JsonNode data
) {
}
