# Integración con el bus de eventos (EDA)

Cómo el backend de Movilidad publica y consume eventos en el bus de CityPass+ que mantiene el Grupo 1
([citypass-event-gateway](https://github.com/carlos-illobre/citypass-event-gateway)). Acá está lo propio de
este backend; las reglas del bus (contratos, versionado, límites) están en la documentación de ese repo.

## Cómo encaja

Todos los módulos de CityPass+ comparten **un solo bus**. Nadie se conecta con otro módulo directamente:
cada uno publica en el bus y lee del bus.

```
                     ┌──────────────── Bus de eventos (Grupo 1) ────────────────┐
  Movilidad ──POST──▶│ event-gateway ──▶ Kafka: un tópico por event type         │──▶ Emergencias, Analítica…
  (outbox)    HTTP   │  (valida schema,     com.citypass.movilidad.ViajeFinalizado│    (leen nuestros tópicos)
                     │   estampa metadata)  com.citypass.emergencias.…           │
  Movilidad ◀──lee───│                      com.citypass.reclamos.…              │◀── los demás publican
  (inbox)     Kafka  └──────────────────────────────────────────────────────────┘    por el gateway
```

- **Publicar es por HTTP**: `POST /api/v1/event-types/{fqn}/events` con el token de servicio. Nunca se escribe
  directo en Kafka; así el gateway valida el contrato y estampa `metadata.source` desde el token.
- **Consumir es leyendo el Kafka del bus**: el mismo broker para todos. El nombre del tópico es el fqn del
  event type (`<namespace>.<Nombre>`), así que para leer lo que publica otro grupo alcanza con suscribirse a
  su tópico. El broker autentica con el mismo servicio de identidad (SASL/OAUTHBEARER) y solo deja usar
  consumer groups que empiecen con nuestro namespace (`com.citypass.movilidad.*`).
- Del otro lado es simétrico: otro grupo que quiera enterarse de nuestros viajes lee
  `com.citypass.movilidad.<Evento>` desde el mismo broker, sin que tengamos que hacer nada más.

Este backend es el cliente `grupo3`, namespace `com.citypass.movilidad`.

## Qué hay implementado

Todo vive en [`eventbus/`](../src/main/java/com/citypass/movilidad/eventbus).

| Pieza | Qué hace |
|---|---|
| `EventPublisher` | Lo que usan los servicios de dominio. Guarda el evento en `event_outbox` **en la misma transacción** que el cambio que lo origina |
| `OutboxRelay` | Cada 5 s publica los pendientes del outbox en el gateway. Reintenta con backoff exponencial y marca `FAILED` lo que no se puede publicar |
| `ServiceTokenProvider` | Token de servicio (`client_credentials`) cacheado hasta poco antes de vencer; se renueva solo ante un 401 |
| `EventGatewayClient` | Cliente HTTP del gateway. Clasifica cada error en reintentable o no |
| `EventTypeRegistrar` | Al arrancar, registra los event types de `resources/eventbus/event-types/*.json` que todavía no existan |
| `EventHandler` | Interfaz para consumir un tópico. Cada implementación es un bean |
| `EventBusListener` + `EventDispatcher` | Consumidor Kafka (Avro + SASL). Entrega cada evento a su handler una sola vez, usando `event_inbox` para descartar duplicados |
| `FailedEventRecorder` | Si un handler sigue fallando después de los reintentos, deja el evento en `event_inbox` como `FAILED` y sigue con el próximo |

### Por qué outbox e inbox

La entrega del bus es *at-least-once*: un evento puede llegar dos veces, nunca cero.

- **Outbox**: si publicáramos por HTTP dentro del servicio de dominio, un rollback posterior dejaría publicado
  un evento de algo que no pasó, y una caída del gateway haría fallar la operación del usuario. Con el outbox
  el evento existe si y solo si el cambio se confirmó, y el usuario no espera al bus.
- **Inbox**: los cambios del handler y la marca de "procesado" se confirman en la misma transacción. Una
  reentrega del mismo `eventId` se descarta; un evento a medio procesar se rehace entero.
- El offset de Kafka se confirma **después** de procesar cada evento (`ack-mode=record`), nunca antes.

## Configuración

Las dos partes están **apagadas por defecto**: el backend arranca y funciona sin el bus. Con la publicación
apagada los eventos se siguen guardando en el outbox y salen cuando se active.

| Variable | Default | Descripción |
|---|---|---|
| `EVENTBUS_PUBLISHER_ENABLED` | `false` | Activa la publicación (relay del outbox y registro de event types) |
| `EVENTBUS_CONSUMER_ENABLED` | `false` | Activa el consumo desde Kafka |
| `EVENTBUS_CLIENT_ID` | — | Cliente del servicio de identidad (`grupo3`) |
| `EVENTBUS_CLIENT_SECRET` | — | Secreto del cliente. **Nunca en el repo** |
| `EVENTBUS_GATEWAY_URL` | `http://localhost:8080/api/v1` | API del gateway. También es el registry de schemas |
| `EVENTBUS_TOKEN_URL` | `http://localhost:8083/oauth/token` | Endpoint `client_credentials` del servicio de identidad |
| `EVENTBUS_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Broker del bus |
| `EVENTBUS_KAFKA_SECURITY_PROTOCOL` | `SASL_PLAINTEXT` | `SASL_SSL` contra el bus desplegado |
| `EVENTBUS_CONSUMER_GROUP` | `com.citypass.movilidad.backend` | Tiene que empezar con el namespace |
| `EVENTBUS_REGISTER_EVENT_TYPES` | `true` | Registrar al arrancar los event types que falten |
| `EVENTBUS_NAMESPACE` | `com.citypass.movilidad` | Solo arma el fqn de lo que publicamos; el que vale es el del token |

Con cualquiera de las dos partes activada, faltar `EVENTBUS_CLIENT_ID` o `EVENTBUS_CLIENT_SECRET` hace fallar
el arranque: mejor eso que acumular eventos que nunca van a salir.

Los parámetros finos (intentos, backoff, tamaño de lote) están en
[`EventBusProperties`](../src/main/java/com/citypass/movilidad/eventbus/EventBusProperties.java) con prefijo
`citypass.eventbus`.

## Probarlo en local

1. Levantar el bus desde su repo (`cp .env.example .env && docker compose up -d --build`).
2. El gateway usa el puerto **8080**, el mismo que este backend: correr el backend en otro puerto.
3. Arrancar el backend con:

   ```bash
   PORT=8081 \
   EVENTBUS_PUBLISHER_ENABLED=true \
   EVENTBUS_CONSUMER_ENABLED=true \
   EVENTBUS_CLIENT_ID=grupo3 \
   EVENTBUS_CLIENT_SECRET=<secreto-del-simulador> \
   ./gradlew bootRun
   ```

   El secreto del simulador de identidad está en la sección «Autenticación» del README del bus. Es una
   credencial de prueba: no la copies en el repo ni la reutilices cuando el Grupo 2 entregue el servicio real.

4. Los eventos publicados se ven en la UI del gateway (http://localhost:5173) y en Kafka UI
   (http://localhost:8090).

## Disparar un evento nuevo

1. **Declarar el contrato** en `src/main/resources/eventbus/event-types/<Nombre>.json`, con el formato de
   `POST /api/v1/event-types`. El nombre es un hecho consumado en PascalCase; los tipos, los más específicos
   posibles (fechas como `timestamp-millis`, importes como `decimal`). Si lo originó una persona, va su
   identificador en `actorSub` (convención del bus):

   ```json
   {
     "name": "ViajeFinalizado",
     "fields": [
       { "name": "tripId",          "type": "long" },
       { "name": "bikeId",          "type": "long" },
       { "name": "actorSub",        "type": "string" },
       { "name": "durationSeconds", "type": "int" },
       { "name": "endedAt",         "type": { "type": "long", "logicalType": "timestamp-millis" } }
     ]
   }
   ```

   Se registra solo al arrancar con la publicación activada. Si ya existe no se toca: cambiar un schema es
   un `PUT` deliberado en el gateway (ver `EVENT-TYPES.md` del bus).

2. **Publicarlo desde el servicio de dominio**, dentro de su transacción. Los datos tienen que coincidir con
   el schema; las fechas van como epoch millis:

   ```java
   eventPublisher.publish("ViajeFinalizado", AggregateType.TRIP, trip.getId(),
           new ViajeFinalizadoData(trip.getId(), bike.getId(), user.getExternalUserId(),
                   trip.getDurationSeconds(), trip.getEndedAt().toEpochMilli()));
   ```

   `publish` exige una transacción activa (`Propagation.MANDATORY`): fuera de una, lanza
   `IllegalTransactionStateException`.

## Consumir un evento de otro grupo

Implementar `EventHandler` como bean. El listener se suscribe solo a los tópicos de los handlers que existan.
Ejemplo ilustrativo (el tópico y `suspendNear` no existen todavía):

```java
@Component
public class EmergenciaReportadaHandler implements EventHandler {

    private final ObjectMapper objectMapper;
    private final StationService stationService;

    // constructor...

    @Override
    public String topic() {
        return "com.citypass.emergencias.EmergenciaReportada";
    }

    @Override
    public void handle(IncomingEvent event) {
        EmergenciaReportada data = objectMapper.treeToValue(event.data(), EmergenciaReportada.class);
        stationService.suspendNear(data.lat(), data.lon());
    }
}
```

- `handle` corre en la misma transacción que la marca en el inbox. **No atrapes excepciones para seguir de
  largo**: si falla, se hace rollback, se reintenta y, si sigue fallando, queda como `FAILED` en el inbox.
- `event.metadata()` trae lo que estampó el gateway: `eventId`, `source` (quién publicó, no falsificable),
  `receivedAt` (reloj del gateway), `payloadHash`.
- Si el otro grupo hace un cambio incompatible, el bus crea un tópico nuevo con sufijo (`….v2`). Para migrar,
  se cambia `topic()` cuando estén listos.
- Un tópico que todavía no existe no rompe nada: el consumidor lo toma cuando el otro grupo lo registre.

## Operación

**Outbox** (`event_outbox.status`): `PENDING` → `PUBLISHED`, o `FAILED` si el gateway lo rechazó (schema
inválido, tópico ajeno, payload demasiado grande) o se agotaron los intentos. `last_error` dice por qué, y
`bus_event_id` es el `eventId` con el que quedó en el bus. Para reintentar uno fallido, después de corregir
la causa:

```sql
UPDATE event_outbox SET status = 'PENDING', attempts = 0, next_attempt_at = NOW(3) WHERE id = '<id>';
```

**Inbox** (`event_inbox.status`): `PROCESSED` o `FAILED`. Los fallidos guardan `payload` y `last_error`. Si el
mismo evento se vuelve a recibir y esta vez se procesa, pasa a `PROCESSED`.

Un mensaje que ni siquiera es un evento del bus (no se puede deserializar) no tiene `eventId`: se registra en
el log con tópico, partición y offset, y se saltea.

## Ambiente de pruebas del bus

El Grupo 1 tiene desplegado un ambiente de pruebas en **https://test.citypass.mrfranco.net.ar**. Es el que
usa el deploy de Render ([`render.yaml`](../render.yaml)):

| Variable | Valor |
|---|---|
| `EVENTBUS_GATEWAY_URL` | `https://test.citypass.mrfranco.net.ar/api/v1` |
| `EVENTBUS_TOKEN_URL` | `https://test.citypass.mrfranco.net.ar/auth/oauth/token` |
| `EVENTBUS_KAFKA_BOOTSTRAP_SERVERS` | `test.citypass.mrfranco.net.ar:9092` |
| `EVENTBUS_KAFKA_SECURITY_PROTOCOL` | `SASL_SSL` |

- La UI del gateway está en la raíz del dominio. Sirve para ver el catálogo de event types de todos los grupos
  y los eventos que publicamos.
- El certificado (también el de Kafka en el 9092) es de Let's Encrypt, así que no hace falta truststore.
- Hoy la identidad la resuelve el **simulador** del bus (`/auth/health` responde `auth-simulator`), con las
  credenciales fijas de su código. Cuando el Grupo 2 entregue el servicio de identidad real cambian
  `EVENTBUS_TOKEN_URL` y las credenciales, no el código.
- En `render.yaml` la publicación y el consumo están activados. Las credenciales van como `sync: false` en el
  dashboard de Render (Environment → `EVENTBUS_CLIENT_ID` y `EVENTBUS_CLIENT_SECRET`) y tienen que estar
  cargadas **antes** del deploy: sin ellas el backend no arranca.

> **Si se actualiza a Kafka 4 (Spring Boot 4):** el cliente de Kafka exige declarar la URL del token en la
> propiedad de sistema `org.apache.kafka.sasl.oauthbearer.allowed.urls`; sin eso el consumidor no conecta.
