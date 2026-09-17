package com.citypass.movilidad.service;

import com.citypass.movilidad.exception.BusinessRuleException;
import com.citypass.movilidad.exception.ForbiddenOperationException;
import com.citypass.movilidad.exception.ResourceNotFoundException;
import com.citypass.movilidad.model.Bike;
import com.citypass.movilidad.model.BikeIncident;
import com.citypass.movilidad.model.IncidentType;
import com.citypass.movilidad.model.Role;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.model.enums.BikeIncidentStatus;
import com.citypass.movilidad.model.enums.UserStatus;
import com.citypass.movilidad.repository.BikeIncidentRepository;
import com.citypass.movilidad.repository.BikeRepository;
import com.citypass.movilidad.repository.IncidentTypeRepository;
import com.citypass.movilidad.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
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

class IncidentAdminServiceTest {

    private BikeIncidentRepository incidentRepository;
    private UserRepository userRepository;
    private IncidentService service;

    @BeforeEach
    void setUp() {
        incidentRepository = mock(BikeIncidentRepository.class);
        userRepository = mock(UserRepository.class);
        service = new IncidentService(incidentRepository, mock(IncidentTypeRepository.class),
                mock(BikeRepository.class), userRepository);
        when(incidentRepository.save(any(BikeIncident.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void adminListsFilteredIncidentsAndReadsDetail() {
        User admin = user(1L, "ADMIN", UserStatus.ACTIVE);
        BikeIncident incident = incident(10L, BikeIncidentStatus.OPEN);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(incidentRepository.search(BikeIncidentStatus.OPEN, 2L, 3L, 4L))
                .thenReturn(List.of(incident));
        when(incidentRepository.findDetailedById(10L)).thenReturn(Optional.of(incident));

        assertThat(service.findAllForAdmin(1L, BikeIncidentStatus.OPEN, 2L, 3L, 4L))
                .singleElement().satisfies(response -> {
                    assertThat(response.id()).isEqualTo(10L);
                    assertThat(response.bikeCode()).isEqualTo("BIKE-2");
                    assertThat(response.reportedByUserEmail()).isEqualTo("user@example.com");
                    assertThat(response.incidentTypeCode()).isEqualTo("FLAT_TIRE");
                });
        assertThat(service.findByIdForAdmin(1L, 10L).id()).isEqualTo(10L);
    }

    @Test
    void rejectsAdministrativeAccessForMissingInactiveOrNonAdminUser() {
        when(userRepository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findAllForAdmin(1L, null, null, null, null))
                .isInstanceOf(ResourceNotFoundException.class);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "USER", UserStatus.ACTIVE)));
        assertThatThrownBy(() -> service.findAllForAdmin(1L, null, null, null, null))
                .isInstanceOf(ForbiddenOperationException.class);

        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "ADMIN", UserStatus.BLOCKED)));
        assertThatThrownBy(() -> service.findAllForAdmin(1L, null, null, null, null))
                .isInstanceOf(ForbiddenOperationException.class);
        verifyNoInteractions(incidentRepository);
    }

    @Test
    void reportsMissingIncidentAfterAuthorizingAdmin() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user(1L, "ADMIN", UserStatus.ACTIVE)));
        when(incidentRepository.findDetailedById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findByIdForAdmin(1L, 99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Incidencia");
    }

    @Test
    void movesOpenIncidentToReviewWithoutClosingIt() {
        User admin = user(1L, "ADMIN", UserStatus.ACTIVE);
        BikeIncident incident = incident(10L, BikeIncidentStatus.OPEN);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(incidentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(incident));

        var response = service.changeStatus(1L, 10L, BikeIncidentStatus.UNDER_REVIEW);

        assertThat(response.status()).isEqualTo(BikeIncidentStatus.UNDER_REVIEW);
        assertThat(response.resolvedAt()).isNull();
        assertThat(response.resolvedByUserId()).isNull();
        verify(incidentRepository).save(incident);
    }

    @Test
    void resolvesIncidentAndRecordsAdminAndDate() {
        User admin = user(1L, "ADMIN", UserStatus.ACTIVE);
        BikeIncident incident = incident(10L, BikeIncidentStatus.UNDER_REVIEW);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(incidentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(incident));

        var response = service.changeStatus(1L, 10L, BikeIncidentStatus.RESOLVED);

        assertThat(response.status()).isEqualTo(BikeIncidentStatus.RESOLVED);
        assertThat(response.resolvedAt()).isNotNull();
        assertThat(response.resolvedByUserId()).isEqualTo(1L);
    }

    @Test
    void rejectsInvalidOrRepeatedTransitionWithoutSaving() {
        User admin = user(1L, "ADMIN", UserStatus.ACTIVE);
        BikeIncident incident = incident(10L, BikeIncidentStatus.RESOLVED);
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(incidentRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(incident));

        assertThatThrownBy(() -> service.changeStatus(1L, 10L, BikeIncidentStatus.UNDER_REVIEW))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("no permitida");
        verify(incidentRepository, never()).save(any());
    }

    private User user(Long id, String roleName, UserStatus status) {
        Role role = new Role();
        role.setName(roleName);
        User user = new User();
        user.setId(id);
        user.setEmail(roleName.toLowerCase() + "@example.com");
        user.setRole(role);
        user.setStatus(status);
        return user;
    }

    private BikeIncident incident(Long id, BikeIncidentStatus status) {
        Bike bike = new Bike();
        bike.setId(2L);
        bike.setCode("BIKE-2");
        IncidentType type = new IncidentType();
        type.setId(4L);
        type.setCode("FLAT_TIRE");
        type.setName("Pinchazo");
        BikeIncident incident = new BikeIncident();
        incident.setId(id);
        incident.setBike(bike);
        incident.setReportedByUser(user(3L, "USER", UserStatus.ACTIVE));
        incident.setIncidentType(type);
        incident.setDescription("Rueda desinflada");
        incident.setStatus(status);
        incident.setReportedAt(Instant.now());
        return incident;
    }
}
