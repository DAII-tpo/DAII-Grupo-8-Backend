package com.citypass.movilidad.controller;

import com.citypass.movilidad.config.GatewayProperties;
import com.citypass.movilidad.service.InboundEventService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Receptor del webhook del gateway del Grupo 1 (CU-INT-01).
 * La URL registrada en la suscripción lleva ?token=...: el dispatcher no firma las
 * entregas, así que el secreto en la URL es lo que separa al gateway de cualquiera.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@Tag(name = "Integración CityPass+", description = "Eventos de otros módulos recibidos por webhook")
public class CitypassWebhookController {

    private final InboundEventService inboundEventService;
    private final GatewayProperties properties;

    public CitypassWebhookController(InboundEventService inboundEventService, GatewayProperties properties) {
        this.inboundEventService = inboundEventService;
        this.properties = properties;
    }

    @PostMapping("/citypass")
    @Operation(summary = "Recibe un evento del gateway",
            description = "204 si se procesó o ya se había procesado; 401 si el token no coincide; "
                    + "400 si el evento no trae los datos mínimos (queda en la DLQ del gateway).")
    public ResponseEntity<Void> receive(@RequestParam(name = "token", required = false) String token,
                                        @RequestBody JsonNode envelope) {
        if (!validToken(token)) {
            return ResponseEntity.status(401).build();
        }
        try {
            inboundEventService.handle(envelope);
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException malformed) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.noContent().build();
    }

    private boolean validToken(String token) {
        String expected = properties.webhookToken();
        if (expected == null || expected.isBlank() || token == null) {
            return false;
        }
        // Comparación en tiempo constante: no da pistas sobre cuántos caracteres coinciden.
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }
}
