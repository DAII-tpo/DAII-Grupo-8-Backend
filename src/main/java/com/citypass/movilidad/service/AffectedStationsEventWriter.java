package com.citypass.movilidad.service;

import com.citypass.movilidad.eventbus.publish.EventPublisher;
import com.citypass.movilidad.model.enums.AggregateType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** Emite el snapshot por el outbox oficial del bus, en una transacción nueva. */
@Service
public class AffectedStationsEventWriter {
    private final EventPublisher publisher;

    public AffectedStationsEventWriter(EventPublisher publisher) {
        this.publisher = publisher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publish(Map<String, Object> payload) {
        publisher.publish("EstacionesAfectadasActualizadas", AggregateType.INCIDENT,
                "estaciones-afectadas", payload);
    }
}
