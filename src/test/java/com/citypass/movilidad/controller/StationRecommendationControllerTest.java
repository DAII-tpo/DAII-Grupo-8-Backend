package com.citypass.movilidad.controller;

import com.citypass.movilidad.dto.StationRecommendationResponse;
import com.citypass.movilidad.dto.StationRecommendationResponse.ComparedStation;
import com.citypass.movilidad.dto.StationRecommendationResponse.Explanation;
import com.citypass.movilidad.dto.StationRecommendationResponse.Factor;
import com.citypass.movilidad.dto.StationRecommendationResponse.RecommendedStation;
import com.citypass.movilidad.model.enums.ExplanationCode;
import com.citypass.movilidad.model.enums.NoRecommendationReason;
import com.citypass.movilidad.model.enums.RecommendationPurpose;
import com.citypass.movilidad.model.enums.RecommendationSource;
import com.citypass.movilidad.model.enums.RecommendationStatus;
import com.citypass.movilidad.service.StationRecommendationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StationRecommendationController.class)
@AutoConfigureMockMvc(addFilters = false)
class StationRecommendationControllerTest {

    private static final String URL = "/api/v1/stations/recommendation";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StationRecommendationService stationRecommendationService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void devuelveLaRecomendacionConSuJustificacion() throws Exception {
        when(stationRecommendationService.recommend(anyDouble(), anyDouble(), any()))
                .thenReturn(recommendation());

        mockMvc.perform(get(URL + "?lat=-34.6037&lng=-58.3816"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECOMMENDED"))
                .andExpect(jsonPath("$.source").value("MODEL"))
                .andExpect(jsonPath("$.purpose").value("PICKUP"))
                .andExpect(jsonPath("$.message").value("Te recomendamos CERRITO"))
                .andExpect(jsonPath("$.station.stationId").value(77))
                .andExpect(jsonPath("$.station.stationName").value("CERRITO"))
                .andExpect(jsonPath("$.station.distanceMeters").value(236))
                .andExpect(jsonPath("$.station.availableBikes").value(13))
                .andExpect(jsonPath("$.station.score").value(0.768498))
                .andExpect(jsonPath("$.alternatives.length()").value(0))
                .andExpect(jsonPath("$.explanation.code").value("NEAREST_LOW_AVAILABILITY"))
                .andExpect(jsonPath("$.explanation.comparedTo.stationName").value("DIAGONAL NORTE"))
                .andExpect(jsonPath("$.explanation.factors[0].feature").value("secure_units"))
                .andExpect(jsonPath("$.modelVersion").value("logreg-synthetic-v1"))
                .andExpect(jsonPath("$.generatedAt").isNotEmpty());
    }

    @Test
    void usaPickupPorDefectoYPropagaElPropositoPedido() throws Exception {
        when(stationRecommendationService.recommend(anyDouble(), anyDouble(), any()))
                .thenReturn(recommendation());

        mockMvc.perform(get(URL + "?lat=-34.6&lng=-58.4")).andExpect(status().isOk());
        verify(stationRecommendationService).recommend(-34.6, -58.4, RecommendationPurpose.PICKUP);

        mockMvc.perform(get(URL + "?lat=-34.6&lng=-58.4&purpose=DROPOFF")).andExpect(status().isOk());
        verify(stationRecommendationService).recommend(-34.6, -58.4, RecommendationPurpose.DROPOFF);
    }

    @Test
    void respondeOkCuandoNoHayNadaParaRecomendar() throws Exception {
        when(stationRecommendationService.recommend(anyDouble(), anyDouble(), any()))
                .thenReturn(new StationRecommendationResponse(RecommendationStatus.NO_RECOMMENDATION,
                        RecommendationSource.FALLBACK, RecommendationPurpose.PICKUP,
                        NoRecommendationReason.NO_CANDIDATES,
                        "No encontramos estaciones cerca de tu ubicación.",
                        null, List.of(), null, null, Instant.parse("2025-09-20T14:31:05Z")));

        mockMvc.perform(get(URL + "?lat=-34.6037&lng=-58.3816"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_RECOMMENDATION"))
                .andExpect(jsonPath("$.reason").value("NO_CANDIDATES"))
                .andExpect(jsonPath("$.station").doesNotExist());
    }

    @Test
    void rechazaUnaLatitudFueraDeRango() throws Exception {
        mockMvc.perform(get(URL + "?lat=91&lng=-58.3816"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("lat")));

        verify(stationRecommendationService, never()).recommend(anyDouble(), anyDouble(), any());
    }

    @Test
    void rechazaUnaLongitudFueraDeRango() throws Exception {
        mockMvc.perform(get(URL + "?lat=-34.6&lng=181"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("lng")));
    }

    @Test
    void rechazaLaConsultaSiFaltaUnaCoordenada() throws Exception {
        mockMvc.perform(get(URL + "?lat=-34.6037"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Falta el parámetro obligatorio 'lng'"))
                .andExpect(jsonPath("$.path").value(URL));
    }

    @Test
    void rechazaUnaCoordenadaQueNoEsNumerica() throws Exception {
        mockMvc.perform(get(URL + "?lat=abc&lng=-58.3816"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El parámetro 'lat' no tiene un valor válido"));
    }

    @Test
    void rechazaUnPropositoDesconocido() throws Exception {
        mockMvc.perform(get(URL + "?lat=-34.6&lng=-58.4&purpose=ALQUILAR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("El parámetro 'purpose' no tiene un valor válido"));

        verify(stationRecommendationService, never()).recommend(anyDouble(), anyDouble(), any());
    }

    private StationRecommendationResponse recommendation() {
        return new StationRecommendationResponse(
                RecommendationStatus.RECOMMENDED, RecommendationSource.MODEL, RecommendationPurpose.PICKUP,
                null, "Te recomendamos CERRITO",
                new RecommendedStation(77L, "CERRITO", "Cerrito 1300", new BigDecimal("-34.6026"),
                        new BigDecimal("-58.3838"), 236, 30, 13, 17, 0.768498),
                List.of(),
                new Explanation(ExplanationCode.NEAREST_LOW_AVAILABILITY,
                        new ComparedStation(44L, "DIAGONAL NORTE", 193, 1, 17),
                        List.of(new Factor("secure_units", 2.759))),
                "logreg-synthetic-v1", Instant.parse("2025-09-20T14:31:05Z"));
    }
}
