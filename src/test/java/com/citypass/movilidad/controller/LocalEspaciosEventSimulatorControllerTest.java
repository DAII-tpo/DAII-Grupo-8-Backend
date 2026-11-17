package com.citypass.movilidad.controller;

import com.citypass.movilidad.service.ClimateAlertService;
import com.citypass.movilidad.service.InboundEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LocalEspaciosEventSimulatorController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("local")
class LocalEspaciosEventSimulatorControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean InboundEventService inboundEvents;
    @MockitoBean ClimateAlertService climateAlerts;

    @Test
    void entregaReservaConfirmadaAlMismoProcesadorQueKafka() throws Exception {
        mockMvc.perform(post("/api/v1/dev/espacios/events/reserva-confirmada")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reservaId\":\"res-1\"}"))
                .andExpect(status().isNoContent());

        verify(inboundEvents).handle(eq("com.citypass.espacios.ReservaConfirmada"), any());
    }

    @Test
    void entregaCriticidadClimaticaAlMismoServicioQueElHandlerDeKafka() throws Exception {
        mockMvc.perform(post("/api/v1/dev/espacios/events/criticidad-climatica-confirmada")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertaId\":\"alerta-1\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/v1/dev/espacios/events/criticidad-climatica-finalizada")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"alertaId\":\"alerta-1\"}"))
                .andExpect(status().isNoContent());

        verify(climateAlerts).confirm(any(), any());
        verify(climateAlerts).finish(any());
    }

    @Test
    void criticidadClimaticaInvalidaResponde400() throws Exception {
        doThrow(new IllegalArgumentException("alertaId es obligatorio")).when(climateAlerts).confirm(any(), any());

        mockMvc.perform(post("/api/v1/dev/espacios/events/criticidad-climatica-confirmada")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rechazaPayloadQueNoEsObjeto() throws Exception {
        mockMvc.perform(post("/api/v1/dev/espacios/events/evento-finalizado")
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest());
    }
}
