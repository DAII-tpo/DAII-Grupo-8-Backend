package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.NearbyStationResponse;
import com.citypass.movilidad.service.NearbyStationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NearbyStationController.class)
@AutoConfigureMockMvc(addFilters = false)
class NearbyStationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NearbyStationService nearbyStationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void devuelveLasEstacionesCercanasConDistanciaYDisponibilidad() throws Exception {
        when(nearbyStationService.findNearby(anyDouble(), anyDouble(), any(), any()))
                .thenReturn(List.of(nearby(1L, "Obelisco", 120), nearby(2L, "Congreso", 480)));

        mockMvc.perform(get("/api/v1/stations/nearby?lat=-34.6037&lng=-58.3816"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].stationName").value("Obelisco"))
                .andExpect(jsonPath("$[0].distanceMeters").value(120))
                .andExpect(jsonPath("$[0].availableBikes").value(4))
                .andExpect(jsonPath("$[0].availableSlots").value(6))
                .andExpect(jsonPath("$[0].latitude").value(-34.6037))
                .andExpect(jsonPath("$[1].distanceMeters").value(480));
    }

    @Test
    void propagaElRadioYElLimiteAlServicio() throws Exception {
        when(nearbyStationService.findNearby(anyDouble(), anyDouble(), any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/stations/nearby?lat=-34.6&lng=-58.4&radius=1500&limit=3"))
                .andExpect(status().isOk());

        verify(nearbyStationService).findNearby(-34.6, -58.4, 1500, 3);
    }

    @Test
    void rechazaUnaLatitudFueraDeRango() throws Exception {
        mockMvc.perform(get("/api/v1/stations/nearby?lat=91&lng=-58.3816"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("lat")));

        verify(nearbyStationService, never()).findNearby(anyDouble(), anyDouble(), any(), any());
    }

    @Test
    void rechazaUnaLongitudFueraDeRango() throws Exception {
        mockMvc.perform(get("/api/v1/stations/nearby?lat=-34.6&lng=181"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("lng")));
    }

    @Test
    void rechazaLaConsultaSiFaltaUnaCoordenada() throws Exception {
        mockMvc.perform(get("/api/v1/stations/nearby?lat=-34.6037"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Falta el parámetro obligatorio 'lng'"))
                .andExpect(jsonPath("$.path").value("/api/v1/stations/nearby"));
    }

    @Test
    void rechazaUnaCoordenadaQueNoEsNumerica() throws Exception {
        mockMvc.perform(get("/api/v1/stations/nearby?lat=abc&lng=-58.3816"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El parámetro 'lat' no tiene un valor válido"));
    }

    @Test
    void rechazaUnRadioNegativo() throws Exception {
        mockMvc.perform(get("/api/v1/stations/nearby?lat=-34.6&lng=-58.4&radius=-100"))
                .andExpect(status().isBadRequest());
    }

    private NearbyStationResponse nearby(Long id, String name, int distanceMeters) {
        return new NearbyStationResponse(id, name, "Dirección de " + name,
                new BigDecimal("-34.6037"), new BigDecimal("-58.3816"),
                distanceMeters, 10, 4, 6);
    }
}
