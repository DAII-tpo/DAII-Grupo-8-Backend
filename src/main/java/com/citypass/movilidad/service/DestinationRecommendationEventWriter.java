package com.citypass.movilidad.service;

import com.citypass.movilidad.eventbus.publish.EventPublisher;
import com.citypass.movilidad.model.enums.AggregateType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.util.Map;

/** Guarda la nueva recomendación en el outbox dentro de una transacción propia. */
@Service
public class DestinationRecommendationEventWriter {

    private final EventPublisher eventPublisher;

    public DestinationRecommendationEventWriter(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    // Se llama también desde AFTER_COMMIT: requiere una transacción nueva para persistir el outbox.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publish(String eventId, Map<String, Object> data) {
        eventPublisher.publish("RecomendacionDestinoActualizada",
                AggregateType.CULTURAL_EVENT, eventId, data);
    }
}
