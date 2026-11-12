-- V7__ciclovia_obstructions.sql
-- Caso de uso: Detección y gestión de obstrucciones de ciclovías reportadas por ciudadanos.

CREATE TABLE ciclovia_obstructions (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    reclamo_id           VARCHAR(64)   NOT NULL,
    ciudadano_id         VARCHAR(64)   NULL,
    titulo               VARCHAR(255)  NULL,
    descripcion          TEXT          NULL,
    direccion            VARCHAR(255)  NULL,
    barrio               VARCHAR(100)  NULL,
    latitude             DECIMAL(10,7) NOT NULL,
    longitude            DECIMAL(10,7) NOT NULL,
    impact_level         VARCHAR(20)   NOT NULL DEFAULT 'MEDIO',
    status               VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    affected_station_ids VARCHAR(500)  NULL,
    detour_description   TEXT          NULL,
    emergency_dispatched BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at          DATETIME      NULL,
    CONSTRAINT uk_ciclovia_obstructions_reclamo UNIQUE (reclamo_id),
    CONSTRAINT chk_ciclovia_obstructions_status CHECK (status IN ('ACTIVE', 'RESOLVED')),
    CONSTRAINT chk_ciclovia_obstructions_impact CHECK (impact_level IN ('BAJO', 'MEDIO', 'ALTO'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_ciclovia_obstructions_status ON ciclovia_obstructions (status);
CREATE INDEX idx_ciclovia_obstructions_coords ON ciclovia_obstructions (latitude, longitude);

