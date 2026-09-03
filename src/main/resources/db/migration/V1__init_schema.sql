-- V1__init_schema.sql
-- Módulo Movilidad Urbana Inteligente — esquema inicial (MOV-012, DER de MOV-009/MOV-010)
-- Inmutable una vez mergeado a main: cualquier corrección futura es un V2__..., V3__..., etc.

CREATE TABLE roles (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(255) NULL,
    CONSTRAINT uk_roles_name UNIQUE (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE users (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_user_id VARCHAR(64)  NULL,
    first_name       VARCHAR(100) NOT NULL,
    last_name        VARCHAR(100) NOT NULL,
    email            VARCHAR(255) NOT NULL,
    password_hash    VARCHAR(255) NULL,
    role_id          BIGINT       NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_users_email UNIQUE (email),
    CONSTRAINT fk_users_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE RESTRICT,
    CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE','BLOCKED','INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_users_status ON users (status);

CREATE TABLE stations (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_id VARCHAR(64)   NULL,
    name        VARCHAR(150)  NOT NULL,
    address     VARCHAR(255)  NULL,
    latitude    DECIMAL(10,7) NOT NULL,
    longitude   DECIMAL(10,7) NOT NULL,
    capacity    INT           NOT NULL,
    status      VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    source      VARCHAR(20)   NOT NULL,
    created_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at  DATETIME      NULL,
    CONSTRAINT uk_stations_external_id UNIQUE (external_id),
    CONSTRAINT chk_stations_status CHECK (status IN ('ACTIVE','INACTIVE','MAINTENANCE')),
    CONSTRAINT chk_stations_source CHECK (source IN ('DATASET','MANUAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_stations_status_deleted ON stations (status, deleted_at);
CREATE INDEX idx_stations_lat_lng ON stations (latitude, longitude);

CREATE TABLE bikes (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    code                VARCHAR(50)  NOT NULL,
    station_id          BIGINT       NULL, -- NULL mientras la bici está IN_USE (ver DER)
    status              VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',
    model               VARCHAR(100) NULL,
    purchase_date       DATE         NULL,
    last_maintenance_at DATETIME     NULL,
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at          DATETIME     NULL,
    CONSTRAINT uk_bikes_code UNIQUE (code),
    CONSTRAINT fk_bikes_station FOREIGN KEY (station_id) REFERENCES stations (id) ON DELETE SET NULL,
    CONSTRAINT chk_bikes_status CHECK (status IN ('AVAILABLE','IN_USE','MAINTENANCE','OUT_OF_SERVICE','STOLEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_bikes_status_deleted ON bikes (status, deleted_at);

CREATE TABLE bike_status_history (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    bike_id            BIGINT       NOT NULL,
    previous_status    VARCHAR(20)  NULL,
    new_status         VARCHAR(20)  NOT NULL,
    changed_by_user_id BIGINT       NULL, -- NULL si el cambio fue automático
    reason             VARCHAR(255) NULL,
    changed_at         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bsh_bike FOREIGN KEY (bike_id) REFERENCES bikes (id) ON DELETE RESTRICT,
    CONSTRAINT fk_bsh_user FOREIGN KEY (changed_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT chk_bsh_previous_status CHECK (previous_status IS NULL OR previous_status IN ('AVAILABLE','IN_USE','MAINTENANCE','OUT_OF_SERVICE','STOLEN')),
    CONSTRAINT chk_bsh_new_status CHECK (new_status IN ('AVAILABLE','IN_USE','MAINTENANCE','OUT_OF_SERVICE','STOLEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE incident_types (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(150) NOT NULL,
    description VARCHAR(255) NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT uk_incident_types_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE trips (
    id                      BIGINT       AUTO_INCREMENT PRIMARY KEY,
    user_id                 BIGINT       NOT NULL,
    bike_id                 BIGINT       NOT NULL,
    origin_station_id       BIGINT       NOT NULL,
    destination_station_id  BIGINT       NULL, -- NULL hasta finalizar el viaje
    started_at              DATETIME     NOT NULL,
    ended_at                DATETIME     NULL,
    status                  VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    distance_km             DECIMAL(6,2) NULL,
    duration_seconds        INT          NULL,
    created_at              DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_trips_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trips_bike FOREIGN KEY (bike_id) REFERENCES bikes (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trips_origin_station FOREIGN KEY (origin_station_id) REFERENCES stations (id) ON DELETE RESTRICT,
    CONSTRAINT fk_trips_destination_station FOREIGN KEY (destination_station_id) REFERENCES stations (id) ON DELETE SET NULL,
    CONSTRAINT chk_trips_status CHECK (status IN ('ACTIVE','COMPLETED','CANCELLED','INCIDENT'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_trips_status ON trips (status);

CREATE TABLE bike_incidents (
    id                   BIGINT      AUTO_INCREMENT PRIMARY KEY,
    bike_id              BIGINT      NOT NULL,
    reported_by_user_id  BIGINT      NOT NULL,
    trip_id              BIGINT      NULL,
    incident_type_id     BIGINT      NOT NULL,
    description          TEXT        NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    reported_at          DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at          DATETIME    NULL,
    resolved_by_user_id  BIGINT      NULL,
    CONSTRAINT fk_bi_bike FOREIGN KEY (bike_id) REFERENCES bikes (id) ON DELETE RESTRICT,
    CONSTRAINT fk_bi_reported_by FOREIGN KEY (reported_by_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT fk_bi_trip FOREIGN KEY (trip_id) REFERENCES trips (id) ON DELETE SET NULL,
    CONSTRAINT fk_bi_incident_type FOREIGN KEY (incident_type_id) REFERENCES incident_types (id) ON DELETE RESTRICT,
    CONSTRAINT fk_bi_resolved_by FOREIGN KEY (resolved_by_user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT chk_bi_status CHECK (status IN ('OPEN','UNDER_REVIEW','RESOLVED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_bike_incidents_status ON bike_incidents (status);

CREATE TABLE maintenance_records (
    id                  BIGINT      AUTO_INCREMENT PRIMARY KEY,
    bike_id             BIGINT      NOT NULL,
    incident_id         BIGINT      NULL,
    created_by_user_id  BIGINT      NOT NULL,
    description         TEXT        NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    started_at          DATETIME    NULL,
    completed_at        DATETIME    NULL,
    resolution          TEXT        NULL,
    CONSTRAINT fk_mr_bike FOREIGN KEY (bike_id) REFERENCES bikes (id) ON DELETE RESTRICT,
    CONSTRAINT fk_mr_incident FOREIGN KEY (incident_id) REFERENCES bike_incidents (id) ON DELETE SET NULL,
    CONSTRAINT fk_mr_created_by FOREIGN KEY (created_by_user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_mr_status CHECK (status IN ('PENDING','IN_PROGRESS','COMPLETED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_maintenance_records_status ON maintenance_records (status);

CREATE TABLE station_availability_history (
    id               BIGINT   AUTO_INCREMENT PRIMARY KEY,
    station_id       BIGINT   NOT NULL,
    available_bikes  INT      NOT NULL,
    available_slots  INT      NOT NULL,
    recorded_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sah_station FOREIGN KEY (station_id) REFERENCES stations (id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_sah_station_recorded ON station_availability_history (station_id, recorded_at);

CREATE TABLE event_outbox (
    id             CHAR(36)     PRIMARY KEY,
    aggregate_type VARCHAR(20)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL, -- referencia polimórfica (bikes/trips/stations/bike_incidents); sin FK formal, validado en la app
    event_type     VARCHAR(100) NOT NULL,
    payload        JSON         NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at   DATETIME     NULL,
    CONSTRAINT chk_event_outbox_aggregate_type CHECK (aggregate_type IN ('BIKE','TRIP','STATION','INCIDENT')),
    CONSTRAINT chk_event_outbox_status CHECK (status IN ('PENDING','PUBLISHED','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE INDEX idx_event_outbox_status ON event_outbox (status);
