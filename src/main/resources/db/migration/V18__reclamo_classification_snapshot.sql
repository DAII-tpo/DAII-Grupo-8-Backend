-- Snapshot de clasificación de Reclamos para reevaluar prioridad y pedido de grúa.
ALTER TABLE ciclovia_obstructions
    ADD COLUMN reclamo_categoria VARCHAR(100) NULL,
    ADD COLUMN reclamo_prioridad VARCHAR(30) NULL,
    ADD COLUMN reclamo_estado VARCHAR(30) NULL,
    ADD COLUMN objeto_afectado VARCHAR(100) NULL,
    ADD COLUMN afecta_circulacion BOOLEAN NULL;
