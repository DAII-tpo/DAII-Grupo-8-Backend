package com.citypass.movilidad.controller;

import com.citypass.movilidad.exception.ValidationException;
import com.citypass.movilidad.service.InboundEventService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Entrada manual para probar, sin Kafka, el mismo procesador que usan los consumidores EDA.
 * Solo existe con el perfil local: jamás se registra en un despliegue con perfil productivo.
 */
@RestController
@Profile("local")
@RequestMapping("/api/v1/dev/espacios/events")
@Tag(name = "Desarrollo local", description = "Simulación local de eventos de Espacios Públicos")
public class LocalEspaciosEventSimulatorController {

    private static final String ESPACIOS = "com.citypass.espacios.";

    private final InboundEventService inboundEvents;

    public LocalEspaciosEventSimulatorController(InboundEventService inboundEvents) {
        this.inboundEvents = inboundEvents;
    }

    @PostMapping("/reserva-confirmada")
    @Transactional
    @Operation(summary = "Simular ReservaConfirmada (solo perfil local)")
    public ResponseEntity<Void> reservationConfirmed(@RequestBody JsonNode payload) {
        process("ReservaConfirmada", payload);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reserva-cancelada")
    @Transactional
    @Operation(summary = "Simular ReservaCancelada (solo perfil local)")
    public ResponseEntity<Void> reservationCancelled(@RequestBody JsonNode payload) {
        process("ReservaCancelada", payload);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/evento-finalizado")
    @Transactional
    @Operation(summary = "Simular EventoFinalizado (solo perfil local)")
    public ResponseEntity<Void> eventFinished(@RequestBody JsonNode payload) {
        process("EventoFinalizado", payload);
        return ResponseEntity.noContent().build();
    }

    private void process(String eventName, JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            throw new ValidationException("El payload debe ser un objeto JSON");
        }
        try {
            inboundEvents.handle(ESPACIOS + eventName, payload);
        } catch (IllegalArgumentException exception) {
            throw new ValidationException(exception.getMessage());
        }
    }
}
