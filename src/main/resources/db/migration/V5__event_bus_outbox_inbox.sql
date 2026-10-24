-- V5__event_bus_outbox_inbox.sql
-- Integración con el bus de eventos (Grupo 1).
--
-- event_outbox: se agregan los datos que necesita el relay para reintentar con backoff y para trazar
-- cada fila con el evento que quedó en el bus. event_type pasa a guardar el fqn completo
-- (com.citypass.movilidad.<Nombre>), que es también el tópico de Kafka.
--
-- event_inbox: eventos recibidos del bus. Su clave es el eventId que asigna el gateway, estable entre
-- reentregas, y es lo que permite descartar duplicados (la entrega es at-least-once).
--
-- Un ALTER por columna para no depender de que la base gestionada admita varios cambios en una sentencia.

ALTER TABLE event_outbox
    MODIFY COLUMN event_type VARCHAR(150) NOT NULL;

ALTER TABLE event_outbox
    ADD COLUMN attempts INT NOT NULL DEFAULT 0 AFTER status;

-- Con milisegundos: un DATETIME sin precisión redondea, y un evento recién creado podía quedar programado
-- hasta medio segundo en el futuro.
ALTER TABLE event_outbox
    ADD COLUMN next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) AFTER attempts;

ALTER TABLE event_outbox
    ADD COLUMN last_error VARCHAR(1000) NULL AFTER next_attempt_at;

ALTER TABLE event_outbox
    ADD COLUMN bus_event_id CHAR(36) NULL AFTER last_error;

CREATE INDEX idx_event_outbox_pending ON event_outbox (status, next_attempt_at, created_at);

CREATE TABLE event_inbox (
    event_id        VARCHAR(64)   PRIMARY KEY,
    event_type      VARCHAR(150)  NOT NULL,
    source          VARCHAR(100)  NOT NULL,
    topic           VARCHAR(200)  NOT NULL,
    kafka_partition INT           NOT NULL,
    kafka_offset    BIGINT        NOT NULL,
    payload_hash    VARCHAR(64)   NULL,
    status          VARCHAR(20)   NOT NULL,
    payload         JSON          NULL,     -- solo se guarda si el procesamiento falló, para poder reprocesarlo
    last_error      VARCHAR(1000) NULL,
    received_at     DATETIME      NOT NULL, -- reloj del gateway (metadata.receivedAt)
    processed_at    DATETIME      NOT NULL,
    CONSTRAINT chk_event_inbox_status CHECK (status IN ('PROCESSED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_event_inbox_status ON event_inbox (status);
