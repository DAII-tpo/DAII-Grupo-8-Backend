package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.CurrentUserResponse;
import com.citypass.movilidad.exception.UnauthorizedException;
import com.citypass.movilidad.service.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.empty;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de {@code GET /api/v1/users/me}: el mapeo de la respuesta. Cómo se calculan el
 * tipo y el rol a partir de un token real lo cubre {@code CurrentUserIntegrationTest}.
 */
@WebMvcTest(UserController.class)
@AutoConfigureMockMvc(addFilters = false)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CurrentUserService currentUserService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void devuelveAlEmpleadoConSuRolYSusGrupos() throws Exception {
        when(currentUserService.describeCurrentUser()).thenReturn(new CurrentUserResponse(
                12L, "STAFF", "ADMIN", "Juan", "Pérez", "u000042@citypass.local",
                List.of("admin", "operadores")));

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(12))
                .andExpect(jsonPath("$.type").value("STAFF"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.firstName").value("Juan"))
                .andExpect(jsonPath("$.lastName").value("Pérez"))
                .andExpect(jsonPath("$.email").value("u000042@citypass.local"))
                .andExpect(jsonPath("$.groups[0]").value("admin"))
                .andExpect(jsonPath("$.groups[1]").value("operadores"));
    }

    /** Para un ciudadano groups es una lista vacía y no null: el front no tiene que chequearlo. */
    @Test
    void devuelveAlCiudadanoConGruposVacios() throws Exception {
        when(currentUserService.describeCurrentUser()).thenReturn(new CurrentUserResponse(
                7L, "CITIZEN", "CITIZEN", "Ana", "Pérez", "ana.perez@example.com", List.of()));

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.type").value("CITIZEN"))
                .andExpect(jsonPath("$.role").value("CITIZEN"))
                .andExpect(jsonPath("$.groups").isArray())
                .andExpect(jsonPath("$.groups", empty()));
    }

    @Test
    void sinIdentidadSaleComo401() throws Exception {
        when(currentUserService.describeCurrentUser())
                .thenThrow(new UnauthorizedException("El request no tiene una identidad asociada"));

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }
}
