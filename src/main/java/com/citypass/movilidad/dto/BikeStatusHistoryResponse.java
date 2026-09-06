package com.citypass.movilidad.dto;

import com.citypass.movilidad.model.enums.BikeStatus;

import java.time.Instant;

public record BikeStatusHistoryResponse(
        Long id,
        BikeStatus previousStatus,
        BikeStatus newStatus,
        Long changedByUserId,
        String reason,
        Instant changedAt
) {
}
