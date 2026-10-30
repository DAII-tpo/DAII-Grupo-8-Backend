package com.citypass.movilidad.eventbus.consume;

import com.citypass.movilidad.dto.event.ReclamoActualizadoData;
import com.citypass.movilidad.service.CicloviaObstructionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Consumidor Kafka del evento com.citypass.reclamos.ReclamoActualizado (Grupo 5 - Reclamos).
 * Cuando el estado pasa a RESUELTO, se normaliza la ciclovía y las estaciones afectadas.
 */
@Component
public class ReclamoActualizadoHandler implements EventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ReclamoActualizadoHandler.class);
    private static final String TOPIC = "com.citypass.reclamos.ReclamoActualizado";

    private final CicloviaObstructionService obstructionService;
    private final ObjectMapper objectMapper;

    public ReclamoActualizadoHandler(CicloviaObstructionService obstructionService, ObjectMapper objectMapper) {
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
            ReclamoActualizadoData data = objectMapper.treeToValue(event.data(), ReclamoActualizadoData.class);
            obstructionService.procesarReclamoActualizado(data);
        } catch (JsonProcessingException ex) {
            throw new InvalidEventException("Error al deserializar payload de " + TOPIC, ex);
        }
    }
}

