package com.citypass.movilidad.eventbus.publish;

import com.citypass.movilidad.eventbus.EventBusProperties;
import com.citypass.movilidad.model.EventOutbox;
import com.citypass.movilidad.model.enums.EventOutboxStatus;
import com.citypass.movilidad.repository.EventOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Publica en el bus los eventos pendientes del outbox.
 * <p>
 * Cada evento se procesa en su propia transacción, con la fila bloqueada mientras dura el POST: si el
 * proceso se cae a mitad de camino, el evento sigue PENDING y se vuelve a publicar. Es decir, la entrega es
 * at-least-once, igual que en el resto del bus; los consumidores deduplican por eventId/payloadHash.
 */
public class OutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    private static final int MAX_ERROR_LENGTH = 1000;

    private final EventOutboxRepository outboxRepository;
    private final EventGatewayClient gatewayClient;
    private final TransactionTemplate transactionTemplate;
    private final EventBusProperties.Publisher settings;
    private final Clock clock;

    public OutboxRelay(EventOutboxRepository outboxRepository, EventGatewayClient gatewayClient,
                       TransactionTemplate transactionTemplate, EventBusProperties properties, Clock clock) {
        this.outboxRepository = outboxRepository;
        this.gatewayClient = gatewayClient;
        this.transactionTemplate = transactionTemplate;
        this.settings = properties.publisher();
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${citypass.eventbus.publisher.poll-interval:5s}")
    public void publishPending() {
        for (int i = 0; i < settings.batchSize(); i++) {
            if (!Boolean.TRUE.equals(transactionTemplate.execute(status -> publishNext()))) {
                return;
            }
        }
    }

    /** Publica el próximo pendiente. false si no había ninguno listo. */
    boolean publishNext() {
        Optional<EventOutbox> next = outboxRepository.findNextPendingForUpdate(clock.instant());
        if (next.isEmpty()) {
            return false;
        }
        EventOutbox event = next.get();
        try {
            String busEventId = gatewayClient.publish(event.getEventType(), event.getPayload());
            event.setStatus(EventOutboxStatus.PUBLISHED);
            event.setPublishedAt(clock.instant());
            event.setBusEventId(busEventId);
            event.setLastError(null);
            LOG.info("Evento publicado en el bus: outboxId={}, eventType={}, eventId={}",
                    event.getId(), event.getEventType(), busEventId);
        } catch (EventGatewayException ex) {
            registerFailure(event, ex.getMessage(), ex.retryable());
        } catch (RuntimeException ex) {
            // Un error inesperado se reintenta: si es permanente, termina en FAILED al agotar los intentos.
            registerFailure(event, ex.toString(), true);
        }
        return true;
    }

    private void registerFailure(EventOutbox event, String error, boolean retryable) {
        int attempts = event.getAttempts() + 1;
        event.setAttempts(attempts);
        event.setLastError(truncate(error));

        if (!retryable || attempts >= settings.maxAttempts()) {
            event.setStatus(EventOutboxStatus.FAILED);
            LOG.error("Evento descartado del outbox tras {} intento(s): outboxId={}, eventType={}, error={}",
                    attempts, event.getId(), event.getEventType(), error);
            return;
        }
        Instant nextAttempt = clock.instant().plus(backoff(attempts));
        event.setNextAttemptAt(nextAttempt);
        LOG.warn("No se pudo publicar el evento (intento {}/{}), se reintenta a las {}: outboxId={}, error={}",
                attempts, settings.maxAttempts(), nextAttempt, event.getId(), error);
    }

    /** initialBackoff * 2^(intento - 1), con tope en maxBackoff. */
    Duration backoff(int attempts) {
        Duration delay = settings.initialBackoff();
        for (int i = 1; i < attempts && delay.compareTo(settings.maxBackoff()) < 0; i++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(settings.maxBackoff()) > 0 ? settings.maxBackoff() : delay;
    }

    private static String truncate(String text) {
        if (text == null || text.length() <= MAX_ERROR_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_ERROR_LENGTH);
    }
}
