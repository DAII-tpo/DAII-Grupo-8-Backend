-- V34__climate_alert_expiration.sql
-- Vencimiento automático de la alerta climática por vigenciaHasta.
--
-- Movilidad da por finalizada sola una alerta cuando llega su valid_until, por el mismo camino que la
-- CriticidadClimaticaFinalizada de Espacios. finalization_reason guarda quién la cerró: ESPACIOS o VENCIMIENTO.
--
-- Un ALTER por sentencia para no depender de que la base gestionada admita varios cambios en una sola.

ALTER TABLE climate_alerts
    ADD COLUMN finalization_reason VARCHAR(16) NULL;

ALTER TABLE climate_alerts
    ADD CONSTRAINT chk_climate_finalization_reason
        CHECK (finalization_reason IS NULL OR finalization_reason IN ('ESPACIOS','VENCIMIENTO'));

-- Las alertas ya finalizadas hasta hoy las cerró Espacios.
UPDATE climate_alerts SET finalization_reason = 'ESPACIOS' WHERE status = 'FINALIZADA';

-- El job de vencimiento busca por (status, valid_until).
CREATE INDEX idx_climate_alert_expiration ON climate_alerts (status, valid_until);
