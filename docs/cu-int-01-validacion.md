# Validación y pendientes del CU-INT-01

Rama: `feat/cu-int-01-ruta-urbana`. Base del PR #36:
`fd3f23f071207163ad1270b96a3b85fb7ecff39d`. Sin cambios a main ni despliegues.

## Correcciones

- Consulta directa y publicación rechazan eventos terminados.
- Recálculo por incidentes respeta el radio configurable del filtro.
- Payload con 12 campos, sin acceder a una estación nula.
- V7 conserva cancelaciones, cierres de incidentes y estados de espacios incluso
  si llegan antes de la creación. Incluye los estados ya existentes al migrar.
- V8 corrige un defecto confirmado con MySQL: el CHECK del outbox rechazaba
  CULTURAL_EVENT, impidiendo guardar los eventos salientes. Se conservan los
  tipos BIKE/TRIP/STATION/INCIDENT y se agrega CULTURAL_EVENT.
- V9 guarda solicitudes de recálculo dentro de la transacción de negocio. La
  inserción en outbox y su confirmación ocurren juntas en REQUIRES_NEW. Si fallan,
  el scheduler reintenta incluso eventos cancelados/finalizados; una confirmación
  vieja no borra una solicitud más nueva (token único). Después de confirmar una
  invalidación no se repite por el scheduler mientras el destino siga inválido.
- Validación de IDs vacíos/tipos, coordenadas numéricas y rangos, fechas y cupos.

No se modificaron V1–V6 ni contratos de terceros. No se incorporó ReservaConfirmada
sin schema ni se mezclaron las ramas BI independientes.

## Evidencia local

Node/Java/Python y MySQL locales; no productores externos ni gateway compartido.

- **397 tests Java correctos, 0 fallos, 0 errores, 0 omitidos** en el arnés local.
- JaCoCo Java global: 1725/1824 líneas (94,57 %), 541/649 ramas (83,36 %).
- Estimación de código nuevo vs origin/main, aplicando las exclusiones de Sonar
  ya existentes: 297/300 líneas, 125/152 ramas; combinación 93,36 %.
  No equivale al Quality Gate remoto.
- Python: 48 tests correctos con cobertura XML generada según CI.
- Integración CU con MySQL/Flyway real: commit persiste outbox después de negocio;
  rollback elimina destino/estado/outbox; cancelación antes de publicación;
  espacio deshabilitado antes del destino; resolución antes de creación;
  recuperación de invalidación fallida y protección de solicitudes nuevas.

La política actual bloquea packages.confluent.io. Por eso se ejecutó la suite
mediante un init script **fuera del repositorio**, en
`/workspace/.setup/cu-unit-tests.init.gradle`, que excluye la dependencia
io.confluent, añade Apache Avro 1.12.1 de Maven Central para compilar los mappers
y excluye únicamente EventBusIntegrationTest.java, que importa KafkaAvroSerializer.
No se cambió el build oficial, no se desactivaron assertions y no se afirma que
Kafka/Schema Registry/Confluent hayan sido validados por este arnés.

```sh
export JAVA_HOME=/workspace/.setup/jdk/usr/lib/jvm/java-21-openjdk-amd64
export GRADLE_USER_HOME=/workspace/.setup/gradle
# Diagnóstico local limitado:
bash gradlew --no-daemon -I /workspace/.setup/cu-unit-tests.init.gradle test jacocoTestReport
# Validación oficial pendiente, cuando Confluent sea accesible:
bash gradlew --no-daemon --max-workers=2 clean compileJava test jacocoTestReport
```

## CI, seguridad e integración pendiente

Los commits se publican solo en feat/cu-int-01-ruta-urbana. El run 40979289450 del
commit 5f750f7 falló en tests antes de Sonar; el detalle del log requiere acceso
que no está disponible. Se solicita nueva ejecución con las correcciones.
La API GitHub y SonarCloud están bloqueadas: no se confirmó issue exacto de
fiabilidad ni Quality Gate remoto. Los dominios requeridos están guardados en el
borrador de configuración. La lectura de páginas públicas de GitHub sí funciona.

El usuario pidió cerrar PR #36 y #25 sin mergear ni borrar ramas. La operación
`gh pr close` fue intentada y devolvió Forbidden en api.github.com: no afirmar
que los PR quedaron cerrados hasta verificarlo.

Los estados terminales dominan creaciones tardías para el mismo ID. No se infiere
reapertura ni orden total entre cambios de espacio: falta acordar versiones o
marcas temporales fiables con los productores. La concurrencia entre tópicos y
las actualizaciones parciales/movimientos de incidentes necesitan probarse con
los contratos y garantías reales. El scheduler requiere un backend activo.

Espacios: publicación real, consumidor de recomendaciones y schema definitivo de
ReservaConfirmada. Reclamos/Emergencias: schemas y prueba de creación/resolución.
BI: aprobación del contrato de estaciones afectadas, consumidor, URLs de dashboards
y política de embedding. No se enviaron mensajes ni se registraron tipos en el
entorno compartido. No se declara E2E real ni el CU completamente terminado.
