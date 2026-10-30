package com.citypass.movilidad.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AffectedStationsMetricsPublisherTest {
    private final AffectedStationsMetricsService metrics = mock(AffectedStationsMetricsService.class);
    private final AffectedStationsEventWriter writer = mock(AffectedStationsEventWriter.class);
    private final AffectedStationsMetricsPublisher publisher = new AffectedStationsMetricsPublisher(metrics, writer);

    @Test void enviaSoloLaMetricaPedidaYLaFechaDelSnapshot() {
        when(metrics.calculate()).thenReturn(new AffectedStationsMetricsService.Snapshot(3));
        publisher.onIncidentChanged(new TrafficIncidentChanged(-34.6, -58.4));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(writer).publish(payload.capture());
        assertThat(payload.getValue()).containsOnlyKeys("cantidadEstacionesAfectadas", "calculadaEn")
                .containsEntry("cantidadEstacionesAfectadas", 3);
        assertThat(Instant.parse((String) payload.getValue().get("calculadaEn"))).isBeforeOrEqualTo(Instant.now());
    }

    @Test void elRefrescoPeriodicoPublicaCeroYRecuperaUnFalloAnterior() {
        when(metrics.calculate()).thenThrow(new IllegalStateException("temporary"))
                .thenReturn(new AffectedStationsMetricsService.Snapshot(0));
        publisher.refresh();
        verifyNoInteractions(writer);
        publisher.refresh();
        verify(writer).publish(argThat(payload -> Integer.valueOf(0).equals(payload.get("cantidadEstacionesAfectadas"))));
    }
}
