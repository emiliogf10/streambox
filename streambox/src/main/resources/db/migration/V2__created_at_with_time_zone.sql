-- ===================================================================
-- V2: las fechas de creación pasan a guardar un instante absoluto.
--
-- Las entidades usan ahora java.time.Instant (con @CreationTimestamp) en lugar
-- de LocalDateTime, que no llevaba zona horaria y dependía de la configuración
-- del servidor que escribía el dato.
--
-- NOTA para bases de datos con datos anteriores: PostgreSQL interpreta los
-- valores existentes en la zona horaria de la sesión al convertirlos. Si había
-- filas creadas con otra zona horaria pueden quedar desplazadas unas horas;
-- el único dato afectado es created_at, que la aplicación no usa para decidir
-- nada.
--
-- Sintaxis válida tanto en PostgreSQL como en H2.
-- ===================================================================

ALTER TABLE users  ALTER COLUMN created_at SET DATA TYPE TIMESTAMP WITH TIME ZONE;
ALTER TABLE movies ALTER COLUMN created_at SET DATA TYPE TIMESTAMP WITH TIME ZONE;
