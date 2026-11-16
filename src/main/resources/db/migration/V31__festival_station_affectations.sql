-- La causa de una afectación por evento público usa el mismo identificador que el plan.
ALTER TABLE station_affectations
    MODIFY COLUMN cause_id VARCHAR(128) NOT NULL;
