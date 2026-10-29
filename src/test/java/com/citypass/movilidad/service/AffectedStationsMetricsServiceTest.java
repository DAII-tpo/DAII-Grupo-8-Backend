package com.citypass.movilidad.service;

import com.citypass.movilidad.model.CityIncident;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.repository.CityIncidentRepository;
import com.citypass.movilidad.repository.StationRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AffectedStationsMetricsServiceTest {
    private final StationRepository stations = mock(StationRepository.class);
    private final CityIncidentRepository incidents = mock(CityIncidentRepository.class);
    private final AffectedStationsMetricsService metrics = new AffectedStationsMetricsService(stations, incidents, 300);

    private Station station(long id, double lat, double lng) {
        return Station.builder().id(id).latitude(BigDecimal.valueOf(lat))
                .longitude(BigDecimal.valueOf(lng)).status(StationStatus.ACTIVE).build();
    }
    private CityIncident incident(double lat, double lng) {
        return CityIncident.builder().latitude(BigDecimal.valueOf(lat))
                .longitude(BigDecimal.valueOf(lng)).active(true).build();
    }

    @Test
    void noDuplicaEstacionCubiertaPorDosIncidentes() {
        when(incidents.findByActiveTrue()).thenReturn(List.of(
                incident(-34.600, -58.400), incident(-34.6002, -58.400)));
        when(stations.findByStatusAndDeletedAtIsNull(StationStatus.ACTIVE))
                .thenReturn(List.of(station(1, -34.6001, -58.400), station(2, -34.620, -58.420)));
        assertThat(metrics.calculate()).isEqualTo(new AffectedStationsMetricsService.Snapshot(1, 2));
    }

    @Test
    void sinIncidentesNoHayEstacionesAfectadas() {
        when(incidents.findByActiveTrue()).thenReturn(List.of());
        assertThat(metrics.calculate()).isEqualTo(new AffectedStationsMetricsService.Snapshot(0, 0));
        verifyNoInteractions(stations);
    }

    @Test
    void noCuentaEstacionesFueraDelRadio() {
        when(incidents.findByActiveTrue()).thenReturn(List.of(incident(-34.60, -58.40)));
        when(stations.findByStatusAndDeletedAtIsNull(StationStatus.ACTIVE))
                .thenReturn(List.of(station(1, -34.62, -58.42)));
        assertThat(metrics.calculate().affectedStations()).isZero();
    }
}
