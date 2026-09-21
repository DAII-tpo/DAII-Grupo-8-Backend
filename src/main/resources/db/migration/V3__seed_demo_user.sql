-- V3__seed_demo_user.sql
-- Usuario de prueba con id = 1, para que el frontend pueda ejercitar los endpoints que
-- identifican al usuario por header (viajes, incidentes) mientras no exista el authorization
-- server del squad de Login Federado.
--
-- Datos ficticios y sin credenciales: password_hash queda en NULL a propósito.
--
-- El rol se resuelve por nombre en lugar de hardcodear el id, porque los ids de roles los
-- asigna el AUTO_INCREMENT de V2 y no son parte del contrato.
--
-- El NOT EXISTS hace la migración segura sobre una base que ya tenga ese id o ese email:
-- si la migración fallara, Flyway aborta el arranque de la aplicación.

INSERT INTO users (id, external_user_id, first_name, last_name, email, password_hash, role_id, status)
SELECT 1, NULL, 'Usuario', 'Demo', 'demo@example.com', NULL, r.id, 'ACTIVE'
FROM roles r
WHERE r.name = 'USER'
  AND NOT EXISTS (
      SELECT 1 FROM users existing WHERE existing.id = 1 OR existing.email = 'demo@example.com'
  );
