package com.citypass.movilidad.eventbus.publish;

import com.citypass.movilidad.eventbus.EventBusProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;

/**
 * Registra en el bus los event types propios que todavía no existen.
 * <p>
 * Los contratos viven versionados en el repo, en classpath:eventbus/event-types/*.json, con el mismo
 * formato que acepta POST /api/v1/event-types: {"name": "...", "fields": [...]}. Un event type que ya existe
 * no se toca: cambiarle el schema es una decisión explícita (PUT en el gateway), no un efecto del deploy.
 */
public class EventTypeRegistrar {

    private static final Logger LOG = LoggerFactory.getLogger(EventTypeRegistrar.class);

    static final String LOCATION = "classpath*:eventbus/event-types/*.json";

    private final EventGatewayClient gatewayClient;
    private final ResourcePatternResolver resourceResolver;
    private final ObjectMapper objectMapper;
    private final EventBusProperties properties;

    public EventTypeRegistrar(EventGatewayClient gatewayClient, ResourcePatternResolver resourceResolver,
                              ObjectMapper objectMapper, EventBusProperties properties) {
        this.gatewayClient = gatewayClient;
        this.resourceResolver = resourceResolver;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** Un fallo acá no frena el arranque: los eventos quedan en el outbox y se reintentan. */
    @EventListener(ApplicationReadyEvent.class)
    public void registerEventTypes() {
        Resource[] definitions;
        try {
            definitions = resourceResolver.getResources(LOCATION);
        } catch (IOException ex) {
            LOG.error("No se pudieron leer las definiciones de event types en {}", LOCATION, ex);
            return;
        }
        for (Resource definition : definitions) {
            register(definition);
        }
    }

    private void register(Resource definition) {
        String fqn = null;
        try (InputStream content = definition.getInputStream()) {
            JsonNode json = objectMapper.readTree(content);
            String name = json.path("name").asText("");
            if (name.isBlank()) {
                LOG.error("La definición {} no tiene 'name'; se ignora", definition.getFilename());
                return;
            }
            fqn = properties.fqn(name);
            if (gatewayClient.eventTypeExists(fqn)) {
                LOG.debug("El event type {} ya está registrado", fqn);
                return;
            }
            gatewayClient.registerEventType(objectMapper.writeValueAsString(json));
            LOG.info("Event type registrado en el bus: {}", fqn);
        } catch (IOException ex) {
            LOG.error("La definición {} no es un JSON válido", definition.getFilename(), ex);
        } catch (EventGatewayException ex) {
            LOG.warn("No se pudo registrar el event type {}: {}", fqn, ex.getMessage());
        }
    }
}
