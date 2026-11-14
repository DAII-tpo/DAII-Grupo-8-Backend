-- Declaraciones de llegada: proyectan demanda, pero no reservan bicicletas ni anclajes.
CREATE TABLE event_arrival_intentions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    event_id VARCHAR(64) NOT NULL,
    station_id BIGINT NOT NULL,
    arrival_at DATETIME(3) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    cancelled_at DATETIME(3) NULL,
    CONSTRAINT uk_event_arrival_intention_user_event UNIQUE (user_id, event_id),
    CONSTRAINT fk_event_arrival_intention_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_event_arrival_intention_event FOREIGN KEY (event_id) REFERENCES cultural_destinations(event_id),
    CONSTRAINT fk_event_arrival_intention_station FOREIGN KEY (station_id) REFERENCES stations(id),
    CONSTRAINT chk_event_arrival_intention_status CHECK (status IN ('ACTIVE','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_event_arrival_intention_projection
    ON event_arrival_intentions (event_id, status, arrival_at, station_id);
