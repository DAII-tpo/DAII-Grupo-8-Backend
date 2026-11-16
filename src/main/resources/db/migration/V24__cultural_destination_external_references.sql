-- Referencias del contrato de Espacios Públicos necesarias para trazabilidad.
ALTER TABLE cultural_destinations
    ADD COLUMN citizen_id VARCHAR(64) NULL,
    ADD COLUMN correlation_id VARCHAR(64) NULL;
