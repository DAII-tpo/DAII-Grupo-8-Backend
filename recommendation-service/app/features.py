"""Ingeniería de features compartida entre entrenamiento e inferencia.

Todas las features se expresan en términos del *recurso* que le importa al usuario según el propósito:
bicicletas disponibles para retirar (PICKUP) o anclajes libres para devolver (DROPOFF). Así un mismo
pipeline sirve para los dos modelos y el entrenamiento y la inferencia nunca calculan cosas distintas.
"""

from __future__ import annotations

import math
from enum import Enum

import numpy as np

EARTH_RADIUS_M = 6_371_008.8

# Por encima de este número de unidades, tener más ya no reduce el riesgo de encontrar la estación sin
# recurso al llegar. Con log(1+u) en su lugar, el modelo lineal subestimaba el salto de riesgo entre 1 y 5
# unidades (prefería una estación cercana con 1 bici a otra a 400 m con 12) y acertaba menos (top-1 90% vs
# 95%). La abundancia por encima del tope la sigue viendo `resource_share`. El tope es feature engineering:
# el peso de la feature lo aprende el modelo.
SECURE_UNITS_CAP = 5

FEATURE_NAMES: tuple[str, ...] = (
    "distance_m",           # distancia en línea recta del usuario a la estación
    "distance_excess_m",    # metros extra respecto de la candidata más cercana
    "secure_units",         # unidades disponibles hasta un nivel seguro (tope SECURE_UNITS_CAP)
    "resource_ratio",       # unidades disponibles / capacidad de la estación
    "resource_share",       # unidades disponibles / máximo entre las candidatas
)


class Purpose(str, Enum):
    PICKUP = "PICKUP"
    DROPOFF = "DROPOFF"


def haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    """Distancia en metros sobre la esfera terrestre (mismo radio que GeoBoundingBox en el backend)."""
    phi1, phi2 = math.radians(lat1), math.radians(lat2)
    d_phi = phi2 - phi1
    d_lambda = math.radians(lng2 - lng1)
    a = math.sin(d_phi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(d_lambda / 2) ** 2
    return 2 * EARTH_RADIUS_M * math.asin(math.sqrt(min(1.0, a)))


def build_features(distances_m, resource_units, capacities) -> np.ndarray:
    """Matriz (n_candidatas, len(FEATURE_NAMES)) para las candidatas de UN escenario.

    Las features relativas (exceso de distancia, share) dependen del resto de las candidatas, por eso
    se calculan siempre sobre el escenario completo y nunca estación por estación.
    """
    distances = np.asarray(distances_m, dtype=float)
    units = np.asarray(resource_units, dtype=float)
    capacity = np.asarray(capacities, dtype=float)
    if distances.size == 0:
        return np.empty((0, len(FEATURE_NAMES)))

    distance_excess = distances - distances.min()
    ratio = units / np.maximum(capacity, 1.0)
    share = units / max(units.max(), 1.0)
    return np.column_stack([distances, distance_excess, np.minimum(units, SECURE_UNITS_CAP), ratio, share])
