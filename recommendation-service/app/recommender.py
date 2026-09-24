"""Rankea las candidatas con el modelo.

Una estación sin recurso nunca se recomienda. Empates: más recurso, más cerca, menor id.
"""

from __future__ import annotations

import numpy as np

from app.explainer import explain
from app.features import Purpose, build_features, haversine_m
from app.schemas import (
    NoRecommendationReason,
    RankedStation,
    RecommendationRequest,
    RecommendationResponse,
    RecommendationStatus,
    ScoredStation,
    StationRef,
)
from app.scorer import Scorer

MAX_ALTERNATIVES = 2
SCORE_DECIMALS = 6


def resource_units(purpose: Purpose, available_bikes: int, available_docks: int) -> int:
    return available_bikes if purpose == Purpose.PICKUP else available_docks


def recommend(request: RecommendationRequest, scorer: Scorer) -> RecommendationResponse:
    purpose = request.purpose
    stations = request.stations
    if not stations:
        return _no_recommendation(purpose, scorer, NoRecommendationReason.NO_CANDIDATES, [])

    distances = np.array([haversine_m(request.user.lat, request.user.lng, s.lat, s.lng) for s in stations])
    units = np.array([resource_units(purpose, s.available_bikes, s.available_docks) for s in stations])
    capacities = np.array([s.capacity for s in stations])
    meters = [int(round(float(d))) for d in distances]
    features = build_features(distances, units, capacities)
    scores = np.round(scorer.score(purpose, features), SCORE_DECIMALS)

    order = sorted(range(len(stations)),
                   key=lambda i: (units[i] <= 0, -scores[i], -units[i], distances[i], stations[i].station_id))
    ranking = [RankedStation(station_id=stations[i].station_id, score=float(scores[i]), rank=position,
                             distance_meters=meters[i])
               for position, i in enumerate(order, start=1)]

    viable = [i for i in order if units[i] > 0]
    if not viable:
        return _no_recommendation(purpose, scorer, NoRecommendationReason.NO_VIABLE_STATION, ranking)

    def scored(i: int) -> ScoredStation:
        s = stations[i]
        return ScoredStation(station_id=s.station_id, score=float(scores[i]), distance_meters=meters[i],
                             available_bikes=s.available_bikes, available_docks=s.available_docks)

    refs = [StationRef(station_id=s.station_id, distance_meters=m, available_bikes=s.available_bikes,
                       available_docks=s.available_docks) for s, m in zip(stations, meters)]
    best = viable[0]
    nearest = min(range(len(stations)), key=lambda i: (distances[i], stations[i].station_id))
    explanation = explain(purpose, best, nearest, units, scorer.contributions(purpose, features), refs)

    return RecommendationResponse(
        status=RecommendationStatus.RECOMMENDED,
        purpose=purpose,
        model_version=scorer.version,
        recommendation=scored(best),
        alternatives=[scored(i) for i in viable[1:1 + MAX_ALTERNATIVES]],
        ranking=ranking,
        explanation=explanation,
    )


def _no_recommendation(purpose: Purpose, scorer: Scorer, reason: NoRecommendationReason,
                       ranking: list[RankedStation]) -> RecommendationResponse:
    return RecommendationResponse(status=RecommendationStatus.NO_RECOMMENDATION, reason=reason,
                                  purpose=purpose, model_version=scorer.version, ranking=ranking)
