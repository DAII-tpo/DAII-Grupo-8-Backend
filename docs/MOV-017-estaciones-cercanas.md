# MOV-017 — Estaciones cercanas por geolocalización

Jira: `SCRUM-41` (EPIC-04 — Mapas y Rutas).

Permite responder "qué estaciones tengo cerca" a partir de la ubicación del usuario,
ordenadas por distancia y con la disponibilidad de cada una. Es el insumo de MOV-020 (mapa
real con la posición del usuario y consulta de estaciones cercanas).

## Endpoint

| Método | Path |
|---|---|
| `GET` | `/api/v1/stations/nearby?lat={lat}&lng={lng}&radius={metros}&limit={n}` |

| Parámetro | Obligatorio | Default | Descripción |
|---|---|---|---|
| `lat` | sí | — | Latitud del usuario, entre -90 y 90 |
| `lng` | sí | — | Longitud del usuario, entre -180 y 180 |
| `radius` | no | 500 m | Radio de búsqueda en metros. Tope: 5000 |
| `limit` | no | 10 | Máximo de resultados. Tope: 50 |

`radius` y `limit` se **recortan** contra su tope en lugar de rechazarse: un cliente no puede
pedir la ciudad entera, pero tampoco recibe un error por pasarse.

### Respuesta

```json
[
  {
    "stationId": 1,
    "stationName": "Estación Obelisco",
    "address": "Av. 9 de Julio 1000",
    "latitude": -34.6037000,
    "longitude": -58.3816000,
    "distanceMeters": 0,
    "capacity": 20,
    "availableBikes": 7,
    "availableSlots": 13
  }
]
```

Ordenada por `distanceMeters` ascendente. Incluye `latitude`/`longitude` porque MOV-020 las
necesita para ubicar los markers sin tener que pedir cada estación por separado.

### Errores

| Código | Cuándo |
|---|---|
| `400 Bad Request` | Falta `lat` o `lng`; coordenada fuera de rango; valor no numérico; `radius`/`limit` no positivos |

Usa el `ErrorResponse` común del módulo (`timestamp`, `status`, `error`, `message`, `path`).

## Reglas de negocio

- Solo estaciones con `status = 'ACTIVE'` y sin baja lógica. Las `INACTIVE` y las que están
  en `MAINTENANCE` quedan excluidas.
- La distancia la calcula MySQL con `ST_Distance_Sphere`, en metros, y se redondea al entero
  más cercano en la respuesta.
- La disponibilidad sale del **mismo cálculo de MOV-016**: se delega en
  `StationAvailabilityService.availabilityFor(...)`, que es el único lugar del módulo donde
  vive la regla de bicis disponibles y anclajes libres.

## Implementación

- `NearbyStationController` — solo valida y orquesta HTTP.
- `NearbyStationService` — resuelve defaults y topes, arma la caja de coordenadas, consulta y
  pega la disponibilidad conservando el orden que devolvió la base.
- `GeoBoundingBox` — calcula una caja que contiene por completo al círculo buscado.
- `StationRepository.findNearby(...)` — la consulta nativa.

### Sobre la consulta

```sql
SELECT * FROM (
    SELECT s.id AS id, ...,
           ST_Distance_Sphere(POINT(s.longitude, s.latitude), POINT(:longitude, :latitude))
               AS distanceMeters
    FROM stations s
    WHERE s.deleted_at IS NULL
      AND s.status = :activeStatus
      AND s.latitude  BETWEEN :minLatitude  AND :maxLatitude
      AND s.longitude BETWEEN :minLongitude AND :maxLongitude
) nearby
WHERE nearby.distanceMeters <= :radiusMeters
ORDER BY nearby.distanceMeters ASC
LIMIT :maxResults
```

Tres detalles que no son obvios:

1. **`POINT` recibe (longitud, latitud), en ese orden.** Invertirlo no rompe nada: devuelve
   distancias silenciosamente equivocadas. Por eso el test de integración usa coordenadas
   reales de Buenos Aires y compara contra distancias conocidas.
2. **El `BETWEEN` es un prefiltro por caja**, no el filtro final. Existe para que la consulta
   pueda usar el índice `idx_stations_lat_lng` creado en MOV-012: una función sobre las
   columnas no puede aprovechar el índice y obligaría a recorrer la tabla entera. La caja
   siempre contiene al círculo completo, así que nunca deja afuera una estación que sí
   corresponde; el filtro exacto lo hace el `WHERE` sobre la distancia real.
3. **Se usa una tabla derivada en lugar de `HAVING`**, porque `HAVING` sin `GROUP BY` puede
   fallar según el `sql_mode` del servidor (`ONLY_FULL_GROUP_BY`).

### Casos de borde de la caja

Cerca de los polos y del antimeridiano la caja se abriría de forma incorrecta (un `BETWEEN`
no puede expresar un rango que da la vuelta por ±180). En esos casos `GeoBoundingBox` abre el
rango completo de longitud: sigue siendo un superconjunto del círculo, y el filtro por
distancia real se encarga del resto. Irrelevante para Buenos Aires, pero evita un resultado
mal calculado si el módulo se reutiliza en otra ciudad.

## Configuración

```properties
movilidad.stations.nearby.default-radius-meters=500
movilidad.stations.nearby.max-radius-meters=5000
movilidad.stations.nearby.default-limit=10
movilidad.stations.nearby.max-limit=50
```

El radio operativo de 500 m es una decisión de negocio, no una constante técnica: va por
configuración, como pide la sección 8 del `CLAUDE.md`.

## ADR — Distancia calculada con `ST_Distance_Sphere` sobre columnas `DECIMAL`

> Formato según la sección 7 de `CLAUDE.md`. **Sin número asignado**: los ADRs del squad no
> están versionados en este repositorio.

### Contexto

MOV-017 necesita ordenar estaciones por distancia a un punto arbitrario y filtrar por radio.
El modelo de MOV-012 —cerrado— guarda la ubicación de cada estación como dos columnas
`DECIMAL(10,7)` (`latitude`, `longitude`) con un índice compuesto `idx_stations_lat_lng`;
no hay columna geométrica ni índice espacial. La tarea pide explícitamente usar "las
capacidades geoespaciales de MySQL definidas en el stack", y el stack es MySQL 8.

El volumen es de unos cientos de estaciones (el dataset de Ecobici que importa MOV-014).

### Alternativas consideradas

1. **`ST_Distance_Sphere` con `POINT` armados al vuelo desde las columnas existentes.**
   Pros: usa la función geoespacial nativa de MySQL 8, ordena y filtra en la base, no toca el
   modelo de datos ni requiere migración, y el índice existente sigue sirviendo vía prefiltro
   por caja. Contras: la función en sí no puede usar un índice espacial, así que depende del
   prefiltro para no recorrer la tabla.
2. **Agregar una columna `POINT` con índice `SPATIAL`.**
   Pros: es la solución canónica a gran escala y luce mejor en el DER. Contras: obliga a una
   migración sobre un modelo ya cerrado en MOV-012, a rellenar la columna para las filas
   existentes, y a mantenerla sincronizada con `latitude`/`longitude` en cada alta o
   actualización de estación —una fuente de inconsistencia nueva—. El beneficio de
   rendimiento es imperceptible con cientos de filas.
3. **Fórmula de Haversine en Java.**
   Pros: sin SQL específico de motor, trivial de testear sin base. Contras: no usa las
   capacidades geoespaciales de MySQL que pide la tarea, obliga a traer todas las estaciones
   activas a memoria, y mueve el orden y el límite a la aplicación.

### Decisión

Se elige la alternativa 1. Cumple literalmente lo que pide `SCRUM-41`, deja el trabajo de
ordenar y filtrar donde mejor se hace —la base—, y no introduce ni migración ni estado
duplicado en un modelo que ya está cerrado y que es entregable del Hito 1.

El prefiltro por caja de coordenadas es lo que hace viable la decisión: acota el conjunto
usando el índice ya existente antes de calcular la distancia exacta.

### Consecuencias

- **Modelo de datos**: sin cambios. El DER de MOV-010/MOV-011 no se toca.
- **APIs**: la distancia viaja en metros como entero; el contrato no expone nada del motor.
- **Testing**: la parte geoespacial solo se puede verificar contra MySQL real, así que hay un
  test de integración con Testcontainers que valida distancias conocidas, orden, radio,
  límite y exclusión de estaciones inactivas.
- **Portabilidad**: la consulta es nativa y usa una función propia de MySQL. Si el squad
  cambiara de motor habría que reescribirla; con el stack fijado en MySQL 8, es un costo
  aceptado.
- **Deuda técnica asumida**: si el número de estaciones creciera en órdenes de magnitud,
  habría que revisar esta decisión y evaluar la columna geométrica con índice espacial.

## Dependencias y gaps señalados

- **Login Federado (Grupo 2, dependencia externa):** el endpoint queda público en la cadena
  de seguridad, junto con los de disponibilidad y con el mismo `TODO`, para que el frontend
  pueda consumirlo mientras no exista el authorization server.
- **MOV-021 (manejo global de errores), `Por hacer` y sin asignar:** para cumplir el criterio
  "coordenadas inválidas generan error controlado" se sumaron tres handlers al
  `GlobalExceptionHandler` (`ConstraintViolationException`,
  `MissingServletRequestParameterException` y `MethodArgumentTypeMismatchException`). Sin
  ellos esos casos devolvían `500`. Es un aporte puntual, no la estandarización completa que
  pide MOV-021.
- **Planificador de viajes / ruteo:** fuera de alcance. MOV-017 responde "qué hay cerca", no
  "cuál conviene para ir a tal destino".
