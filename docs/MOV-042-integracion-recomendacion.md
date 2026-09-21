# MOV-042 — Integración de la recomendación inteligente

Jira: `SCRUM-69` (EPIC-07 — IA / Innovación). Depende de
[MOV-041](MOV-041-recomendacion-inteligente.md), que es el microservicio de recomendación, y alimenta a
MOV-043 (pantalla del frontend).

Expone la recomendación de estación como un endpoint del módulo de Movilidad. El backend arma el
snapshot de estaciones candidatas, se lo manda al servicio de recomendación y traduce su respuesta a un
contrato propio, con nombres de estación y un mensaje en español listo para mostrar.

**El servicio de recomendación es opcional**: si no responde, el endpoint contesta igual con la estación
más cercana que tenga el recurso necesario.

## Endpoint

| Método | Path |
|---|---|
| `GET` | `/api/v1/stations/recommendation?lat={lat}&lng={lng}&purpose={PICKUP\|DROPOFF}` |

| Parámetro | Obligatorio | Default | Descripción |
|---|---|---|---|
| `lat` | sí | — | Latitud del usuario, entre -90 y 90 |
| `lng` | sí | — | Longitud del usuario, entre -180 y 180 |
| `purpose` | no | `PICKUP` | `PICKUP` para retirar una bicicleta, `DROPOFF` para devolverla |

Público, igual que `/nearby`. Usa `lng` (no `lon`) para mantener el mismo nombre que el resto del módulo.

### Respuesta

Siempre `200`, incluso cuando no hay nada para recomendar. El estado real viaja en el cuerpo.

```json
{
  "status": "RECOMMENDED",
  "source": "MODEL",
  "purpose": "PICKUP",
  "reason": null,
  "message": "Te recomendamos CERRITO (236 m) en lugar de DIAGONAL NORTE (193 m): en DIAGONAL NORTE queda solo 1 bicicleta disponible y podría no estar cuando llegues; en CERRITO hay 13.",
  "station": {
    "stationId": 77,
    "stationName": "CERRITO",
    "address": "Cerrito 1300",
    "latitude": -34.6026000,
    "longitude": -58.3838000,
    "distanceMeters": 236,
    "capacity": 30,
    "availableBikes": 13,
    "availableSlots": 17,
    "score": 0.768498
  },
  "alternatives": [],
  "explanation": {
    "code": "NEAREST_LOW_AVAILABILITY",
    "comparedTo": {
      "stationId": 44,
      "stationName": "DIAGONAL NORTE",
      "distanceMeters": 193,
      "availableBikes": 1,
      "availableSlots": 17
    },
    "factors": [{ "feature": "secure_units", "impact": 2.759 }]
  },
  "modelVersion": "logreg-synthetic-v1",
  "generatedAt": "2025-09-20T14:31:05Z"
}
```

| Campo | Descripción |
|---|---|
| `status` | `RECOMMENDED` o `NO_RECOMMENDATION` |
| `source` | `MODEL` (decidió el modelo) o `FALLBACK` (decidió el backend) |
| `reason` | Solo con `NO_RECOMMENDATION`: `NO_CANDIDATES` o `NO_VIABLE_STATION` |
| `message` | Texto en español listo para mostrar. El frontend puede usarlo tal cual o armar el suyo con `explanation.code` |
| `station` | Estación recomendada. `null` con `NO_RECOMMENDATION` |
| `alternatives` | Hasta 2 alternativas viables, en orden de conveniencia |
| `explanation` | Justificación del modelo. `null` cuando `source` es `FALLBACK` |
| `explanation.comparedTo` | La estación más cercana, cuando no es la recomendada |
| `explanation.factors` | Cuánto pesó cada señal del modelo; positivo favorece a la recomendada |
| `modelVersion` | Versión del modelo que decidió. `null` con `FALLBACK` |
| `generatedAt` | Momento de la consulta. La disponibilidad cambia: pasado un rato conviene volver a consultar |

Los datos de estación (`stationName`, `address`, `capacity`, coordenadas, `distanceMeters`) salen siempre
del snapshot del backend, no de la respuesta del servicio de recomendación, que solo conoce ids y números.

#### Códigos de `explanation.code`

| `code` | Cuándo |
|---|---|
| `NEAREST_IS_BEST` | La recomendada es también la más cercana |
| `NEAREST_HAS_NO_BIKES` / `NEAREST_NO_DOCKS` | La más cercana no tiene bicicletas / anclajes libres |
| `NEAREST_LOW_AVAILABILITY` / `NEAREST_FEW_DOCKS` | A la más cercana le quedan 1 o 2 unidades: pueden agotarse antes de llegar |
| `BETTER_AVAILABILITY_NEARBY` | Hay bastante más disponibilidad a poca distancia extra |

Los códigos describen el estado **actual** de la estación; nunca afirman nada sobre su historial, porque
el modelo no lo conoce (ver limitaciones en MOV-041). Los dos códigos reservados de MOV-041
(`NEAREST_USUALLY_*_AT_THIS_HOUR`) no se aceptan a propósito: hoy no se emiten y no hay mensaje que se
pueda afirmar sin historial real. Si llegaran, la respuesta se trata como no confiable y se cae al
criterio de respaldo.

### Cuando no hay recomendación

| `reason` | Cuándo | `message` |
|---|---|---|
| `NO_CANDIDATES` | No hay estaciones habilitadas dentro del radio de búsqueda | "No encontramos estaciones cerca de tu ubicación." |
| `NO_VIABLE_STATION` | Hay estaciones cerca, pero ninguna tiene el recurso necesario | "Ninguna estación cerca tiene bicicletas disponibles en este momento." |

### Errores

| Código | Cuándo |
|---|---|
| `400 Bad Request` | Falta `lat` o `lng`; coordenada fuera de rango o no numérica; `purpose` desconocido |

Usa el `ErrorResponse` común del módulo (MOV-021). **Una falla del servicio de recomendación nunca
produce un error**: se resuelve con el criterio de respaldo.

## Criterio de respaldo (fallback)

Si el servicio de recomendación no está disponible —caído, dormido, lento, con un error, con una
respuesta ilegible o con una estación que no estaba en el snapshot enviado— el backend responde con la
**candidata más cercana que tenga el recurso necesario** (bicicletas para `PICKUP`, anclajes libres para
`DROPOFF`), con `source = FALLBACK`, sin `explanation` ni `score`, y deja un WARN en el log.

Después de cada respaldo el backend hace un ping asíncrono a `/health` del servicio. En el plan free de
Render el servicio se duerme sin tráfico y tarda 30-60 s en despertar: así la siguiente consulta ya
llega al modelo. El mismo ping se hace al arrancar el backend.

## Reglas de negocio

- Las candidatas salen de `NearbyStationService.findNearby(...)` (MOV-017), que ya excluye las
  estaciones `INACTIVE`, en `MAINTENANCE` y las dadas de baja. Una estación deshabilitada **nunca** se
  envía al modelo ni aparece en la respuesta, aunque sea la más cercana y la que más stock tiene.
- La disponibilidad es la misma de MOV-016 (`StationAvailabilityService`): un solo lugar define qué es
  una bicicleta disponible y un anclaje libre.
- Una estación sin el recurso necesario nunca se recomienda. Es un guardrail del servicio de
  recomendación y el backend lo repite en su criterio de respaldo.

## Configuración

| Property | Variable | Default | Descripción |
|---|---|---|---|
| `movilidad.recommendation.base-url` | `RECOMMENDATION_SERVICE_URL` | `http://localhost:8000` | URL del servicio de recomendación |
| `movilidad.recommendation.connect-timeout-millis` | — | 1000 | Timeout de conexión |
| `movilidad.recommendation.read-timeout-millis` | — | 2000 | Timeout de lectura |
| `movilidad.recommendation.candidate-radius-meters` | — | 1000 | Radio en el que se buscan candidatas |
| `movilidad.recommendation.max-candidates` | — | 15 | Tope de estaciones del snapshot |
| `movilidad.recommendation.warm-up-enabled` | — | `true` | Ping a `/health` al arrancar y tras cada respaldo |

Los timeouts son cortos a propósito: la recomendación mejora la experiencia pero no es imprescindible.
Antes que hacer esperar al usuario, el backend responde con el criterio de respaldo.

En Render, `RECOMMENDATION_SERVICE_URL` va con `sync: false` y se carga en el dashboard con la URL del
servicio `movilidad-recommendation`.

## Implementación

- `StationRecommendationController` — valida coordenadas (`@Latitude` / `@Longitude`, MOV-021) y orquesta.
- `StationRecommendationService` — arma el snapshot, llama al cliente, traduce la respuesta y resuelve el
  respaldo. Sin `@Transactional`: una llamada HTTP no debe mantener abierta una transacción de base.
- `RecommendationClient` — habla HTTP con el servicio y traduce **cualquier** forma de falla a
  `RecommendationUnavailableException`, que a propósito no extiende `ApiException` para que nunca pueda
  llegar al cliente como error.
- `RecommendationClientConfig` — `RestClient` con timeouts cortos y HTTP/1.1 forzado (uvicorn no negocia
  el upgrade a HTTP/2 que el cliente del JDK intenta por defecto).
- `RecommendationMessageComposer` — arma el mensaje en español a partir del `code`, con los nombres
  reales de las estaciones.

## Probar de punta a punta

1. Levantar el servicio de recomendación:
   ```bash
   cd recommendation-service && python -m uvicorn app.main:app --port 8000
   ```
   La primera vez entrena el modelo (~20 s) y lo deja cacheado en `recommendation-service/models/`.
2. Levantar el backend importando las estaciones (solo hace falta la primera vez):
   ```bash
   ECOBICI_IMPORT_ENABLED=true ./gradlew bootRun
   ```
3. **Sembrar bicicletas.** El import trae las estaciones pero ninguna bicicleta, y sin bicicletas
   `PICKUP` siempre responde `NO_VIABLE_STATION`. Con 1 bicicleta en la estación más cercana y 13 en la
   siguiente se reproduce el escenario del ejemplo:
   ```bash
   for pair in "44:1" "77:13" "7:6"; do st=${pair%%:*}; n=${pair##*:}; for i in $(seq 1 $n); do curl -s -o /dev/null -X POST http://localhost:8080/api/v1/bikes -H 'Content-Type: application/json' -d "{\"code\":\"DEMO-$st-$i\",\"stationId\":$st,\"status\":\"AVAILABLE\"}"; done; done
   ```
   Los ids 44 (DIAGONAL NORTE), 77 (CERRITO) y 7 (OBELISCO) son los del dataset oficial de Ecobici;
   se pueden confirmar con `GET /api/v1/stations/nearby?lat=-34.6037&lng=-58.3816&radius=1000`.
4. `GET /api/v1/stations/recommendation?lat=-34.6037&lng=-58.3816` → `source = MODEL`, recomienda
   CERRITO con `NEAREST_LOW_AVAILABILITY`.
5. Deshabilitar una estación cercana (`status = INACTIVE`) → deja de aparecer.
6. Apagar uvicorn y repetir la consulta → `200` con `source = FALLBACK` en pocos milisegundos.

## Evolución prevista (sin ticket)

Guardar cada recomendación con su snapshot y vincularla al viaje que la usó, para saber si el usuario la
siguió y reentrenar el modelo con datos de uso. El `ranking` completo que devuelve el servicio ya trae
todo lo necesario; hoy el backend lo descarta porque el frontend no lo usa.
