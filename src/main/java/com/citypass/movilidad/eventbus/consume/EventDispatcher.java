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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Entrega cada evento recibido a sus handlers, una sola vez.
 * <p>
 * La entrega del bus es at-least-once: el mismo evento puede llegar dos veces (por ejemplo, si el proceso se
 * cae entre procesar y confirmar el offset). El inbox registra el eventId en la misma transacción que los
 * cambios del handler, así que un evento ya procesado se descarta y uno a medio procesar se rehace entero.
 */
public class EventDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(EventDispatcher.class);

    private final Map<String, List<EventHandler>> handlersByTopic;
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
        List<EventHandler> handlers = handlersByTopic.get(consumerRecord.topic());
        if (handlers == null) {
            throw new InvalidEventException("No hay handler para el tópico " + consumerRecord.topic());
        }

        // Todos en la misma transacción: si uno falla, se deshace lo de los demás y el evento se reintenta entero.
        for (EventHandler handler : handlers) {
            handler.handle(event);
        }

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

    /**
     * Un tópico puede tener varios handlers, de dominios distintos (por ejemplo, EmergenciaActualizada cierra
     * incidentes urbanos y también viajes pausados). Se ejecutan en el orden de los beans.
     */
    private static Map<String, List<EventHandler>> indexByTopic(List<EventHandler> handlers) {
        return handlers.stream().collect(Collectors.groupingBy(EventHandler::topic, LinkedHashMap::new,
                Collectors.collectingAndThen(Collectors.toList(), List::copyOf)));
    }
}
