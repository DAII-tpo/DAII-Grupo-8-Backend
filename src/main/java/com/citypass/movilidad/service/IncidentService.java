package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.IncidentCreateRequest;
import com.citypass.movilidad.dto.IncidentResponse;
import com.citypass.movilidad.dto.IncidentTypeResponse;
import com.citypass.movilidad.exception.BusinessRuleException;
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

    public IncidentService(BikeIncidentRepository incidentRepository,
                           IncidentTypeRepository incidentTypeRepository,
                           BikeRepository bikeRepository, UserRepository userRepository) {
        this.incidentRepository = incidentRepository;
        this.incidentTypeRepository = incidentTypeRepository;
        this.bikeRepository = bikeRepository;
        this.userRepository = userRepository;
    }

    public List<IncidentTypeResponse> findActiveTypes() {
        return incidentTypeRepository.findAllByActiveTrueOrderByName().stream()
                .map(type -> new IncidentTypeResponse(
                        type.getId(), type.getCode(), type.getName(), type.getDescription()))
                .toList();
    }

    @Transactional
    public IncidentResponse report(Long userId, IncidentCreateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado: " + userId));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessRuleException("El usuario no está habilitado para reportar incidencias: " + userId);
        }
        Bike bike = bikeRepository.findByIdAndDeletedAtIsNull(request.bikeId())
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
        return toResponse(incidentRepository.save(incident));
    }

    private IncidentResponse toResponse(BikeIncident incident) {
        Bike bike = incident.getBike();
        IncidentType type = incident.getIncidentType();
        return new IncidentResponse(incident.getId(), bike.getId(), bike.getCode(),
                incident.getReportedByUser().getId(), type.getId(), type.getCode(), type.getName(),
                incident.getDescription(), incident.getStatus(), incident.getReportedAt());
    }
}
