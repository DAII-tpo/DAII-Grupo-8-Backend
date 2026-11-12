CREATE TABLE festival_restock_tasks (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    activity_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    correlation_id VARCHAR(132) NOT NULL,
    activity_title VARCHAR(255) NOT NULL,
    station_id BIGINT NOT NULL,
    distance_meters INT NOT NULL,
    deadline_epoch_ms BIGINT NOT NULL,
    CONSTRAINT uk_festival_restock_activity_station UNIQUE (activity_id, station_id),
    CONSTRAINT fk_festival_restock_station FOREIGN KEY (station_id) REFERENCES stations (id),
    INDEX idx_festival_restock_deadline (deadline_epoch_ms)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
