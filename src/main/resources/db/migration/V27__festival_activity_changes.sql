CREATE TABLE festival_activity_changes (
    activity_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
    activity_state VARCHAR(16) NOT NULL,
    modified_epoch_ms BIGINT NOT NULL,
    reason VARCHAR(500) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
