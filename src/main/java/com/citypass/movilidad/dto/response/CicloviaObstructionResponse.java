package com.citypass.movilidad.dto.response;

import com.citypass.movilidad.model.enums.ObstructionImpactLevel;
import com.citypass.movilidad.model.enums.ObstructionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record CicloviaObstructionResponse(
        Long id,
        String reclamoId,
        String ciudadanoId,
        String titulo,
        String descripcion,
        String direccion,
        String barrio,
        BigDecimal latitude,
        BigDecimal longitude,
        ObstructionImpactLevel impactLevel,
        ObstructionStatus status,
        List<Long> affectedStationIds,
        String detourDescription,
        boolean emergencyDispatched,
        Instant createdAt,
        Instant resolvedAt
) {
}

