package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.request.StationRequestDTO;
import com.citypass.movilidad.dto.response.StationDTO;
import com.citypass.movilidad.exception.station.StationNotFoundException;
import com.citypass.movilidad.model.enums.StationSource;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.service.StationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StationController.class)
@AutoConfigureMockMvc(addFilters = false)
class StationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private StationService stationService;

    private static StationDTO respuesta(Long id, String nombre) {
        return new StationDTO(id, nombre, "Av. 9 de Julio 1030", new BigDecimal("-34.6037"),
                new BigDecimal("-58.3816"), 10, "ACTIVE", "2026-09-04T00:07:02Z", "2026-09-04T00:07:02Z", null);
    }

    private static StationRequestDTO pedido() {
        return StationRequestDTO.builder()
                .name("Estación Nueva")
                .address("Av. Corrientes 500")
                .latitude(new BigDecimal("-34.6000"))
                .longitude(new BigDecimal("-58.3800"))
                .capacity(25)
                .status(StationStatus.ACTIVE)
                .source(StationSource.MANUAL)
                .build();
    }

    @Test
    void getDevuelveLaListaDeEstaciones() throws Exception {
        when(stationService.getAllStations()).thenReturn(List.of(respuesta(1L, "Estación 9 de Julio")));

        mockMvc.perform(get("/api/v1/stations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].name").value("Estación 9 de Julio"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    void getPorIdDevuelveLaEstacion() throws Exception {
        when(stationService.getStationById(1L)).thenReturn(respuesta(1L, "Estación 9 de Julio"));

        mockMvc.perform(get("/api/v1/stations/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.capacity").value(10));
    }

    @Test
    void getPorIdDevuelveNotFoundSiLaEstacionNoExiste() throws Exception {
        when(stationService.getStationById(99L)).thenThrow(new StationNotFoundException(99L));

        mockMvc.perform(get("/api/v1/stations/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("La estación con ID 99 no existe"))
                .andExpect(jsonPath("$.path").value("/api/v1/stations/99"));
    }

    @Test
    void postCreaLaEstacion() throws Exception {
        when(stationService.createStation(any(StationRequestDTO.class))).thenReturn(respuesta(5L, "Estación Nueva"));

        mockMvc.perform(post("/api/v1/stations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pedido())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5))
                .andExpect(jsonPath("$.name").value("Estación Nueva"));
    }

    @Test
    void patchActualizaLaEstacion() throws Exception {
        when(stationService.updateStation(eq(1L), any(StationRequestDTO.class)))
                .thenReturn(respuesta(1L, "Estación Renombrada"));

        mockMvc.perform(patch("/api/v1/stations/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(pedido())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Estación Renombrada"));
    }
}
