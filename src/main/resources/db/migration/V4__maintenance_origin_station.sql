-- V4__maintenance_origin_station.sql
-- Una bicicleta que entra a mantenimiento se retira de su estación (deja de ocupar el anclaje mientras
-- se repara). Se guarda la estación de origen en la orden para devolverla ahí al finalizar, salvo que el
-- admin indique otra.
--
-- Nullable: las órdenes anteriores a esta migración no la tienen, y una bicicleta sin estación tampoco.
-- Dos ALTER separados en lugar de uno combinado para no depender de que la base gestionada admita varios
-- cambios de esquema en una misma sentencia.

ALTER TABLE maintenance_records
    ADD COLUMN origin_station_id BIGINT NULL AFTER incident_id;

ALTER TABLE maintenance_records
    ADD CONSTRAINT fk_mr_origin_station FOREIGN KEY (origin_station_id) REFERENCES stations (id) ON DELETE SET NULL;
