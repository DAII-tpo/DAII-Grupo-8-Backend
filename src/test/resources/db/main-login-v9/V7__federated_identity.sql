-- V7__federated_identity.sql
-- Identidad federada (CityPass+ Login Federado, Grupo 2). El access token no trae email ni
-- nombre y apellido: la única clave estable es `sub` (employeeNumber), que se guarda en
-- external_user_id. Las columnas que el token no puede llenar pasan a NULL en lugar de
-- rellenarse con valores inventados.
ALTER TABLE users
    MODIFY COLUMN email      VARCHAR(255) NULL,
    MODIFY COLUMN first_name VARCHAR(100) NULL,
    MODIFY COLUMN last_name  VARCHAR(100) NULL;

-- Clave del aprovisionamiento just-in-time: si dos requests simultáneos del mismo `sub`
-- intentan crear la fila, este índice hace fallar al segundo con DataIntegrityViolation
-- y el servicio relee, en lugar de dejar dos identidades para la misma persona.
-- MySQL admite varios NULL en un índice único, así que las filas sin external_user_id
-- (el usuario demo de V3) conviven sin colisionar.
ALTER TABLE users
    ADD CONSTRAINT uk_users_external_user_id UNIQUE (external_user_id);
