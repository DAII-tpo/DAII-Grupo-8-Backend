package com.citypass.movilidad.service;

import com.citypass.movilidad.client.RecommendationClient;
import com.citypass.movilidad.client.RecommendationUnavailableException;
import com.citypass.movilidad.client.dto.RecommendationApiRequest;
import com.citypass.movilidad.client.dto.RecommendationApiResponse;
import com.citypass.movilidad.config.RecommendationProperties;
import com.citypass.movilidad.dto.NearbyStationResponse;
import com.citypass.movilidad.dto.StationRecommendationResponse;
import com.citypass.movilidad.model.enums.ExplanationCode;
import com.citypass.movilidad.model.enums.NoRecommendationReason;
import com.citypass.movilidad.model.enums.RecommendationPurpose;
import com.citypass.movilidad.model.enums.RecommendationSource;
import com.citypass.movilidad.model.enums.RecommendationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * El criterio de aceptación central de MOV-042 es que una falla del componente de recomendación
 * no haga caer el módulo. Por eso la mitad de estos tests son formas distintas de que el
 * servicio falle, y en todas la respuesta sigue siendo una recomendación utilizable.
 */
class StationRecommendationServiceTest {

    private static final double LAT = -34.6037;
    private static final double LNG = -58.3816;

    private NearbyStationService nearbyStationService;
    private RecommendationClient recommendationClient;
    private StationRecommendationService service;

    @BeforeEach
    void setUp() {
        nearbyStationService = mock(NearbyStationService.class);
        recommendationClient = mock(RecommendationClient.class);
        service = new StationRecommendationService(nearbyStationService, recommendationClient,
                new RecommendationMessageComposer(),
                new RecommendationProperties("http://localhost:8000", 1000, 2000, 1000, 15, true));
    }

    @Test
    void devuelveLaEstacionQueRecomiendaElModeloConSusDatosDelSnapshot() {
        givenCandidates(diagonalNorte(), cerrito());
        when(recommendationClient.recommend(any())).thenReturn(modelResponse());

        StationRecommendationResponse response = service.recommend(LAT, LNG, RecommendationPurpose.PICKUP);

        assertThat(response.status()).isEqualTo(RecommendationStatus.RECOMMENDED);
        assertThat(response.source()).isEqualTo(RecommendationSource.MODEL);
        assertThat(response.reason()).isNull();
        assertThat(response.modelVersion()).isEqualTo("logreg-synthetic-v1");
        assertThat(response.generatedAt()).isNotNull();

        // Nombre, dirección y capacidad los pone el backend: el modelo solo conoce ids y números.
        assertThat(response.station().stationId()).isEqualTo(77L);
        assertThat(response.station().stationName()).isEqualTo("CERRITO");
        assertThat(response.station().address()).isEqualTo("Dirección de CERRITO");
        assertThat(response.station().capacity()).isEqualTo(30);
        assertThat(response.station().distanceMeters()).isEqualTo(236);
        assertThat(response.station().availableBikes()).isEqualTo(13);
        assertThat(response.station().score()).isEqualTo(0.768498);

        assertThat(response.alternatives()).hasSize(1);
        assertThat(response.alternatives().getFirst().stationName()).isEqualTo("DIAGONAL NORTE");

        assertThat(response.explanation().code()).isEqualTo(ExplanationCode.NEAREST_LOW_AVAILABILITY);
        assertThat(response.explanation().comparedTo().stationName()).isEqualTo("DIAGONAL NORTE");
        assertThat(response.explanation().comparedTo().distanceMeters()).isEqualTo(193);
        assertThat(response.explanation().factors()).hasSize(1);
        assertThat(response.explanation().factors().getFirst().feature()).isEqualTo("secure_units");

        assertThat(response.message()).contains("CERRITO", "DIAGONAL NORTE", "1 bicicleta disponible");
    }

    @Test
    void mandaElSnapshotCompletoDeLasCandidatasAlModelo() {
        givenCandidates(diagonalNorte(), cerrito());
        when(recommendationClient.recommend(any())).thenReturn(modelResponse());

        service.recommend(LAT, LNG, RecommendationPurpose.PICKUP);

        ArgumentCaptor<RecommendationApiRequest> captor =
                ArgumentCaptor.forClass(RecommendationApiRequest.class);
        verify(recommendationClient).recommend(captor.capture());
        RecommendationApiRequest request = captor.getValue();

        assertThat(request.purpose()).isEqualTo(RecommendationPurpose.PICKUP);
        assertThat(request.user().lat()).isEqualTo(LAT);
        assertThat(request.user().lng()).isEqualTo(LNG);
        assertThat(request.stations()).hasSize(2);
        assertThat(request.stations().getFirst().stationId()).isEqualTo(44L);
        assertThat(request.stations().getFirst().capacity()).isEqualTo(18);
        assertThat(request.stations().getFirst().availableBikes()).isEqualTo(1);
        assertThat(request.stations().getFirst().availableDocks()).isEqualTo(17);
    }

    @Test
    void usaElRadioYElTopeDeCandidatasConfigurados() {
        givenCandidates(cerrito());
        when(recommendationClient.recommend(any())).thenReturn(modelResponse());

        service.recommend(LAT, LNG, RecommendationPurpose.PICKUP);

        verify(nearbyStationService).findNearby(LAT, LNG, 1000, 15);
    }

    @Test
    void asumePickupCuandoNoSeIndicaElProposito() {
        givenCandidates(cerrito());
        when(recommendationClient.recommend(any())).thenReturn(modelResponse());

        StationRecommendationResponse response = service.recommend(LAT, LNG, null);

        assertThat(response.purpose()).isEqualTo(RecommendationPurpose.PICKUP);
    }

    @Test
    void noConsultaAlModeloSiNoHayEstacionesCerca() {
        givenCandidates();

        StationRecommendationResponse response = service.recommend(LAT, LNG, RecommendationPurpose.PICKUP);

        assertThat(response.status()).isEqualTo(RecommendationStatus.NO_RECOMMENDATION);
        assertThat(response.reason()).isEqualTo(NoRecommendationReason.NO_CANDIDATES);
        assertThat(response.station()).isNull();
        assertThat(response.alternatives()).isEmpty();
        assertThat(response.message()).isEqualTo("No encontramos estaciones cerca de tu ubicación.");
        verifyNoInteractions(recommendationClient);
    }

    @Test
    void respetaElNoRecommendationQueDevuelveElModelo() {
        givenCandidates(cerrito());
        when(recommendationClient.recommend(any())).thenReturn(new RecommendationApiResponse(
                RecommendationStatus.NO_RECOMMENDATION, NoRecommendationReason.NO_VIABLE_STATION,
                RecommendationPurpose.PICKUP, "logreg-synthetic-v1", null, List.of(), null));

        StationRecommendationResponse response = service.recommend(LAT, LNG, RecommendationPurpose.PICKUP);

        assertThat(response.status()).isEqualTo(RecommendationStatus.NO_RECOMMENDATION);
        assertThat(response.reason()).isEqualTo(NoRecommendationReason.NO_VIABLE_STATION);
        assertThat(response.source()).isEqualTo(RecommendationSource.MODEL);
    }

    @Test
    void recomiendaLaMasCercanaConBicicletasCuandoElModeloNoResponde() {
        givenCandidates(sinBicicletas(), diagonalNorte(), cerrito());
        when(recommendationClient.recommend(any()))
                .thenThrow(new RecommendationUnavailableException("timeout"));

        StationRecommendationResponse response = service.recommend(LAT, LNG, RecommendationPurpose.PICKUP);

        assertThat(response.status()).isEqualTo(RecommendationStatus.RECOMMENDED);
        assertThat(response.source()).isEqualTo(RecommendationSource.FALLBACK);
        assertThat(response.station().stationName()).isEqualTo("DIAGONAL NORTE");
        assertThat(response.station().score()).isNull();
        assertThat(response.explanation()).isNull();
        assertThat(response.modelVersion()).isNull();
        assertThat(response.alternatives()).extracting(
                StationRecommendationResponse.RecommendedStation::stationName).containsExactly("CERRITO");
        assertThat(response.message()).contains("DIAGONAL NORTE");
    }

    @Test
    void intentaDespertarAlServicioDespuesDeUnRespaldo() {
        givenCandidates(cerrito());
        when(recommendationClient.recommend(any()))
                .thenThrow(new RecommendationUnavailableException("caído"));

        service.recommend(LAT, LNG, RecommendationPurpose.PICKUP);

        verify(recommendationClient).warmUp();
    }

    @Test
    void usaElRespaldoSiElModeloDevuelveUnaEstacionQueNoEstabaEnElSnapshot() {
        givenCandidates(cerrito());
        when(recommendationClient.recommend(any())).thenReturn(new RecommendationApiResponse(
                RecommendationStatus.RECOMMENDED, null, RecommendationPurpose.PICKUP, "logreg-synthetic-v1",
                new RecommendationApiResponse.ScoredStation(999L, 0.9, 100, 5, 5), List.of(),
                new RecommendationApiResponse.Explanation(ExplanationCode.NEAREST_IS_BEST, null, List.of())));

        StationRecommendationResponse response = service.recommend(LAT, LNG, RecommendationPurpose.PICKUP);

        assertThat(response.source()).isEqualTo(RecommendationSource.FALLBACK);
        assertThat(response.station().stationId()).isEqualTo(77L);
    }

    @Test
    void usaElRespaldoSiElModeloRecomiendaSinExplicacion() {
        givenCandidates(cerrito());
        when(recommendationClient.recommend(any())).thenReturn(new RecommendationApiResponse(
                RecommendationStatus.RECOMMENDED, null, RecommendationPurpose.PICKUP, "logreg-synthetic-v1",
                new RecommendationApiResponse.ScoredStation(77L, 0.9, 236, 13, 17), List.of(), null));

        StationRecommendationResponse response = service.recommend(LAT, LNG, RecommendationPurpose.PICKUP);

        assertThat(response.source()).isEqualTo(RecommendationSource.FALLBACK);
    }

    @Test
    void usaElRespaldoSiElModeloRespondeParaOtroProposito() {
        givenCandidates(cerrito());
        when(recommendationClient.recommend(any())).thenReturn(modelResponse());

        StationRecommendationResponse response = service.recommend(LAT, LNG, RecommendationPurpose.DROPOFF);

        assertThat(response.source()).isEqualTo(RecommendationSource.FALLBACK);
        assertThat(response.purpose()).isEqualTo(RecommendationPurpose.DROPOFF);
    }

    @Test
    void elRespaldoDeDevolucionMiraLosAnclajesLibresYNoLasBicicletas() {
        // La más cercana está llena: tiene bicicletas de sobra pero ningún anclaje libre.
        NearbyStationResponse llena = new NearbyStationResponse(44L, "DIAGONAL NORTE",
                "Dirección de DIAGONAL NORTE", new BigDecimal("-34.6046"), new BigDecimal("-58.3798"),
                193, 18, 18, 0);
        givenCandidates(llena, cerrito());
        when(recommendationClient.recommend(any()))
                .thenThrow(new RecommendationUnavailableException("caído"));

        StationRecommendationResponse response = service.recommend(LAT, LNG, RecommendationPurpose.DROPOFF);

        assertThat(response.station().stationName()).isEqualTo("CERRITO");
        assertThat(response.message()).contains("anclajes libres");
    }

    @Test
    void noRecomiendaNadaSiNingunaCandidataTieneElRecursoYElModeloNoResponde() {
        givenCandidates(sinBicicletas());
        when(recommendationClient.recommend(any()))
                .thenThrow(new RecommendationUnavailableException("caído"));

        StationRecommendationResponse response = service.recommend(LAT, LNG, RecommendationPurpose.PICKUP);

        assertThat(response.status()).isEqualTo(RecommendationStatus.NO_RECOMMENDATION);
        assertThat(response.reason()).isEqualTo(NoRecommendationReason.NO_VIABLE_STATION);
        assertThat(response.source()).isEqualTo(RecommendationSource.FALLBACK);
        assertThat(response.station()).isNull();
        assertThat(response.message())
                .isEqualTo("Ninguna estación cerca tiene bicicletas disponibles en este momento.");
    }

    @Test
    void devuelveComoMuchoDosAlternativasEnElRespaldo() {
        givenCandidates(diagonalNorte(), cerrito(), otra(101L, "LAVALLE"), otra(102L, "TRIBUNALES"));
        when(recommendationClient.recommend(any()))
                .thenThrow(new RecommendationUnavailableException("caído"));

        StationRecommendationResponse response = service.recommend(LAT, LNG, RecommendationPurpose.PICKUP);

        assertThat(response.alternatives()).hasSize(2);
    }

    private void givenCandidates(NearbyStationResponse... stations) {
        when(nearbyStationService.findNearby(anyDouble(), anyDouble(), eq(1000), eq(15)))
                .thenReturn(List.of(stations));
    }

    private RecommendationApiResponse modelResponse() {
        return new RecommendationApiResponse(
                RecommendationStatus.RECOMMENDED, null, RecommendationPurpose.PICKUP, "logreg-synthetic-v1",
                new RecommendationApiResponse.ScoredStation(77L, 0.768498, 236, 13, 17),
                List.of(new RecommendationApiResponse.ScoredStation(44L, 0.151128, 193, 1, 17)),
                new RecommendationApiResponse.Explanation(ExplanationCode.NEAREST_LOW_AVAILABILITY,
                        new RecommendationApiResponse.StationRef(44L, 193, 1, 17),
                        List.of(new RecommendationApiResponse.Factor("secure_units", 2.759))));
    }

    private NearbyStationResponse diagonalNorte() {
        return new NearbyStationResponse(44L, "DIAGONAL NORTE", "Dirección de DIAGONAL NORTE",
                new BigDecimal("-34.6046"), new BigDecimal("-58.3798"), 193, 18, 1, 17);
    }

    private NearbyStationResponse cerrito() {
        return new NearbyStationResponse(77L, "CERRITO", "Dirección de CERRITO",
                new BigDecimal("-34.6026"), new BigDecimal("-58.3838"), 236, 30, 13, 17);
    }

    private NearbyStationResponse sinBicicletas() {
        return new NearbyStationResponse(12L, "OBELISCO", "Dirección de OBELISCO",
                new BigDecimal("-34.6037"), new BigDecimal("-58.3816"), 80, 20, 0, 20);
    }

    private NearbyStationResponse otra(long id, String name) {
        return new NearbyStationResponse(id, name, "Dirección de " + name,
                new BigDecimal("-34.6010"), new BigDecimal("-58.3850"), 400, 20, 6, 14);
    }
}
