package com.citypass.movilidad.dto;

import com.citypass.movilidad.model.enums.BikeStatus;

import java.time.Instant;
import java.time.LocalDate;

public record BikeResponse(
        Long id,
        String code,
        Long stationId,
        String stationName,
        BikeStatus status,
        String model,
        LocalDate purchaseDate,
        Instant lastMaintenanceAt,
        Instant createdAt,
        Instant updatedAt
) {
}
