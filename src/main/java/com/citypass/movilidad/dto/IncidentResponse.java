package com.citypass.movilidad.dto;

import com.citypass.movilidad.model.enums.BikeIncidentStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

@Schema(description = "Incidencia registrada")
public record IncidentResponse(
        Long id,
        Long bikeId,
        String bikeCode,
        Long reportedByUserId,
        Long incidentTypeId,
        String incidentTypeCode,
        String incidentTypeName,
        String description,
        BikeIncidentStatus status,
        Instant reportedAt
) {
}
