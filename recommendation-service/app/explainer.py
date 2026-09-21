"""Explicación de la recomendación para el usuario final.

El `code` describe el estado *actual* de la estación más cercana (nunca afirma nada sobre su historial).
Los `factors` salen del modelo: diferencia de aporte al logit entre la recomendada y la más cercana, es
decir, qué features hicieron que el modelo prefiera una sobre la otra.
"""

from __future__ import annotations

import numpy as np

from app.features import FEATURE_NAMES, Purpose
from app.schemas import Explanation, ExplanationCode, Factor, StationRef

# Por debajo de este número de unidades el recurso se considera escaso: puede agotarse antes de llegar.
LOW_AVAILABILITY_UNITS = 3


def explain(purpose: Purpose, best: int, nearest: int, units: np.ndarray,
            contributions: np.ndarray, station_refs: list[StationRef]) -> Explanation:
    if best == nearest:
        return Explanation(code=ExplanationCode.NEAREST_IS_BEST, compared_to=None,
                           factors=_factors(contributions[best]))

    code = _code_for_nearest(purpose, int(units[nearest]))
    return Explanation(code=code, compared_to=station_refs[nearest],
                       factors=_factors(contributions[best] - contributions[nearest]))


def _code_for_nearest(purpose: Purpose, nearest_units: int) -> ExplanationCode:
    pickup = purpose == Purpose.PICKUP
    if nearest_units == 0:
        return ExplanationCode.NEAREST_HAS_NO_BIKES if pickup else ExplanationCode.NEAREST_NO_DOCKS
    if nearest_units < LOW_AVAILABILITY_UNITS:
        return ExplanationCode.NEAREST_LOW_AVAILABILITY if pickup else ExplanationCode.NEAREST_FEW_DOCKS
    return ExplanationCode.BETTER_AVAILABILITY_NEARBY


def _factors(impacts: np.ndarray) -> list[Factor]:
    factors = [Factor(feature=name, impact=round(float(value), 3))
               for name, value in zip(FEATURE_NAMES, impacts)]
    return sorted(factors, key=lambda f: abs(f.impact), reverse=True)
