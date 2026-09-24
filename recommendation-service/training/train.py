"""Entrena el modelo y lo deja como vigente: `python -m training.train`.

Las métricas sobre el holdout quedan en el model card (también en GET /v1/model).
"""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timezone

from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler

from app import model_store
from app.features import FEATURE_NAMES, Purpose
from app.scorer import ModelBundle
from training.evaluate import Metrics, evaluate, split
from training.sources import SyntheticDataSource, TrainingDataSource, TrainingSet

SYNTHETIC_VERSION = "logreg-synthetic-v1"
DEFAULT_SEED = 42

LIMITATIONS = [
    "Entrenado con datos sintéticos: aprende compromisos generales entre distancia y escasez, "
    "no patrones reales de estaciones u horarios.",
    "Usa el estado instantáneo de disponibilidad; no predice cómo va a cambiar hasta que el usuario llegue.",
    "La distancia es en línea recta (haversine), no a pie por calles.",
    "Los motivos por franja horaria están reservados en el contrato y requieren historial real.",
]


def fit_pipeline(data: TrainingSet, seed: int = DEFAULT_SEED) -> Pipeline:
    pipeline = Pipeline([
        ("scaler", StandardScaler()),
        ("model", LogisticRegression(max_iter=1000, random_state=seed)),
    ], memory=None)  # sin caché de transformaciones: el scaler es barato y el fit corre una vez
    return pipeline.fit(data.features, data.labels)


def train_bundle(datasets: dict[Purpose, TrainingSet], version: str, source: str,
                 seed: int = DEFAULT_SEED) -> tuple[ModelBundle, dict[Purpose, Metrics]]:
    """Entrena un pipeline por propósito. Devuelve el bundle y las métricas sobre el holdout."""
    pipelines, metrics, purposes_card = {}, {}, {}
    for purpose, data in datasets.items():
        train, test = split(data, seed=seed)
        pipeline = fit_pipeline(train, seed)
        pipelines[purpose] = pipeline
        metrics[purpose] = evaluate(pipeline, test)
        model = pipeline.named_steps["model"]
        purposes_card[purpose.value] = {
            "trainScenarios": train.scenario_count,
            "trainRows": int(len(train.labels)),
            "positiveRate": round(float(train.labels.mean()), 4),
            "holdoutMetrics": metrics[purpose].as_dict(),
            "coefficients": {name: round(float(c), 4) for name, c in zip(FEATURE_NAMES, model.coef_[0])},
            "intercept": round(float(model.intercept_[0]), 4),
        }

    card = {
        "version": version,
        "algorithm": "LogisticRegression + StandardScaler (scikit-learn)",
        "task": "Clasificación binaria 'buena elección' por estación candidata; el score rankea.",
        "features": list(FEATURE_NAMES),
        "trainingSource": source,
        "trainedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "seed": seed,
        "purposes": purposes_card,
        "limitations": LIMITATIONS,
    }
    return ModelBundle(version=version, pipelines=pipelines, card=card), metrics


def train_synthetic(scenarios: int = 4000, seed: int = DEFAULT_SEED) -> ModelBundle:
    source = SyntheticDataSource(scenarios=scenarios, seed=seed)
    bundle, _ = train_bundle(_load_all(source), SYNTHETIC_VERSION, source.description, seed)
    return bundle


def _load_all(source: TrainingDataSource) -> dict[Purpose, TrainingSet]:
    return {purpose: source.load(purpose) for purpose in Purpose}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Entrena el modelo de recomendación de estaciones")
    parser.add_argument("--version", type=model_store.validate_version)
    parser.add_argument("--scenarios", type=int, default=4000)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    args = parser.parse_args(argv)

    bundle = train_synthetic(args.scenarios, args.seed)
    if args.version:
        bundle.version = bundle.card["version"] = args.version
    # El destino sale de MODEL_DIR (igual que el servicio), no de un argumento de línea de comandos.
    path = model_store.save(bundle, model_store.default_model_dir(), promote=True)
    print(f"Modelo {bundle.version} entrenado y vigente en {path}")
    print(json.dumps(bundle.card["purposes"], indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
