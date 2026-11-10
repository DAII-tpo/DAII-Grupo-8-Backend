-- Incidentes de otros modulos que afectan la circulacion (reclamos y emergencias).
CREATE TABLE city_incidents (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    source      VARCHAR(20)   NOT NULL,
    external_id VARCHAR(64)   NOT NULL,
    category    VARCHAR(60)   NOT NULL,
    latitude    DECIMAL(10,7) NOT NULL,
    longitude   DECIMAL(10,7) NOT NULL,
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    reported_at DATETIME      NOT NULL,
    updated_at  DATETIME      NOT NULL,
    CONSTRAINT uk_city_incidents_source_external UNIQUE (source, external_id),
    CONSTRAINT chk_city_incidents_source CHECK (source IN ('RECLAMOS','EMERGENCIAS'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_city_incidents_active ON city_incidents (active);

-- Eventos culturales publicados por Espacios Publicos: los destinos de la ruta.
CREATE TABLE cultural_destinations (
    event_id      VARCHAR(64)   PRIMARY KEY,
    space_id      VARCHAR(64)   NOT NULL,
    title         VARCHAR(200)  NOT NULL,
    zone          VARCHAR(120)  NULL,
    address       VARCHAR(255)  NULL,
    latitude      DECIMAL(10,7) NULL,
    longitude     DECIMAL(10,7) NULL,
    starts_at     DATETIME      NOT NULL,
    ends_at       DATETIME      NOT NULL,
    max_capacity  INT           NOT NULL,
    registrations INT           NOT NULL DEFAULT 0,
    cancelled     BOOLEAN       NOT NULL DEFAULT FALSE,
    space_enabled BOOLEAN       NOT NULL DEFAULT TRUE,
    updated_at    DATETIME      NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_cultural_destinations_space ON cultural_destinations (space_id);
CREATE INDEX idx_cultural_destinations_ends ON cultural_destinations (ends_at);
