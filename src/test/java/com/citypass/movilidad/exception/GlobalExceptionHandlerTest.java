package com.citypass.movilidad.exception;

import com.citypass.movilidad.exception.station.StationNotFoundException;
import com.citypass.movilidad.validation.EntityId;
import com.citypass.movilidad.validation.Latitude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica el contrato de error de MOV-021 contra un controller de prueba, de punta a punta:
 * cada caso pasa por el mismo pipeline de Spring MVC que usan los controllers reales.
 */
@WebMvcTest(controllers = GlobalExceptionHandlerTest.ErrorProbeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, GlobalExceptionHandlerTest.ErrorProbeController.class})
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    // --- Estructura común ---

    @Test
    void todaRespuestaDeErrorTraeTimestampStatusErrorCodeMensajeYPath() throws Exception {
        mockMvc.perform(get("/probe/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Viaje no encontrado: 7"))
                .andExpect(jsonPath("$.path").value("/probe/not-found"))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void noExponeStackTracesNiDetalleInternoAnteUnErrorInesperado() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("Ocurrió un error inesperado"))
                .andExpect(jsonPath("$.path").value("/probe/boom"))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("com.citypass"))));
    }

    // --- 404 Not Found ---

    @Test
    void devuelveNotFoundParaLasExcepcionesDeDominioQueHeredanDeResourceNotFound() throws Exception {
        mockMvc.perform(get("/probe/station-not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STATION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("La estación con ID 99 no existe"));
    }

    @Test
    void devuelveNotFoundParaUnaRutaInexistente() throws Exception {
        mockMvc.perform(get("/probe/ruta-que-no-existe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("El recurso solicitado no existe"));
    }

    // --- 409 Conflict ---

    @Test
    void devuelveConflictParaUnaReglaDeNegocioIncumplida() throws Exception {
        mockMvc.perform(get("/probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.message").value("La bicicleta no está disponible"));
    }

    @Test
    void devuelveConflictSinFiltrarElDetalleDeLaBaseAnteUnaViolacionDeIntegridad() throws Exception {
        mockMvc.perform(get("/probe/data-integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DATA_INTEGRITY_VIOLATION"))
                .andExpect(jsonPath("$.message").value("La operación entra en conflicto con datos ya existentes"))
                .andExpect(content().string(not(containsString("uk_stations_external_id"))));
    }

    // --- 400 Bad Request ---

    @Test
    void devuelveBadRequestParaUnDatoDeEntradaInvalidoDetectadoEnElDominio() throws Exception {
        mockMvc.perform(get("/probe/invalid-input"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("El rango de fechas es incoherente"));
    }

    @Test
    void devuelveBadRequestConUnDetallePorCadaCampoInvalidoDelCuerpo() throws Exception {
        mockMvc.perform(post("/probe/body")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"  \",\"capacity\":-3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]", hasSize(1)))
                .andExpect(jsonPath("$.errors[?(@.field == 'capacity')]", hasSize(1)))
                .andExpect(jsonPath("$.path").value("/probe/body"));
    }

    @Test
    void devuelveBadRequestSiElCuerpoEstaAusenteOEsIlegible() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content("{no es json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("El cuerpo de la solicitud está ausente o contiene valores inválidos"));
    }

    @Test
    void devuelveBadRequestSiFaltaUnParametroObligatorio() throws Exception {
        mockMvc.perform(get("/probe/params"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Falta el parámetro obligatorio 'lat'"));
    }

    @Test
    void devuelveBadRequestSiUnParametroNoTieneElTipoEsperado() throws Exception {
        mockMvc.perform(get("/probe/params").param("lat", "no-es-un-numero"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El parámetro 'lat' no tiene un valor válido"));
    }

    @Test
    void devuelveBadRequestSiUnParametroEstaFueraDeRango() throws Exception {
        mockMvc.perform(get("/probe/params").param("lat", "91"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("lat")));
    }

    @Test
    void devuelveBadRequestSiFaltaUnHeaderObligatorio() throws Exception {
        mockMvc.perform(get("/probe/header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Falta el header obligatorio X-User-Id"));
    }

    @Test
    void devuelveBadRequestSiUnIdDePathNoEsPositivo() throws Exception {
        mockMvc.perform(get("/probe/entity/-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors", hasSize(1)));
    }

    // --- Otros estados que antes caían en 500 ---

    @Test
    void devuelveMethodNotAllowedParaUnMetodoNoSoportado() throws Exception {
        mockMvc.perform(delete("/probe/not-found"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.message").value("El método DELETE no está permitido en este recurso"));
    }

    @Test
    void devuelveUnsupportedMediaTypeParaUnContentTypeNoSoportado() throws Exception {
        mockMvc.perform(post("/probe/body").contentType(MediaType.TEXT_PLAIN).content("hola"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.message").value("El tipo de contenido de la solicitud no está soportado"));
    }

    @RestController
    @RequestMapping("/probe")
    static class ErrorProbeController {

        @GetMapping("/not-found")
        void notFound() {
            throw new ResourceNotFoundException("Viaje no encontrado: 7");
        }

        @GetMapping("/station-not-found")
        void stationNotFound() {
            throw new StationNotFoundException(99L);
        }

        @GetMapping("/conflict")
        void conflict() {
            throw new BusinessRuleException("La bicicleta no está disponible");
        }

        @GetMapping("/data-integrity")
        void dataIntegrity() {
            throw new DataIntegrityViolationException("Duplicate entry for key 'uk_stations_external_id'");
        }

        @GetMapping("/invalid-input")
        void invalidInput() {
            throw new ValidationException("El rango de fechas es incoherente");
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("detalle interno que no debe salir");
        }

        @PostMapping("/body")
        void body(@Valid @RequestBody Payload payload) {
            // Solo interesa la validación del cuerpo.
        }

        @GetMapping("/params")
        void params(@RequestParam @Latitude double lat) {
            // Solo interesa la validación del parámetro.
        }

        @GetMapping("/header")
        void header(@RequestHeader("X-User-Id") Long userId) {
            // Solo interesa la validación del header.
        }

        @GetMapping("/entity/{id}")
        void entity(@PathVariable @EntityId Long id) {
            // Solo interesa la validación del id.
        }

        record Payload(@NotBlank String name, @NotNull @Positive Integer capacity) {
        }
    }
}
