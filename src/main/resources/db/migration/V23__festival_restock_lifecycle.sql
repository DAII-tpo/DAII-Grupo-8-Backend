ALTER TABLE festival_restock_tasks
    ADD COLUMN target_bikes INT NULL,
    ADD COLUMN resolution VARCHAR(24) NULL,
    ADD COLUMN resolved_epoch_ms BIGINT NULL;

UPDATE festival_restock_tasks t
JOIN stations s ON s.id = t.station_id
SET t.target_bikes = CEIL(s.capacity * 0.90);

ALTER TABLE festival_restock_tasks
    MODIFY target_bikes INT NOT NULL,
    ADD INDEX idx_festival_restock_open (resolution, deadline_epoch_ms, station_id);
