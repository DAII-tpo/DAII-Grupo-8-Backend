package com.citypass.movilidad.eventbus.consume;

import com.citypass.movilidad.dto.event.ReclamoReclasificadoData;
import com.citypass.movilidad.service.CicloviaObstructionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Consumidor Kafka de com.citypass.reclamos.ReclamoReclasificado. */
@Component
public class ReclamoReclasificadoHandler implements EventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ReclamoReclasificadoHandler.class);
    private static final String TOPIC = "com.citypass.reclamos.ReclamoReclasificado";

    private final CicloviaObstructionService obstructionService;
    private final ObjectMapper objectMapper;

    public ReclamoReclasificadoHandler(CicloviaObstructionService obstructionService, ObjectMapper objectMapper) {
        this.obstructionService = obstructionService;
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
            ReclamoReclasificadoData data = objectMapper.treeToValue(event.data(), ReclamoReclasificadoData.class);
            obstructionService.procesarReclamoReclasificado(data);
        } catch (JsonProcessingException ex) {
            throw new InvalidEventException("Error al deserializar payload de " + TOPIC, ex);
        }
    }
}
