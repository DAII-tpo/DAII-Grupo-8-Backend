-- Cada fila representa una afectación abierta; el historial queda en el outbox/EDA.
ALTER TABLE city_incidents
    ADD COLUMN correlation_id VARCHAR(255) NULL,
    ADD COLUMN road_incident_id VARCHAR(64) NULL;

CREATE TABLE station_affectation_lock (
    id INT PRIMARY KEY
) ENGINE=InnoDB;
INSERT INTO station_affectation_lock (id) VALUES (1);

CREATE TABLE station_affectations (
    affectation_id VARCHAR(36) PRIMARY KEY,
    station_id BIGINT NOT NULL,
    cause_id VARCHAR(64) NOT NULL,
    cause_type VARCHAR(30) NOT NULL,
    origin_module VARCHAR(20) NOT NULL,
    correlation_id VARCHAR(255) NOT NULL,
    CONSTRAINT uk_station_affectation UNIQUE (station_id, cause_type, cause_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
