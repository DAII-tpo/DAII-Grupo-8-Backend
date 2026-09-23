"""Persistencia de modelos entrenados.

Estructura en disco:
    models/<version>/model.joblib        pipelines por propósito
    models/<version>/model_card.json     cómo se entrenó y cómo rinde
    models/current.json                  {"version": "<versión que sirve la API>"}

Los artefactos no se versionan en git: se generan con `python -m training.train` (el Dockerfile lo corre en
el build) y, si faltan al levantar el servicio, se entrenan con el dataset sintético (semilla fija, por lo
que el resultado es reproducible).
"""

from __future__ import annotations

import json
import logging
import os
import re
from pathlib import Path

import joblib

from app.features import Purpose
from app.scorer import ModelBundle

log = logging.getLogger(__name__)

SERVICE_ROOT = Path(__file__).resolve().parent.parent
CURRENT_FILE = "current.json"
# La versión termina siendo un nombre de directorio: solo se aceptan nombres simples, sin separadores ni "..".
VERSION_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")


def default_model_dir() -> Path:
    return Path(os.getenv("MODEL_DIR", SERVICE_ROOT / "models"))


def validate_version(version: str) -> str:
    if not VERSION_PATTERN.fullmatch(version) or ".." in version:
        raise ValueError(f"Versión de modelo inválida: {version!r}")
    return version


def _version_dir(model_dir: Path, version: str) -> Path:
    base = Path(model_dir).resolve()
    target = (base / validate_version(version)).resolve()
    if target.parent != base:
        raise ValueError(f"La versión {version!r} apunta fuera de {base}")
    return target


def save(bundle: ModelBundle, model_dir: Path, promote: bool) -> Path:
    target = _version_dir(model_dir, bundle.version)
    target.mkdir(parents=True, exist_ok=True)
    joblib.dump({purpose.value: pipeline for purpose, pipeline in bundle.pipelines.items()},
                target / "model.joblib")
    (target / "model_card.json").write_text(json.dumps(bundle.card, indent=2, ensure_ascii=False),
                                            encoding="utf-8")
    if promote:
        (target.parent / CURRENT_FILE).write_text(json.dumps({"version": bundle.version}), encoding="utf-8")
    return target


def current_version(model_dir: Path) -> str | None:
    current = Path(model_dir) / CURRENT_FILE
    if not current.exists():
        return None
    return json.loads(current.read_text(encoding="utf-8"))["version"]


def load(model_dir: Path, version: str | None = None) -> ModelBundle:
    version = version or current_version(model_dir)
    if version is None:
        raise FileNotFoundError(f"No hay modelo vigente en {model_dir}")
    source = _version_dir(model_dir, version)
    raw = joblib.load(source / "model.joblib")
    card = json.loads((source / "model_card.json").read_text(encoding="utf-8"))
    return ModelBundle(version=version, pipelines={Purpose(k): v for k, v in raw.items()}, card=card)


def load_or_train(model_dir: Path) -> ModelBundle:
    if current_version(model_dir) is None:
        log.warning("No hay modelo entrenado en %s: se entrena el modelo sintético", model_dir)
        from training.train import train_synthetic  # import diferido: training depende de app
        save(train_synthetic(), model_dir, promote=True)
    return load(model_dir)
