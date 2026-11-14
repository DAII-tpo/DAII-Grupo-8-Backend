ALTER TABLE festival_restock_tasks
    ADD COLUMN start_epoch_ms BIGINT NULL,
    ADD COLUMN location_changed_epoch_ms BIGINT NULL,
    ADD COLUMN removed_from_coverage_epoch_ms BIGINT NULL;
