package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.StationAvailabilityResponse;
import com.citypass.movilidad.exception.ResourceNotFoundException;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.enums.BikeStatus;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.repository.BikeRepository;
import com.citypass.movilidad.repository.StationRepository;
import com.citypass.movilidad.repository.projection.StationBikeCountProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StationAvailabilityServiceTest {

    private StationRepository stationRepository;
    private BikeRepository bikeRepository;
    private StationAvailabilityService service;

    @BeforeEach
    void setUp() {
        stationRepository = mock(StationRepository.class);
        bikeRepository = mock(BikeRepository.class);
        service = new StationAvailabilityService(stationRepository, bikeRepository);
    }

    @Test
    void fallaCuandoLaEstacionNoExisteOFueDadaDeBaja() {
        when(stationRepository.findByIdAndDeletedAtIsNull(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByStationId(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void respondeConCeroBicicletasCuandoLaEstacionEstaVacia() {
        givenStation(1L, 20, StationStatus.ACTIVE);
        givenCounts();

        StationAvailabilityResponse response = service.getByStationId(1L);

        assertThat(response.availableBikes()).isZero();
        assertThat(response.availableSlots()).isEqualTo(20);
        assertThat(response.capacity()).isEqualTo(20);
        assertThat(response.checkedAt()).isNotNull();
    }

    @Test
    void cuentaSolamenteLasBicicletasDisponibles() {
        givenStation(1L, 20, StationStatus.ACTIVE);
        // 8 bicicletas presentes en la estación, de las cuales solo 5 se pueden retirar.
        givenCounts(counts(1L, 8L, 5L));

        StationAvailabilityResponse response = service.getByStationId(1L);

        assertThat(response.availableBikes()).isEqualTo(5);
        assertThat(response.availableSlots()).isEqualTo(12);
    }

    @Test
    void lasBicicletasNoDisponiblesOcupanAnclajePeroNoSeOfrecen() {
        givenStation(1L, 10, StationStatus.ACTIVE);
        // Las 10 presentes ocupan todos los anclajes aunque ninguna esté AVAILABLE (ej. MAINTENANCE).
        givenCounts(counts(1L, 10L, 0L));

        StationAvailabilityResponse response = service.getByStationId(1L);

        assertThat(response.availableBikes()).isZero();
        assertThat(response.availableSlots()).isZero();
    }

    @Test
    void nuncaDevuelveAnclajesNegativosSiHayMasBicicletasQueCapacidad() {
        givenStation(1L, 5, StationStatus.ACTIVE);
        givenCounts(counts(1L, 9L, 9L));

        StationAvailabilityResponse response = service.getByStationId(1L);

        assertThat(response.availableBikes()).isEqualTo(9);
        assertThat(response.availableSlots()).isZero();
    }

    @Test
    void respondeIgualParaEstacionesInactivasInformandoSuEstado() {
        givenStation(1L, 12, StationStatus.INACTIVE);
        givenCounts(counts(1L, 3L, 3L));

        StationAvailabilityResponse response = service.getByStationId(1L);

        assertThat(response.status()).isEqualTo(StationStatus.INACTIVE);
        assertThat(response.availableBikes()).isEqualTo(3);
    }

    @Test
    void resuelveVariasEstacionesIncluyendoUnaSinBicicletas() {
        Station conBicis = station(1L, 20, StationStatus.ACTIVE);
        Station sinBicis = station(2L, 15, StationStatus.ACTIVE);
        when(stationRepository.findAllByDeletedAtIsNullOrderByName()).thenReturn(List.of(conBicis, sinBicis));
        givenCounts(counts(1L, 6L, 4L));

        List<StationAvailabilityResponse> responses = service.getAll();

        assertThat(responses).hasSize(2);
        assertThat(responses.getFirst().availableBikes()).isEqualTo(4);
        assertThat(responses.getFirst().availableSlots()).isEqualTo(14);
        assertThat(responses.getLast().availableBikes()).isZero();
        assertThat(responses.getLast().availableSlots()).isEqualTo(15);
    }

    @Test
    void noConsultaBicicletasCuandoNoHayEstaciones() {
        when(stationRepository.findAllByDeletedAtIsNullOrderByName()).thenReturn(List.of());

        assertThat(service.getAll()).isEmpty();
    }

    @Test
    void noConsultaBicicletasSiSeLePideLaDisponibilidadDeUnConjuntoVacio() {
        assertThat(service.availabilityFor(java.util.Map.of())).isEmpty();
        verify(bikeRepository, never()).countBikesByStationIds(anyCollection(), any(BikeStatus.class));
    }

    private void givenStation(Long id, int capacity, StationStatus status) {
        when(stationRepository.findByIdAndDeletedAtIsNull(id))
                .thenReturn(Optional.of(station(id, capacity, status)));
    }

    private void givenCounts(StationBikeCountProjection... counts) {
        when(bikeRepository.countBikesByStationIds(anyCollection(), any(BikeStatus.class)))
                .thenReturn(List.of(counts));
    }

    private Station station(Long id, int capacity, StationStatus status) {
        Station station = new Station();
        station.setName("Estación " + id);
        station.setCapacity(capacity);
        station.setStatus(status);
        ReflectionTestUtils.setField(station, "id", id);
        return station;
    }

    private StationBikeCountProjection counts(Long stationId, Long totalBikes, Long availableBikes) {
        StationBikeCountProjection projection = mock(StationBikeCountProjection.class);
        when(projection.getStationId()).thenReturn(stationId);
        when(projection.getTotalBikes()).thenReturn(totalBikes);
        when(projection.getAvailableBikes()).thenReturn(availableBikes);
        return projection;
    }
}
