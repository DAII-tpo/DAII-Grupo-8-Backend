package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.*;
import com.citypass.movilidad.exception.*;
import com.citypass.movilidad.model.*;
import com.citypass.movilidad.model.enums.*;
import com.citypass.movilidad.repository.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Órdenes de mantenimiento de bicis (solo administradores). */
@Service
@Transactional(readOnly = true)
public class MaintenanceService {
    private final MaintenanceRecordRepository records;
    private final BikeIncidentRepository incidents;
    private final UserRepository users;
    private final BikeService bikes;

    public MaintenanceService(MaintenanceRecordRepository records, BikeIncidentRepository incidents,
                              UserRepository users, BikeService bikes) {
        this.records=records; this.incidents=incidents; this.users=users; this.bikes=bikes;
    }

    /** Todas las órdenes, de la más reciente a la más antigua. */
    @PreAuthorize("hasRole('ADMIN')")
    public List<MaintenanceResponse> findAll(Long adminId) {
        requireActiveUser(adminId);
        return records.findAllByOrderByStartedAtDesc().stream().map(this::response).toList();
    }

    @Transactional
    /** Abre una orden: la bici pasa a MAINTENANCE y se guarda su estación de origen. */
    @PreAuthorize("hasRole('ADMIN')")
    public MaintenanceResponse create(Long adminId, MaintenanceCreateRequest request) {
        User admin=requireActiveUser(adminId);
        Bike bike=bikes.lockActiveBike(request.bikeId());
        BikeIncident incident=request.incidentId()==null ? null : incidents.findById(request.incidentId())
                .orElseThrow(() -> new ResourceNotFoundException("Incidencia no encontrada: "+request.incidentId()));
        if (incident!=null && !incident.getBike().getId().equals(bike.getId()))
            throw new BusinessRuleException("La incidencia no corresponde a la bicicleta indicada");
        if (records.existsByBikeIdAndStatus(bike.getId(), MaintenanceStatus.IN_PROGRESS))
            throw new BusinessRuleException("La bicicleta ya tiene un mantenimiento en curso");
        Station origin=bikes.sendToMaintenance(bike, admin, request.description().trim());
        MaintenanceRecord maintenance=new MaintenanceRecord();
        maintenance.setBike(bike); maintenance.setIncident(incident); maintenance.setCreatedByUser(admin);
        maintenance.setOriginStation(origin);
        maintenance.setDescription(request.description().trim()); maintenance.setStatus(MaintenanceStatus.IN_PROGRESS);
        maintenance.setStartedAt(Instant.now());
        return response(records.save(maintenance));
    }

    @Transactional
    /** Cierra la orden y devuelve la bici a circulación. */
    @PreAuthorize("hasRole('ADMIN')")
    public MaintenanceResponse complete(Long adminId, Long id, MaintenanceCompleteRequest request) {
        User admin=requireActiveUser(adminId);
        MaintenanceRecord maintenance=records.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Mantenimiento no encontrado: "+id));
        if(maintenance.getStatus()!=MaintenanceStatus.IN_PROGRESS)
            throw new BusinessRuleException("El mantenimiento no está en curso: "+id);
        Bike bike=bikes.lockActiveBike(maintenance.getBike().getId());
        bikes.returnFromMaintenance(bike, destinationStationId(request, bike, maintenance), admin,
                request.resolution().trim());
        maintenance.setStatus(MaintenanceStatus.COMPLETED); maintenance.setCompletedAt(Instant.now());
        maintenance.setResolution(request.resolution().trim());
        return response(records.save(maintenance));
    }

    // Estación elegida por el admin o, si no eligió, la de origen (null si la bici sigue en su estación).
    private Long destinationStationId(MaintenanceCompleteRequest request, Bike bike, MaintenanceRecord maintenance) {
        if (request.stationId()!=null) return request.stationId();
        if (bike.getStation()!=null || maintenance.getOriginStation()==null) return null;
        return maintenance.getOriginStation().getId();
    }

    /** Igual que en IncidentService: el rol lo decide @PreAuthorize, acá solo el estado. */
    private User requireActiveUser(Long id) {
        User user=users.findById(id).orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado: "+id));
        if(user.getStatus()!=UserStatus.ACTIVE)
            throw new ForbiddenOperationException("El usuario no está habilitado: "+id);
        return user;
    }
    private MaintenanceResponse response(MaintenanceRecord r) {
        return new MaintenanceResponse(r.getId(),r.getBike().getId(),r.getBike().getCode(),
                r.getIncident()==null?null:r.getIncident().getId(),r.getCreatedByUser().getId(),r.getDescription(),
                r.getStatus(),r.getStartedAt(),r.getCompletedAt(),r.getResolution(),
                r.getOriginStation()==null?null:r.getOriginStation().getId(),
                r.getOriginStation()==null?null:r.getOriginStation().getName());
    }
}
