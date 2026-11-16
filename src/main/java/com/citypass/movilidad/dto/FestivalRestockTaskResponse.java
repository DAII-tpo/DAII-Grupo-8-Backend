package com.citypass.movilidad.dto;

import java.time.Instant;

/** Tarea preventiva para que el operador evalúe el reabastecimiento de una estación. */
public record FestivalRestockTaskResponse(
        long id, String activityId, String correlationId, String activityTitle,
        long stationId, String stationName, int distanceMeters, int coverageMeters, Instant deadline,
        int capacity, int totalBikes, int availableBikes, int availableSlots, int targetBikes,
        String status, String resolution, Instant resolvedAt, ActivityChange activityChange, Instant previousDeadline,
        Instant deadlineChangedAt, Instant locationChangedAt, boolean outsideCoverage) {
    public record ActivityChange(String state, Instant modifiedAt, String reason) {}
}
