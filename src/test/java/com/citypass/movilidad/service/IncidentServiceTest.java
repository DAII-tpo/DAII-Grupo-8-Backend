package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.IncidentCreateRequest;
import com.citypass.movilidad.exception.BusinessRuleException;
import com.citypass.movilidad.exception.ResourceNotFoundException;
import com.citypass.movilidad.model.Bike;
import com.citypass.movilidad.model.BikeIncident;
import com.citypass.movilidad.model.IncidentType;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.model.enums.BikeIncidentStatus;
import com.citypass.movilidad.model.enums.BikeStatus;
import com.citypass.movilidad.model.enums.UserStatus;
import com.citypass.movilidad.repository.BikeIncidentRepository;
import com.citypass.movilidad.repository.BikeRepository;
import com.citypass.movilidad.repository.IncidentTypeRepository;
import com.citypass.movilidad.repository.UserRepository;
import com.citypass.movilidad.model.Trip;
import com.citypass.movilidad.model.enums.TripStatus;
import com.citypass.movilidad.repository.TripRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

class IncidentServiceTest {

    private BikeIncidentRepository incidentRepository;
    private IncidentTypeRepository typeRepository;
    private BikeRepository bikeRepository;
    private UserRepository userRepository;
    private TripRepository tripRepository;
    private BikeService bikeService;
    private IncidentService service;

    @BeforeEach
    void setUp() {
        incidentRepository = mock(BikeIncidentRepository.class);
        typeRepository = mock(IncidentTypeRepository.class);
        bikeRepository = mock(BikeRepository.class);
        userRepository = mock(UserRepository.class);
        tripRepository = mock(TripRepository.class);
        bikeService = mock(BikeService.class);
        service = new IncidentService(
                incidentRepository, typeRepository, bikeRepository, userRepository, tripRepository, bikeService);
        when(incidentRepository.save(any(BikeIncident.class))).thenAnswer(invocation -> {
            BikeIncident incident = invocation.getArgument(0);
            incident.setId(50L);
            return incident;
        });
    }

    @Test
    void returnsOnlyActiveIncidentTypes() {
        IncidentType type = type(1L, true);
        when(typeRepository.findAllByActiveTrueOrderByName()).thenReturn(List.of(type));

        assertThat(service.findActiveTypes()).singleElement().satisfies(response -> {
            assertThat(response.id()).isEqualTo(1L);
            assertThat(response.code()).isEqualTo("FLAT_TIRE");
            assertThat(response.name()).isEqualTo("Pinchazo");
        });
    }

    @Test
    void reportsOpenIncidentAssociatedWithUserBikeAndTypeWithoutActiveTrip() {
        User user = user(1L, UserStatus.ACTIVE);
        Bike bike = bike(2L);
        IncidentType type = type(3L, true);
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(bikeRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(bike));
        when(typeRepository.findByIdAndActiveTrue(3L)).thenReturn(Optional.of(type));
        when(tripRepository.findByUserIdAndStatus(1L, TripStatus.ACTIVE)).thenReturn(Optional.empty());

        var response = service.report(1L, new IncidentCreateRequest(2L, 3L, "  Rueda desinflada  "));

        assertThat(response.id()).isEqualTo(50L);
        assertThat(response.reportedByUserId()).isEqualTo(1L);
        assertThat(response.bikeId()).isEqualTo(2L);
        assertThat(response.incidentTypeId()).isEqualTo(3L);
        assertThat(response.description()).isEqualTo("Rueda desinflada");
        assertThat(response.status()).isEqualTo(BikeIncidentStatus.OPEN);
        assertThat(response.reportedAt()).isNotNull();

        ArgumentCaptor<BikeIncident> saved = ArgumentCaptor.forClass(BikeIncident.class);
        verify(incidentRepository).save(saved.capture());
        assertThat(saved.getValue().getReportedByUser()).isSameAs(user);
        assertThat(saved.getValue().getBike()).isSameAs(bike);
        assertThat(saved.getValue().getIncidentType()).isSameAs(type);
        assertThat(saved.getValue().getTrip()).isNull();
        verify(bikeService).reportIncidentOnBike(bike, user, "Incidencia reportada: Pinchazo");
    }

    @Test
    void reportsOpenIncidentDuringActiveTripLinksTripAndModifiesBikeStatus() {
        User user = user(1L, UserStatus.ACTIVE);
        Bike bike = bike(2L);
        IncidentType type = type(3L, true);
        Trip trip = new Trip();
        trip.setId(99L);
        trip.setBike(bike);
        trip.setUser(user);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(bikeRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(bike));
        when(typeRepository.findByIdAndActiveTrue(3L)).thenReturn(Optional.of(type));
        when(tripRepository.findByUserIdAndStatus(1L, TripStatus.ACTIVE)).thenReturn(Optional.of(trip));

        var response = service.report(1L, new IncidentCreateRequest(2L, 3L, "Rueda pinchada en trayecto"));

        assertThat(response.status()).isEqualTo(BikeIncidentStatus.OPEN);

        ArgumentCaptor<BikeIncident> saved = ArgumentCaptor.forClass(BikeIncident.class);
        verify(incidentRepository).save(saved.capture());
        assertThat(saved.getValue().getTrip()).isSameAs(trip);
        assertThat(saved.getValue().getBike()).isSameAs(bike);
        verify(bikeService).reportIncidentOnBike(bike, user, "Incidencia reportada: Pinchazo");
    }

    @Test
    void rejectsMissingOrBlockedUserBeforeLookingUpBike() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());
        IncidentCreateRequest request = new IncidentCreateRequest(2L, 3L, "Problema");

        assertThatThrownBy(() -> service.report(1L, request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Usuario");

        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, UserStatus.BLOCKED)));
        assertThatThrownBy(() -> service.report(1L, request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no está habilitado");
        verifyNoInteractions(bikeRepository, typeRepository);
        verify(incidentRepository, never()).save(any());
    }

    @Test
    void rejectsMissingBikeBeforeLookingUpType() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, UserStatus.ACTIVE)));
        when(bikeRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.report(1L,
                new IncidentCreateRequest(2L, 3L, "Problema")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Bicicleta");
        verifyNoInteractions(typeRepository);
        verify(incidentRepository, never()).save(any());
    }

    @Test
    void rejectsMissingOrInactiveIncidentType() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, UserStatus.ACTIVE)));
        when(bikeRepository.findByIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(bike(2L)));
        when(typeRepository.findByIdAndActiveTrue(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.report(1L,
                new IncidentCreateRequest(2L, 3L, "Problema")))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Tipo de incidencia");
        verify(incidentRepository, never()).save(any());
    }

    private User user(Long id, UserStatus status) {
        User user = new User();
        user.setId(id);
        user.setStatus(status);
        return user;
    }

    private Bike bike(Long id) {
        Bike bike = new Bike();
        bike.setId(id);
        bike.setCode("BIKE-" + id);
        bike.setStatus(BikeStatus.AVAILABLE);
        return bike;
    }

    private IncidentType type(Long id, boolean active) {
        IncidentType type = new IncidentType();
        type.setId(id);
        type.setCode("FLAT_TIRE");
        type.setName("Pinchazo");
        type.setDescription("Neumático desinflado");
        type.setActive(active);
        return type;
    }
}
