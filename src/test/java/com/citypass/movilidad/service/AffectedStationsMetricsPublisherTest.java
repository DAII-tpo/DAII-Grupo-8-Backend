package com.citypass.movilidad.service;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class AffectedStationsMetricsPublisherTest {
    @Test void recalculaDespuesDelCommitYEnElTimer() {
        var service = mock(AffectedStationsService.class);
        var publisher = new AffectedStationsMetricsPublisher(service);
        publisher.onIncidentChanged(new TrafficIncidentChanged(-34.6, -58.4));
        publisher.refresh();
        verify(service, times(2)).synchronize();
    }

    @Test void unFalloNoBloqueaElProximoReintento() {
        var service = mock(AffectedStationsService.class);
        doThrow(new IllegalStateException("fallo temporal")).doNothing().when(service).synchronize();
        var publisher = new AffectedStationsMetricsPublisher(service);
        publisher.refresh();
        publisher.refresh();
        verify(service, times(2)).synchronize();
    }
}
