package com.citypass.movilidad.service;

import com.citypass.movilidad.client.RecommendationClient;
import com.citypass.movilidad.client.RecommendationUnavailableException;
import com.citypass.movilidad.config.RecommendationProperties;
import com.citypass.movilidad.dto.NearbyStationResponse;
import com.citypass.movilidad.model.enums.RecommendationStatus;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CulturalDestinationDemandTest {
    private final NearbyStationService nearby = mock(NearbyStationService.class);
    private final RecommendationClient client = mock(RecommendationClient.class);
    private final IncidentProximityFilter filter = mock(IncidentProximityFilter.class);
    private final StationRecommendationService service = new StationRecommendationService(nearby, client,
            new RecommendationMessageComposer(), new RecommendationProperties("http://localhost:8000", 1000, 2000, 1000, 15, false), filter);
    private NearbyStationResponse station(long id, int distance, int slots) {
        return new NearbyStationResponse(id, "Estación " + id, "Calle", BigDecimal.valueOf(-34.6),
                BigDecimal.valueOf(-58.4), distance, 30, 30 - slots, slots);
    }
    private void fallback() {
        when(client.recommend(any())).thenThrow(new RecommendationUnavailableException("offline"));
    }
    @Test void prefiereCubrirLaDemandaAntesQueLaEstacionMasCercana() {
        fallback();
        var result = service.recommendDestinationFromCandidates(-34.6, -58.4, 10,
                List.of(station(1, 50, 2), station(2, 300, 15), station(3, 500, 20)));
        assertThat(result.station().stationId()).isEqualTo(2L);
        assertThat(result.message()).contains("10 asistentes").contains("no reserva anclajes");
    }
    @Test void siNingunaCubreLaDemandaPriorizaMayorDisponibilidad() {
        fallback();
        var result = service.recommendDestinationFromCandidates(-34.6, -58.4, 100,
                List.of(station(1, 50, 2), station(2, 300, 15), station(3, 500, 15)));
        assertThat(result.station().stationId()).isEqualTo(2L);
        assertThat(result.alternatives()).extracting("stationId").containsExactly(3L);
    }
    @Test void sinDemandaConocidaConservaElCriterioGeneral() {
        fallback();
        var result = service.recommendDestinationFromCandidates(-34.6, -58.4, 0,
                List.of(station(1, 50, 2), station(2, 300, 15)));
        assertThat(result.station().stationId()).isEqualTo(1L);
        assertThat(result.message()).doesNotContain("Demanda de referencia");
    }
    @Test void sinAnclajesNoRecomiendaYSinCandidatasNoLlamaALaIA() {
        fallback();
        var full = service.recommendDestinationFromCandidates(-34.6, -58.4, 100, List.of(station(1, 50, 0)));
        assertThat(full.status()).isEqualTo(RecommendationStatus.NO_RECOMMENDATION);
        clearInvocations(client);
        var empty = service.recommendDestinationFromCandidates(-34.6, -58.4, 100, List.of());
        assertThat(empty.status()).isEqualTo(RecommendationStatus.NO_RECOMMENDATION);
        verifyNoInteractions(client);
    }
    @Test void filtraIncidentesAntesDeAplicarDemandaYCargaUnSoloSnapshot() {
        fallback();
        var unsafe = station(1, 50, 30);
        var safe = station(2, 300, 2);
        when(nearby.findNearby(anyDouble(), anyDouble(), anyInt(), anyInt())).thenReturn(List.of(unsafe, safe));
        when(filter.apply(anyList())).thenReturn(List.of(safe));
        var result = service.recommendDestination(-34.6, -58.4, 30);
        assertThat(result.station().stationId()).isEqualTo(2L);
        verify(nearby).findNearby(-34.6, -58.4, 1000, 15);
        verify(filter).apply(List.of(unsafe, safe));
    }
}
