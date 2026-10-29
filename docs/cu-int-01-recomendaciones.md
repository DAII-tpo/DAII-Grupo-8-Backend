# RecomendacionDestinoActualizada · Contrato propuesto para Espacios

El contrato de publicación está en `src/main/resources/eventbus/event-types/RecomendacionDestinoActualizada.json`.
Enviar este archivo a Espacios para confirmar los tipos, sobre todo `estacionId` (long nullable).

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

Ejecutar `./gradlew test` y ensayar Kafka/EDA con eventos reales; revisar esquema existente en
el catálogo EDA y confirmar con Espacios antes de registrar una versión incompatible.
