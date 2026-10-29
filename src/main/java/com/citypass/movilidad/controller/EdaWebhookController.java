package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.event.EdaWebhookEnvelope;
import com.citypass.movilidad.dto.event.ReclamoActualizadoData;
import com.citypass.movilidad.dto.event.ReclamoCreadoData;
import com.citypass.movilidad.model.EventInbox;
import com.citypass.movilidad.model.enums.EventInboxStatus;
import com.citypass.movilidad.repository.EventInboxRepository;
import com.citypass.movilidad.service.CicloviaObstructionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.Map;

/**
 * Endpoint receptor de webhooks despachados por el EDA Gateway / Webhook Dispatcher de Grupo 1.
 * Permite recibir eventos vía HTTP sin depender de una conexión persistente a Kafka.
 */
@RestController
@RequestMapping("/api/v1/integrations/eda")
@Tag(name = "Integraciones EDA", description = "Endpoints de recepción de webhooks de la Arquitectura Orientada a Eventos")
public class EdaWebhookController {

    private static final Logger LOG = LoggerFactory.getLogger(EdaWebhookController.class);

    private final CicloviaObstructionService obstructionService;
    private final EventInboxRepository inboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public EdaWebhookController(CicloviaObstructionService obstructionService,
                                EventInboxRepository inboxRepository,
                                ObjectMapper objectMapper,
                                Clock clock) {
        this.obstructionService = obstructionService;
        this.inboxRepository = inboxRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @PostMapping("/events")
    @Operation(summary = "Recepción de eventos webhook desde EDA Gateway")
    @Transactional
    public ResponseEntity<Map<String, Object>> receiveEvent(@RequestBody EdaWebhookEnvelope envelope) {
        if (envelope == null || envelope.metadata() == null || envelope.metadata().eventType() == null) {
            LOG.warn("Webhook EDA recibido con sobre inválido o sin metadata");
            return ResponseEntity.badRequest().body(Map.of("status", "IGNORED", "reason", "Metadata requerida"));
        }

        String eventId = envelope.metadata().eventId();
        String eventType = envelope.metadata().eventType();

        // Idempotencia: si ya fue procesado en el inbox, responder OK sin reprocesar
        if (eventId != null && inboxRepository.existsByEventIdAndStatus(eventId, EventInboxStatus.PROCESSED)) {
            LOG.debug("Evento webhook ya procesado anteriormente: eventId={}", eventId);
            return ResponseEntity.ok(Map.of("status", "ALREADY_PROCESSED", "eventId", eventId));
        }

        LOG.info("Webhook recibido de EDA: eventType={}, eventId={}", eventType, eventId);

        try {
            switch (eventType) {
                case "com.citypass.reclamos.ReclamoCreado" -> {
                    ReclamoCreadoData data = objectMapper.treeToValue(envelope.data(), ReclamoCreadoData.class);
                    obstructionService.procesarReclamoCreado(data);
                }
                case "com.citypass.reclamos.ReclamoActualizado" -> {
                    ReclamoActualizadoData data = objectMapper.treeToValue(envelope.data(), ReclamoActualizadoData.class);
                    obstructionService.procesarReclamoActualizado(data);
                }
                default -> LOG.info("Evento {} ignorado (no requiere acción en Movilidad)", eventType);
            }

            // Registrar en inbox para deduplicación
            if (eventId != null) {
                inboxRepository.save(EventInbox.builder()
                        .eventId(eventId)
                        .eventType(eventType)
                        .source(envelope.metadata().source() != null ? envelope.metadata().source() : "eda-webhook")
                        .topic(eventType)
                        .partition(0)
                        .offset(0L)
                        .payloadHash(envelope.metadata().payloadHash())
                        .status(EventInboxStatus.PROCESSED)
                        .receivedAt(envelope.metadata().receivedAt() != null ? envelope.metadata().receivedAt() : clock.instant())
                        .processedAt(clock.instant())
                        .build());
            }

            return ResponseEntity.ok(Map.of("status", "PROCESSED", "eventType", eventType, "eventId", eventId != null ? eventId : ""));
        } catch (JsonProcessingException ex) {
            LOG.error("Error deserializando payload para evento {}", eventType, ex);
            return ResponseEntity.badRequest().body(Map.of("status", "ERROR", "message", ex.getMessage()));
        }
    }
}
