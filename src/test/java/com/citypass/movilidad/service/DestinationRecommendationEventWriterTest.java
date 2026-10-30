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
        new DestinationRecommendationEventWriter(events).publish("ev-1", payload);
        verify(events).publish("RecomendacionDestinoActualizada", AggregateType.CULTURAL_EVENT, "ev-1", payload);
    }
}
