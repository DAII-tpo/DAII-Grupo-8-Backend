# RecomendacionDestinoActualizada · Contrato propuesto para Espacios

El contrato de publicación está en `src/main/resources/eventbus/event-types/RecomendacionDestinoActualizada.json`.
Espacios confirmó el 29/10/2026 los 12 nombres del contrato y aceptó `estacionId` como `long` nullable.

El tipo completo/tópico es `com.citypass.movilidad.RecomendacionDestinoActualizada`.
Se registra automáticamente al iniciar con `EVENTBUS_PUBLISHER_ENABLED=true` y
`EVENTBUS_REGISTER_EVENT_TYPES=true`, si no existía previamente. **No cambia un tipo ya registrado**.

## Semántica

- `estado`: `RECOMMENDED` o `NO_RECOMMENDATION`.
- Campos de estación y ubicación: null cuando no existe una recomendación.
- `descartadasPorIncidentes`: cantidad de estaciones candidatas excluidas por incidentes activos.
- `calculadaEn`: texto ISO-8601 UTC.
- `correlationId`: `evento-<eventoId>`, estable a lo largo de recálculos.
- Si todas las estaciones candidatas están cerca de incidentes: NO_RECOMMENDATION.
- Ante cancelación o espacio deshabilitado: última actualización NO_RECOMMENDATION; no se recalcula mientras siga así.
- Se emite al publicar un evento, ante un incidente cercano (creado/resuelto) y cada 15 min para eventos cuya fecha de inicio está en las próximas 24 h.

## Eventos de Espacios y pendientes

- `EventoComunitarioPublicado`: Espacios enviará tanto `cupoMaximo` como `capacidad`, con el mismo número; Movilidad acepta ambos y prioriza `cupoMaximo`. Si faltan los dos, rechaza el evento en lugar de guardar capacidad cero.
- `EventoCancelado`: `estado = CANCELLED`.
- `EspacioEstadoCambiado`: `estadoNuevo = ENABLED` o `DISABLED`.
- El evento de reservas de Espacios se llama **`ReservaConfirmada`**, no `ReservaCreada`. No se suscribe todavía hasta recibir nombres y tipos de sus campos; queda pendiente la actualización de inscriptos a partir de reservas.
- Espacios aún no está publicando estos eventos en EDA; está preparando los contratos y su consumidor de `RecomendacionDestinoActualizada`.

## Operación

La recepción de Kafka confirma los cambios en base. Después del commit, el listener de aplicación
recalcula y escribe el resultado en el outbox mediante `EventPublisher`. El envío HTTP lo hace
`OutboxRelay`, con reintentos. El scheduler sirve también para recuperar recálculos fallidos.

El recálculo programado requiere un backend **despierto**. Un ping a `/actuator/health` cada 10
min puede ayudar a evitar suspensión, pero no garantiza ejecución precisa: verificar en Render
o utilizar un servicio/cron persistente.

La rama no cambia `main` y no incluye el código de BI. La publicación de
`ViajeIniciado`/`ViajeTerminado` está en `feat/integracion-bi` del backend.

## Pendientes de prueba

Ejecutar `./gradlew test` y ensayar Kafka/EDA con eventos reales; revisar el esquema existente en
el catálogo EDA antes de registrar una versión incompatible. Esperar contrato de `ReservaConfirmada`.
