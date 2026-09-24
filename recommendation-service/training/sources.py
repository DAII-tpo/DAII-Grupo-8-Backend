"""Datos de entrenamiento.

Un escenario es una consulta (ubicación + candidatas); cada candidata es una fila etiquetada 1/0.
Hoy se usa un dataset sintético; otra fuente se agrega implementando TrainingDataSource.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

import numpy as np

from app.features import FEATURE_NAMES, Purpose, build_features


@dataclass
class TrainingSet:
    features: np.ndarray   # (n_filas, n_features)
    labels: np.ndarray     # (n_filas,) 0/1
    groups: np.ndarray     # (n_filas,) id de escenario

    @property
    def scenario_count(self) -> int:
        return int(np.unique(self.groups).size)

    def subset(self, mask: np.ndarray) -> "TrainingSet":
        return TrainingSet(self.features[mask], self.labels[mask], self.groups[mask])


class TrainingDataSource(Protocol):
    description: str

    def load(self, purpose: Purpose) -> TrainingSet:
        ...


def _assemble(scenarios: list[tuple[np.ndarray, np.ndarray]]) -> TrainingSet:
    if not scenarios:
        return TrainingSet(np.empty((0, len(FEATURE_NAMES))), np.empty(0, dtype=int), np.empty(0, dtype=int))
    features = np.vstack([x for x, _ in scenarios])
    labels = np.concatenate([y for _, y in scenarios]).astype(int)
    groups = np.concatenate([np.full(len(y), g) for g, (_, y) in enumerate(scenarios)])
    return TrainingSet(features, labels, groups)


# --- Dataset sintético ------------------------------------------------------------------------------

# Parámetros de la regla de etiquetado.
SAFE_UNITS = {Purpose.PICKUP: 4, Purpose.DROPOFF: 3}   # unidades a partir de las cuales no hay riesgo
SCARCITY_COST_PER_UNIT = 2.0                          # 1 unidad faltante "equivale" a caminar 200 m
WALK_COST_PER_100M = 1.0
GOOD_CHOICE_TOLERANCE = 1.0                           # margen (≈100 m) para considerar dos opciones buenas
MAX_WALK_M = 1500
LABEL_NOISE = 0.05


def label_scenario(purpose: Purpose, distances: np.ndarray, units: np.ndarray) -> np.ndarray:
    """Regla de sentido común usada SOLO para etiquetar el dataset sintético."""
    shortfall = np.maximum(SAFE_UNITS[purpose] - units, 0)
    cost = distances / 100 * WALK_COST_PER_100M + shortfall * SCARCITY_COST_PER_UNIT
    viable = (units > 0) & (distances <= MAX_WALK_M)
    if not viable.any():
        return np.zeros(len(units), dtype=int)
    best_cost = cost[viable].min()
    return (viable & (cost <= best_cost + GOOD_CHOICE_TOLERANCE)).astype(int)


class SyntheticDataSource:
    description = "Escenarios sintéticos etiquetados con regla de sentido común (sin datos reales)"

    def __init__(self, scenarios: int = 4000, seed: int = 42, label_noise: float = LABEL_NOISE):
        self.scenarios = scenarios
        self.seed = seed
        self.label_noise = label_noise

    def load(self, purpose: Purpose) -> TrainingSet:
        rng = np.random.default_rng(self.seed + (0 if purpose == Purpose.PICKUP else 1))
        return _assemble([self._scenario(rng, purpose) for _ in range(self.scenarios)])

    def _scenario(self, rng: np.random.Generator, purpose: Purpose) -> tuple[np.ndarray, np.ndarray]:
        n = int(rng.integers(3, 13))
        nearest = rng.uniform(30, 500)
        distances = np.minimum(nearest + np.concatenate([[0.0], rng.exponential(350, n - 1)]), 2500)
        capacities = rng.integers(8, 41, n)
        units = np.array([self._units(rng, int(c)) for c in capacities])

        labels = label_scenario(purpose, distances, units)
        flip = rng.random(n) < self.label_noise
        labels = np.where(flip, 1 - labels, labels)
        return build_features(distances, units, capacities), labels

    @staticmethod
    def _units(rng: np.random.Generator, capacity: int) -> int:
        draw = rng.random()
        if draw < 0.15:
            return 0
        if draw < 0.40:
            return int(rng.integers(1, 4))
        return int(rng.integers(4, capacity + 1))

