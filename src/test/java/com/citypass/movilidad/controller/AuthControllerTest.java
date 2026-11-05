package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.AuthTokenResponse;
import com.citypass.movilidad.exception.ForbiddenOperationException;
import com.citypass.movilidad.exception.UnauthorizedException;
import com.citypass.movilidad.service.CitizenAuthService;
import com.citypass.movilidad.support.TestSecrets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP del login de ciudadanos: los códigos de estado y que los errores salgan con el
 * {@code code} estable del {@code ErrorResponse}.
 *
 * <p>Importa especialmente el 401: {@code UnauthorizedException} no extiende la excepción de
 * Spring Security justamente para que el handler la mapee y no termine en un 500 genérico en
 * endpoints sin entry point configurado, que es el caso de estos tres.
 */
@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    private static final String EMAIL = "ana.perez@example.com";

    private static final String REGISTRO = "{\"email\":\"" + EMAIL + "\",\"password\":\""
            + TestSecrets.CITIZEN_PASSWORD + "\",\"firstName\":\"Ana\",\"lastName\":\"Pérez\"}";

    private static final String LOGIN = "{\"email\":\"" + EMAIL + "\",\"password\":\""
            + TestSecrets.CITIZEN_PASSWORD + "\"}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CitizenAuthService citizenAuthService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private static AuthTokenResponse respuesta() {
        return new AuthTokenResponse("el-access-token", "Bearer", 1800L, "el-refresh-token",
                7L, "ana.perez@example.com", "Ana", "Pérez", "USER");
    }

    @Test
    void elRegistroDevuelve201ConElParDeTokens() throws Exception {
        when(citizenAuthService.register(any())).thenReturn(respuesta());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(REGISTRO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("el-access-token"))
                .andExpect(jsonPath("$.refreshToken").value("el-refresh-token"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(1800))
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void rechazaUnEmailConFormatoInvalido() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"no-es-un-email\",\"password\":\"Bicicleta2026\","
                                + "\"firstName\":\"Ana\",\"lastName\":\"Pérez\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(citizenAuthService, never()).register(any());
    }

    /** La política de contraseña se aplica al registrarse, antes de llegar al servicio. */
    @Test
    void rechazaUnaContrasenaQueNoCumpleLaPolitica() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ana.perez@example.com\",\"password\":\"corta\","
                                + "\"firstName\":\"Ana\",\"lastName\":\"Pérez\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(citizenAuthService, never()).register(any());
    }

    @Test
    void rechazaUnRegistroSinNombre() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"ana.perez@example.com\",\"password\":\"Bicicleta2026\"}"))
                .andExpect(status().isBadRequest());

        verify(citizenAuthService, never()).register(any());
    }

    @Test
    void elLoginDevuelve200ConElParDeTokens() throws Exception {
        when(citizenAuthService.login(any())).thenReturn(respuesta());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("el-access-token"))
                .andExpect(jsonPath("$.email").value("ana.perez@example.com"));
    }

    @Test
    void credencialesIncorrectasSalenComo401ConSuCodigo() throws Exception {
        when(citizenAuthService.login(any())).thenThrow(new UnauthorizedException(
                CitizenAuthService.CODE_INVALID_CREDENTIALS, "Email o contraseña incorrectos"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void laCuentaBloqueadaPorIntentosSaleComo401ConSuPropioCodigo() throws Exception {
        when(citizenAuthService.login(any())).thenThrow(new UnauthorizedException(
                CitizenAuthService.CODE_ACCOUNT_LOCKED, "La cuenta está bloqueada temporalmente"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    void laCuentaDeshabilitadaSaleComo403() throws Exception {
        when(citizenAuthService.login(any()))
                .thenThrow(new ForbiddenOperationException("La cuenta no está habilitada: 7"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON).content(LOGIN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_OPERATION"));
    }

    @Test
    void elRefreshDevuelve200ConElParNuevo() throws Exception {
        when(citizenAuthService.refresh(any())).thenReturn(respuesta());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"el-viejo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").value("el-refresh-token"));
    }

    @Test
    void rechazaUnRefreshSinToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        verify(citizenAuthService, never()).refresh(any());
    }
}
