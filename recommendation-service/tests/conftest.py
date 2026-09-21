from __future__ import annotations

import numpy as np
import pytest
from fastapi.testclient import TestClient

from app import model_store
from app.features import Purpose
from app.main import create_app
from app.schemas import RecommendationRequest, StationSnapshot, UserLocation
from app.scorer import SklearnScorer
from training.train import train_synthetic

USER_LAT, USER_LNG = -34.6037, -58.3816
METERS_PER_DEGREE_LAT = 111_195.0


@pytest.fixture(scope="session")
def bundle():
    return train_synthetic(scenarios=1500)


@pytest.fixture(scope="session")
def scorer(bundle):
    return SklearnScorer(bundle)


@pytest.fixture(scope="session")
def model_dir(tmp_path_factory, bundle):
    directory = tmp_path_factory.mktemp("models")
    model_store.save(bundle, directory, promote=True)
    return directory


@pytest.fixture
def client(model_dir):
    with TestClient(create_app(model_dir)) as test_client:
        yield test_client


def station(station_id: int, meters_north: float, bikes: int, docks: int, capacity: int = 20) -> StationSnapshot:
    """Estación ubicada `meters_north` metros al norte del usuario de prueba."""
    return StationSnapshot(station_id=station_id, lat=USER_LAT + meters_north / METERS_PER_DEGREE_LAT,
                           lng=USER_LNG, capacity=capacity, available_bikes=bikes, available_docks=docks)


def request(*stations: StationSnapshot, purpose: Purpose = Purpose.PICKUP) -> RecommendationRequest:
    return RecommendationRequest(purpose=purpose, user=UserLocation(lat=USER_LAT, lng=USER_LNG),
                                 stations=list(stations))


class ConstantScorer:
    """Scorer de prueba: mismo score para todas las candidatas (para ejercitar el desempate)."""

    version = "constant-test"

    def score(self, purpose, features):
        return np.full(len(features), 0.5)

    def contributions(self, purpose, features):
        return np.zeros_like(features)
