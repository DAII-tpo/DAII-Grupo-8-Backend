package com.citypass.movilidad.service;

import com.citypass.movilidad.client.gateway.GatewayEventClient;
import com.citypass.movilidad.client.gateway.GatewayRejectedException;
import com.citypass.movilidad.client.gateway.GatewayUnavailableException;
import com.citypass.movilidad.config.GatewayProperties;
import com.citypass.movilidad.model.EventOutbox;
import com.citypass.movilidad.model.enums.EventOutboxStatus;
import com.citypass.movilidad.repository.EventOutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Vacía el outbox contra el gateway del Grupo 1.
 *
 * Un evento rechazado por contrato pasa a FAILED en el acto (reintentarlo daría lo
 * mismo). Uno que falló por red se reintenta en la próxima pasada hasta maxAttempts.
 * Si el gateway está caído se corta el lote: no tiene sentido seguir golpeándolo.
 */
@Component
@Slf4j
public class OutboxDispatcher {

    private final EventOutboxRepository outboxRepository;
    private final GatewayEventClient gatewayClient;
    private final GatewayProperties properties;

    public OutboxDispatcher(EventOutboxRepository outboxRepository,
                            GatewayEventClient gatewayClient,
                            GatewayProperties properties) {
        this.outboxRepository = outboxRepository;
        this.gatewayClient = gatewayClient;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${movilidad.gateway.dispatch-interval-millis:5000}")
    public void scheduledDispatch() {
        if (properties.enabled()) {
            dispatch();
        }
    }

    /** Publica los pendientes. Devuelve cuántos salieron. */
    public int dispatch() {
        List<EventOutbox> pending = outboxRepository.findTop50ByStatusOrderByCreatedAtAsc(EventOutboxStatus.PENDING);
        int published = 0;

        for (EventOutbox event : pending) {
            try {
                gatewayClient.publish(event.getEventType(), event.getPayload());
                event.setStatus(EventOutboxStatus.PUBLISHED);
                event.setPublishedAt(Instant.now());
                event.setLastError(null);
                outboxRepository.save(event);
                published++;
            } catch (GatewayRejectedException rejected) {
                log.error("El gateway rechazó {} {}: {}", event.getEventType(), event.getId(), rejected.getMessage());
                markFailedAttempt(event, rejected.getMessage(), true);
            } catch (GatewayUnavailableException unavailable) {
                log.warn("Gateway no disponible, se reintenta en la próxima pasada: {}", unavailable.getMessage());
                markFailedAttempt(event, unavailable.getMessage(), false);
                break;
            }
        }

        return published;
    }

    private void markFailedAttempt(EventOutbox event, String error, boolean permanent) {
        event.setAttempts(event.getAttempts() + 1);
        event.setLastError(error == null ? null : error.substring(0, Math.min(error.length(), 1000)));
        if (permanent || event.getAttempts() >= properties.maxAttempts()) {
            event.setStatus(EventOutboxStatus.FAILED);
        }
        outboxRepository.save(event);
    }
}
