-- V8__drop_operator_role.sql
-- Baja del rol OPERATOR. Era decorativo: ningún @PreAuthorize lo mencionaba, así que alguien del
-- grupo `operadores` terminaba con el mismo acceso efectivo que un USER. Un rol que no protege
-- nada es peor que no tenerlo, porque sugiere una separación de permisos que no existe.
--
-- Los grupos LDAP que apuntaban a OPERATOR (operadores, inspectores, soporte-n2) pasan a USER en
-- movilidad.auth.group-roles. No se remapean a ADMIN a propósito: son personal técnico, no
-- administradores del sistema.

-- La V2, que siembra el rol, NO se toca: ya está aplicada en los entornos y cambiarla alteraría su
-- checksum, con lo que Flyway abortaría el arranque por un error de validación.

-- El orden importa: users.role_id tiene fk_users_role ... ON DELETE RESTRICT, así que primero hay
-- que reasignar a cualquiera que tenga el rol. Al revés, el DELETE falla y Flyway aborta el
-- arranque de la aplicación.
UPDATE users
SET role_id = (SELECT id FROM roles WHERE name = 'USER')
WHERE role_id = (SELECT id FROM roles WHERE name = 'OPERATOR');

DELETE FROM roles WHERE name = 'OPERATOR';
