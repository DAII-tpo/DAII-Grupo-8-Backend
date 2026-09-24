# DAII-Grupo-8-Backend

Backend del módulo **Movilidad Urbana Inteligente** (Grupo 8) del proyecto **CityPass+**: alquiler de
bicicletas públicas con estaciones, viajes, reportes de incidencias, mantenimiento y recomendación
inteligente de estaciones.

| | URL |
|---|---|
| Frontend (producción) | https://daii-grupo-8-frontend.vercel.app |
| API (producción) | https://movilidad-backend.onrender.com |
| Swagger UI (producción) | https://movilidad-backend.onrender.com/swagger-ui.html |
| Health check | https://movilidad-backend.onrender.com/actuator/health |

> El backend corre en el plan free de Render: si no recibió tráfico en un rato, la primera request tarda
> 30–60 s mientras el servicio despierta.

## Índice

- [Stack](#stack)
- [Arquitectura](#arquitectura)
- [Estructura del proyecto](#estructura-del-proyecto)
- [Cómo correr localmente](#cómo-correr-localmente)
- [Variables de entorno](#variables-de-entorno)
- [API](#api)
- [Reglas de negocio](#reglas-de-negocio)
- [Base de datos](#base-de-datos)
- [Estaciones Ecobici (dataset oficial)](#estaciones-ecobici-dataset-oficial)
- [Recomendación inteligente](#recomendación-inteligente)
- [Seguridad](#seguridad)
- [Tests y calidad](#tests-y-calidad)
- [CI/CD y deploy](#cicd-y-deploy)
- [Documentación adicional](#documentación-adicional)
- [Pendientes y dependencias externas](#pendientes-y-dependencias-externas)

## Stack

| Capa | Tecnología |
|---|---|
| Backend | Java 21 LTS + Spring Boot 3.5.x |
| Base de datos | MySQL 8, esquema versionado con Flyway |
| API | REST versionada (`/api/v1`), documentada con springdoc-openapi (Swagger UI) |
| Mapeo | Spring Data JPA (Hibernate), MapStruct, Lombok |
| Auth | JWT (Spring Security + `NimbusJwtDecoder`), desactivable para desarrollo |
| IA / recomendación | Python 3.12 + FastAPI + scikit-learn (microservicio en [`recommendation-service/`](recommendation-service)) |
| Event Bus | Apache Kafka (Grupo 1). La dependencia está incluida; la publicación de eventos está pendiente del contrato |
| Testing | JUnit 5, Mockito, Testcontainers (MySQL real), Jacoco · pytest + pytest-cov |
| CI/CD | GitHub Actions + SonarQube (quality gate) · deploy en Render |

Se usa Spring Boot 3.5.x (no 4.x) porque el Grupo 1 reportó incompatibilidad entre Jackson 3 (usado por
Spring Boot 4.x) y el serializador Avro de Confluent (decisión MOV-004).

## Arquitectura

```
                 ┌──────────────────────────┐
  Frontend  ───► │  movilidad-backend       │ ───► MySQL 8 gestionado
  (Vercel)       │  Spring Boot · /api/v1   │
                 └────────────┬─────────────┘
                              │ HTTP (timeouts cortos, fallback si no responde)
                              ▼
                 ┌──────────────────────────┐
                 │ movilidad-recommendation │   FastAPI + scikit-learn, sin base de datos
                 └──────────────────────────┘
```

- **Backend (este repo)**: API REST con toda la lógica de negocio. Es la única pieza que escribe en la base.
- **Servicio de recomendación**: recibe un snapshot de estaciones candidatas y devuelve un ranking con su
  justificación. Si no responde, el backend contesta igual con un criterio de respaldo
  ([ADR-001](docs/adr/ADR-001-recomendacion-http-sincrona.md)).
- **Base de datos**: MySQL gestionado externo (Render no ofrece MySQL). Flyway crea y migra el esquema al arrancar.

## Estructura del proyecto

```
.
├── src/main/java/com/citypass/movilidad/
│   ├── controller/    # Endpoints REST (/api/v1/...) y su documentación OpenAPI
│   ├── service/       # Lógica de negocio y reglas (viajes, bicis, incidencias, mantenimiento, estaciones)
│   ├── repository/    # Acceso a datos (Spring Data JPA) y proyecciones de consultas
│   ├── model/         # Entidades JPA y enums de estado
│   ├── dto/           # Requests y responses de la API
│   ├── mapper/        # Conversión entidad ↔ DTO (MapStruct) y mapeo del dataset Ecobici
│   ├── client/        # Cliente HTTP del servicio de recomendación
│   ├── importer/      # Modelos de la importación del dataset Ecobici
│   ├── config/        # Seguridad, CORS, OpenAPI, propiedades y runner de importación
│   ├── validation/    # Restricciones de Bean Validation reutilizables (@EntityId, @Latitude, @Longitude)
│   └── exception/     # Excepciones de dominio y manejo global de errores
├── src/main/resources/
│   ├── application.properties
│   ├── db/migration/  # Migraciones Flyway (V1, V2, ...)
│   └── datasets/      # Dataset oficial de estaciones Ecobici (GeoJSON)
├── src/test/java/...  # Tests unitarios y de integración
├── recommendation-service/   # Microservicio Python de recomendación (MOV-041)
├── docs/              # Documentación por ticket y ADRs
├── Dockerfile         # Imagen del backend (build multi-stage)
├── render.yaml        # Blueprint de Render (backend + servicio de recomendación)
└── .github/workflows/ # CI (PRs) y CD (push a main)
```

## Cómo correr localmente

### Requisitos

- **JDK 21** (el Gradle Wrapper descarga el resto; no hace falta instalar Gradle).
- **MySQL 8**, o **Docker** para levantarlo en un contenedor.
- **Docker** también es necesario para los tests de integración (Testcontainers).
- Opcional: **Python 3.12** para el servicio de recomendación.

### 1. Base de datos

Con Docker:

```bash
docker run --name movilidad-mysql -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=movilidad -p 3306:3306 -d mysql:8.0
```

Eso crea la base `movilidad` con usuario `root` / password `root`, que coinciden con los defaults de la app.

Si ya tenés un MySQL propio, alcanza con crear la base (`CREATE DATABASE IF NOT EXISTS movilidad;`) y pasar
tus credenciales por variables de entorno. **No hardcodees tu password en ningún archivo del repo.**

No hace falta crear tablas: Flyway arma el esquema en el primer arranque.

### 2. Levantar la app

```bash
./gradlew bootRun
```

En Windows (PowerShell): `.\gradlew.bat bootRun`.

Con otras credenciales de MySQL:

```bash
DB_USERNAME=miusuario DB_PASSWORD=mipassword ./gradlew bootRun
```

```powershell
$env:DB_USERNAME="miusuario"; $env:DB_PASSWORD="mipassword"; .\gradlew.bat bootRun
```

En IntelliJ: Run Configuration → Environment variables (queda solo en tu configuración local).

La API queda en `http://localhost:8080`:

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/api-docs`
- Health: `http://localhost:8080/actuator/health`

### 3. Cargar estaciones reales (recomendado)

La base arranca sin estaciones. Para cargar las ~470 estaciones Ecobici de Buenos Aires:

```bash
ECOBICI_IMPORT_ENABLED=true ./gradlew bootRun
```

```powershell
$env:ECOBICI_IMPORT_ENABLED="true"; .\gradlew.bat bootRun
```

Ver [Estaciones Ecobici](#estaciones-ecobici-dataset-oficial).

### 4. (Opcional) Servicio de recomendación

```bash
cd recommendation-service
python -m venv .venv
.venv/Scripts/pip install -r requirements.txt      # Windows (en Linux/Mac: .venv/bin/pip)
.venv/Scripts/python -m uvicorn app.main:app --port 8000
```

No es obligatorio: si no está levantado, `GET /api/v1/stations/recommendation` responde igual con el
criterio de respaldo (`source = FALLBACK`).

### 5. Usuario de prueba

La migración V3 crea un usuario demo con **id = 1** y rol `USER`. Es el que usa el frontend para los
endpoints que identifican al usuario con el header `X-User-Id` (ver [Seguridad](#seguridad)).

Para probar los endpoints de administración (incidencias y mantenimiento) localmente, hace falta un usuario
con rol `ADMIN`. Por ejemplo, en tu base local:

```sql
UPDATE users SET role_id = (SELECT id FROM roles WHERE name = 'ADMIN') WHERE id = 1;
```

## Variables de entorno

Todas tienen un default pensado para desarrollo local.

| Variable | Default | Descripción |
|---|---|---|
| `PORT` | `8080` | Puerto HTTP (Render lo define solo) |
| `DB_HOST` | `localhost` | Host de MySQL |
| `DB_PORT` | `3306` | Puerto de MySQL |
| `DB_NAME` | `movilidad` | Nombre de la base |
| `DB_USERNAME` | `root` | Usuario de MySQL |
| `DB_PASSWORD` | `root` | Password de MySQL |
| `DB_PARAMS` | `useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC` | Parámetros de la URL JDBC. En bases gestionadas hay que exigir TLS |
| `SECURITY_LOCAL_ENABLED` | `true` | `true` deja todos los endpoints abiertos; `false` exige JWT (ver [Seguridad](#seguridad)) |
| `AUTH_JWK_SET_URI` | `http://localhost:9000/.well-known/jwks.json` | JWKS para validar los JWT (solo con seguridad activa) |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173,http://localhost:3000` | Orígenes del frontend habilitados, separados por coma. Admite comodines |
| `RECOMMENDATION_SERVICE_URL` | `http://localhost:8000` | URL del servicio de recomendación |
| `ECOBICI_IMPORT_ENABLED` | `false` | `true` importa las estaciones Ecobici al arrancar (idempotente) |
| `ECOBICI_DATASET_LOCATION` | `classpath:datasets/ecobici-stations.geojson` | Dataset a importar |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Broker de Kafka (todavía sin uso, ver [Pendientes](#pendientes-y-dependencias-externas)) |
| `SPRING_PROFILES_ACTIVE` | `local` | Perfil de Spring |

Otros parámetros de negocio viven en [`application.properties`](src/main/resources/application.properties):
radio y límite de la búsqueda de estaciones cercanas y timeouts y candidatas de la recomendación.

## API

Todas las rutas cuelgan de `/api/v1`. La referencia completa, con esquemas de request y response y ejemplos,
está en Swagger UI. Resumen:

### Estaciones

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/stations` | Todas las estaciones |
| `GET` | `/stations/{id}` | Una estación |
| `POST` | `/stations` | Crear una estación |
| `PATCH` | `/stations/{id}` | Actualizar una estación (incluye activar/desactivar con `status`) |
| `GET` | `/stations/availability` | Disponibilidad (bicis y anclajes libres) de todas las estaciones |
| `GET` | `/stations/{id}/availability` | Disponibilidad de una estación |
| `GET` | `/stations/nearby?lat&lng[&radius][&limit]` | Estaciones activas cercanas, ordenadas por distancia, con disponibilidad |
| `GET` | `/stations/recommendation?lat&lng[&purpose]` | Estación recomendada para retirar (`PICKUP`) o devolver (`DROPOFF`) |

### Bicicletas

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/bikes[?status]` | Todas las bicicletas vigentes en una sola respuesta (panel de administración) |
| `GET` | `/bikes/{id}` | Una bicicleta |
| `GET` | `/bikes/station/{stationId}` | Bicicletas de una estación (cualquier estado) |
| `GET` | `/bikes/available[?stationId]` | Bicicletas disponibles para retirar |
| `POST` | `/bikes` | Alta de bicicleta |
| `PATCH` | `/bikes/{id}/status` | Cambio de estado administrativo |
| `PATCH` | `/bikes/{id}/station` | Traslado a otra estación |
| `GET` | `/bikes/{id}/status-history` | Historial de cambios de estado |
| `DELETE` | `/bikes/{id}` | Baja lógica |

### Viajes (requieren `X-User-Id`)

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/trips` | Iniciar un viaje con una bicicleta disponible |
| `GET` | `/trips/active` | Viaje activo del usuario (`204` si no tiene) |
| `POST` | `/trips/{id}/end` | Finalizar el viaje devolviendo la bici en una estación |
| `GET` | `/trips/history?page&size` | Historial paginado de viajes finalizados |

### Incidencias

| Método | Ruta | Header | Descripción |
|---|---|---|---|
| `GET` | `/incidents/types` | — | Tipos de incidencia activos |
| `POST` | `/incidents` | `X-User-Id` | Reportar una incidencia sobre una bicicleta |
| `GET` | `/incidents[?status&bikeId&userId&typeId]` | `X-User-Id` (ADMIN) | Listar y filtrar incidencias |
| `GET` | `/incidents/{id}` | `X-User-Id` (ADMIN) | Detalle de una incidencia |
| `PATCH` | `/incidents/{id}/status` | `X-User-Id` (ADMIN) | Cambiar el estado (revisión, resuelta, rechazada) |

### Mantenimiento (requieren `X-User-Id` de un ADMIN)

| Método | Ruta | Descripción |
|---|---|---|
| `GET` | `/maintenance` | Listar órdenes de mantenimiento |
| `POST` | `/maintenance` | Abrir una orden (envía la bici a mantenimiento) |
| `PATCH` | `/maintenance/{id}/complete` | Finalizar la orden; `stationId` opcional para elegir dónde vuelve la bici |

### Formato de error

Todos los errores responden con el mismo cuerpo:

```json
{
  "timestamp": "2026-09-24T12:00:00Z",
  "status": 409,
  "error": "Conflict",
  "code": "BUSINESS_RULE_VIOLATION",
  "message": "La estación no tiene capacidad disponible: 12",
  "path": "/api/v1/trips/7/end",
  "errors": []
}
```

| Código HTTP | `code` | Cuándo |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Parámetros o cuerpo inválidos (`errors` detalla cada campo) |
| 403 | `FORBIDDEN_OPERATION` | El usuario no tiene permisos (por ejemplo, no es ADMIN) |
| 404 | `RESOURCE_NOT_FOUND` / `STATION_NOT_FOUND` | El recurso no existe o fue dado de baja |
| 409 | `BUSINESS_RULE_VIOLATION` | La operación viola una regla de negocio |
| 409 | `DATA_INTEGRITY_VIOLATION` / `CONCURRENT_UPDATE` | Conflicto en la base o modificación concurrente |
| 500 | `INTERNAL_ERROR` | Error inesperado (el detalle queda solo en el log) |

Detalle en [docs/MOV-021](docs/MOV-021-manejo-errores-validaciones.md).

## Reglas de negocio

### Estados de una bicicleta

| Estado | Significado | ¿Ocupa anclaje? | ¿Se puede retirar? |
|---|---|---|---|
| `AVAILABLE` | Lista para usar, en una estación | Sí | Sí |
| `IN_USE` | En un viaje (sin estación asignada) | No | No |
| `OUT_OF_SERVICE` | Fuera de circulación (por ejemplo, reportada y pendiente de revisión) | Sí | No |
| `MAINTENANCE` | En reparación, con una orden de mantenimiento abierta (sin estación asignada) | No | No |
| `STOLEN` | Robada | Sí, si sigue asignada a una estación | No |

- `AVAILABLE ↔ IN_USE` solo lo producen los viajes; no es una transición administrativa.
- Transiciones administrativas permitidas (`PATCH /bikes/{id}/status`):
  `AVAILABLE → MAINTENANCE | OUT_OF_SERVICE | STOLEN`,
  `MAINTENANCE → AVAILABLE | OUT_OF_SERVICE | STOLEN`,
  `OUT_OF_SERVICE → MAINTENANCE | STOLEN`, `STOLEN → MAINTENANCE | OUT_OF_SERVICE`.
- Una bici `AVAILABLE` siempre tiene estación.
- Cada cambio de estado queda registrado en `bike_status_history`.
- La baja (`DELETE`) es lógica: la bici queda `OUT_OF_SERVICE` con `deleted_at` y deja de aparecer en las consultas.

### Disponibilidad de una estación

Se calcula al momento de la consulta; no hay contadores persistidos:

- **Bicis disponibles** = bicis `AVAILABLE` en la estación.
- **Anclajes libres** = capacidad − bicis presentes en cualquier estado (nunca negativo).

Toda operación que deja una bici en una estación (alta, traslado, fin de viaje o fin de mantenimiento) valida
que la estación esté activa y tenga capacidad.

### Viajes

1. **Inicio**: el usuario tiene que estar activo y no tener otro viaje activo. La bici tiene que estar
   `AVAILABLE`; pasa a `IN_USE` y sale de su estación.
2. **Fin**: se devuelve en una estación activa con lugar. La bici queda `AVAILABLE` en esa estación y se
   calcula la duración. Un usuario bloqueado durante el viaje igual puede devolver la bici.
3. Las operaciones usan locks pesimistas para que dos requests simultáneas no generen dos viajes para el mismo
   usuario, no usen la misma bici ni superen la capacidad de una estación.

### Incidencias

1. Cualquier usuario activo puede reportar una incidencia sobre una bici.
2. Si la bici estaba `AVAILABLE`, pasa a `OUT_OF_SERVICE` y deja de poder retirarse.
3. Si se reporta durante un viaje, la bici sigue `IN_USE` para que el usuario pueda terminar el viaje. Al
   devolverla, pasa a `OUT_OF_SERVICE` en la estación de destino si la incidencia sigue pendiente.
4. El admin revisa: `OPEN → UNDER_REVIEW → RESOLVED | REJECTED` (también `OPEN → RESOLVED | REJECTED`).
5. **Rechazada (reporte falso)**: la bici vuelve sola a `AVAILABLE`, siempre que haya salido de servicio por
   ese reporte y no tenga otras incidencias pendientes.
6. **Resuelta**: la bici no cambia; vuelve a circular a través del mantenimiento.

### Mantenimiento

1. **Abrir la orden** (`POST /maintenance`): la bici pasa a `MAINTENANCE` y **se retira de su estación**,
   liberando el anclaje mientras se repara. La orden guarda la estación de origen.
2. Mientras la orden está abierta, la bici no se puede trasladar ni cambiar de estado a mano.
3. **Finalizar la orden** (`PATCH /maintenance/{id}/complete`): la bici vuelve a `AVAILABLE` en su estación de
   origen, o en la indicada con `stationId`. Si esa estación está desactivada o llena, responde `409` para
   que se elija otra.
4. No puede haber dos órdenes abiertas para la misma bici.

## Base de datos

El esquema lo administra **Flyway** ([`db/migration`](src/main/resources/db/migration)); Hibernate solo lo valida
(`ddl-auto=validate`). **Nunca modifiques una migración ya aplicada**: los cambios van en una migración nueva
(`V5__...sql`, `V6__...sql`, ...).

| Migración | Contenido |
|---|---|
| `V1__init_schema.sql` | Esquema inicial: roles, usuarios, estaciones, bicicletas, historial de estados, viajes, tipos de incidencia, incidencias, mantenimiento, historial de disponibilidad, outbox de eventos |
| `V2__seed_reference_data.sql` | Roles (`ADMIN`, `OPERATOR`, `USER`) y tipos de incidencia (pinchazo, frenos, cadena, vandalismo, otro) |
| `V3__seed_demo_user.sql` | Usuario demo con id = 1 y rol `USER` |
| `V4__maintenance_origin_station.sql` | Estación de origen de cada orden de mantenimiento |

Las tablas `station_availability_history` y `event_outbox` ya existen en el esquema, pero todavía no se usan.

## Estaciones Ecobici (dataset oficial)

El repo incluye el dataset oficial de estaciones de bicicletas públicas de Buenos Aires Data
([`src/main/resources/datasets`](src/main/resources/datasets), licencia CC-BY-2.5-AR).

- Se importa al arrancar si `ECOBICI_IMPORT_ENABLED=true`. En producción está activado desde el `render.yaml`.
- Es **idempotente**: usa el identificador oficial como `external_id` (único), saltea las estaciones que ya
  existen y no pisa cambios ni bajas manuales. Por eso puede quedar activado siempre.
- De los 471 registros se importan 468; 3 son duplicados del propio dataset.
- La capacidad sale de la propiedad `ANCLAJES`. Los registros inválidos se informan en el log sin frenar la importación.
- Las estaciones se cargan **sin bicicletas**: las bicis se dan de alta aparte.

## Recomendación inteligente

`GET /api/v1/stations/recommendation?lat=..&lng=..&purpose=PICKUP|DROPOFF`

1. El backend busca las estaciones candidatas cercanas (hasta 1.000 m, máximo 15) con su disponibilidad actual.
2. Le envía ese snapshot al servicio de recomendación, que las rankea con un modelo de regresión logística
   ([ADR-002](docs/adr/ADR-002-modelo-regresion-logistica.md)) y devuelve la mejor, alternativas y una explicación.
3. Si el servicio no responde a tiempo (1 s de conexión, 2 s de lectura), falla o devuelve algo inconsistente,
   el backend usa el **criterio de respaldo**: la estación más cercana que tenga el recurso necesario
   (`source = FALLBACK`). La recomendación nunca hace caer el módulo.
4. Si no hay estaciones cerca o ninguna tiene el recurso, responde `200` con `status = NO_RECOMMENDATION` y el motivo.

Modelo, entrenamiento, métricas y contrato: [recommendation-service/README.md](recommendation-service/README.md),
[docs/MOV-041](docs/MOV-041-recomendacion-inteligente.md) y [docs/MOV-042](docs/MOV-042-integracion-recomendacion.md).

## Seguridad

Hay dos modos, controlados por `SECURITY_LOCAL_ENABLED`:

| Modo | Valor | Comportamiento |
|---|---|---|
| Local / demo | `true` (default, y el que usa hoy producción) | Todos los endpoints abiertos |
| Seguro | `false` | Exige JWT válido (validado contra `AUTH_JWK_SET_URI`), salvo disponibilidad, estaciones cercanas, recomendación, health y Swagger, que quedan públicos |

**Identificación del usuario:** mientras no esté integrado el Login Federado (Grupo 2), el usuario actual se
identifica con el header **`X-User-Id`**. Lo usan viajes, incidencias y mantenimiento. Los endpoints de
administración validan que ese usuario esté activo y tenga rol `ADMIN` (si no, `403`).

> Con `SECURITY_LOCAL_ENABLED=true`, cualquiera con la URL puede usar los endpoints de escritura y hacerse pasar
> por otro usuario cambiando `X-User-Id`. Es aceptable para la demo, no para un entorno real.

**CORS:** `CORS_ALLOWED_ORIGINS` tiene que incluir el dominio del frontend (admite patrones como
`https://daii-grupo-8-frontend-*.vercel.app` para los previews de Vercel). No uses `https://*.vercel.app`:
habilitaría a cualquier sitio de Vercel con credenciales.

## Tests y calidad

```bash
./gradlew test                  # unitarios + integración (requiere Docker corriendo)
./gradlew jacocoTestReport      # cobertura: build/reports/jacoco/test/html/index.html
```

- **Unitarios** (services, controllers, mappers, validaciones): no necesitan base de datos.
- **Integración** (`*IntegrationTest`, `repository/*Test`, `MovilidadBackendApplicationTests`): levantan un MySQL
  real con **Testcontainers**, aplican las migraciones de Flyway y prueban los flujos completos por HTTP. Sin
  Docker fallan con `Could not find a valid Docker environment`.
- Para correr una sola clase: `./gradlew test --tests "*BikeServiceTest"`.

Servicio de recomendación (desde `recommendation-service/`, con `requirements-dev.txt` instalado):

```bash
pytest --cov
```

La calidad se controla en **SonarQube** (SonarCloud, organización `daii-tpo`) con quality gate obligatorio en
cada PR. El informe de tests de mutación está en [docs/INFORME_TESTS_MUTACION.md](docs/INFORME_TESTS_MUTACION.md).

## CI/CD y deploy

| Workflow | Cuándo | Qué hace |
|---|---|---|
| [`ci.yml`](.github/workflows/ci.yml) | Cada PR a `main` | Compila, corre los tests (Java y Python), genera cobertura y pasa el quality gate de SonarQube |
| [`cd.yml`](.github/workflows/cd.yml) | Cada push a `main` | Verifica que la imagen Docker compile |

**Deploy:** Render deploya automáticamente cada push a `main` a partir del [`render.yaml`](render.yaml):

| Servicio | Contenido | Se redeploya cuando |
|---|---|---|
| `movilidad-backend` | Este backend ([`Dockerfile`](Dockerfile)) | Cambia cualquier archivo del repo |
| `movilidad-recommendation` | Servicio de recomendación ([`recommendation-service/Dockerfile`](recommendation-service/Dockerfile)); el modelo se entrena durante el build | Cambia algo en `recommendation-service/` |

- Las credenciales (base de datos, CORS, URL del servicio de recomendación) están marcadas con `sync: false`
  y se cargan en el dashboard de Render. **Nunca van en el repo.**
- Las migraciones de Flyway se aplican solas al arrancar. En el log del deploy se ve `Successfully applied N migration(s)`.
- Después de un merge, el deploy tarda unos minutos. Si no aparece, revisar la pestaña *Events* del servicio
  en Render; ahí se puede lanzar un *Manual Deploy*.
- Plan free: los servicios se duermen sin tráfico y tardan 30–60 s en despertar.

## Documentación adicional

| Documento | Tema |
|---|---|
| [MOV-015](docs/MOV-015-api-bicicletas.md) | API de bicicletas |
| [MOV-016](docs/MOV-016-disponibilidad-estaciones.md) | Disponibilidad de estaciones |
| [MOV-017](docs/MOV-017-estaciones-cercanas.md) | Búsqueda de estaciones cercanas |
| [MOV-021](docs/MOV-021-manejo-errores-validaciones.md) | Manejo de errores y validaciones |
| [MOV-041](docs/MOV-041-recomendacion-inteligente.md) | Modelo de recomendación inteligente |
| [MOV-042](docs/MOV-042-integracion-recomendacion.md) | Integración de la recomendación en el backend |
| [ADR-001](docs/adr/ADR-001-recomendacion-http-sincrona.md) | Recomendación por HTTP síncrono |
| [ADR-002](docs/adr/ADR-002-modelo-regresion-logistica.md) | Elección del modelo (regresión logística) |
| [Tests de mutación](docs/INFORME_TESTS_MUTACION.md) | Informe de calidad de los tests |

## Pendientes y dependencias externas

- **Login Federado (Grupo 2)**: reemplazar el header `X-User-Id` por la identidad del JWT y pasar producción a
  `SECURITY_LOCAL_ENABLED=false`.
- **Event bus (Grupo 1)**: publicar los eventos de viaje (`movilidad.viaje.iniciado` / `movilidad.viaje.finalizado`)
  cuando esté definido el contrato. Hoy `TripEventPublisher` solo los registra en el log.
- **Recomendación con datos reales**: el modelo está entrenado con datos sintéticos (ver sus limitaciones en
  [recommendation-service/README.md](recommendation-service/README.md)).
- **Historial de disponibilidad**: la tabla `station_availability_history` está creada pero todavía no se completa.
