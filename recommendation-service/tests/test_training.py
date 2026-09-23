import numpy as np
import pytest

from app import model_store
from app.features import Purpose
from app.scorer import SklearnScorer
from training import train as train_module
from training.evaluate import Metrics, evaluate, split
from training.sources import SyntheticDataSource, label_scenario


def test_regla_de_etiquetado_descarta_vacias_y_lejanas():
    labels = label_scenario(Purpose.PICKUP, np.array([100.0, 400.0, 2000.0]), np.array([0, 9, 20]))

    assert labels.tolist() == [0, 1, 0]


def test_regla_de_etiquetado_prefiere_stock_seguro_a_distancia_similar():
    labels = label_scenario(Purpose.PICKUP, np.array([120.0, 400.0]), np.array([1, 12]))

    assert labels.tolist() == [0, 1]


def test_regla_de_etiquetado_sin_viables_es_todo_cero():
    assert label_scenario(Purpose.DROPOFF, np.array([100.0]), np.array([0])).tolist() == [0]


def test_dataset_sintetico_es_reproducible_con_la_misma_semilla():
    first = SyntheticDataSource(scenarios=50, seed=7).load(Purpose.PICKUP)
    second = SyntheticDataSource(scenarios=50, seed=7).load(Purpose.PICKUP)

    np.testing.assert_array_equal(first.features, second.features)
    np.testing.assert_array_equal(first.labels, second.labels)
    assert first.scenario_count == 50


def test_el_modelo_entrenado_supera_umbrales_minimos(bundle):
    for purpose in Purpose:
        metrics = bundle.card["purposes"][purpose.value]["holdoutMetrics"]
        assert metrics["roc_auc"] > 0.8
        assert metrics["top1"] > 0.8


def test_los_coeficientes_aprendidos_tienen_sentido(bundle):
    coefficients = bundle.card["purposes"]["PICKUP"]["coefficients"]

    assert coefficients["distance_excess_m"] < 0      # caminar de más penaliza
    assert coefficients["secure_units"] > 0           # más stock (hasta el nivel seguro) suma


def test_guardar_cargar_e_inferir_da_el_mismo_resultado(tmp_path, bundle):
    model_store.save(bundle, tmp_path, promote=True)
    loaded = model_store.load(tmp_path)
    features = SyntheticDataSource(scenarios=10, seed=1).load(Purpose.DROPOFF).features

    assert loaded.version == bundle.version
    np.testing.assert_allclose(SklearnScorer(loaded).score(Purpose.DROPOFF, features),
                               SklearnScorer(bundle).score(Purpose.DROPOFF, features))


def test_guardar_sin_promover_no_cambia_el_vigente(tmp_path, bundle):
    model_store.save(bundle, tmp_path, promote=False)

    assert model_store.current_version(tmp_path) is None
    with pytest.raises(FileNotFoundError):
        model_store.load(tmp_path)


def test_load_or_train_entrena_si_no_hay_modelo(tmp_path, monkeypatch, bundle):
    monkeypatch.setattr(train_module, "train_synthetic", lambda: bundle)

    loaded = model_store.load_or_train(tmp_path)

    assert loaded.version == bundle.version
    assert model_store.current_version(tmp_path) == bundle.version


def test_evaluate_calcula_top1_por_escenario(bundle):
    data = SyntheticDataSource(scenarios=100, seed=3).load(Purpose.PICKUP)
    _, test = split(data, test_size=0.3)

    metrics = evaluate(bundle.pipelines[Purpose.PICKUP], test)

    assert 0 <= metrics.top1 <= 1
    assert metrics.scenarios == test.scenario_count


def test_metrics_se_serializan_para_el_model_card():
    assert Metrics(None, 0.9, 0.8, 10).as_dict() == {"roc_auc": None, "accuracy": 0.9, "top1": 0.8, "scenarios": 10}


def test_main_sintetico_deja_modelo_vigente(tmp_path, capsys, monkeypatch):
    monkeypatch.setenv("MODEL_DIR", str(tmp_path))
    assert train_module.main(["--scenarios", "200", "--version", "test-v1"]) == 0
    assert model_store.current_version(tmp_path) == "test-v1"
    assert "test-v1" in capsys.readouterr().out


@pytest.mark.parametrize("version", ["../fuera", "a/b", "..", "", "/abs", "v1\\..\\x"])
def test_save_rechaza_versiones_que_salen_del_directorio(tmp_path, version):
    bundle = train_module.train_synthetic(scenarios=50)
    bundle.version = version

    with pytest.raises(ValueError):
        model_store.save(bundle, tmp_path / "models", promote=True)

    assert not (tmp_path / "fuera").exists()


def test_load_rechaza_version_vigente_manipulada(tmp_path):
    (tmp_path / model_store.CURRENT_FILE).write_text('{"version": "../otro"}', encoding="utf-8")

    with pytest.raises(ValueError):
        model_store.load(tmp_path)
