"""Request y response del servicio. JSON en camelCase, igual que el backend Java."""

from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, ConfigDict, Field, field_validator
from pydantic.alias_generators import to_camel

from app.features import Purpose

MAX_CANDIDATES = 200


class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class UserLocation(CamelModel):
    lat: float = Field(ge=-90, le=90, allow_inf_nan=False, examples=[-34.6037])
    lng: float = Field(ge=-180, le=180, allow_inf_nan=False, examples=[-58.3816])


class StationSnapshot(CamelModel):
    """Estado actual de una estación candidata. El backend solo envía estaciones habilitadas."""

    station_id: int = Field(ge=1, examples=[12])
    lat: float = Field(ge=-90, le=90, allow_inf_nan=False)
    lng: float = Field(ge=-180, le=180, allow_inf_nan=False)
    capacity: int = Field(ge=0, examples=[20])
    available_bikes: int = Field(ge=0, examples=[5])
    available_docks: int = Field(ge=0, examples=[15])


class RecommendationRequest(CamelModel):
    purpose: Purpose = Purpose.PICKUP
    user: UserLocation
    stations: list[StationSnapshot] = Field(default_factory=list, max_length=MAX_CANDIDATES)

    @field_validator("stations")
    @classmethod
    def unique_station_ids(cls, stations: list[StationSnapshot]) -> list[StationSnapshot]:
        ids = [s.station_id for s in stations]
        if len(ids) != len(set(ids)):
            raise ValueError("stationId repetido en el snapshot")
        return stations


class RecommendationStatus(str, Enum):
    RECOMMENDED = "RECOMMENDED"
    NO_RECOMMENDATION = "NO_RECOMMENDATION"


class NoRecommendationReason(str, Enum):
    NO_CANDIDATES = "NO_CANDIDATES"
    NO_VIABLE_STATION = "NO_VIABLE_STATION"


class ExplanationCode(str, Enum):
    NEAREST_IS_BEST = "NEAREST_IS_BEST"
    NEAREST_HAS_NO_BIKES = "NEAREST_HAS_NO_BIKES"
    NEAREST_LOW_AVAILABILITY = "NEAREST_LOW_AVAILABILITY"
    NEAREST_NO_DOCKS = "NEAREST_NO_DOCKS"
    NEAREST_FEW_DOCKS = "NEAREST_FEW_DOCKS"
    BETTER_AVAILABILITY_NEARBY = "BETTER_AVAILABILITY_NEARBY"
    # Reservados: requieren historial real de ocupación por franja horaria (no implementados).
    NEAREST_USUALLY_EMPTY_AT_THIS_HOUR = "NEAREST_USUALLY_EMPTY_AT_THIS_HOUR"
    NEAREST_USUALLY_FULL_AT_THIS_HOUR = "NEAREST_USUALLY_FULL_AT_THIS_HOUR"


class ScoredStation(CamelModel):
    station_id: int
    score: float = Field(description="Probabilidad de 'buena elección' según el modelo (0-1)")
    distance_meters: int
    available_bikes: int
    available_docks: int


class RankedStation(CamelModel):
    station_id: int
    score: float
    rank: int = Field(description="1 = mejor candidata")
    distance_meters: int


class StationRef(CamelModel):
    station_id: int
    distance_meters: int
    available_bikes: int
    available_docks: int


class Factor(CamelModel):
    feature: str
    impact: float = Field(description="Aporte al logit (coeficiente × valor estandarizado). "
                                      "Positivo favorece a la recomendada.")


class Explanation(CamelModel):
    code: ExplanationCode
    compared_to: StationRef | None = Field(
        default=None, description="Estación más cercana, si no fue la recomendada")
    factors: list[Factor]


class RecommendationResponse(CamelModel):
    status: RecommendationStatus
    reason: NoRecommendationReason | None = None
    purpose: Purpose
    model_version: str
    recommendation: ScoredStation | None = None
    alternatives: list[ScoredStation] = Field(default_factory=list)
    ranking: list[RankedStation] = Field(default_factory=list)
    explanation: Explanation | None = None
