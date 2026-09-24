"""Interfaz del modelo: permite cambiar el algoritmo sin tocar el ranking ni la API."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol

import numpy as np
from sklearn.pipeline import Pipeline

from app.features import Purpose


class Scorer(Protocol):
    version: str

    def score(self, purpose: Purpose, features: np.ndarray) -> np.ndarray:
        """Probabilidad de 'buena elección' por candidata."""
        ...

    def contributions(self, purpose: Purpose, features: np.ndarray) -> np.ndarray:
        """Aporte de cada feature al logit, por candidata: (n_candidatas, n_features)."""
        ...


@dataclass
class ModelBundle:
    """Un modelo entrenado por propósito, más su model card."""

    version: str
    pipelines: dict[Purpose, Pipeline]
    card: dict = field(default_factory=dict)


class SklearnScorer:
    """Scorer respaldado por `Pipeline(StandardScaler, LogisticRegression)`."""

    def __init__(self, bundle: ModelBundle):
        self._bundle = bundle
        self.version = bundle.version

    @property
    def card(self) -> dict:
        return self._bundle.card

    def score(self, purpose: Purpose, features: np.ndarray) -> np.ndarray:
        return self._pipeline(purpose).predict_proba(features)[:, 1]

    def contributions(self, purpose: Purpose, features: np.ndarray) -> np.ndarray:
        pipeline = self._pipeline(purpose)
        scaler = pipeline.named_steps["scaler"]
        model = pipeline.named_steps["model"]
        return scaler.transform(features) * model.coef_[0]

    def _pipeline(self, purpose: Purpose) -> Pipeline:
        return self._bundle.pipelines[Purpose(purpose)]
