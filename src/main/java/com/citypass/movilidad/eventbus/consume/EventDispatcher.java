package com.citypass.movilidad.eventbus.consume;

import com.citypass.movilidad.model.EventInbox;
import com.citypass.movilidad.model.enums.EventInboxStatus;
import com.citypass.movilidad.repository.EventInboxRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Entrega cada evento recibido a su handler, una sola vez.
 * <p>
 * La entrega del bus es at-least-once: el mismo evento puede llegar dos veces (por ejemplo, si el proceso se
 * cae entre procesar y confirmar el offset). El inbox registra el eventId en la misma transacción que los
 * cambios del handler, así que un evento ya procesado se descarta y uno a medio procesar se rehace entero.
 */
public class EventDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(EventDispatcher.class);

    private final Map<String, EventHandler> handlersByTopic;
    private final EventEnvelopeMapper envelopeMapper;
    private final EventInboxRepository inboxRepository;
    private final Clock clock;

    public EventDispatcher(List<EventHandler> handlers, EventEnvelopeMapper envelopeMapper,
                           EventInboxRepository inboxRepository, Clock clock) {
        this.handlersByTopic = indexByTopic(handlers);
        this.envelopeMapper = envelopeMapper;
        this.inboxRepository = inboxRepository;
        this.clock = clock;
    }

    /** Tópicos a los que hay que suscribirse. */
    public Collection<String> topics() {
        return handlersByTopic.keySet();
    }

    @Transactional
    public void dispatch(ConsumerRecord<?, ?> consumerRecord) {
        IncomingEvent event = envelopeMapper.map(consumerRecord);
        String eventId = event.metadata().eventId();

        if (inboxRepository.existsByEventIdAndStatus(eventId, EventInboxStatus.PROCESSED)) {
            LOG.debug("Evento duplicado descartado: eventId={}, topic={}", eventId, event.topic());
            return;
        }
        EventHandler handler = handlersByTopic.get(consumerRecord.topic());
        if (handler == null) {
            throw new InvalidEventException("No hay handler para el tópico " + consumerRecord.topic());
        }

        handler.handle(event);

        // Si había quedado FAILED en una entrega anterior, se pisa: ahora sí se procesó.
        inboxRepository.save(EventInbox.builder()
                .eventId(eventId)
                .eventType(event.metadata().eventType())
                .source(event.metadata().source())
                .topic(consumerRecord.topic())
                .partition(consumerRecord.partition())
                .offset(consumerRecord.offset())
                .payloadHash(event.metadata().payloadHash())
                .status(EventInboxStatus.PROCESSED)
                .receivedAt(event.metadata().receivedAt())
                .processedAt(clock.instant())
                .build());
        LOG.info("Evento procesado: eventId={}, topic={}, source={}",
                eventId, event.topic(), event.metadata().source());
    }

    private static Map<String, EventHandler> indexByTopic(List<EventHandler> handlers) {
        // Dos handlers para el mismo tópico es un error de configuración: no hay forma correcta de elegir uno.
        return handlers.stream().collect(Collectors.toUnmodifiableMap(EventHandler::topic, Function.identity(),
                (first, second) -> {
                    throw new IllegalStateException("Hay más de un EventHandler para el tópico " + first.topic()
                            + ": " + first.getClass().getName() + " y " + second.getClass().getName());
                }));
    }
}
