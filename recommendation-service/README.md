# Servicio de recomendación de estaciones

Microservicio Python (FastAPI + scikit-learn) que recomienda la estación más conveniente para **retirar**
(`PICKUP`) o **devolver** (`DROPOFF`) una bicicleta, a partir de la ubicación del usuario y del estado actual
de las estaciones. No tiene base de datos propia: el backend le envía el estado de las candidatas en cada
request y, si el servicio no responde, usa un criterio de respaldo.

## Qué parte es IA y qué parte no

| Parte | ¿Es IA/ML? | Dónde |
|---|---|---|
| Ranking de candidatas (qué estación conviene más) | **Sí**: regresión logística entrenada | `app/recommender.py`, `app/scorer.py` |
| Explicación (`factors`) | **Sí**: aportes del modelo (coeficiente × valor estandarizado) | `app/explainer.py` |
| Código de explicación (`NEAREST_LOW_AVAILABILITY`, etc.) | No: describe el estado actual de la estación más cercana | `app/explainer.py` |
| Guardrail "una estación sin recurso nunca se recomienda" | No: regla de negocio | `app/recommender.py` |
| Desempate por stock → distancia → id | No: determinismo | `app/recommender.py` |
| Fallback cuando el servicio no responde | No: responsabilidad del consumidor | Backend (`StationRecommendationService`) |
| Filtro de estaciones deshabilitadas | No: el consumidor envía solo estaciones habilitadas | Backend (`NearbyStationService`) |

## Model card

**Algoritmo:** `Pipeline(StandardScaler, LogisticRegression)`, un modelo por propósito. Clasifica cada
candidata como "buena elección" (1) o no (0); `predict_proba` es el score con el que se rankea. Por qué este
algoritmo y no otros: [ADR-002](../docs/adr/ADR-002-modelo-regresion-logistica.md).

**Features** (`app/features.py`, compartidas entre entrenamiento e inferencia). "Recurso" = bicicletas en
PICKUP, anclajes libres en DROPOFF.

| Feature | Qué mide |
|---|---|
| `distance_m` | Distancia en línea recta (haversine) del usuario a la estación |
| `distance_excess_m` | Metros extra respecto de la candidata más cercana |
| `secure_units` | Unidades del recurso, saturadas en 5 (por encima, más stock ya no reduce el riesgo de llegar y no encontrar) |
| `resource_ratio` | Unidades / capacidad de la estación |
| `resource_share` | Unidades / máximo entre las candidatas del escenario |

`secure_units` reemplazó a `log(1 + unidades)` después de que un test mostrara que, con el log, el modelo
lineal prefería una estación a 120 m con 1 bici sobre otra a 400 m con 12. Con la feature saturada el acierto
top-1 subió de 90% a 95% y todos los coeficientes quedaron con signo interpretable.

**Dataset de entrenamiento v1 (sintético)** — `training/sources.py::SyntheticDataSource`:

- 4.000 escenarios por propósito (semilla 42). Cada escenario tiene 3 a 12 candidatas con distancias
  realistas (la más cercana a 30–500 m, el resto más lejos) y stock variado (15% vacías, 25% con 1–3
  unidades, resto entre 4 y la capacidad).
- **Etiquetado con regla de sentido común, usada solo para etiquetar** (el modelo no la ve):
  costo = metros/100 + 2 × unidades faltantes para el nivel seguro (4 bicis / 3 anclajes). Es buena
  elección la candidata viable (con recurso y a ≤ 1.500 m) cuyo costo está a menos de 1 del mejor del
  escenario. Se agrega 5% de ruido en las etiquetas para que el modelo generalice en vez de memorizar.
- Holdout del 20% separado **por escenario** (las candidatas de una consulta nunca quedan repartidas).

**Métricas en el holdout** (se regeneran en `models/<versión>/model_card.json` y en `GET /v1/model`):

| Propósito | ROC-AUC | Accuracy | Top-1* |
|---|---|---|---|
| PICKUP | 0.903 | 0.880 | 0.949 |
| DROPOFF | 0.913 | 0.881 | 0.934 |

\* Top-1: porcentaje de escenarios en los que la estación mejor rankeada por el modelo es una buena elección.

**Coeficientes aprendidos (PICKUP):** `distance_excess_m` −2.45 (caminar de más penaliza), `secure_units` +1.35
(tener stock seguro suma), `resource_share` +0.21, el resto cerca de 0. En DROPOFF: −2.96 y +1.24. Los pesos los aprende el modelo; no
están escritos a mano.

## Limitaciones actuales

- Entrenado con **datos sintéticos**: aprende compromisos generales entre distancia
  y escasez, no patrones reales de estaciones u horarios. Por eso la explicación nunca afirma cosas como "esta
  estación se suele vaciar a esta hora" (esos códigos están reservados en el contrato).
- Usa la disponibilidad **instantánea**: no predice cuánto va a cambiar hasta que el usuario llegue.
- Distancia en línea recta, no a pie por calles.
- Modelo lineal: relaciones más complejas requieren nuevas features.

## Uso

```bash
python -m venv .venv
.venv/Scripts/pip install -r requirements-dev.txt     # Windows (en Linux/Mac: .venv/bin/pip)
python -m training.train                               # entrena v1 sintético y lo deja vigente
uvicorn app.main:app --port 8000                       # API en http://localhost:8000, OpenAPI en /docs
pytest --cov                                           # tests + cobertura
```

Endpoints: `POST /v1/recommendations`, `GET /v1/model` (model card), `GET /health`.
Variables: `MODEL_DIR` (default `./models`), `PORT` (Docker/Render).

Si no hay modelo entrenado al arrancar, el servicio entrena el sintético automáticamente (tarda unos segundos).

### Dependencias

`requirements.txt` / `requirements-dev.txt` declaran las dependencias directas. El CI y el Dockerfile instalan
desde `requirements.lock` / `requirements-dev.lock`, que fijan también las transitivas con sus hashes
(`pip install --require-hashes --only-binary :all:`). Si cambiás un `.txt`, regenerá el lock correspondiente:

```bash
uv pip compile requirements-dev.txt -o requirements-dev.lock --generate-hashes \
  --python-version 3.12 --python-platform x86_64-unknown-linux-gnu --only-binary :all: --no-header
```

(Lo mismo con `requirements.txt` → `requirements.lock`.)

## Evolución

El entrenamiento recibe los datos a través del protocolo `TrainingDataSource` (`training/sources.py`). Para
reentrenar con datos de uso (por ejemplo, un registro de recomendaciones con la estación que el usuario terminó
eligiendo) se implementa otra fuente y se la pasa a `train_bundle`, sin tocar el modelo, la inferencia ni el
contrato HTTP. El `ranking` completo de cada respuesta ya trae lo necesario para registrar el snapshot.
