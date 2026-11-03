# Ambiente compartido de integración CU-INT-01

Objetivo: ejecutar la rama `feat/cu-int-01-ruta-urbana` con Espacios, Reclamos, Emergencias y BI,
sin cambiar `main`, su servicio Render, su base ni su frontend. El blueprint independiente es
`render.integration.yaml`; no reemplaza `render.yaml` y no despliega nada por sí mismo.

## Separación de recursos

| Componente | Integración |
| --- | --- |
| Backend | Servicio Render nuevo `movilidad-backend-cu-int-01`, rama del CU y despliegue manual |
| Base | Schema/base `movilidad_cu_int_01`, usuario con permisos solo sobre esa base |
| Frontend | Proyecto Vercel adicional apuntando a `feat/cu-int-01-frontend`, o frontend local |
| Kafka | Grupo `com.citypass.movilidad.cu-int-01.integracion`, exclusivo de este ambiente |
| Bus | Gateway, identidad y broker de integración confirmados por EDA y usados por todos los grupos |
| Datos | Eventos, incidentes y usuarios de prueba; nunca copiar datos de producción |

Un consumer group distinto evita que dos backends se repartan los mensajes. **No aísla los tópicos
ni las publicaciones:** si main u otro ambiente usa el mismo bus y namespace, puede recibir eventos
de esta prueba. El `render.yaml` actual también referencia el dominio de pruebas de EDA. No basta
con llamarlo “test” para garantizar separación.

Para garantizar aislamiento, EDA debe proporcionar un bus de integración separado del usado por
main, o una separación de namespaces/tópicos acordada por TODOS los productores y consumidores.
No cambiar unilateralmente `EVENTBUS_NAMESPACE`: modifica nuestros eventos de salida, no los
nombres fijos de los handlers entrantes ni los permisos del token. Si se comparte el bus existente,
coordinar explícitamente quién está conectado y aceptar que esa modalidad no garantiza aislamiento.
`correlationId` permite rastrear una prueba; no es un filtro de consumo.

## 1. Crear el servicio sin activar el intercambio

En Render crear un **Web Service nuevo**, conectar este repositorio y seleccionar la rama
`feat/cu-int-01-ruta-urbana`, runtime Docker, Dockerfile `./Dockerfile` y health check
`/actuator/health`. Usar `render.integration.yaml` como blueprint independiente si la interfaz
permite seleccionar ese archivo; en caso contrario trasladar sus campos al servicio nuevo.
No editar la rama del servicio existente ni sincronizar este blueprint con él.

Mantener auto-deploy desactivado para probar commits concretos y anotar el SHA desplegado.
El nombre del servicio no garantiza una URL determinada: usar la URL real que asigne Render.

Antes del arranque:

1. Crear la base/schema vacío `movilidad_cu_int_01` y un usuario exclusivo con permisos limitados
   a esa base. Flyway crea las tablas y aplica V1–V12; el importador carga las estaciones Ecobici.
2. Completar DB_HOST, DB_PORT, DB_USERNAME y DB_PASSWORD en la configuración segura de Render.
   No reutilizar el usuario de producción ni conectar el servicio a su base. Los parámetros JDBC
   mantienen verificación TLS; adaptar la CA según el proveedor si lo requiere.
3. Configurar CORS_ALLOWED_ORIGINS con el origen exacto del frontend de integración, o el origen
   local utilizado. No cambiar la configuración CORS del servicio de main.
4. RECOMMENDATION_SERVICE_URL puede señalar un servicio Python de integración separado. También
   se puede probar inicialmente el fallback con un servicio no disponible; verificar el campo
   `source` y no describir esa prueba como validación del modelo. No usar el servicio productivo.
5. Mantener EVENTBUS_CONSUMER_ENABLED=false, EVENTBUS_PUBLISHER_ENABLED=false y
   EVENTBUS_REGISTER_EVENT_TYPES=false. Completar las URLs y credenciales de integración cuando
   las entregue EDA, mediante Render; no pegar secretos en chat, Git ni variables VITE_*.

El blueprint conserva el modo demo abierto que ya usa el módulo. Alojar solo datos de prueba;
ese modo no acredita Login Federado. La autenticación de servicio contra EDA es independiente.

El plan free puede dormir el servicio: los mensajes Kafka no deben considerarse un mecanismo para
despertarlo. Para pruebas continuas usar un plan sin suspensión elegido por el responsable. En una
prueba puntual con free, despertar el backend mediante HTTP, esperar el inicio del consumidor y
mantenerlo activo durante la ventana. No cambiar planes pagos automáticamente.

## 2. Conectar el frontend de desarrollo

Crear un proyecto Vercel separado para el frontend y la rama `feat/cu-int-01-frontend`, o ejecutarlo
localmente. Configurar VITE_API_BASE_URL con la URL real del backend de integración y volver a
compilar/desplegar. Esa variable se incorpora al build, no cambia dinámicamente.
VITE_RECOMMENDATION_SERVICE_URL debe apuntar al Python de integración si se quiere probar el modelo.
Configurar las URLs de dashboards únicamente cuando BI entregue su HTML alojado. No editar las
variables del proyecto productivo ni asumir que un preview usa automáticamente el backend correcto.

## 3. Habilitar EDA después de la coordinación

Confirmar con el responsable de EDA:

- Gateway de integración (URL con /api/v1), URL del token, broker SASL_SSL y credenciales del cliente.
- Permiso del consumer group exclusivo y de lectura de los tópicos de los handlers del CU.
- Eventos de Espacios: com.citypass.espacios.EventoComunitarioPublicado, EventoCancelado y
  EspacioEstadoCambiado. ReservaConfirmada todavía requiere implementar su consumidor.
- Eventos de Reclamos: com.citypass.reclamos.ReclamoCreado y ReclamoActualizado, con schemas confirmados.
- Eventos de Emergencias: no tratarlos como validados hasta recibir su contrato directo y adaptar
  ubicación/estado si corresponde. No generar coordenadas a partir de un barrio por suposición.
- Registro y permiso de publicación de com.citypass.movilidad.RecomendacionDestinoActualizada,
  EstacionUrbanaAfectada y EstacionUrbanaRestaurada. BI/EDA deben aceptar las extensiones propuestas
  moduloOrigen, EMERGENCIA y RECOMENDACION_RESTRINGIDA antes de registrar/publicar esos schemas.
- La suscripción de Espacios a las recomendaciones y la de BI a las afectaciones/restauraciones.

Una vez confirmado, cambiar en el servicio NUEVO EVENTBUS_CONSUMER_ENABLED=true y
EVENTBUS_PUBLISHER_ENABLED=true y reiniciar/desplegar. Mantener el registro automático desactivado
si EDA registra los tipos. Solo habilitarlo si el responsable autoriza el registro de los schemas
exactos. El registrador no actualiza contratos existentes.

Nuestro consumidor es Kafka directo; no configurar un webhook inexistente. Si EDA usa además un
registro administrativo de suscripciones/permisos, coordinarlo: los handlers locales no prueban
autorización ni conectividad. Conservar el grupo al reiniciar para mantener los offsets.
Un grupo nuevo comienza con auto-offset-reset=earliest y puede leer eventos históricos del tópico:
no cambiar grupos ni resetear offsets de main para repetir pruebas.

## 4. Prueba coordinada y evidencias

Primero comprobar health=UP y los endpoints del servicio nuevo. Health solo no demuestra conexión EDA.
Verificar en los logs que el consumidor arranca con los tópicos correctos, obtiene asignaciones y
no presenta errores de autenticación, autorización, TLS o deserialización.

Elegir un evento NUEVO y futuro, con coordenadas próximas a estaciones importadas, e identificarlo
por eventoId. Acordar la ventana y los identificadores de prueba entre los grupos.

1. Espacios publica EventoComunitarioPublicado. Confirmar aceptación/eventId en el gateway,
   event_inbox=PROCESSED en Movilidad y la proyección en cultural_destinations.
2. Consultar /api/v1/destinations/events y su recomendación. Confirmar la salida
   RecomendacionDestinoActualizada en event_outbox=PUBLISHED con bus_event_id, y que Espacios la recibió.
3. Reclamos publica un incidente próximo con afectaCirculacion=true y contrato confirmado.
   Verificar cambio de recomendación o NO_RECOMMENDATION y EstacionUrbanaAfectada recibida por BI.
4. Resolver ese incidente. Verificar EstacionUrbanaRestaurada con el mismo afectacionId y recuperación.
5. Cancelar el evento. Verificar NO_RECOMMENDATION y que Espacios lo deja de mostrar como destino válido.
6. Probar duplicados con EDA sin tocar offsets ajenos. Confirmar que no se duplican efectos ni afectaciones.
7. Incorporar Emergencias cuando su contrato esté confirmado/adaptado y el dashboard cuando BI lo entregue.

Si algo falla, seguir el orden gateway → Kafka/inbox → proyección → outbox → gateway → consumidor destino.
Un PENDING/FAILED en outbox no es una publicación exitosa. Conservar los IDs y estados de cada salto;
no compartir tokens ni datos personales en capturas o logs.

No reutilizar el evento cancelado de la prueba anterior: Movilidad conserva estados terminales para
evitar resucitar destinos al recibir mensajes atrasados. Para otro escenario, crear un evento nuevo.

## 5. Cierre y paso a main

Registrar commit desplegado, schemas aprobados y evidencia de la secuencia. Completar la suite oficial
con Confluent accesible: el arnés local de dominio no valida el transporte real. Revisar compatibilidad
de migraciones y cambios recientes de main. Solo después abrir/revisar el PR e integrar a main según
lo acordado por el equipo. Crear este ambiente no implica hacer merge ni desplegar producción.

Estado de este archivo: preparación de configuración e instrucciones, sin recursos externos creados
ni despliegue realizado. Las pruebas conjuntas siguen pendientes de acceso y acuerdos de los equipos.
