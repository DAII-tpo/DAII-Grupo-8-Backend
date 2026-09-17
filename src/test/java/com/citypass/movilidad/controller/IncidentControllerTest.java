package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.IncidentCreateRequest;
import com.citypass.movilidad.dto.IncidentResponse;
import com.citypass.movilidad.dto.IncidentTypeResponse;
import com.citypass.movilidad.model.enums.BikeIncidentStatus;
import com.citypass.movilidad.service.IncidentService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IncidentControllerTest {

    private final IncidentService service = mock(IncidentService.class);
    private final IncidentController controller = new IncidentController(service);

    @Test
    void delegatesTypeQueryAndIncidentReport() {
        IncidentTypeResponse type = new IncidentTypeResponse(3L, "FLAT_TIRE", "Pinchazo", "Detalle");
        IncidentCreateRequest request = new IncidentCreateRequest(2L, 3L, "Rueda desinflada");
        IncidentResponse incident = new IncidentResponse(50L, 2L, "BIKE-2", 1L, 3L,
                "FLAT_TIRE", "Pinchazo", "Rueda desinflada", BikeIncidentStatus.OPEN, Instant.now());
        when(service.findActiveTypes()).thenReturn(List.of(type));
        when(service.report(1L, request)).thenReturn(incident);

        assertThat(controller.findTypes()).containsExactly(type);
        var response = controller.report(1L, request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).hasPath("/api/v1/incidents/50");
        assertThat(response.getBody()).isEqualTo(incident);
        verify(service).report(1L, request);
    }
}
