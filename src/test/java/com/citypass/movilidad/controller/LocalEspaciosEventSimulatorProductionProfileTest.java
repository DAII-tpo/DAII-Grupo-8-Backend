package com.citypass.movilidad.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** El simulador no debe registrarse cuando la aplicación no usa el perfil local. */
@WebMvcTest(controllers = LocalEspaciosEventSimulatorController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("prod")
class LocalEspaciosEventSimulatorProductionProfileTest {

    @Autowired MockMvc mockMvc;

    @Test
    void noExponeElSimuladorFueraDelPerfilLocal() throws Exception {
        mockMvc.perform(post("/api/v1/dev/espacios/events/reserva-confirmada")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isNotFound());
    }
}
