package com.citypass.movilidad.service;
import com.citypass.movilidad.eventbus.publish.EventPublisher;
import com.citypass.movilidad.model.enums.AggregateType;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.mockito.Mockito.*;
class DestinationRecommendationEventWriterTest {
    @Test void delegaAlOutboxOficialConElContratoYAgrupacionDelEvento() {
        EventPublisher events = mock(EventPublisher.class);
        Map<String, Object> payload = Map.of("eventoId", "ev-1", "estado", "NO_RECOMMENDATION");
        var jobs = mock(com.citypass.movilidad.repository.DestinationRecommendationJobRepository.class);
        new DestinationRecommendationEventWriter(events, jobs).publish("ev-1", payload, "request-1");
        verify(jobs).complete("ev-1", "request-1");
        verify(events).publish("RecomendacionDestinoActualizada", AggregateType.CULTURAL_EVENT, "ev-1", payload);
    }
}
