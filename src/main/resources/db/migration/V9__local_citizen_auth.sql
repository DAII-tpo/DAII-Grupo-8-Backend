-- V9__local_citizen_auth.sql
-- Registro y login local de ciudadanos. El IdP del Grupo 2 es solo para personal municipal, así
-- que los usuarios finales los resuelve este módulo por separado.
--
-- La identidad no agrega columnas: password_hash existe desde la V1 (nunca se uso) y la V7 dejó
-- email nullable. Un ciudadano es una fila con email y password_hash, y external_user_id en NULL;
-- un empleado es lo inverso. El índice único de external_user_id admite varios NULL, así que
-- conviven sin colisionar.

-- Control de intentos fallidos. last_failed_login_at es lo que hace que "5 intentos en 15 minutos"
-- signifique algo: sin esa columna el contador sería acumulativo de por vida, y quien erró cuatro
-- veces en marzo quedaría bloqueado con un solo error en octubre.
ALTER TABLE users
    ADD COLUMN failed_login_attempts INT         NOT NULL DEFAULT 0,
    ADD COLUMN last_failed_login_at  DATETIME(3) NULL,
    ADD COLUMN locked_until          DATETIME(3) NULL;

-- Sesiones de refresh. El token NUNCA se guarda en claro: va su SHA-256 en hexadecimal, que
-- alcanza porque el token es aleatorio de 256 bits y no hay diccionario que atacar. Un dump de la
-- base no entrega sesiones.
--
-- family_id agrupa la cadena de rotaciones de una misma sesión: si llega un token ya revocado, se
-- revoca la familia entera, porque o es un replay o el cliente se desincronizó, y en los dos casos
-- lo correcto es forzar un login.
CREATE TABLE refresh_tokens (
    id         BIGINT      AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    -- VARCHAR y no CHAR aunque el largo sea fijo: Hibernate mapea String a VARCHAR y con
    -- ddl-auto=validate un CHAR hace fallar el arranque.
    token_hash VARCHAR(64) NOT NULL,
    family_id  VARCHAR(36) NOT NULL,
    expires_at DATETIME(3) NOT NULL,
    revoked_at DATETIME(3) NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Revocar una familia ante un reuso, y borrar los vencidos, son las dos únicas consultas que no
-- entran por el único de token_hash.
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id, revoked_at);
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at);
