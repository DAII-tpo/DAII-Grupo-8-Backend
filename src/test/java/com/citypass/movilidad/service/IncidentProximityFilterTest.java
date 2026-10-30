package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.NearbyStationResponse;
import com.citypass.movilidad.model.CityIncident;
import com.citypass.movilidad.repository.CityIncidentRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class IncidentProximityFilterTest {
    private final CityIncidentRepository incidents = mock(CityIncidentRepository.class);
    private final IncidentProximityFilter filter = new IncidentProximityFilter(incidents, 300);

    private NearbyStationResponse station(long id, double lat, double lng) {
        return new NearbyStationResponse(id, "Estacion " + id, "Direccion",
                BigDecimal.valueOf(lat), BigDecimal.valueOf(lng), 100, 15, 5, 10);
    }

    private CityIncident incident(double lat, double lng) {
        return CityIncident.builder().latitude(BigDecimal.valueOf(lat))
                .longitude(BigDecimal.valueOf(lng)).active(true).build();
    }

    @Test
    void descartaSoloEstacionesDentroDe300Metros() {
        NearbyStationResponse cercana = station(1, -34.6001, -58.4000);
        NearbyStationResponse segura = station(2, -34.6200, -58.4200);
        when(incidents.findByActiveTrue()).thenReturn(List.of(incident(-34.6000, -58.4000)));

        IncidentProximityFilter.FilterResult result = filter.filter(List.of(cercana, segura));

        assertThat(result.safe()).containsExactly(segura);
        assertThat(result.discarded()).isEqualTo(1);
    }

    @Test
    void siTodasEstanAfectadasNoRecomiendaUnaInsegura() {
        NearbyStationResponse a = station(1, -34.6001, -58.4000);
        NearbyStationResponse b = station(2, -34.6002, -58.4000);
        when(incidents.findByActiveTrue()).thenReturn(List.of(incident(-34.6000, -58.4000)));

        IncidentProximityFilter.FilterResult result = filter.filter(List.of(a, b));

        assertThat(result.safe()).isEmpty();
        assertThat(result.discarded()).isEqualTo(2);
    }

    @Test
    void sinIncidentesDevuelveTodasLasCandidatas() {
        NearbyStationResponse a = station(1, -34.6, -58.4);
        when(incidents.findByActiveTrue()).thenReturn(List.of());
        assertThat(filter.filter(List.of(a)).safe()).containsExactly(a);
    }
    @Test
    void sinCandidatasNoConsultaIncidentes() {
        assertThat(filter.apply(List.of())).isEmpty();
        verifyNoInteractions(incidents);
    }

    @Test
    void respetaRadioConfiguradoYCuentaUnaEstacionUnaSolaVez() {
        var configured = new IncidentProximityFilter(incidents, 600);
        assertThat(configured.radiusMeters()).isEqualTo(600);
        when(incidents.findByActiveTrue()).thenReturn(List.of(incident(-34.6, -58.4), incident(-34.6, -58.4)));
        var candidate = station(1, -34.604, -58.4);
        assertThat(filter.filter(List.of(candidate)).discarded()).isZero();
        assertThat(configured.filter(List.of(candidate)).discarded()).isEqualTo(1);
    }

}
