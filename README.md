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
| CI/CD | GitHub Actions |

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

Variables de entorno (todas tienen default para desarrollo local):

| Variable | Default | Descripción |
|---|---|---|
| `DB_HOST` | `localhost` | Host de MySQL |
| `DB_PORT` | `3306` | Puerto de MySQL |
| `DB_NAME` | `movilidad` | Nombre de la base |
| `DB_USERNAME` | `root` | Usuario de MySQL |
| `DB_PASSWORD` | `root` | Password de MySQL |
| `AUTH_JWK_SET_URI` | `http://localhost:9000/.well-known/jwks.json` | JWKS del auth-simulator (Grupo 1) |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Broker de Kafka |

```bash
./gradlew bootRun
```

## Tests

```bash
./gradlew test
```

Los tests de integración usan Testcontainers (levantan un MySQL real en Docker), por lo que Docker debe estar corriendo.

## Pendientes

- Modelo de dominio (MOV-010): entidades Usuario, Bicicleta, Estación, Viaje, Reporte/Incidencia.
- Serializador Avro/Confluent para consumir eventos de Kafka, una vez que el Grupo 1 publique el contrato.
- Endpoint real de autenticación contra el auth-simulator del Grupo 1 (namespace `com.citypass.movilidad`).
