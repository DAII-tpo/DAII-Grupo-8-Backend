package com.citypass.movilidad.dto;

public record EventStationProjection(
        Long stationId,
        int availableSlots,
        int projectedArrivals,
        int projectedBalance,
        int projectedAvailableSlots,
        String saturationRisk) {
}
