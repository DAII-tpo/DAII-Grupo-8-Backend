package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.IncidentCreateRequest;
import com.citypass.movilidad.dto.IncidentResponse;
import com.citypass.movilidad.dto.AdminIncidentResponse;
import com.citypass.movilidad.dto.IncidentTypeResponse;
import com.citypass.movilidad.exception.BusinessRuleException;
import com.citypass.movilidad.exception.ForbiddenOperationException;
import com.citypass.movilidad.exception.ResourceNotFoundException;
import com.citypass.movilidad.model.Bike;
import com.citypass.movilidad.model.BikeIncident;
import com.citypass.movilidad.model.IncidentType;
import com.citypass.movilidad.model.User;
import com.citypass.movilidad.model.enums.BikeIncidentStatus;
import com.citypass.movilidad.model.enums.UserStatus;
import com.citypass.movilidad.repository.BikeIncidentRepository;
import com.citypass.movilidad.repository.BikeRepository;
import com.citypass.movilidad.repository.IncidentTypeRepository;
import com.citypass.movilidad.repository.UserRepository;
import com.citypass.movilidad.model.Trip;
import com.citypass.movilidad.model.enums.TripStatus;
import com.citypass.movilidad.repository.TripRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class IncidentService {

    private final BikeIncidentRepository incidentRepository;
    private final IncidentTypeRepository incidentTypeRepository;
    private final BikeRepository bikeRepository;
    private final UserRepository userRepository;
    private final TripRepository tripRepository;
    private final BikeService bikeService;

    public IncidentService(BikeIncidentRepository incidentRepository,
                           IncidentTypeRepository incidentTypeRepository,
                           BikeRepository bikeRepository, UserRepository userRepository,
                           TripRepository tripRepository, BikeService bikeService) {
        this.incidentRepository = incidentRepository;
        this.incidentTypeRepository = incidentTypeRepository;
        this.bikeRepository = bikeRepository;
        this.userRepository = userRepository;
        this.tripRepository = tripRepository;
        this.bikeService = bikeService;
    }

    public List<IncidentTypeResponse> findActiveTypes() {
        return incidentTypeRepository.findAllByActiveTrueOrderByName().stream()
                .map(type -> new IncidentTypeResponse(
                        type.getId(), type.getCode(), type.getName(), type.getDescription()))
                .toList();
    }

    public List<AdminIncidentResponse> findAllForAdmin(Long adminId, BikeIncidentStatus status,
                                                        Long bikeId, Long userId, Long typeId) {
        requireAdmin(adminId);
        return incidentRepository.search(status, bikeId, userId, typeId).stream()
                .map(this::toAdminResponse)
                .toList();
    }

    public AdminIncidentResponse findByIdForAdmin(Long adminId, Long incidentId) {
        requireAdmin(adminId);
        return incidentRepository.findDetailedById(incidentId)
                .map(this::toAdminResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Incidencia no encontrada: " + incidentId));
    }

    @Transactional
    public AdminIncidentResponse changeStatus(Long adminId, Long incidentId, BikeIncidentStatus newStatus) {
        User admin = requireAdmin(adminId);
        BikeIncident incident = incidentRepository.findByIdForUpdate(incidentId)
                .orElseThrow(() -> new ResourceNotFoundException("Incidencia no encontrada: " + incidentId));
        if (!isAllowedTransition(incident.getStatus(), newStatus)) {
            throw new BusinessRuleException(
                    "Transición de incidencia no permitida: " + incident.getStatus() + " -> " + newStatus);
        }

        incident.setStatus(newStatus);
        if (newStatus == BikeIncidentStatus.RESOLVED || newStatus == BikeIncidentStatus.REJECTED) {
            incident.setResolvedAt(Instant.now());
            incident.setResolvedByUser(admin);
        }
        // Reporte falso: la bicicleta vuelve a circular, salvo que tenga otra incidencia pendiente.
        Long bikeId = incident.getBike().getId();
        if (newStatus == BikeIncidentStatus.REJECTED
                && !incidentRepository.existsByBikeIdAndStatusInAndIdNot(bikeId, BikeIncidentStatus.PENDING, incidentId)) {
            bikeService.returnToServiceAfterRejectedIncident(bikeId, admin);
        }
        return toAdminResponse(incidentRepository.save(incident));
    }

    @Transactional
    public IncidentResponse report(Long userId, IncidentCreateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado: " + userId));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessRuleException("El usuario no está habilitado para reportar incidencias: " + userId);
        }
        // Con lock: sin él, un viaje que arranca en paralelo podría quedar pisado por el save de
        // esta bicicleta leída antes del inicio (estado y estación viejos).
        Bike bike = bikeRepository.findByIdAndDeletedAtIsNullForUpdate(request.bikeId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Bicicleta no encontrada: " + request.bikeId()));
        IncidentType type = incidentTypeRepository.findByIdAndActiveTrue(request.incidentTypeId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tipo de incidencia no encontrado o inactivo: " + request.incidentTypeId()));

        BikeIncident incident = new BikeIncident();
        incident.setBike(bike);
        incident.setReportedByUser(user);
        incident.setIncidentType(type);
        incident.setDescription(request.description().trim());
        incident.setStatus(BikeIncidentStatus.OPEN);
        incident.setReportedAt(Instant.now());

        tripRepository.findByUserIdAndStatus(userId, TripStatus.ACTIVE)
                .filter(trip -> trip.getBike() != null && bike.getId().equals(trip.getBike().getId()))
                .ifPresent(incident::setTrip);

        bikeService.reportIncidentOnBike(bike, user, BikeService.INCIDENT_REASON_PREFIX + ": " + type.getName());

        return toResponse(incidentRepository.save(incident));
    }

    private IncidentResponse toResponse(BikeIncident incident) {
        Bike bike = incident.getBike();
        IncidentType type = incident.getIncidentType();
        return new IncidentResponse(incident.getId(), bike.getId(), bike.getCode(),
                incident.getReportedByUser().getId(), type.getId(), type.getCode(), type.getName(),
                incident.getDescription(), incident.getStatus(), incident.getReportedAt());
    }

    private User requireAdmin(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado: " + userId));
        if (user.getStatus() != UserStatus.ACTIVE || user.getRole() == null
                || !"ADMIN".equals(user.getRole().getName())) {
            throw new ForbiddenOperationException("El usuario no tiene permisos de administrador: " + userId);
        }
        return user;
    }

    private boolean isAllowedTransition(BikeIncidentStatus current, BikeIncidentStatus next) {
        return switch (current) {
            case OPEN -> next == BikeIncidentStatus.UNDER_REVIEW
                    || next == BikeIncidentStatus.RESOLVED
                    || next == BikeIncidentStatus.REJECTED;
            case UNDER_REVIEW -> next == BikeIncidentStatus.RESOLVED
                    || next == BikeIncidentStatus.REJECTED;
            case RESOLVED, REJECTED -> false;
        };
    }

    private AdminIncidentResponse toAdminResponse(BikeIncident incident) {
        Bike bike = incident.getBike();
        IncidentType type = incident.getIncidentType();
        User reporter = incident.getReportedByUser();
        User resolver = incident.getResolvedByUser();
        return new AdminIncidentResponse(incident.getId(), bike.getId(), bike.getCode(),
                reporter.getId(), reporter.getEmail(), type.getId(), type.getCode(), type.getName(),
                incident.getDescription(), incident.getStatus(), incident.getReportedAt(),
                incident.getResolvedAt(), resolver == null ? null : resolver.getId());
    }
}
