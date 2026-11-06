package com.citypass.movilidad.service;

import com.citypass.movilidad.eventbus.publish.EventPublisher;
import com.citypass.movilidad.repository.EspaciosEventCursorRepository;
import com.citypass.movilidad.repository.DestinationRecommendationJobRepository;
import com.citypass.movilidad.model.enums.AggregateType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.util.Map;
import java.util.Objects;

/** Guarda la nueva recomendación en el outbox dentro de una transacción propia. */
@Service
public class DestinationRecommendationEventWriter {

    private final EventPublisher eventPublisher;

    private final DestinationRecommendationJobRepository jobs;
    private final EspaciosEventCursorRepository cursors;

    public DestinationRecommendationEventWriter(EventPublisher eventPublisher, DestinationRecommendationJobRepository jobs,
                                                EspaciosEventCursorRepository cursors) {
        this.eventPublisher = eventPublisher;
        this.jobs = jobs;
        this.cursors = cursors;
    }

    // Se llama también desde AFTER_COMMIT: requiere una transacción nueva para persistir el outbox.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publish(String eventId, Map<String, Object> data, String requestToken) {
        cursors.lock();
        // Un cálculo anterior no debe sobrescribir la invalidación de un snapshot más reciente.
        if (!Objects.equals(requestToken, jobs.token(eventId))) return;
        eventPublisher.publish("RecomendacionDestinoActualizada",
                AggregateType.CULTURAL_EVENT, eventId, data);
        if (requestToken != null) jobs.complete(eventId, requestToken);
    }
}
