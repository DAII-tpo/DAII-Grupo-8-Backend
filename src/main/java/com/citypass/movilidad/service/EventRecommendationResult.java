package com.citypass.movilidad.service;

import com.citypass.movilidad.dto.EventStationProjection;
import com.citypass.movilidad.dto.StationRecommendationResponse;

import java.util.List;

public record EventRecommendationResult(
        StationRecommendationResponse recommendation,
        List<EventStationProjection> projections) {
}
