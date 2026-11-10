-- Datos de demostración: ejecutar explícitamente solo en la base de pruebas elegida.
-- Este script está fuera de las migraciones y no se ejecuta durante el arranque.
-- Carga bicicletas disponibles (status = 'AVAILABLE') en las estaciones de la zona céntrica,
-- Monserrat (cerca de UADE) y San Nicolás (Obelisco, Tribunales, Congreso, etc.),
-- para que las consultas de retiro (/stations/recommendation?purpose=PICKUP) y /stations/nearby
-- devuelvan stock de demostración y permitan probar la ruta de retiro en el CU-INT-01.

INSERT INTO bikes (code, station_id, status, model, purchase_date, created_at, updated_at)
SELECT 
    CONCAT('ECO-', CAST(s.id AS CHAR), '-', LPAD(n.n, 2, '0')) AS code,
    s.id AS station_id,
    'AVAILABLE' AS status,
    'Ecobici Classic' AS model,
    '2024-01-15' AS purchase_date,
    NOW() AS created_at,
    NOW() AS updated_at
FROM stations s
CROSS JOIN (
    SELECT 1 AS n UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
    UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10
) n
WHERE s.status = 'ACTIVE' 
  AND s.deleted_at IS NULL
  AND s.capacity >= 10
  AND (
      s.name LIKE '%MONSERRAT%' 
      OR s.name LIKE '%OBELISCO%' 
      OR s.name LIKE '%CONGRESO%' 
      OR s.name LIKE '%TRIBUNALES%' 
      OR s.name LIKE '%ADUANA%' 
      OR s.name LIKE '%PLAZA DE MAYO%' 
      OR s.name LIKE '%RETIRO%' 
      OR s.name LIKE '%INDEPENDENCIA%'
      OR s.name LIKE '%SAN TELMO%'
      OR s.name LIKE '%BALVANERA%'
      OR s.name LIKE '%ONCE%'
      OR s.name LIKE '%FACULTAD DE DERECHO%'
  )
  AND NOT EXISTS (
      SELECT 1 FROM bikes b WHERE b.station_id = s.id
  );
