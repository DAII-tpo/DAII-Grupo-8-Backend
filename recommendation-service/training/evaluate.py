"""Métricas del modelo sobre un holdout separado por escenario."""

from __future__ import annotations

from dataclasses import asdict, dataclass

import numpy as np
from sklearn.metrics import accuracy_score, roc_auc_score
from sklearn.model_selection import GroupShuffleSplit

from training.sources import TrainingSet


@dataclass(frozen=True)
class Metrics:
    roc_auc: float | None    # None si el holdout tiene una sola clase
    accuracy: float
    top1: float              # % de escenarios donde la mejor candidata según el modelo es una buena elección
    scenarios: int

    def as_dict(self) -> dict:
        return asdict(self)


def split(data: TrainingSet, test_size: float = 0.2, seed: int = 42) -> tuple[TrainingSet, TrainingSet]:
    """Separa por escenario: las candidatas de una misma consulta nunca quedan repartidas."""
    splitter = GroupShuffleSplit(n_splits=1, test_size=test_size, random_state=seed)
    train_idx, _ = next(splitter.split(data.features, data.labels, data.groups))
    mask = np.zeros(len(data.labels), dtype=bool)
    mask[train_idx] = True
    return data.subset(mask), data.subset(~mask)


def evaluate(pipeline, data: TrainingSet) -> Metrics:
    probabilities = pipeline.predict_proba(data.features)[:, 1]
    roc_auc = float(roc_auc_score(data.labels, probabilities)) if np.unique(data.labels).size > 1 else None
    accuracy = float(accuracy_score(data.labels, probabilities >= 0.5))

    hits = []
    for group in np.unique(data.groups):
        idx = np.flatnonzero(data.groups == group)
        hits.append(data.labels[idx[np.argmax(probabilities[idx])]] == 1)
    top1 = float(np.mean(hits)) if hits else 0.0
    return Metrics(roc_auc=_round(roc_auc), accuracy=round(accuracy, 4), top1=round(top1, 4),
                   scenarios=data.scenario_count)


def _round(value: float | None) -> float | None:
    return None if value is None else round(value, 4)
