package com.citypass.movilidad.dto;

import com.citypass.movilidad.model.enums.TripStatus;

import java.time.Instant;

public record TripResponse(
        Long id,
        TripStatus status,
        Long bikeId,
        String bikeCode,
        Long originStationId,
        String originStationName,
        Long destinationStationId,
        String destinationStationName,
        Instant startedAt,
        Instant endedAt,
        Integer durationSeconds
) {
}
