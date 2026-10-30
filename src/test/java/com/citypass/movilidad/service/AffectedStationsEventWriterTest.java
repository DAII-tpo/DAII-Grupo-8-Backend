package com.citypass.movilidad.service;
import com.citypass.movilidad.eventbus.publish.EventPublisher;
import com.citypass.movilidad.model.enums.AggregateType;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.mockito.Mockito.*;
class AffectedStationsEventWriterTest {
    @Test void usaElPublicadorYOutboxExistentes() {
        var publisher = mock(EventPublisher.class);
        var data = Map.<String, Object>of("cantidadEstacionesAfectadas", 2, "calculadaEn", "2026-10-30T00:00:00Z");
        new AffectedStationsEventWriter(publisher).publish(data);
        verify(publisher).publish("EstacionesAfectadasActualizadas", AggregateType.INCIDENT, "estaciones-afectadas", data);
    }
}
