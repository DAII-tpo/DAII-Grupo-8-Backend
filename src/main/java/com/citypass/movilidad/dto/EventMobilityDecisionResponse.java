package com.citypass.movilidad.dto;

import java.util.List;

/** Recomendaciones actuales; nunca representa una reserva ni garantiza disponibilidad futura. */
public record EventMobilityDecisionResponse(
        String mode,
        StationRecommendationResponse pickup,
        StationRecommendationResponse dropoff,
        StationRecommendationResponse alternativeDropoff,
        String message,
        List<EventStationProjection> stationProjections) {
}
