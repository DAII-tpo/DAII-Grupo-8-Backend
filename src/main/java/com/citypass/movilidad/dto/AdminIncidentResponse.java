package com.citypass.movilidad.dto;

import com.citypass.movilidad.model.enums.BikeIncidentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Detalle administrativo de una incidencia")
public record AdminIncidentResponse(
        Long id,
        Long bikeId,
        String bikeCode,
        Long reportedByUserId,
        String reportedByUserEmail,
        Long incidentTypeId,
        String incidentTypeCode,
        String incidentTypeName,
        String description,
        BikeIncidentStatus status,
        Instant reportedAt,
        Instant resolvedAt,
        Long resolvedByUserId
) {
}
