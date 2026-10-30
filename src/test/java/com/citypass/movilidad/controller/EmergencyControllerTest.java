package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.EmergencyCreateRequest;
import com.citypass.movilidad.dto.EmergencyResponse;
import com.citypass.movilidad.dto.EmergencyTriageRequest;
import com.citypass.movilidad.model.enums.EmergencyStatus;
import com.citypass.movilidad.service.EmergencyService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmergencyControllerTest {

    private final EmergencyService service = mock(EmergencyService.class);
    private final EmergencyController controller = new EmergencyController(service);

    @Test
    void delegaElCicloDeUnPedidoDeAyuda() {
        EmergencyResponse pending = response(EmergencyStatus.PENDING);
        EmergencyResponse cancelled = response(EmergencyStatus.CANCELLED);
        EmergencyResponse reported = response(EmergencyStatus.REPORTED);
        EmergencyCreateRequest location = new EmergencyCreateRequest(-34.6037, -58.3816, 12.5);
        EmergencyTriageRequest answers = new EmergencyTriageRequest(true, false, false);
        when(service.report(1L, 7L, location)).thenReturn(pending);
        when(service.findCurrent(1L)).thenReturn(Optional.of(pending));
        when(service.findCurrent(2L)).thenReturn(Optional.empty());
        when(service.cancel(1L, 91L)).thenReturn(cancelled);
        when(service.submitTriage(1L, 91L, answers)).thenReturn(reported);

        var created = controller.report(1L, 7L, location);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).hasToString("/api/v1/emergencies/91");
        assertThat(created.getBody()).isEqualTo(pending);

        assertThat(controller.findCurrent(1L).getBody()).isEqualTo(pending);
        assertThat(controller.findCurrent(2L).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(controller.cancel(1L, 91L)).isEqualTo(cancelled);
        assertThat(controller.triage(1L, 91L, answers)).isEqualTo(reported);
    }

    private static EmergencyResponse response(EmergencyStatus status) {
        Instant now = Instant.now();
        return new EmergencyResponse(91L, "EMG-20261029-0091", status, 7L, 5L, "BIKE-5",
                new BigDecimal("-34.603700"), new BigDecimal("-58.381600"), new BigDecimal("12.50"),
                now, now.plusSeconds(10), now.plusSeconds(70), 10, 60, null, null, null, null, null);
    }
}
