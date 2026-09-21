import numpy as np
import pytest

from app.explainer import explain
from app.features import FEATURE_NAMES, Purpose, build_features, haversine_m
from app.schemas import ExplanationCode, StationRef

REFS = [StationRef(station_id=i, distance_meters=100 * i, available_bikes=0, available_docks=0) for i in (1, 2)]
ZERO = np.zeros((2, len(FEATURE_NAMES)))


@pytest.mark.parametrize("purpose, nearest_units, expected", [
    (Purpose.PICKUP, 0, ExplanationCode.NEAREST_HAS_NO_BIKES),
    (Purpose.PICKUP, 2, ExplanationCode.NEAREST_LOW_AVAILABILITY),
    (Purpose.PICKUP, 5, ExplanationCode.BETTER_AVAILABILITY_NEARBY),
    (Purpose.DROPOFF, 0, ExplanationCode.NEAREST_NO_DOCKS),
    (Purpose.DROPOFF, 1, ExplanationCode.NEAREST_FEW_DOCKS),
    (Purpose.DROPOFF, 8, ExplanationCode.BETTER_AVAILABILITY_NEARBY),
])
def test_codigo_segun_el_estado_actual_de_la_mas_cercana(purpose, nearest_units, expected):
    explanation = explain(purpose, best=1, nearest=0, units=np.array([nearest_units, 10]),
                          contributions=ZERO, station_refs=REFS)

    assert explanation.code == expected
    assert explanation.compared_to.station_id == 1


def test_los_factores_son_la_diferencia_de_aportes_entre_recomendada_y_mas_cercana():
    contributions = np.array([[0.0, 0.0, -1.0, 0.0, 0.0],
                              [0.0, -0.5, 1.5, 0.0, 0.2]])

    explanation = explain(Purpose.PICKUP, best=1, nearest=0, units=np.array([0, 9]),
                          contributions=contributions, station_refs=REFS)

    assert explanation.factors[0].feature == "secure_units"
    assert explanation.factors[0].impact == 2.5
    assert explanation.factors[1].feature == "distance_excess_m"


def test_haversine_con_distancias_conocidas():
    assert haversine_m(-34.6, -58.4, -34.6, -58.4) == 0
    assert haversine_m(0, 0, 1, 0) == pytest.approx(111_195, rel=1e-3)
    assert haversine_m(-34.6037, -58.3816, -34.6083, -58.3712) == pytest.approx(1070, rel=0.05)


def test_features_relativas_al_escenario():
    features = build_features([100, 400], [0, 9], [20, 10])

    assert features.shape == (2, len(FEATURE_NAMES))
    np.testing.assert_allclose(features[:, 1], [0, 300])            # exceso de distancia
    np.testing.assert_allclose(features[:, 2], [0, 5])             # unidades hasta el nivel seguro
    np.testing.assert_allclose(features[:, 3], [0, 0.9])           # ratio sobre capacidad
    np.testing.assert_allclose(features[:, 4], [0, 1])             # share sobre la mejor


def test_features_de_escenario_vacio_y_capacidad_cero():
    assert build_features([], [], []).shape == (0, len(FEATURE_NAMES))
    assert np.isfinite(build_features([100], [0], [0])).all()
