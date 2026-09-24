package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.PagedResponse;
import com.citypass.movilidad.dto.TripEndRequest;
import com.citypass.movilidad.dto.TripResponse;
import com.citypass.movilidad.dto.TripStartRequest;
import com.citypass.movilidad.exception.BusinessRuleException;
import com.citypass.movilidad.exception.ResourceNotFoundException;
import com.citypass.movilidad.model.Bike;
import com.citypass.movilidad.model.Station;
import com.citypass.movilidad.model.Trip;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.model.enums.BikeStatus;
import com.citypass.movilidad.model.enums.TripStatus;
import com.citypass.movilidad.model.enums.UserStatus;
import com.citypass.movilidad.repository.TripRepository;
import com.citypass.movilidad.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TripServiceTest {

    private TripRepository tripRepository;
    private UserRepository userRepository;
    private BikeService bikeService;
    private TripEventPublisher eventPublisher;
    private TripService service;

    @BeforeEach
    void setUp() {
        tripRepository = mock(TripRepository.class);
        userRepository = mock(UserRepository.class);
        bikeService = mock(BikeService.class);
        eventPublisher = mock(TripEventPublisher.class);
        service = new TripService(tripRepository, userRepository, bikeService, eventPublisher);
        when(tripRepository.save(any(Trip.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // MOV-026 — iniciar viaje

    @Test
    void startsTripWithAvailableBikeFromItsStation() {
        User user = user(1L, UserStatus.ACTIVE);
        Station origin = station(10L, "Centro");
        Bike bike = bike(5L, BikeStatus.AVAILABLE, origin);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(tripRepository.findByUserIdAndStatus(1L, TripStatus.ACTIVE)).thenReturn(Optional.empty());
        when(bikeService.lockActiveBike(5L)).thenReturn(bike);
        when(bikeService.checkOutForTrip(bike, user)).thenReturn(origin);

        TripResponse response = service.startTrip(1L, new TripStartRequest(5L));

        assertThat(response.status()).isEqualTo(TripStatus.ACTIVE);
        assertThat(response.bikeId()).isEqualTo(5L);
        assertThat(response.bikeCode()).isEqualTo("BIKE-5");
        assertThat(response.originStationId()).isEqualTo(10L);
        assertThat(response.originStationName()).isEqualTo("Centro");
        assertThat(response.startedAt()).isNotNull();
        assertThat(response.destinationStationId()).isNull();
        assertThat(response.endedAt()).isNull();

        ArgumentCaptor<Trip> saved = ArgumentCaptor.forClass(Trip.class);
        verify(tripRepository).save(saved.capture());
        assertThat(saved.getValue().getUser()).isSameAs(user);
        assertThat(saved.getValue().getBike()).isSameAs(bike);
        assertThat(saved.getValue().getOriginStation()).isSameAs(origin);
        assertThat(saved.getValue().getStatus()).isEqualTo(TripStatus.ACTIVE);
        verify(eventPublisher).tripStarted(saved.getValue());
    }

    @Test
    void rejectsStartWhenUserDoesNotExist() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.startTrip(1L, new TripStartRequest(5L)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Usuario");
        verifyNoInteractions(bikeService, eventPublisher);
        verify(tripRepository, never()).save(any());
    }

    @Test
    void rejectsStartWhenUserIsNotActive() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user(1L, UserStatus.BLOCKED)));

        assertThatThrownBy(() -> service.startTrip(1L, new TripStartRequest(5L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("habilitado");
        verifyNoInteractions(bikeService, eventPublisher);
        verify(tripRepository, never()).save(any());
    }

    @Test
    void rejectsStartWhenUserAlreadyHasAnActiveTrip() {
        User user = user(1L, UserStatus.ACTIVE);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(tripRepository.findByUserIdAndStatus(1L, TripStatus.ACTIVE))
                .thenReturn(Optional.of(activeTrip(7L, user, bike(5L, BikeStatus.IN_USE, null))));

        assertThatThrownBy(() -> service.startTrip(1L, new TripStartRequest(6L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("viaje activo");
        verifyNoInteractions(bikeService, eventPublisher);
        verify(tripRepository, never()).save(any());
    }

    @Test
    void rejectsStartWhenBikeDoesNotExist() {
        User user = user(1L, UserStatus.ACTIVE);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(tripRepository.findByUserIdAndStatus(1L, TripStatus.ACTIVE)).thenReturn(Optional.empty());
        when(bikeService.lockActiveBike(5L)).thenThrow(new ResourceNotFoundException("Bicicleta no encontrada: 5"));

        assertThatThrownBy(() -> service.startTrip(1L, new TripStartRequest(5L)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Bicicleta");
        verify(bikeService, never()).checkOutForTrip(any(), any());
        verify(tripRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void rejectsStartWhenBikeIsNotAvailable() {
        User user = user(1L, UserStatus.ACTIVE);
        Bike bike = bike(5L, BikeStatus.MAINTENANCE, station(10L, "Centro"));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(tripRepository.findByUserIdAndStatus(1L, TripStatus.ACTIVE)).thenReturn(Optional.empty());
        when(bikeService.lockActiveBike(5L)).thenReturn(bike);
        when(bikeService.checkOutForTrip(bike, user))
                .thenThrow(new BusinessRuleException("La bicicleta no está disponible: 5 (MAINTENANCE)"));

        assertThatThrownBy(() -> service.startTrip(1L, new TripStartRequest(5L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no está disponible");
        verify(tripRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    // MOV-027 — consultar viaje activo

    @Test
    void returnsActiveTripOfUser() {
        User user = user(1L, UserStatus.ACTIVE);
        Trip trip = activeTrip(7L, user, bike(5L, BikeStatus.IN_USE, null));
        when(userRepository.existsById(1L)).thenReturn(true);
        when(tripRepository.findByUserIdAndStatus(1L, TripStatus.ACTIVE)).thenReturn(Optional.of(trip));

        assertThat(service.findActiveTrip(1L)).hasValueSatisfying(response -> {
            assertThat(response.id()).isEqualTo(7L);
            assertThat(response.status()).isEqualTo(TripStatus.ACTIVE);
            assertThat(response.bikeId()).isEqualTo(5L);
            assertThat(response.originStationId()).isEqualTo(10L);
            assertThat(response.startedAt()).isEqualTo(trip.getStartedAt());
        });
    }

    @Test
    void returnsEmptyWhenUserHasNoActiveTrip() {
        when(userRepository.existsById(1L)).thenReturn(true);
        when(tripRepository.findByUserIdAndStatus(1L, TripStatus.ACTIVE)).thenReturn(Optional.empty());

        assertThat(service.findActiveTrip(1L)).isEmpty();
    }

    @Test
    void rejectsActiveTripQueryForUnknownUser() {
        when(userRepository.existsById(1L)).thenReturn(false);

        assertThatThrownBy(() -> service.findActiveTrip(1L))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(tripRepository);
    }

    // MOV-030 — historial de viajes

    @Test
    void returnsCompletedTripsOfUserNewestFirst() {
        User user = user(1L, UserStatus.ACTIVE);
        Instant now = Instant.now();
        Trip recent = completedTrip(9L, user, now.minusSeconds(600), 300);
        Trip older = completedTrip(8L, user, now.minusSeconds(6000), 900);
        givenHistoryPage(new PageImpl<>(List.of(recent, older), PageRequest.of(0, 10), 2));

        PagedResponse<TripResponse> history = service.findTripHistory(1L, 0, 10);

        assertThat(history.content()).extracting(TripResponse::id).containsExactly(9L, 8L);
        TripResponse first = history.content().getFirst();
        assertThat(first.status()).isEqualTo(TripStatus.COMPLETED);
        assertThat(first.originStationId()).isEqualTo(10L);
        assertThat(first.originStationName()).isEqualTo("Centro");
        assertThat(first.destinationStationId()).isEqualTo(20L);
        assertThat(first.destinationStationName()).isEqualTo("Retiro");
        assertThat(first.startedAt()).isEqualTo(recent.getStartedAt());
        assertThat(first.endedAt()).isEqualTo(recent.getEndedAt());
        assertThat(first.durationSeconds()).isEqualTo(300);
    }

    @Test
    void returnsEmptyPageWhenUserHasNoCompletedTrips() {
        givenHistoryPage(Page.empty(PageRequest.of(0, 10)));

        PagedResponse<TripResponse> history = service.findTripHistory(1L, 0, 10);

        assertThat(history.content()).isEmpty();
        assertThat(history.totalElements()).isZero();
        assertThat(history.totalPages()).isZero();
        assertThat(history.last()).isTrue();
    }

    @Test
    void queriesOnlyCompletedTripsOfTheGivenUser() {
        givenHistoryPage(Page.empty(PageRequest.of(0, 10)));

        service.findTripHistory(1L, 0, 10);

        // El aislamiento entre usuarios y la exclusión de los viajes en curso están en la consulta,
        // no en un filtrado posterior que se pueda olvidar.
        verify(tripRepository).findByUserIdAndStatusOrderByStartedAtDescIdDesc(
                eq(1L), eq(TripStatus.COMPLETED), any(Pageable.class));
    }

    @Test
    void passesRequestedPageAndSizeToRepository() {
        givenHistoryPage(Page.empty(PageRequest.of(2, 5)));

        service.findTripHistory(1L, 2, 5);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(tripRepository).findByUserIdAndStatusOrderByStartedAtDescIdDesc(
                eq(1L), eq(TripStatus.COMPLETED), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        // El orden lo define el nombre del método del repositorio: un Sort acá lo alteraría.
        assertThat(pageable.getValue().getSort()).isEqualTo(Sort.unsorted());
    }

    @Test
    void copiesPageMetadataIntoTheResponse() {
        User user = user(1L, UserStatus.ACTIVE);
        Trip trip = completedTrip(9L, user, Instant.now().minusSeconds(600), 300);
        givenHistoryPage(new PageImpl<>(List.of(trip), PageRequest.of(1, 10), 25));

        PagedResponse<TripResponse> history = service.findTripHistory(1L, 1, 10);

        assertThat(history.page()).isEqualTo(1);
        assertThat(history.size()).isEqualTo(10);
        assertThat(history.totalElements()).isEqualTo(25);
        assertThat(history.totalPages()).isEqualTo(3);
        assertThat(history.last()).isFalse();
    }

    @Test
    void rejectsHistoryQueryForUnknownUser() {
        when(userRepository.existsById(1L)).thenReturn(false);

        assertThatThrownBy(() -> service.findTripHistory(1L, 0, 10))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Usuario");
        verifyNoInteractions(tripRepository);
    }

    // MOV-028 — finalizar viaje

    @Test
    void endsActiveTripAtDestinationStation() {
        User user = user(1L, UserStatus.ACTIVE);
        Bike bike = bike(5L, BikeStatus.IN_USE, null);
        Trip trip = activeTrip(7L, user, bike);
        trip.setStartedAt(Instant.now().minusSeconds(600));
        Station destination = station(20L, "Retiro");
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(tripRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(trip));
        when(bikeService.lockActiveBike(5L)).thenReturn(bike);
        when(bikeService.checkInFromTrip(bike, 20L, user)).thenReturn(destination);

        TripResponse response = service.endTrip(1L, 7L, new TripEndRequest(20L));

        assertThat(response.status()).isEqualTo(TripStatus.COMPLETED);
        assertThat(response.destinationStationId()).isEqualTo(20L);
        assertThat(response.destinationStationName()).isEqualTo("Retiro");
        assertThat(response.endedAt()).isNotNull();
        assertThat(response.durationSeconds()).isBetween(600, 610);
        verify(tripRepository).save(trip);
        verify(eventPublisher).tripEnded(trip);
    }

    @Test
    void allowsBlockedUserToEndTheirActiveTrip() {
        User user = user(1L, UserStatus.BLOCKED);
        Bike bike = bike(5L, BikeStatus.IN_USE, null);
        Trip trip = activeTrip(7L, user, bike);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(tripRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(trip));
        when(bikeService.lockActiveBike(5L)).thenReturn(bike);
        when(bikeService.checkInFromTrip(bike, 20L, user)).thenReturn(station(20L, "Retiro"));

        assertThat(service.endTrip(1L, 7L, new TripEndRequest(20L)).status()).isEqualTo(TripStatus.COMPLETED);
    }

    @Test
    void rejectsEndForUnknownUser() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.endTrip(1L, 7L, new TripEndRequest(20L)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Usuario");
        verifyNoInteractions(tripRepository, bikeService, eventPublisher);
    }

    @Test
    void rejectsEndWhenTripDoesNotExist() {
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user(1L, UserStatus.ACTIVE)));
        when(tripRepository.findByIdForUpdate(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.endTrip(1L, 7L, new TripEndRequest(20L)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Viaje");
        verifyNoInteractions(bikeService, eventPublisher);
    }

    @Test
    void reportsTripOfAnotherUserAsNotFound() {
        User owner = user(2L, UserStatus.ACTIVE);
        Trip trip = activeTrip(7L, owner, bike(5L, BikeStatus.IN_USE, null));
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user(1L, UserStatus.ACTIVE)));
        when(tripRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(trip));

        assertThatThrownBy(() -> service.endTrip(1L, 7L, new TripEndRequest(20L)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Viaje");
        assertThat(trip.getStatus()).isEqualTo(TripStatus.ACTIVE);
        verifyNoInteractions(bikeService, eventPublisher);
    }

    @Test
    void rejectsEndWhenTripIsNotActive() {
        User user = user(1L, UserStatus.ACTIVE);
        Trip trip = activeTrip(7L, user, bike(5L, BikeStatus.AVAILABLE, null));
        trip.setStatus(TripStatus.COMPLETED);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(tripRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(trip));

        assertThatThrownBy(() -> service.endTrip(1L, 7L, new TripEndRequest(20L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no está activo");
        verifyNoInteractions(bikeService, eventPublisher);
        verify(tripRepository, never()).save(any());
    }

    @Test
    void rejectsEndWhenTripHasNoBike() {
        User user = user(1L, UserStatus.ACTIVE);
        Trip trip = activeTrip(7L, user, null);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(tripRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(trip));

        assertThatThrownBy(() -> service.endTrip(1L, 7L, new TripEndRequest(20L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("bicicleta válida");
        assertThat(trip.getStatus()).isEqualTo(TripStatus.ACTIVE);
        verifyNoInteractions(bikeService, eventPublisher);
        verify(tripRepository, never()).save(any());
    }

    @Test
    void rejectsEndWhenLockedBikeDoesNotMatchTripBike() {
        User user = user(1L, UserStatus.ACTIVE);
        Bike tripBike = bike(5L, BikeStatus.IN_USE, null);
        Bike differentBike = bike(6L, BikeStatus.IN_USE, null);
        Trip trip = activeTrip(7L, user, tripBike);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(tripRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(trip));
        when(bikeService.lockActiveBike(5L)).thenReturn(differentBike);

        assertThatThrownBy(() -> service.endTrip(1L, 7L, new TripEndRequest(20L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("inconsistente");
        assertThat(trip.getStatus()).isEqualTo(TripStatus.ACTIVE);
        verify(bikeService, never()).checkInFromTrip(any(), any(), any());
        verifyNoInteractions(eventPublisher);
        verify(tripRepository, never()).save(any());
    }

    @Test
    void rejectsEndWhenDestinationStationCannotReceiveTheBike() {
        User user = user(1L, UserStatus.ACTIVE);
        Bike bike = bike(5L, BikeStatus.IN_USE, null);
        Trip trip = activeTrip(7L, user, bike);
        when(userRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(user));
        when(tripRepository.findByIdForUpdate(7L)).thenReturn(Optional.of(trip));
        when(bikeService.lockActiveBike(5L)).thenReturn(bike);
        when(bikeService.checkInFromTrip(bike, 20L, user))
                .thenThrow(new BusinessRuleException("La estación no tiene capacidad disponible: 20"));

        assertThatThrownBy(() -> service.endTrip(1L, 7L, new TripEndRequest(20L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("capacidad");
        assertThat(trip.getStatus()).isEqualTo(TripStatus.ACTIVE);
        assertThat(trip.getDestinationStation()).isNull();
        assertThat(trip.getEndedAt()).isNull();
        verify(tripRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    private User user(Long id, UserStatus status) {
        User user = new User();
        user.setId(id);
        user.setStatus(status);
        return user;
    }

    private Station station(Long id, String name) {
        Station station = new Station();
        station.setId(id);
        station.setName(name);
        return station;
    }

    private Bike bike(Long id, BikeStatus status, Station station) {
        Bike bike = new Bike();
        bike.setId(id);
        bike.setCode("BIKE-" + id);
        bike.setStatus(status);
        bike.setStation(station);
        return bike;
    }

    private void givenHistoryPage(Page<Trip> page) {
        when(userRepository.existsById(1L)).thenReturn(true);
        when(tripRepository.findByUserIdAndStatusOrderByStartedAtDescIdDesc(
                eq(1L), eq(TripStatus.COMPLETED), any(Pageable.class))).thenReturn(page);
    }

    private Trip completedTrip(Long id, User user, Instant startedAt, int durationSeconds) {
        Trip trip = activeTrip(id, user, bike(5L, BikeStatus.AVAILABLE, null));
        trip.setStartedAt(startedAt);
        trip.setEndedAt(startedAt.plusSeconds(durationSeconds));
        trip.setDurationSeconds(durationSeconds);
        trip.setDestinationStation(station(20L, "Retiro"));
        trip.setStatus(TripStatus.COMPLETED);
        return trip;
    }

    private Trip activeTrip(Long id, User user, Bike bike) {
        Trip trip = new Trip();
        trip.setId(id);
        trip.setUser(user);
        trip.setBike(bike);
        trip.setOriginStation(station(10L, "Centro"));
        trip.setStartedAt(Instant.now().minusSeconds(60));
        trip.setStatus(TripStatus.ACTIVE);
        return trip;
    }
}
