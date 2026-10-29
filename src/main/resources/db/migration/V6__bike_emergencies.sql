-- V6__bike_emergencies.sql
-- Emergencias reportadas durante un viaje (botón SOS).
--
-- Al pedir ayuda el viaje queda pausado (EMERGENCY) y la bici bloqueada (BLOCKED). Hay una ventana para
-- cancelar y, después, otra para responder el cuestionario de triage. El evento EmergenciaBicicleta se
-- publica una sola vez, cuando se envía el cuestionario o vence su plazo (status REPORTED).
--
-- Un ALTER por sentencia para no depender de que la base gestionada admita varios cambios en una sola.

ALTER TABLE trips DROP CHECK chk_trips_status;

ALTER TABLE trips
    ADD CONSTRAINT chk_trips_status CHECK (status IN ('ACTIVE','COMPLETED','CANCELLED','INCIDENT','EMERGENCY'));

ALTER TABLE bikes DROP CHECK chk_bikes_status;

ALTER TABLE bikes
    ADD CONSTRAINT chk_bikes_status
        CHECK (status IN ('AVAILABLE','IN_USE','MAINTENANCE','OUT_OF_SERVICE','STOLEN','BLOCKED'));

ALTER TABLE bike_status_history DROP CHECK chk_bsh_previous_status;

ALTER TABLE bike_status_history
    ADD CONSTRAINT chk_bsh_previous_status CHECK (previous_status IS NULL
        OR previous_status IN ('AVAILABLE','IN_USE','MAINTENANCE','OUT_OF_SERVICE','STOLEN','BLOCKED'));

ALTER TABLE bike_status_history DROP CHECK chk_bsh_new_status;

ALTER TABLE bike_status_history
    ADD CONSTRAINT chk_bsh_new_status
        CHECK (new_status IN ('AVAILABLE','IN_USE','MAINTENANCE','OUT_OF_SERVICE','STOLEN','BLOCKED'));

CREATE TABLE bike_emergencies (
    id                BIGINT        AUTO_INCREMENT PRIMARY KEY,
    trip_id           BIGINT        NOT NULL,
    user_id           BIGINT        NOT NULL,
    bike_id           BIGINT        NOT NULL,
    status            VARCHAR(20)   NOT NULL,
    latitude          DECIMAL(9,6)  NOT NULL, -- ubicación del usuario al pedir ayuda (navegador)
    longitude         DECIMAL(9,6)  NOT NULL,
    accuracy_meters   DECIMAL(9,2)  NULL,
    reported_at       DATETIME(3)   NOT NULL,
    cancelable_until  DATETIME(3)   NOT NULL,
    triage_deadline   DATETIME(3)   NOT NULL, -- vencido sin respuestas, se publica asumiendo el peor caso
    can_move          BOOLEAN       NULL,
    bleeding          BOOLEAN       NULL,
    vehicle_involved  BOOLEAN       NULL,
    priority          VARCHAR(2)    NULL,
    cancelled_at      DATETIME(3)   NULL,
    sent_at           DATETIME(3)   NULL,
    created_at        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bike_emergencies_trip FOREIGN KEY (trip_id) REFERENCES trips (id) ON DELETE RESTRICT,
    CONSTRAINT fk_bike_emergencies_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_bike_emergencies_bike FOREIGN KEY (bike_id) REFERENCES bikes (id) ON DELETE RESTRICT,
    CONSTRAINT chk_bike_emergencies_status CHECK (status IN ('PENDING','CANCELLED','REPORTED')),
    CONSTRAINT chk_bike_emergencies_priority CHECK (priority IS NULL OR priority IN ('P1','P2','P3','P4'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_bike_emergencies_pending ON bike_emergencies (status, triage_deadline);
CREATE INDEX idx_bike_emergencies_user_status ON bike_emergencies (user_id, status);
