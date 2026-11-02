# Entrega CU-INT-01 y BI: estaciones afectadas por incidentes

## Alcance y estado del acuerdo

Este cambio implementa la propuesta de `EstacionUrbanaAfectada` y `EstacionUrbanaRestaurada`
para el CU-INT-01. Sustituye el snapshot propio `EstacionesAfectadasActualizadas`.
Se reutilizan EventDispatcher/inbox y EventPublisher/outbox/OutboxRelay; no se modifica
la lógica de recomendaciones ni se implementan los demás casos del contrato general de Movilidad.

**Pendiente de aceptación por BI/EDA:** el campo `moduloOrigen`, el valor `EMERGENCIA` de
`causaTipo` y el valor `RECOMENDACION_RESTRINGIDA` de `tipoAfectacion` son extensiones propuestas.
No equivalen a un contrato ya registrado o validado con BI. Antes de desplegar con publicación y
registro automático habilitados, coordinar las extensiones y schemas con BI/EDA. El registrar no
actualiza tipos existentes; una versión ya registrada necesita una actualización coordinada.
No se publicaron ni registraron contratos en el gateway real como parte de este cambio.

## Eventos y semántica

Schemas para el gateway en `src/main/resources/eventbus/event-types/`:

- `EstacionUrbanaAfectada.json`: afectacionId (UUID string), estacionId (string del ID interno
  estable de estación), latitud/longitud (double), tipoAfectacion (enum), afectacionDesde (string UTC),
  afectacionHastaEstimada (string UTC o null), causaId (string), causaTipo (enum), moduloOrigen (enum),
  correlationId (string), occurredAt (string UTC).
- `EstacionUrbanaRestaurada.json`: afectacionId, estacionId, correlationId y restauradaAt (strings).

Ejemplo de afectación:

```json
{
  "afectacionId": "e71b4c2a-9d3f-4a1e-8c5b-2f6d0a9e3b77",
  "estacionId": "42",
  "latitud": -34.6037,
  "longitud": -58.3816,
  "tipoAfectacion": "RECOMENDACION_RESTRINGIDA",
  "afectacionDesde": "2026-11-02T18:00:00Z",
  "afectacionHastaEstimada": null,
  "causaId": "reclamo-1",
  "causaTipo": "RECLAMO",
  "moduloOrigen": "RECLAMOS",
  "correlationId": "c-7781",
  "occurredAt": "2026-11-02T18:00:00Z"
}
```

La restricción solo representa la exclusión de recomendaciones por proximidad a un incidente.
No declara una estación físicamente BLOQUEADA, con CAPACIDAD_REDUCIDA ni FUERA_DE_SERVICIO.
Esos tres valores del contrato original se conservan en el enum, pero este CU no los emite.

Origen y causa son distintos:

| Mensaje recibido | causaTipo | causaId | moduloOrigen |
| --- | --- | --- | --- |
| Reclamo sin vínculo vial explícito | RECLAMO | reclamoId | RECLAMOS |
| Emergencia sin vínculo vial explícito | EMERGENCIA | emergenciaId | EMERGENCIAS |
| Cualquiera de los anteriores con causaTipo=INCIDENTE_VIAL y causaId válido | INCIDENTE_VIAL | causaId recibido | módulo que informó |

No se infiere un incidente vial por categoría ni se inventa su UUID. Un vínculo explícito sin
causaId se rechaza como payload inválido. El enum moduloOrigen reserva MOVILIDAD para futuros
productores propios; este cambio no añade IncidenteVialCreado ni su consumo directo.
Se conserva correlationId del payload de origen. Si no se recibió, se genera una correlación estable
`<moduloOrigen>-<idExterno>`; no se presenta como una correlación original del productor.
Los incidentes proyectados antes de V12 no tienen vínculo vial conocido y usan su origen real.

## Persistencia, transiciones y recuperación

V12 añade trazabilidad a city_incidents y el registro station_affectations. V1–V11 no se modifican.

- Una afectación abierta por pareja estación/causa, con UUID estable durante su vigencia.
- Solo estaciones ACTIVE no eliminadas e incidentes activos que el CU proyectó con afectaCirculacion=true.
- Radio Haversine configurable, 300 m por defecto, compartido con el filtro de recomendaciones.
- No depende de eventos culturales ni del límite de estaciones candidatas.
- Se reconcilia al confirmar incidentes y cada 15 minutos con el cron existente.
- Un refresco sin transiciones no publica nada. El arranque/primer refresco reconstruye las afectaciones
  de incidentes y estaciones ya existentes; afectacionDesde es la detección local, no una fecha histórica inventada.
- Resolver una causa publica su restauración con el mismo UUID. Si quedan otras causas, sus afectaciones
  siguen abiertas: EstacionUrbanaRestaurada cierra una afectación, no declara libre toda la estación.
- Baja, inactivación, cambio de ubicación/radio o cierre del incidente retiran las parejas que dejaron
  de cumplir el criterio. El refresco puede tardar hasta 15 minutos en detectar cambios del catálogo.
- Si cambia la causa, cierra la anterior y crea otra afectación. Si varios productores informan el mismo
  incidente vial, se conserva una sola afectación hasta que no quede ningún reporte activo de esa causa.
  moduloOrigen y correlationId conservan los valores del reporte que abrió la afectación.
- El bloqueo transaccional compartido serializa timers/listeners entre instancias. Persistencia y outbox
  se confirman juntos; un fallo de publicación revierte el estado y permite reintentar en el refresco.
- Tras restaurarse, la fila abierta se elimina; el historial y el UUID quedan en los eventos del outbox/EDA.
  No se crean snapshots cero ni se eliminan antiguos mensajes del outbox encolados por versiones anteriores.

BI debe contar estaciones distintas, no sumar afectaciones: varias causas pueden afectar una estación.
Para el indicador original, el filtro es causaTipo=INCIDENTE_VIAL. BI debe confirmar si también incluirá
RECLAMO y EMERGENCIA sin vínculo vial, o si necesita que los productores entreguen ese vínculo.
No se cambia unilateralmente la definición de la métrica. También hay que alinear el ID estable de estación
con los eventos de viajes de la rama separada; esta entrega no modifica esos eventos.

## Demanda del destino en CU-INT-01

La migración nueva es `V11__cultural_destination_demand.sql`. No se modifican V1–V10.
Se conserva `full_capacity` para el estado documentado `ACTIVE_FULL`:

- Evento normal: demanda de referencia = inscriptos conocidos.
- ACTIVE_FULL: demanda de referencia = cupo máximo.
- Actualizaciones explícitas de inscriptos pueden disminuir el valor (antes solo se guardaba el máximo histórico).
- Si falta inscriptos o estado, se conserva el dato conocido; no se inventa ReservaConfirmada.

Regla implementada, revisable con Espacios: tras excluir incidentes y estaciones sin anclajes libres,
se priorizan las estaciones con anclajes suficientes para la demanda de referencia. Si ninguna alcanza,
se consideran las de mayor disponibilidad. La IA existente o su fallback decide dentro de ese grupo;
no se modifica ni se reentrena el modelo. Sin demanda conocida se conserva el criterio existente.
No se estima un porcentaje de asistentes en bicicleta: el número es una referencia conservadora,
no una predicción de viajes ni una garantía/reserva de anclajes.

Esta regla se aplica solo a destinos del CU. La recomendación general PICKUP/DROPOFF sigue usando su
comportamiento anterior. La recomendación publicada usa el mismo snapshot ya filtrado para contar
los descartes y decidir la estación, evitando una segunda consulta que podía observar otros incidentes
u otra disponibilidad. El contrato de 12 campos de RecomendacionDestinoActualizada se conserva.
La API de eventos agrega `expectedDemand`; la UI lo muestra y distingue el cupo completo.

## Implementación y validación

- AffectedStationsService: reconcilia afectaciones y publica por el outbox oficial en REQUIRES_NEW.
- StationAffectationRepository: bloqueo compartido y registro de parejas abiertas.
- AffectedStationsMetricsPublisher: listener AFTER_COMMIT y cron de recuperación existentes.
- CityIncident / InboundEventService: correlación y vínculo vial explícito recibido del productor.
- V12: migración aditiva; schemas nuevos reemplazan el snapshot propio.
- AffectedStationsBiIntegrationTest: MySQL/Flyway reales, idempotencia, restauraciones independientes,
  catálogo, emergencias, vínculo vial, correlación, rollback, concurrencia y recuperación ante fallo de outbox.
- MigrationUpgradeIntegrationTest: upgrade desde V6 hasta V12 conservando datos y checksums.

Comando oficial: `./gradlew --no-daemon test bootJar`.
La descarga de Confluent sigue bloqueada con HTTP 403 en este entorno. Para diagnóstico local se usa un
init script **fuera del repositorio**, que excluye esa dependencia y su EventBusIntegrationTest.
Las pruebas de dominio/MySQL/outbox ejecutadas con ese arnés no validan Kafka/Avro/EDA real ni sustituyen
la suite oficial. El pipeline debe ejecutar la suite completa con acceso a las dependencias oficiales.

Validación local de esta entrega: 71 pruebas ejecutadas, sin fallos, errores ni omitidas, usando
el arnés externo descrito. Incluye deserialización de los payloads con Apache Avro, migración V6→V12,
MySQL/outbox y regresiones de recomendaciones. No se probó el transporte Kafka/Confluent ni el consumidor real de BI.

## Qué falta confirmar y con quién

| Responsable | Confirmación o entrega pendiente |
| --- | --- |
| BI | Aceptar schemas y extensiones propuestas, filtro de causas y conteo por estación; URL HTTPS del HTML; permisos CSP/frame y autenticación del embed |
| Espacios | Schema exacto de ReservaConfirmada; publicación real y consumidor de RecomendacionDestinoActualizada; semántica de inscriptos/ACTIVE_FULL y validación de la regla de demanda; versiones/fechas para ordenar actualizaciones |
| Reclamos | Schemas definitivos de creación/actualización/resolución, publicación real, estados terminales, cambios de coordenadas/afectaCirculacion y garantías de orden |
| Emergencias | Lo mismo para EmergenciaCreada/EmergenciaActualizada; acordar reaperturas y cambios de ubicación sin inventar campos |
| Compañero de EDA / Grupo 1 | Revisar contratos propios nuevos y registros existentes; verificar permisos/suscripciones en el entorno de integración y coordinar prueba real/reprocesamiento |

La guía general del caso contiene nombres antiguos de eventos. Se mantienen los nombres posteriores
acordados en el CU; cualquier alias o cambio exige el schema del productor. No se implementa un
handler de ReservaConfirmada ni reaperturas por suposición. La redistribución física de bicicletas,
que el PDF menciona como posibilidad, no se automatiza ni se generan órdenes sin reglas operativas.
No hay cambios de Login, permisos generales, credenciales ni infraestructura EDA.

## Prueba conjunta pendiente

Publicar evento cultural → ver demanda y recomendación → crear incidente cercano → verificar
alternativa/invalidez y EstacionUrbanaAfectada → resolver incidente → verificar recuperación y EstacionUrbanaRestaurada →
cancelar evento → verificar última invalidación → comprobar que BI muestra el indicador en su HTML.
Debe ejecutarse en un ambiente de integración, con backend activo, sin desplegar esta rama a producción.
