"""API HTTP del servicio de recomendación de estaciones (documentación en /docs)."""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, Request

from app import model_store
from app.recommender import recommend
from app.schemas import RecommendationRequest, RecommendationResponse
from app.scorer import SklearnScorer

logging.basicConfig(level=logging.INFO)


def create_app(model_dir: Path | None = None) -> FastAPI:
    directory = model_dir or model_store.default_model_dir()

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        app.state.scorer = SklearnScorer(model_store.load_or_train(directory))
        yield

    app = FastAPI(
        title="CityPass+ Movilidad — Recomendación de estaciones",
        version="1.0.0",
        description="Recomienda la estación más conveniente para retirar o devolver una bicicleta a partir de "
                    "la ubicación del usuario y el estado actual de las estaciones. El ranking lo hace un modelo "
                    "de regresión logística entrenado (ver GET /v1/model).",
        lifespan=lifespan,
    )

    @app.get("/health", tags=["Operación"], summary="Estado del servicio y modelo vigente")
    def health(request: Request) -> dict:
        return {"status": "UP", "modelVersion": request.app.state.scorer.version}

    @app.get("/v1/model", tags=["Modelo"], summary="Model card: algoritmo, features, entrenamiento y métricas")
    def model_card(request: Request) -> dict:
        return request.app.state.scorer.card

    @app.post("/v1/recommendations", tags=["Recomendación"],
              summary="Recomendar una estación",
              description="Siempre responde 200 para casos de negocio: si no hay candidatas o ninguna tiene el "
                          "recurso necesario, `status` es NO_RECOMMENDATION con su `reason`. 422 solo si el "
                          "payload es inválido.")
    def recommendations(payload: RecommendationRequest, request: Request) -> RecommendationResponse:
        return recommend(payload, request.app.state.scorer)

    return app


app = create_app()
