"""Features del modelo, compartidas entre entrenamiento e inferencia.

"Recurso" = bicis disponibles (PICKUP) o anclajes libres (DROPOFF).
"""

from __future__ import annotations

import math
from enum import Enum

import numpy as np

EARTH_RADIUS_M = 6_371_008.8

# Por encima de este número de unidades, tener más ya no reduce el riesgo de llegar y no encontrar lugar.
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
    """Matriz de features de las candidatas de una consulta (algunas son relativas entre candidatas)."""
    distances = np.asarray(distances_m, dtype=float)
    units = np.asarray(resource_units, dtype=float)
    capacity = np.asarray(capacities, dtype=float)
    if distances.size == 0:
        return np.empty((0, len(FEATURE_NAMES)))

    distance_excess = distances - distances.min()
    ratio = units / np.maximum(capacity, 1.0)
    share = units / max(units.max(), 1.0)
    return np.column_stack([distances, distance_excess, np.minimum(units, SECURE_UNITS_CAP), ratio, share])
