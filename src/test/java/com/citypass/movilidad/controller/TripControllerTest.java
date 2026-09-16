package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.TripEndRequest;
import com.citypass.movilidad.dto.TripResponse;
import com.citypass.movilidad.dto.TripStartRequest;
import com.citypass.movilidad.model.enums.TripStatus;
import com.citypass.movilidad.service.TripService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TripControllerTest {

    private final TripService service = mock(TripService.class);
    private final TripController controller = new TripController(service);

    @Test
    void delegatesTripLifecycleOperations() {
        Instant startedAt = Instant.now().minusSeconds(300);
        TripResponse active = new TripResponse(7L, TripStatus.ACTIVE, 5L, "BIKE-5", 10L, "Centro",
                null, null, startedAt, null, null);
        TripResponse completed = new TripResponse(7L, TripStatus.COMPLETED, 5L, "BIKE-5", 10L, "Centro",
                20L, "Retiro", startedAt, Instant.now(), 300);
        TripStartRequest start = new TripStartRequest(5L);
        TripEndRequest end = new TripEndRequest(20L);
        when(service.startTrip(1L, start)).thenReturn(active);
        when(service.findActiveTrip(1L)).thenReturn(Optional.of(active));
        when(service.findActiveTrip(2L)).thenReturn(Optional.empty());
        when(service.endTrip(1L, 7L, end)).thenReturn(completed);

        var created = controller.start(1L, start);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).hasToString("/api/v1/trips/7");
        assertThat(created.getBody()).isEqualTo(active);

        var found = controller.findActive(1L);
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(found.getBody()).isEqualTo(active);

        var none = controller.findActive(2L);
        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(none.getBody()).isNull();

        assertThat(controller.end(1L, 7L, end)).isEqualTo(completed);
    }
}
