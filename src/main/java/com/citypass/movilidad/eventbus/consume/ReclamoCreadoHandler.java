package com.citypass.movilidad.eventbus.consume;

import com.citypass.movilidad.dto.event.ReclamoCreadoData;
import com.citypass.movilidad.service.CicloviaObstructionService;
import com.citypass.movilidad.service.InboundEventService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Consumidor Kafka compartido por ciclovías y CU-INT-01 de la versión 3 del evento de Reclamos.
 * Suscripción automática a través del bus de eventos EDA.
 */
@Component
public class ReclamoCreadoHandler implements EventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ReclamoCreadoHandler.class);
    private static final String TOPIC = "com.citypass.reclamos.ReclamoCreado.v3";

    private final CicloviaObstructionService obstructionService;
    private final InboundEventService inboundEventService;
    private final ObjectMapper objectMapper;

    public ReclamoCreadoHandler(CicloviaObstructionService obstructionService,
                               InboundEventService inboundEventService,
                               ObjectMapper objectMapper) {
        this.obstructionService = obstructionService;
        this.inboundEventService = inboundEventService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String topic() {
        return TOPIC;
    }

    @Override
    public void handle(IncomingEvent event) {
        LOG.info("Consumiendo evento {} desde Kafka: eventId={}, source={}",
                TOPIC, event.metadata().eventId(), event.metadata().source());
        try {
            ReclamoCreadoData data = objectMapper.treeToValue(event.data(), ReclamoCreadoData.class);
            obstructionService.procesarReclamoCreado(data);
            inboundEventService.handle(TOPIC, event.data(), event.metadata().receivedAt());
        } catch (JsonProcessingException ex) {
            throw new InvalidEventException("Error al deserializar payload de " + TOPIC, ex);
        }
    }
}

