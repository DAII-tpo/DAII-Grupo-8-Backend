-- No modifica V1–V12 ya aplicadas en el ambiente de integración.
ALTER TABLE cultural_destinations
    ADD COLUMN event_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN recommendation_token VARCHAR(36) NULL;
-- Las filas antiguas no guardaban el estado textual: sin evidencia no inferir ACTIVE.
-- Un snapshot nuevo habilita el destino; CANCELLED y ACTIVE_FULL sí eran estados persistidos.
UPDATE cultural_destinations SET event_status = CASE
    WHEN cancelled = TRUE THEN 'CANCELLED'
    WHEN full_capacity = TRUE THEN 'ACTIVE_FULL'
    ELSE 'UNKNOWN' END;

-- El cursor conserva precisión de Instant; el bloqueo serializa la proyección y su publicación.
CREATE TABLE espacios_event_cursors (
    entity_type VARCHAR(32) NOT NULL,
    external_id VARCHAR(64) NOT NULL,
    received_at VARCHAR(40) NULL,
    PRIMARY KEY (entity_type, external_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO espacios_event_cursors (entity_type, external_id) VALUES ('LOCK', 'ESPACIOS');

CREATE TABLE space_reservations (
    reservation_id VARCHAR(64) PRIMARY KEY,
    reservation_type VARCHAR(16) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    space_id VARCHAR(64) NOT NULL,
    people INT NOT NULL,
    starts_at DATETIME(6) NOT NULL,
    ends_at DATETIME(6) NOT NULL,
    CONSTRAINT chk_space_reservation_type CHECK (reservation_type IN ('EVENTO','ESPACIO')),
    CONSTRAINT chk_space_reservation_people CHECK (people >= 0),
    CONSTRAINT chk_space_reservation_interval CHECK (ends_at > starts_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_space_reservations_space_time ON space_reservations (space_id, starts_at, ends_at);

-- Conservar la última revisión aun después de publicar evita que un cálculo viejo gane al nuevo.
ALTER TABLE destination_recommendation_jobs ADD COLUMN pending BOOLEAN NOT NULL DEFAULT TRUE;
INSERT INTO destination_recommendation_jobs (event_id, request_token, pending)
SELECT d.event_id, UUID(), TRUE FROM cultural_destinations d
WHERE NOT EXISTS (SELECT 1 FROM destination_recommendation_jobs j WHERE j.event_id = d.event_id);

UPDATE cultural_destinations d
JOIN destination_recommendation_jobs j ON j.event_id = d.event_id
SET d.recommendation_token = j.request_token;
