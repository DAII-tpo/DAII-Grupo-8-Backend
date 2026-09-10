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
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Test
    void rejectsDuplicateCodeAndInvalidInitialStates() {
        when(bikeRepository.existsByCode("BIKE-1")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new BikeCreateRequest(
                "BIKE-1", null, BikeStatus.AVAILABLE, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("código");

        when(bikeRepository.existsByCode("BIKE-1")).thenReturn(false);
        assertThatThrownBy(() -> service.create(new BikeCreateRequest(
                "BIKE-1", null, BikeStatus.IN_USE, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("IN_USE");
        assertThatThrownBy(() -> service.create(new BikeCreateRequest(
                "BIKE-1", null, null, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("estación");
    }

    @Test
    void createsNonAvailableBikeWithoutStation() {
        var response = service.create(new BikeCreateRequest(
                "BIKE-2", null, BikeStatus.MAINTENANCE, " ", null));

        assertThat(response.status()).isEqualTo(BikeStatus.MAINTENANCE);
        assertThat(response.stationId()).isNull();
        assertThat(response.model()).isNull();
    }

    @Test
    void rejectsCreationWhenStationIsFull() {
        Station station = activeStation(10L, 1);
        when(stationRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(station));
        when(bikeRepository.countByStationIdAndDeletedAtIsNull(10L)).thenReturn(1L);

        assertThatThrownBy(() -> service.create(new BikeCreateRequest(
                "BIKE-2", 10L, BikeStatus.AVAILABLE, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("capacidad");
    }

    @Test
    void findsBikeByIdAndReportsMissingBike() {
        Bike bike = bike(BikeStatus.AVAILABLE, activeStation(10L, 20));
        when(bikeRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(bike));

        assertThat(service.findById(1L).code()).isEqualTo("BIKE-1");
        assertThatThrownBy(() -> service.findById(2L))
                .isInstanceOf(com.citypass.movilidad.exception.ResourceNotFoundException.class);
    }

    @Test
    void findsBikesByStationAndAvailableBikesAtStation() {
        Station station = activeStation(10L, 20);
        station.setStatus(StationStatus.MAINTENANCE);
        Bike bike = bike(BikeStatus.AVAILABLE, station);
        when(stationRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(station));
        when(bikeRepository.findAllByStationIdAndDeletedAtIsNullOrderByCode(10L)).thenReturn(List.of(bike));
        when(bikeRepository.findAllByStationIdAndStatusAndDeletedAtIsNullOrderByCode(10L, BikeStatus.AVAILABLE))
                .thenReturn(List.of(bike));

        assertThat(service.findByStation(10L)).hasSize(1);
        station.setStatus(StationStatus.ACTIVE);
        assertThat(service.findAvailable(10L)).hasSize(1);
    }

    @Test
    void changesMaintenanceBikeToAvailableAndRecordsHistory() {
        Bike bike = bike(BikeStatus.MAINTENANCE, activeStation(10L, 20));
        when(bikeRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(bike));

        var response = service.changeStatus(1L,
                new BikeStatusChangeRequest(BikeStatus.AVAILABLE, " lista "));

        assertThat(response.status()).isEqualTo(BikeStatus.AVAILABLE);
        assertThat(response.lastMaintenanceAt()).isNotNull();
        verify(historyRepository).save(any(BikeStatusHistory.class));
    }

    @Test
    void rejectsAvailableStatusWithoutStation() {
        Bike bike = bike(BikeStatus.MAINTENANCE, null);
        when(bikeRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(bike));

        assertThatThrownBy(() -> service.changeStatus(1L,
                new BikeStatusChangeRequest(BikeStatus.AVAILABLE, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("estación");
    }

    @Test
    void transfersBikeToActiveStation() {
        Station origin = activeStation(10L, 20);
        Station destination = activeStation(20L, 20);
        Bike bike = bike(BikeStatus.MAINTENANCE, origin);
        when(bikeRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(bike));
        when(stationRepository.findByIdAndDeletedAtIsNull(20L)).thenReturn(Optional.of(destination));

        assertThat(service.transfer(1L, 20L).stationId()).isEqualTo(20L);
    }

    @Test
    void rejectsTransferToSameOrInactiveStation() {
        Station origin = activeStation(10L, 20);
        Bike bike = bike(BikeStatus.AVAILABLE, origin);
        when(bikeRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(bike));
        when(stationRepository.findByIdAndDeletedAtIsNull(10L)).thenReturn(Optional.of(origin));

        assertThatThrownBy(() -> service.transfer(1L, 10L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ya se encuentra");

        Station inactive = activeStation(20L, 20);
        inactive.setStatus(StationStatus.INACTIVE);
        when(stationRepository.findByIdAndDeletedAtIsNull(20L)).thenReturn(Optional.of(inactive));
        assertThatThrownBy(() -> service.transfer(1L, 20L))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("habilitada");
    }

    @Test
    void rejectsDeletingBikeInUseAndDoesNotDuplicateOutOfServiceHistory() {
        Bike inUse = bike(BikeStatus.IN_USE, null);
        when(bikeRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(inUse));
        assertThatThrownBy(() -> service.delete(1L)).isInstanceOf(BusinessRuleException.class);

        Bike alreadyOut = bike(BikeStatus.OUT_OF_SERVICE, null);
        when(bikeRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(alreadyOut));
        service.delete(2L);
        verifyNoInteractions(historyRepository);
    }

    @Test
    void returnsStatusHistoryIncludingActorWhenPresent() {
        Bike bike = bike(BikeStatus.OUT_OF_SERVICE, null);
        BikeStatusHistory item = new BikeStatusHistory();
        item.setBike(bike);
        item.setPreviousStatus(BikeStatus.AVAILABLE);
        item.setNewStatus(BikeStatus.OUT_OF_SERVICE);
        item.setReason("baja");
        when(bikeRepository.findById(1L)).thenReturn(Optional.of(bike));
        when(historyRepository.findAllByBikeIdOrderByChangedAtDesc(1L)).thenReturn(List.of(item));

        assertThat(service.history(1L)).singleElement().satisfies(history -> {
            assertThat(history.previousStatus()).isEqualTo(BikeStatus.AVAILABLE);
            assertThat(history.newStatus()).isEqualTo(BikeStatus.OUT_OF_SERVICE);
            assertThat(history.changedByUserId()).isNull();
        });
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
