# MOV-041 — Recomendación inteligente de estaciones

Jira: `SCRUM-68` (EPIC-07 — IA / Innovación). La integración con el backend es MOV-042 (`SCRUM-69`).

Microservicio que recomienda la estación más conveniente para **retirar** (`PICKUP`) o **devolver**
(`DROPOFF`) una bicicleta. Considera la proximidad y la disponibilidad actual, así que puede sugerir una
estación más lejana que la más cercana si esa tiene stock escaso. La decisión la toma un **modelo de machine
learning entrenado** (regresión logística, scikit-learn), no una fórmula con pesos escritos a mano.

- Código: [`recommendation-service/`](../recommendation-service) (Python 3.12 + FastAPI). Su README tiene el
  **model card** completo (features, dataset, métricas y coeficientes).
- Decisiones:
  - [ADR-001](adr/ADR-001-recomendacion-http-sincrona.md) — el servicio se consume por HTTP síncrono (Kafka
    queda para eventos entre módulos).
  - [ADR-002](adr/ADR-002-modelo-regresion-logistica.md) — por qué regresión logística y no pesos fijos, kNN,
    Isolation Forest o deep learning.

## Qué es IA/ML y qué no

| Parte | ¿IA/ML? |
|---|---|
| Ranking de estaciones candidatas | **Sí**: regresión logística entrenada; el score es la probabilidad de "buena elección" |
| Factores de la explicación (`explanation.factors`) | **Sí**: aporte de cada feature al modelo (coeficiente × valor estandarizado) |
| Entrenamiento y evaluación | **Sí**: pipeline reproducible con holdout y métricas publicadas en el model card |
| Código de explicación (`explanation.code`) | No: describe el estado actual de la estación más cercana |
| "Una estación sin recurso nunca se recomienda" | No: guardrail de negocio |
| Filtro de estaciones deshabilitadas, timeout y fallback ante caída | No: responsabilidad del consumidor (MOV-042) |

## El modelo

- **Tarea:** clasificación binaria por candidata ("buena elección" sí/no); `predict_proba` rankea.
- **Algoritmo:** `Pipeline(StandardScaler, LogisticRegression)`, un modelo por propósito.
- **Features** (relativas al recurso: bicis para retirar, anclajes para devolver): distancia, distancia extra
  respecto de la más cercana, unidades saturadas en un nivel seguro (5), proporción sobre la capacidad y
  proporción respecto de la mejor candidata.
- **Entrenamiento (`logreg-synthetic-v1`):** 4.000 escenarios sintéticos por propósito. La regla de sentido
  común vive **solo en el etiquetado** (el modelo aprende la relación a partir de ejemplos) y tiene 5% de ruido.
  Holdout del 20% separado por escenario. El modelo se entrena en el build de la imagen Docker.
- **Métricas en el holdout:** PICKUP ROC-AUC 0.903 / top-1 94.9%; DROPOFF ROC-AUC 0.913 / top-1 93.4%.
- **Coeficientes aprendidos (PICKUP):** distancia extra −2.45, unidades seguras +1.35. Caminar de más penaliza
  y tener stock suficiente suma, sin que nadie haya fijado esos pesos.

Un hallazgo durante el desarrollo: con `log(1 + unidades)` como feature, el modelo lineal prefería una estación
a 120 m con 1 bici sobre otra a 400 m con 12. Lo detectó un test. Reemplazarla por unidades saturadas corrigió
el caso y subió el acierto top-1 de 90% a 95%.

Ejemplo real con estaciones de Ecobici: con DIAGONAL NORTE (193 m) con 1 bici y CERRITO (236 m) con 13, el
modelo recomienda CERRITO con `explanation.code = NEAREST_LOW_AVAILABILITY`.

## Fallback del servicio

Siempre responde `200` para casos de negocio:

- Sin candidatas → `status = NO_RECOMMENDATION`, `reason = NO_CANDIDATES`.
- Ninguna candidata con el recurso → `status = NO_RECOMMENDATION`, `reason = NO_VIABLE_STATION`, con el
  `ranking` completo igual.
- Payload inválido (coordenadas fuera de rango o `NaN`, stock negativo, ids repetidos, `purpose` desconocido)
  → `422`.

Si el servicio no está disponible (caído, dormido en Render o lento), el fallback lo tiene que resolver el
consumidor (ver ADR-001).

## Contrato para la integración (MOV-042)

Documentación interactiva: `GET /docs` (OpenAPI en `/openapi.json`). Otros endpoints: `GET /health` y
`GET /v1/model` (model card).

`POST /v1/recommendations`

```json
{
  "purpose": "PICKUP",
  "user": { "lat": -34.6037, "lng": -58.3816 },
  "stations": [
    { "stationId": 44, "lat": -34.6046, "lng": -58.3798, "capacity": 18, "availableBikes": 1, "availableDocks": 17 },
    { "stationId": 77, "lat": -34.6026, "lng": -58.3838, "capacity": 30, "availableBikes": 13, "availableDocks": 17 }
  ]
}
```

- `stations`: solo estaciones **habilitadas**, hasta 200. El `stationId` es el id de `Station` del backend.
- `purpose`: `PICKUP` (default) o `DROPOFF`.

Respuesta real del modelo `logreg-synthetic-v1` para ese request:

```json
{
  "status": "RECOMMENDED",
  "reason": null,
  "purpose": "PICKUP",
  "modelVersion": "logreg-synthetic-v1",
  "recommendation": { "stationId": 77, "score": 0.768498, "distanceMeters": 236, "availableBikes": 13, "availableDocks": 17 },
  "alternatives": [ { "stationId": 44, "score": 0.151128, "distanceMeters": 193, "availableBikes": 1, "availableDocks": 17 } ],
  "ranking": [ { "stationId": 77, "score": 0.768498, "rank": 1, "distanceMeters": 236 },
               { "stationId": 44, "score": 0.151128, "rank": 2, "distanceMeters": 193 } ],
  "explanation": {
    "code": "NEAREST_LOW_AVAILABILITY",
    "comparedTo": { "stationId": 44, "distanceMeters": 193, "availableBikes": 1, "availableDocks": 17 },
    "factors": [ { "feature": "secure_units", "impact": 2.759 }, { "feature": "resource_share", "impact": 0.545 },
                 { "feature": "distance_excess_m", "impact": -0.3 }, "..." ]
  }
}
```

| Campo | Descripción |
|---|---|
| `recommendation` | Estación recomendada con su score (0–1) |
| `alternatives` | Hasta 2 alternativas viables, en orden |
| `ranking` | Todas las candidatas rankeadas (útil si se quiere registrar la recomendación para reentrenar) |
| `explanation.comparedTo` | La estación más cercana, cuando no es la recomendada (`null` si la recomendada es la más cercana) |
| `explanation.factors` | Aporte de cada feature a la decisión, de mayor a menor impacto. Positivo favorece a la recomendada |
| `modelVersion` | Versión del modelo que decidió |

Códigos de explicación. Con `explanation.code` y `comparedTo` el consumidor puede armar el mensaje para el
usuario, por ejemplo: *"Te recomendamos CERRITO (236 m) en lugar de DIAGONAL NORTE (193 m): en DIAGONAL NORTE
queda solo 1 bicicleta y podría no estar cuando llegues"*.

| `code` | Cuándo |
|---|---|
| `NEAREST_IS_BEST` | La recomendada es la más cercana |
| `NEAREST_HAS_NO_BIKES` / `NEAREST_NO_DOCKS` | La más cercana no tiene bicis / anclajes libres |
| `NEAREST_LOW_AVAILABILITY` / `NEAREST_FEW_DOCKS` | A la más cercana le quedan 1 o 2 unidades: pueden agotarse antes de llegar |
| `BETTER_AVAILABILITY_NEARBY` | Hay bastante más disponibilidad a poca distancia extra |
| `NEAREST_USUALLY_EMPTY_AT_THIS_HOUR` / `NEAREST_USUALLY_FULL_AT_THIS_HOUR` | **Reservados**, no se emiten: requieren historial real |

## Deploy

`render.yaml` define el servicio `movilidad-recommendation` (Docker, plan free), que se redeploya solo cuando
cambia `recommendation-service/`. En el plan free se duerme sin tráfico y tarda 30–60 s en despertar.
Conviene que el consumidor aplique timeouts cortos y haga un ping a `/health` para despertarlo.

## Limitaciones actuales

- Datos de entrenamiento **sintéticos**: el modelo aprende compromisos generales (distancia vs. escasez), no
  patrones reales de estaciones u horarios. Por eso la explicación nunca afirma cosas como "esta estación se
  suele vaciar a esta hora".
- Usa la disponibilidad instantánea, sin predecir cómo cambia hasta que el usuario llega.
- Distancia en línea recta (haversine), no recorrido a pie.

## Evolución prevista (sin ticket)

- **Reentrenar con datos de uso:** registrar cada recomendación con su snapshot (el `ranking` ya lo trae) y la
  estación que el usuario terminó eligiendo (vínculo con el viaje). Con eso se implementa otra
  `TrainingDataSource` y se reentrena sin tocar el modelo ni el contrato. Como el proyecto no tiene usuarios
  reales, esos datos habría que generarlos con una simulación rotulada como tal.
- Features horarias y predicción de ocupación cuando exista historial real.
- El `Scorer` es intercambiable: se puede cambiar el modelo sin tocar el contrato.
