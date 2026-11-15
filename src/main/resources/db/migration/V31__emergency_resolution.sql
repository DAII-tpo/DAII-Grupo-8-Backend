-- V31__emergency_resolution.sql
-- Cierre de los viajes pausados por una emergencia cuando Emergencias la resuelve.
--
-- Movilidad consume com.citypass.emergencias.EmergenciaActualizada: con estadoNuevo RESUELTA, la emergencia
-- pasa a RESOLVED, el viaje a CLOSED_BY_EMERGENCY (finalizado sin cargo) y la bici queda OUT_OF_SERVICE en
-- la estación activa más cercana con lugar, para que la revisen.
--
-- Un ALTER por sentencia para no depender de que la base gestionada admita varios cambios en una sola.

ALTER TABLE trips DROP CHECK chk_trips_status;

ALTER TABLE trips
    ADD CONSTRAINT chk_trips_status
        CHECK (status IN ('ACTIVE','COMPLETED','CANCELLED','INCIDENT','EMERGENCY','CLOSED_BY_EMERGENCY'));

ALTER TABLE bike_emergencies DROP CHECK chk_bike_emergencies_status;

ALTER TABLE bike_emergencies
    ADD CONSTRAINT chk_bike_emergencies_status CHECK (status IN ('PENDING','CANCELLED','REPORTED','RESOLVED'));

-- Cuándo la resolvió Emergencias (resueltoAt del evento, o la recepción si no lo trae).
ALTER TABLE bike_emergencies
    ADD COLUMN resolved_at DATETIME(3) NULL AFTER sent_at;
