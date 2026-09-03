-- V2__seed_reference_data.sql
-- Datos mínimos de referencia (catálogos cerrados, no datos de negocio de prueba)

INSERT INTO roles (name, description) VALUES
    ('ADMIN', 'Administrador del módulo de movilidad'),
    ('OPERATOR', 'Operador de mantenimiento y estaciones'),
    ('USER', 'Usuario final que alquila bicicletas');

INSERT INTO incident_types (code, name, description, active) VALUES
    ('FLAT_TIRE', 'Pinchazo', 'Neumático pinchado o desinflado', TRUE),
    ('BRAKE_FAILURE', 'Falla de frenos', 'Frenos no responden correctamente', TRUE),
    ('CHAIN_ISSUE', 'Problema de cadena', 'Cadena trabada, suelta o rota', TRUE),
    ('VANDALISM', 'Vandalismo', 'Daño intencional a la bicicleta', TRUE),
    ('OTHER', 'Otro', 'Incidente no clasificado en las categorías anteriores', TRUE);
