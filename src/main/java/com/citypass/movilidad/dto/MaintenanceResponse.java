package com.citypass.movilidad.dto;
import com.citypass.movilidad.model.enums.MaintenanceStatus;
import java.time.Instant;
public record MaintenanceResponse(Long id, Long bikeId, String bikeCode, Long incidentId,
        Long createdByUserId, String description, MaintenanceStatus status,
        Instant startedAt, Instant completedAt, String resolution) {}
