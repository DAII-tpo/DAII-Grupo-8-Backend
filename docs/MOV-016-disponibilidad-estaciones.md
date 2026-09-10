# MOV-016 — Consulta de disponibilidad por estación

Jira: `SCRUM-40` (EPIC-01 — Gestión de Bicicletas).

Permite conocer, para una estación, cuántas bicicletas se pueden retirar y cuántos anclajes
quedan libres para devolver. Es el insumo de MOV-019 (disponibilidad en el frontend),
MOV-020 (markers del mapa) y MOV-017 (estaciones cercanas, que incluye disponibilidad en
cada resultado).

## Endpoints

| Método | Path | Descripción |
|---|---|---|
| `GET` | `/api/v1/stations/{stationId}/availability` | Disponibilidad de una estación |
| `GET` | `/api/v1/stations/availability` | Disponibilidad de todas las estaciones vigentes |

Ambos son de solo lectura y no reciben parámetros más allá del `stationId`.

### Respuesta

```json
{
  "stationId": 1,
  "stationName": "Estación 9 de Julio",
  "status": "ACTIVE",
  "capacity": 20,
  "availableBikes": 7,
  "availableSlots": 13,
  "checkedAt": "2026-09-08T14:30:00Z"
}
```

### Errores

| Código | Cuándo |
|---|---|
| `404 Not Found` | La estación no existe o tiene baja lógica (`deleted_at`) |

Usa el `ErrorResponse` común del módulo (`timestamp`, `status`, `error`, `message`, `path`).

## Reglas de negocio

Sobre una estación con `deleted_at IS NULL`:

- **`capacity`**: la capacidad declarada de la estación.
- **`availableBikes`**: bicicletas de la estación en estado `AVAILABLE` y sin baja lógica.
  Son las únicas que un usuario puede retirar.
- **anclajes ocupados**: todas las bicicletas presentes en la estación sin baja lógica,
  **en cualquier estado**. Una bicicleta en `MAINTENANCE` ocupa físicamente un anclaje
  aunque no se pueda retirar. Una bicicleta `IN_USE` tiene `station_id` en `NULL` mientras
  dura el viaje, así que no ocupa anclaje en ninguna estación.
  Es el mismo criterio que ya aplica `BikeService.ensureCapacity()` (MOV-015): hay una sola
  definición de "ocupación" en el módulo.
- **`availableSlots`**: `max(0, capacity - anclajes ocupados)`. El piso en cero evita
  valores negativos si el dataset importado (MOV-014) trae más bicicletas que anclajes
  declarados.
- Una estación **responde aunque tenga cero bicicletas disponibles**.
- Las estaciones `INACTIVE` o en `MAINTENANCE` **también responden**, con su `status` en el
  cuerpo para que el cliente decida qué mostrar. Excluirlas es criterio de MOV-017, no de
  esta tarea.

## Implementación

- `StationAvailabilityController` — solo orquesta HTTP.
- `StationAvailabilityService` — reglas de negocio y armado del DTO.
- `BikeRepository.countBikesByStationIds(...)` — **una sola consulta agrupada** que devuelve,
  por estación, bicicletas presentes y bicicletas disponibles. Tanto la consulta de una
  estación como la de todas usan esta misma query: no hay N+1, y MOV-017 la puede reutilizar
  tal cual.
- Una estación sin bicicletas no genera fila en el resultado; el service la interpreta como
  cero, no como error.

No se agregaron columnas ni migraciones: el esquema de MOV-012 ya tiene todo lo necesario.

## ADR — Disponibilidad calculada on-read en lugar de materializada

> Formato según la sección 7 de `CLAUDE.md`. **Sin número asignado**: los ADRs del squad no
> están versionados en este repositorio, así que al portarlo hay que numerarlo según la
> serie existente de Sprint 0.

### Contexto

La disponibilidad de una estación (bicicletas retirables y anclajes libres) es un dato que
cambia con cada movimiento de bicicleta: altas, bajas, traslados, cambios de estado y —más
adelante— inicios y finalizaciones de viaje. Varias funcionalidades del módulo dependen de
él (mapa, planificador de viajes, dashboard de rebalanceo), y el módulo de Analítica Urbana
de otro squad probablemente quiera su evolución histórica.

El modelo de MOV-012 ya incluye la tabla `station_availability_history`, con
`available_bikes` y `available_slots`, lo que habilita la tentación de mantener contadores
persistidos. El criterio de aceptación de `SCRUM-40` pide explícitamente resolverlo
"sin guardar valores inconsistentes innecesariamente".

### Alternativas consideradas

1. **Calcular on-read a partir de `bikes` y `stations.capacity`.**
   Pros: imposible que quede desfasado; ningún camino de escritura nuevo que mantener; el
   criterio "los cambios en las bicicletas se reflejan en la disponibilidad" se cumple por
   construcción. Contras: una consulta agregada por request; sin historia.
2. **Contadores denormalizados en `stations` (`available_bikes`, `available_slots`).**
   Pros: lectura trivial. Contras: cada operación sobre bicicletas —incluida la importación
   masiva de Ecobici y, a futuro, los eventos de viaje que lleguen por el event bus— tiene
   que acordarse de actualizarlos; cualquier camino olvidado deja el dato mintiendo. Es
   exactamente lo que el criterio de aceptación pide evitar.
3. **Escribir un snapshot en `station_availability_history` en cada consulta.**
   Pros: da series históricas gratis. Contras: una lectura genera escritura, la tabla crece
   con el tráfico de consulta y no con los cambios reales de estado, y el historial queda
   sesgado por cuánto se mira cada estación.

### Decisión

Se elige la alternativa 1: la disponibilidad se calcula en el momento de la consulta, con
una sola query agrupada sobre `bikes`. No se persiste ningún contador ni se escribe en
`station_availability_history` desde este endpoint.

El volumen del módulo lo justifica: son unos cientos de estaciones y unos miles de
bicicletas, con índices ya creados en MOV-012 (`idx_bikes_status_deleted`, FK sobre
`bikes.station_id`). La consistencia vale más que la microoptimización, sobre todo con
varias tareas todavía sin implementar que van a mover bicicletas.

### Consecuencias

- **Modelo de datos**: sin cambios. `station_availability_history` queda creada pero sin
  uso; se mantiene como base para una carga programada de snapshots si Analítica Urbana la
  necesita. Esa carga sería una tarea aparte, desacoplada de la consulta.
- **APIs**: el contrato expone contadores calculados, sin campo de "última actualización"
  de un contador persistido; en su lugar viaja `checkedAt`, el momento del cálculo.
- **Testing**: la lógica es determinística y testeable con mocks; el test de repositorio con
  Testcontainers cubre que la query agrupada corra de verdad contra MySQL.
- **Integración con otros squads**: cuando el event bus (Grupo 1) empiece a publicar eventos
  de viaje, no hay contadores que sincronizar — la disponibilidad se recalcula sola.
- **Deuda técnica asumida**: si el volumen creciera mucho o apareciera un endpoint de alta
  frecuencia, habría que revisar esta decisión y evaluar caché o materialización con
  invalidación por evento.

## Dependencias y gaps señalados

- **Login Federado (Grupo 2, dependencia externa):** ambos endpoints quedan en el
  `permitAll()` de `SecurityConfig` con un `TODO` explícito, para que el frontend pueda
  consumirlos mientras no exista el authorization server del proyecto. Cuando esté
  disponible hay que revisar si requieren usuario autenticado.
- **MOV-021 (manejo global de errores), `Por hacer` y sin asignar:** el `404` se resolvió
  sumando `ResourceNotFoundException` al `GlobalExceptionHandler` existente, con el mismo
  formato de error del módulo. Es un aporte puntual y consistente, no la estandarización
  completa que pide MOV-021.
- **Vista según viaje activo:** fuera de alcance. Ninguna tarea del backlog la pide y el
  ciclo de vida de `Trip` todavía no está implementado (EPIC-03 sin tareas hijas). Los tres
  contadores le alcanzan al frontend para resolver tanto la vista de retiro como la de
  devolución.
