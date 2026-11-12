package com.citypass.movilidad.dto;

import java.time.Instant;

/** Tarea preventiva para que el operador evalúe el reabastecimiento de una estación. */
public record FestivalRestockTaskResponse(
        long id, String activityId, String correlationId, String activityTitle,
        long stationId, String stationName, int distanceMeters, Instant deadline) {
}
