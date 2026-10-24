package com.citypass.movilidad.service;

import com.citypass.movilidad.config.GatewayProperties;
import com.citypass.movilidad.dto.NearbyStationResponse;
import com.citypass.movilidad.model.CityIncident;
import com.citypass.movilidad.model.enums.IncidentSource;
import com.citypass.movilidad.repository.CityIncidentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IncidentProximityFilterTest {

    private CityIncidentRepository incidentRepository;
    private IncidentProximityFilter filter;

    // Dos estaciones a ~1,2 km entre sí: Obelisco y Plaza de Mayo.
    private final NearbyStationResponse obelisco = station(1L, "-34.6037", "-58.3816");
    private final NearbyStationResponse plazaDeMayo = station(2L, "-34.6083", "-58.3712");

    @BeforeEach
    void setUp() {
        incidentRepository = mock(CityIncidentRepository.class);
        filter = new IncidentProximityFilter(incidentRepository, properties(300));
    }

    @Test
    void sinIncidentesActivosDevuelveLasCandidatasTalCual() {
        when(incidentRepository.findByActiveTrue()).thenReturn(List.of());

        assertThat(filter.apply(List.of(obelisco, plazaDeMayo))).containsExactly(obelisco, plazaDeMayo);
    }

    @Test
    void descartaLasEstacionesDentroDelRadioDeUnIncidente() {
        // Corte a ~100 m del Obelisco.
        when(incidentRepository.findByActiveTrue()).thenReturn(List.of(incident("-34.6045", "-58.3820")));

        assertThat(filter.apply(List.of(obelisco, plazaDeMayo))).containsExactly(plazaDeMayo);
    }

    @Test
    void conservaElOrdenPorDistanciaDeLasQueQuedan() {
        NearbyStationResponse tercera = station(3L, "-34.6100", "-58.3700");
        when(incidentRepository.findByActiveTrue()).thenReturn(List.of(incident("-34.6045", "-58.3820")));

        assertThat(filter.apply(List.of(obelisco, plazaDeMayo, tercera))).containsExactly(plazaDeMayo, tercera);
    }

    @Test
    void siTodasQuedanCercaDeIncidentesLasDevuelveIgual() {
        when(incidentRepository.findByActiveTrue()).thenReturn(List.of(
                incident("-34.6045", "-58.3820"), incident("-34.6083", "-58.3712")));

        assertThat(filter.apply(List.of(obelisco, plazaDeMayo))).containsExactly(obelisco, plazaDeMayo);
    }

    @Test
    void conCandidatasVaciasNoConsultaIncidentes() {
        assertThat(filter.apply(List.of())).isEmpty();
    }

    private static NearbyStationResponse station(long id, String lat, String lng) {
        return new NearbyStationResponse(id, "Estación " + id, "Dirección " + id,
                new BigDecimal(lat), new BigDecimal(lng), 100, 20, 5, 15);
    }

    private static CityIncident incident(String lat, String lng) {
        return CityIncident.builder()
                .source(IncidentSource.RECLAMOS).externalId(lat + lng).category("TRANSITO")
                .latitude(new BigDecimal(lat)).longitude(new BigDecimal(lng)).active(true)
                .build();
    }

    static GatewayProperties properties(int radiusMeters) {
        return new GatewayProperties(true, "http://gateway", "http://token", "svc-movilidad", "secret",
                "com.citypass.movilidad", "webhook-secret", 1000, 1000, 5000, 3, radiusMeters);
    }
}
