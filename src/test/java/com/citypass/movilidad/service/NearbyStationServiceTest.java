package com.citypass.movilidad.service;

import com.citypass.movilidad.config.NearbyStationProperties;
import com.citypass.movilidad.dto.NearbyStationResponse;
import com.citypass.movilidad.repository.StationRepository;
import com.citypass.movilidad.repository.projection.NearbyStationProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NearbyStationServiceTest {

    private static final double LAT = -34.6037;
    private static final double LNG = -58.3816;

    private StationRepository stationRepository;
    private StationAvailabilityService availabilityService;
    private NearbyStationService service;

    @BeforeEach
    void setUp() {
        stationRepository = mock(StationRepository.class);
        availabilityService = mock(StationAvailabilityService.class);
        service = new NearbyStationService(stationRepository, availabilityService,
                new NearbyStationProperties(500, 5000, 10, 50));
    }

    @Test
    void usaElRadioYElLimiteConfiguradosCuandoNoSeIndican() {
        givenStations();

        service.findNearby(LAT, LNG, null, null);

        assertThat(capturedRadius()).isEqualTo(500);
        assertThat(capturedMaxResults()).isEqualTo(10);
    }

    @Test
    void recortaElRadioYElLimitePedidosContraLosTopesConfigurados() {
        givenStations();

        service.findNearby(LAT, LNG, 99_999, 999);

        assertThat(capturedRadius()).isEqualTo(5000);
        assertThat(capturedMaxResults()).isEqualTo(50);
    }

    @Test
    void respetaElRadioYElLimitePedidosCuandoEstanDentroDeLosTopes() {
        givenStations();

        service.findNearby(LAT, LNG, 1200, 5);

        assertThat(capturedRadius()).isEqualTo(1200);
        assertThat(capturedMaxResults()).isEqualTo(5);
    }

    @Test
    void conservaElOrdenPorDistanciaQueDevolvioLaBase() {
        givenStations(station(1L, "Cerca", 120.4, 10), station(2L, "Lejos", 480.6, 8));
        when(availabilityService.availabilityFor(anyMap())).thenReturn(Map.of(
                1L, new StationAvailability(3, 7),
                2L, new StationAvailability(1, 7)));

        List<NearbyStationResponse> result = service.findNearby(LAT, LNG, null, null);

        assertThat(result).extracting(NearbyStationResponse::stationName).containsExactly("Cerca", "Lejos");
        assertThat(result).extracting(NearbyStationResponse::distanceMeters).containsExactly(120, 481);
    }

    @Test
    void incluyeLaDisponibilidadDeCadaEstacion() {
        givenStations(station(1L, "Obelisco", 100.0, 20));
        when(availabilityService.availabilityFor(anyMap())).thenReturn(Map.of(1L, new StationAvailability(6, 14)));

        NearbyStationResponse response = service.findNearby(LAT, LNG, null, null).getFirst();

        assertThat(response.stationId()).isEqualTo(1L);
        assertThat(response.capacity()).isEqualTo(20);
        assertThat(response.availableBikes()).isEqualTo(6);
        assertThat(response.availableSlots()).isEqualTo(14);
        assertThat(response.latitude()).isEqualByComparingTo("-34.6037000");
    }

    @Test
    void devuelveListaVaciaSinConsultarDisponibilidadCuandoNoHayEstacionesCerca() {
        givenStations();

        assertThat(service.findNearby(LAT, LNG, null, null)).isEmpty();
        verify(availabilityService, never()).availabilityFor(anyMap());
    }

    @Test
    void consultaSolamenteEstacionesActivas() {
        givenStations();

        service.findNearby(LAT, LNG, null, null);

        verify(stationRepository).findNearby(anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyInt(), eq("ACTIVE"), anyInt());
    }

    private void givenStations(NearbyStationProjection... stations) {
        when(stationRepository.findNearby(anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyInt(), anyString(), anyInt()))
                .thenReturn(List.of(stations));
    }

    private int capturedRadius() {
        ArgumentCaptor<Integer> radius = ArgumentCaptor.forClass(Integer.class);
        verify(stationRepository).findNearby(anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), radius.capture(), anyString(), anyInt());
        return radius.getValue();
    }

    private int capturedMaxResults() {
        ArgumentCaptor<Integer> maxResults = ArgumentCaptor.forClass(Integer.class);
        verify(stationRepository).findNearby(anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                anyDouble(), anyDouble(), anyInt(), anyString(), maxResults.capture());
        return maxResults.getValue();
    }

    private NearbyStationProjection station(Long id, String name, double distanceMeters, int capacity) {
        NearbyStationProjection projection = mock(NearbyStationProjection.class);
        when(projection.getId()).thenReturn(id);
        when(projection.getName()).thenReturn(name);
        when(projection.getAddress()).thenReturn("Dirección de " + name);
        when(projection.getLatitude()).thenReturn(new BigDecimal("-34.6037000"));
        when(projection.getLongitude()).thenReturn(new BigDecimal("-58.3816000"));
        when(projection.getCapacity()).thenReturn(capacity);
        when(projection.getDistanceMeters()).thenReturn(distanceMeters);
        return projection;
    }
}
