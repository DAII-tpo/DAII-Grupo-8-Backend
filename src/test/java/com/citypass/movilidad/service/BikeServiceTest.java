package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.BikeCreateRequest;
import com.citypass.movilidad.dto.BikeStatusChangeRequest;
import com.citypass.movilidad.exception.BusinessRuleException;
import com.citypass.movilidad.model.Bike;
import com.citypass.movilidad.model.BikeStatusHistory;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.enums.BikeStatus;
import com.citypass.movilidad.model.enums.StationStatus;
import com.citypass.movilidad.repository.BikeRepository;
import com.citypass.movilidad.repository.BikeStatusHistoryRepository;
import com.citypass.movilidad.repository.StationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BikeServiceTest {

    private BikeRepository bikeRepository;
    private StationRepository stationRepository;
    private BikeStatusHistoryRepository historyRepository;
    private BikeService service;

    @BeforeEach
    void setUp() {
        bikeRepository = mock(BikeRepository.class);
        stationRepository = mock(StationRepository.class);
        historyRepository = mock(BikeStatusHistoryRepository.class);
        service = new BikeService(bikeRepository, stationRepository, historyRepository);
        when(bikeRepository.save(any(Bike.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsAvailableBikeAndRecordsInitialHistory() {
        Station station = activeStation(10L, 20);
        when(stationRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(station));

        var response = service.create(new BikeCreateRequest(
                " BIKE-10 ", 10L, BikeStatus.AVAILABLE, "Urbana", LocalDate.now()));

        assertThat(response.code()).isEqualTo("BIKE-10");
        assertThat(response.status()).isEqualTo(BikeStatus.AVAILABLE);
        verify(historyRepository).save(any(BikeStatusHistory.class));
    }

    @Test
    void availabilityNeverReturnsOtherStatuses() {
        Bike available = bike(BikeStatus.AVAILABLE, activeStation(10L, 20));
        when(bikeRepository.findAllByStatusAndDeletedAtIsNullOrderByCode(BikeStatus.AVAILABLE))
                .thenReturn(List.of(available));

        assertThat(service.findAvailable(null)).allMatch(item -> item.status() == BikeStatus.AVAILABLE);
    }

    @Test
    void rejectsAdministrativeStatusChangeWhileBikeIsInUse() {
        Bike bike = bike(BikeStatus.IN_USE, null);
        when(bikeRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(bike));

        assertThatThrownBy(() -> service.changeStatus(1L,
                new BikeStatusChangeRequest(BikeStatus.AVAILABLE, "devolución")))
                .isInstanceOf(BusinessRuleException.class);
        verify(historyRepository, never()).save(any());
    }

    @Test
    void rejectsTransferWhileBikeIsInUse() {
        Bike bike = bike(BikeStatus.IN_USE, null);
        when(bikeRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(bike));

        assertThatThrownBy(() -> service.transfer(1L, 20L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("IN_USE");
        verify(stationRepository, never()).findByIdAndDeletedAtIsNull(any());
    }

    @Test
    void softDeleteChangesStatusAndRecordsHistory() {
        Bike bike = bike(BikeStatus.AVAILABLE, activeStation(10L, 20));
        when(bikeRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(bike));

        service.delete(1L);

        assertThat(bike.getDeletedAt()).isNotNull();
        assertThat(bike.getStatus()).isEqualTo(BikeStatus.OUT_OF_SERVICE);
        verify(historyRepository).save(any(BikeStatusHistory.class));
    }

    private Bike bike(BikeStatus status, Station station) {
        Bike bike = new Bike();
        bike.setCode("BIKE-1");
        bike.setStatus(status);
        bike.setStation(station);
        return bike;
    }

    private Station activeStation(Long id, int capacity) {
        Station station = new Station();
        station.setStatus(StationStatus.ACTIVE);
        station.setCapacity(capacity);
        try {
            var field = Station.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(station, id);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
        return station;
    }
}
