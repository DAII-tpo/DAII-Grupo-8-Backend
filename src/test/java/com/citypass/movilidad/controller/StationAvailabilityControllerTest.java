package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.StationAvailabilityResponse;
import com.citypass.movilidad.exception.ResourceNotFoundException;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.service.StationAvailabilityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StationAvailabilityController.class)
@AutoConfigureMockMvc(addFilters = false)
class StationAvailabilityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StationAvailabilityService stationAvailabilityService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void devuelveLaDisponibilidadDeUnaEstacion() throws Exception {
        when(stationAvailabilityService.getByStationId(1L)).thenReturn(availability(1L, 20, 7, 13));

        mockMvc.perform(get("/api/v1/stations/1/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stationId").value(1))
                .andExpect(jsonPath("$.stationName").value("Estación 1"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.capacity").value(20))
                .andExpect(jsonPath("$.availableBikes").value(7))
                .andExpect(jsonPath("$.availableSlots").value(13))
                .andExpect(jsonPath("$.checkedAt").exists());
    }

    @Test
    void devuelve404ConElFormatoDeErrorEstandarCuandoLaEstacionNoExiste() throws Exception {
        when(stationAvailabilityService.getByStationId(anyLong()))
                .thenThrow(new ResourceNotFoundException("Estación no encontrada: 999"));

        mockMvc.perform(get("/api/v1/stations/999/availability"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Estación no encontrada: 999"))
                .andExpect(jsonPath("$.path").value("/api/v1/stations/999/availability"));
    }

    @Test
    void devuelveLaDisponibilidadDeTodasLasEstaciones() throws Exception {
        when(stationAvailabilityService.getAll())
                .thenReturn(List.of(availability(1L, 20, 7, 13), availability(2L, 10, 0, 10)));

        mockMvc.perform(get("/api/v1/stations/availability"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[1].stationId").value(2))
                .andExpect(jsonPath("$[1].availableBikes").value(0));
    }

    private StationAvailabilityResponse availability(Long id, int capacity, int availableBikes, int availableSlots) {
        return new StationAvailabilityResponse(id, "Estación " + id, StationStatus.ACTIVE,
                capacity, availableBikes, availableSlots, Instant.parse("2026-09-08T14:30:00Z"));
    }
}
