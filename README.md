# DAII-Grupo-8-Backend

Backend del módulo **Movilidad Urbana Inteligente** (Grupo 8) del proyecto **CityPass+**.

## Stack

| Capa | Tecnología |
|---|---|
| Backend | Java 21 LTS + Spring Boot 3.5.x |
| Base de datos | MySQL 8 |
| API | REST versionada (`/api/v1`), documentada con springdoc-openapi |
| Event Bus | Apache Kafka (gestionado por el Grupo 1, vía Event Gateway HTTP para publicar) |
| Auth | JWT (Spring Security + `NimbusJwtDecoder`, contra el auth-simulator del Grupo 1) |
| Testing | JUnit 5, Mockito, Testcontainers, spring-kafka-test, Jacoco |
| CI/CD | GitHub Actions, Sonarqube |

Se usa Spring Boot 3.5.x (no 4.x) porque el Grupo 1 reportó incompatibilidad entre Jackson 3 (usado por Spring Boot 4.x) y el serializador Avro de Confluent. Ver la decisión completa en MOV-004.

## Estructura

```
src/main/java/com/citypass/movilidad/
├── controller/    # Endpoints REST (/api/v1/...)
├── service/       # Lógica de negocio
├── repository/    # Acceso a datos (Spring Data JPA)
├── model/         # Entidades JPA
├── dto/           # Objetos de transferencia
├── config/        # Seguridad, OpenAPI, etc.
└── exception/     # Manejo global de errores
```

`service/`, `repository/`, `model/` y `dto/` están vacíos: el modelo de dominio (Usuario, Bicicleta, Estación, Viaje, Reporte) se agrega cuando se defina el detalle de MOV-010.

## Cómo correr localmente

### 1. Base de datos MySQL

Si no tenés MySQL corriendo local, levantalo con Docker:

```bash
docker run --name movilidad-mysql \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_DATABASE=movilidad \
  -p 3306:3306 \
  -d mysql:8.0
```

Eso crea un contenedor con usuario `root` / password `root` y la base `movilidad` ya creada — coincide con los defaults de abajo, así que no hace falta configurar nada más.

Si ya tenés un MySQL propio corriendo (con otro usuario/password/puerto, por ejemplo un contenedor compartido con otros proyectos), no hace falta recrearlo: solo asegurate de que exista la base `movilidad` (`CREATE DATABASE IF NOT EXISTS movilidad;`) y pasá tus credenciales reales por variables de entorno al correr la app (ver tabla abajo). **No hardcodees tu password real en ningún archivo del repo.**

### 2. Variables de entorno

Todas tienen default para desarrollo local (pensados para el contenedor Docker de arriba):

| Variable | Default | Descripción |
|---|---|---|
| `DB_HOST` | `localhost` | Host de MySQL |
| `DB_PORT` | `3306` | Puerto de MySQL |
| `DB_NAME` | `movilidad` | Nombre de la base |
| `DB_USERNAME` | `root` | Usuario de MySQL |
| `DB_PASSWORD` | `root` | Password de MySQL |
| `AUTH_JWK_SET_URI` | `http://localhost:9000/.well-known/jwks.json` | JWKS del auth-simulator (Grupo 1) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Broker de Kafka |

Si tu MySQL local tiene otras credenciales, sobreescribí las variables al correr:

```bash
DB_USERNAME=miusuario DB_PASSWORD=mipassword ./gradlew bootRun
```

O en IntelliJ: Run Configuration → Environment variables → agregá `DB_PASSWORD=miPassword` (no lo commitees, queda solo en tu config local del IDE).

### 3. Levantar la app

```bash
./gradlew bootRun
```

## Tests

```bash
./gradlew test
```

## Importar estaciones Ecobici

El repositorio incluye una copia del dataset oficial de estaciones de Buenos Aires Data. La importación está
deshabilitada por defecto y se activa para una ejecución con `ECOBICI_IMPORT_ENABLED=true`:

```bash
ECOBICI_IMPORT_ENABLED=true ./gradlew bootRun
```

En PowerShell:

```powershell
$env:ECOBICI_IMPORT_ENABLED="true"
.\gradlew.bat bootRun
```

La importación usa el `id` oficial como identificador externo, ignora estaciones ya existentes y no sobrescribe
cambios o bajas manuales. Los registros inválidos se informan en el log y no interrumpen el resto del proceso.
La capacidad se importa desde la propiedad `ANCLAJES` del recurso GeoJSON oficial.

Los tests de integración usan Testcontainers (levantan un MySQL real en Docker), por lo que Docker debe estar corriendo.

## Pendientes

- Modelo de dominio (MOV-010): entidades Usuario, Bicicleta, Estación, Viaje, Reporte/Incidencia.
- Serializador Avro/Confluent para consumir eventos de Kafka, una vez que el Grupo 1 publique el contrato.
- Endpoint real de autenticación contra el auth-simulator del Grupo 1 (namespace `com.citypass.movilidad`).
