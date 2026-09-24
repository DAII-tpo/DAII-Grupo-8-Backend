package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.*;
import com.citypass.movilidad.exception.*;
import com.citypass.movilidad.model.*;
import com.citypass.movilidad.model.enums.*;
import com.citypass.movilidad.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

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

    public List<MaintenanceResponse> findAll(Long adminId) {
        requireAdmin(adminId);
        return records.findAllByOrderByStartedAtDesc().stream().map(this::response).toList();
    }

    @Transactional
    public MaintenanceResponse create(Long adminId, MaintenanceCreateRequest request) {
        User admin=requireAdmin(adminId);
        Bike bike=bikes.lockActiveBike(request.bikeId());
        BikeIncident incident=request.incidentId()==null ? null : incidents.findById(request.incidentId())
                .orElseThrow(() -> new ResourceNotFoundException("Incidencia no encontrada: "+request.incidentId()));
        if (incident!=null && !incident.getBike().getId().equals(bike.getId()))
            throw new BusinessRuleException("La incidencia no corresponde a la bicicleta indicada");
        if (records.existsByBikeIdAndStatus(bike.getId(), MaintenanceStatus.IN_PROGRESS))
            throw new BusinessRuleException("La bicicleta ya tiene un mantenimiento en curso");
        Station origin=bikes.sendToMaintenance(bike, admin, request.description().trim());
        MaintenanceRecord record=new MaintenanceRecord();
        record.setBike(bike); record.setIncident(incident); record.setCreatedByUser(admin);
        record.setOriginStation(origin);
        record.setDescription(request.description().trim()); record.setStatus(MaintenanceStatus.IN_PROGRESS);
        record.setStartedAt(Instant.now());
        return response(records.save(record));
    }

    @Transactional
    public MaintenanceResponse complete(Long adminId, Long id, MaintenanceCompleteRequest request) {
        User admin=requireAdmin(adminId);
        MaintenanceRecord record=records.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Mantenimiento no encontrado: "+id));
        if(record.getStatus()!=MaintenanceStatus.IN_PROGRESS)
            throw new BusinessRuleException("El mantenimiento no está en curso: "+id);
        Bike bike=bikes.lockActiveBike(record.getBike().getId());
        bikes.returnFromMaintenance(bike, destinationStationId(request, bike, record), admin,
                request.resolution().trim());
        record.setStatus(MaintenanceStatus.COMPLETED); record.setCompletedAt(Instant.now());
        record.setResolution(request.resolution().trim());
        return response(records.save(record));
    }

    /**
     * La estación que indica el admin; si no indica ninguna, la de origen. Una bicicleta que sigue en su
     * estación (orden previa a V4) se queda donde está.
     */
    private Long destinationStationId(MaintenanceCompleteRequest request, Bike bike, MaintenanceRecord record) {
        if (request.stationId()!=null) return request.stationId();
        if (bike.getStation()!=null || record.getOriginStation()==null) return null;
        return record.getOriginStation().getId();
    }

    private User requireAdmin(Long id) {
        User user=users.findById(id).orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado: "+id));
        if(user.getStatus()!=UserStatus.ACTIVE || user.getRole()==null || !"ADMIN".equals(user.getRole().getName()))
            throw new ForbiddenOperationException("El usuario no tiene permisos de administrador: "+id);
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
