-- Catálogo y estado recibido de Espacios Públicos. Las estaciones existentes no tienen
-- todavía un barrio verificado: la FK permanece nullable hasta importar el catálogo.
CREATE TABLE neighborhoods (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    normalized_name VARCHAR(120) NOT NULL,
    locality VARCHAR(80) NOT NULL,
    latitude DECIMAL(10,7) NOT NULL,
    longitude DECIMAL(10,7) NOT NULL,
    CONSTRAINT uk_neighborhood_normalized_name UNIQUE (normalized_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE stations ADD COLUMN neighborhood_id VARCHAR(64) NULL;
ALTER TABLE stations ADD CONSTRAINT fk_stations_neighborhood
    FOREIGN KEY (neighborhood_id) REFERENCES neighborhoods(id);
CREATE INDEX idx_stations_neighborhood ON stations (neighborhood_id);

CREATE TABLE climate_alerts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_alert_id VARCHAR(100) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    phenomenon VARCHAR(50) NOT NULL,
    valid_from DATETIME(3) NOT NULL,
    valid_until DATETIME(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    received_at DATETIME(3) NOT NULL,
    activated_at DATETIME(3) NULL,
    finalized_at DATETIME(3) NULL,
    actor_sub VARCHAR(100) NULL,
    original_payload JSON NULL,
    CONSTRAINT uk_climate_alert_external_id UNIQUE (external_alert_id),
    CONSTRAINT chk_climate_severity CHECK (severity IN ('BAJA','MEDIA','ALTA','CRITICA')),
    CONSTRAINT chk_climate_status CHECK (status IN ('PROGRAMADA','ACTIVA','FINALIZADA'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX idx_climate_alert_activation ON climate_alerts (status, valid_from);

CREATE TABLE climate_alert_neighborhoods (
    climate_alert_id BIGINT NOT NULL,
    neighborhood_id VARCHAR(64) NOT NULL,
    PRIMARY KEY (climate_alert_id, neighborhood_id),
    CONSTRAINT fk_climate_alert_neighborhood_alert FOREIGN KEY (climate_alert_id) REFERENCES climate_alerts(id),
    CONSTRAINT fk_climate_alert_neighborhood_neighborhood FOREIGN KEY (neighborhood_id) REFERENCES neighborhoods(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE climate_alert_trip_notifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    climate_alert_id BIGINT NOT NULL,
    trip_id BIGINT NOT NULL,
    suggested_station_id BIGINT NULL,
    notified_at DATETIME(3) NOT NULL,
    CONSTRAINT uk_climate_notification_alert_trip UNIQUE (climate_alert_id, trip_id),
    CONSTRAINT fk_climate_notification_alert FOREIGN KEY (climate_alert_id) REFERENCES climate_alerts(id),
    CONSTRAINT fk_climate_notification_trip FOREIGN KEY (trip_id) REFERENCES trips(id),
    CONSTRAINT fk_climate_notification_station FOREIGN KEY (suggested_station_id) REFERENCES stations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Idempotencia de eventId: reutilizar event_inbox existente (V5), sin crear otra tabla.
