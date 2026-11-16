-- ===================================================================
-- V13: reservas de nombre de usuario en la base de datos
--      (username_reservations + username_reservation_owners).
--
-- POR QUÉ
--   El registro responde el mismo 202 exista o no el email. Si el email ya
--   tenía cuenta, no se crea nada, pero el nombre pedido queda RESERVADO 24 h
--   (streambox.email.verification-ttl), igual que lo dejaría ocupado una cuenta
--   pendiente nueva: así un segundo registro con ese nombre da el mismo 409 en
--   los dos casos y no revela si el email tenía cuenta (ver el Javadoc de
--   UsernameReservations). En realidad TODO registro que responde 202 reserva
--   su nombre, también el que crea la cuenta pendiente (con el mismo instante
--   que su created_at), y la fila del nombre hace además de CERROJO: el
--   registro y «Editar perfil» la reclaman y la dejan bloqueada antes de
--   decidir nada, para que dos peticiones sobre el mismo nombre vayan una
--   detrás de otra (ver UsernameReservationRepository). Hasta ahora esas
--   reservas vivían EN MEMORIA: se
--   perdían al reiniciar y se podían expulsar rellenando el tope de claves de
--   los contadores. Al perderse, volvía el oráculo «¿tiene cuenta este email?»
--   (pista NV-2 de la auditoría de seguridad run-3). En la base de datos
--   sobreviven a los reinicios, no tienen tope y las comparten las réplicas.
--
-- POR QUÉ DOS TABLAS Y NO UNA CON CLAVE (username, email_hash)
--   La caducidad es de la RESERVA, no de cada email: todos sus dueños caducan
--   a la vez, a las 24 h del primer registro. Con una sola tabla, expires_at se
--   repetiría en cada fila y dos registros simultáneos con el mismo nombre (y
--   emails distintos) crearían dos filas con caducidades distintas (cada uno
--   con su «ahora»), porque ninguno ve la fila del otro hasta el commit. Con la
--   reserva en su propia tabla y el nombre como clave primaria, la base de
--   datos garantiza una sola caducidad por nombre: el segundo INSERT ... ON
--   CONFLICT DO NOTHING espera al primero y no inserta nada, y luego se añade
--   como dueño. La regla vive en el esquema, no en el código.
--
-- username_reservations (una fila por nombre reservado)
--   * username: el nombre NORMALIZADO, VARCHAR(50) como users.username. Clave
--     primaria: es la búsqueda de cada registro y de cada «Editar perfil».
--   * created_at / expires_at: los pone la aplicación con su Clock (los tests
--     lo controlan), nunca now() de la base de datos, como en account_tokens.
--     ck_username_reservations_expires_at los compara.
--   * Una reserva caducada no cuenta para nada (las consultas filtran por
--     expires_at > :now). Puede seguir en la tabla hasta que la borre la
--     limpieza programada o la siguiente petición que reclame ese nombre: un
--     registro (que la sustituye por una nueva) o un cambio de nombre en
--     «Editar perfil» (que solo la usa como cerrojo y no deja reserva).
--
-- username_reservation_owners (los emails que hicieron la reserva)
--   * email_hash: SHA-256 del email NORMALIZADO en hexadecimal en minúsculas
--     (64 caracteres), con el mismo CHECK de formato que account_tokens (V9) y
--     refresh_tokens (V4): guardar el email en claro por error falla en vez de
--     pasar desapercibido. Solo hace falta comparar por igualdad («¿este email
--     es dueño?»), así que no se guarda el email: menos datos personales en una
--     tabla más. OJO: un hash de un email NO es anónimo (quien tenga la tabla y
--     una lista de emails puede comprobarlos uno a uno). Aquí no revela nada
--     nuevo: estos emails ya tienen cuenta en users, donde están en claro.
--     VARCHAR(64) y no CHAR(64), como los otros hashes del esquema: CHAR rellena
--     con espacios y H2 y PostgreSQL lo tratan distinto al comparar.
--   * Clave primaria (username, email_hash): un email es dueño una sola vez.
--     Empieza por username, así que también sirve a la consulta «dueños de
--     este nombre» y a la cascada (PostgreSQL no indexa solas las claves
--     foráneas).
--   * fk_username_reservation_owners_reservation ON DELETE CASCADE: borrar
--     (o sustituir) una reserva caducada se lleva a sus dueños. Sin cascada,
--     un dueño de una reserva antigua pasaría a ser dueño de la nueva que se
--     creara con el mismo nombre.
--
-- SIN CLAVE FORÁNEA HACIA users (decisión deliberada)
--   La reserva tiene que sobrevivir aunque se borre la cuenta del email (por
--   ejemplo, una cuenta pendiente sustituida): si desapareciera con ella, el
--   nombre quedaría libre antes de tiempo y volvería el oráculo. Tampoco
--   serviría un users.id: muchas reservas no tienen cuenta detrás (las de un
--   registro con un email que ya tenía cuenta, que no crea ninguna), y las
--   que sí la tienen (la pendiente que crea el registro) deben seguir aunque
--   esa cuenta se borre.
--
-- ÍNDICES
--   * Las claves primarias: búsqueda por nombre (username_reservations) y
--     dueños de un nombre (username_reservation_owners).
--   * idx_username_reservations_expires_at: la limpieza programada
--     (DELETE ... WHERE expires_at <= :now). A diferencia de account_tokens
--     (V9), aquí la purga es la ÚNICA forma de vaciar la tabla de nombres que
--     nadie vuelve a pedir, y cualquiera puede llenarla registrándose: sin el
--     índice, cada limpieza recorrería la tabla entera. Encarece un poco cada
--     reserva, que ya es lenta de por sí (el registro calcula un BCrypt).
--
-- RIESGOS PARA DATOS EXISTENTES (incluida la base de desarrollo en Supabase)
--   - Solo CREA dos tablas vacías. No toca ni lee ninguna otra.
--   - Las reservas que hubiera en memoria en la aplicación en marcha se
--     pierden al desplegar (ya pasaba en cada reinicio); duran 24 h, así que
--     el efecto es transitorio.
--   - Todo va en una transacción (Flyway; en PostgreSQL el DDL también es
--     transaccional): si algo falla, no queda ninguna de las dos tablas.
--   - Base adoptada (anterior a Flyway): las dos tablas son nuevas en todas
--     las bases. Como en V3, V4, V8, V9 y V11, sin IF NOT EXISTS: si alguien
--     hubiera creado a mano una tabla con este nombre, mejor que la migración
--     falle de forma visible.
--   - Supabase: el callback afterMigrate__close_public_api.sql cierra las dos
--     tablas a la API pública en el mismo arranque (RLS sin políticas, sin
--     permisos para anon/authenticated). No hay que añadirlas a mano a
--     docs/supabase-seguridad.sql.
--   - Vuelta atrás: una versión ANTERIOR de la aplicación sigue arrancando
--     sobre este esquema (Hibernate no valida tablas que no conoce) y vuelve a
--     las reservas en memoria; estas tablas se quedarían sin usar.
--
-- Las sentencias de este archivo son SQL estándar: funcionan igual en
-- PostgreSQL y en H2 (modo PostgreSQL). El INSERT ... ON CONFLICT DO NOTHING
-- con el que la aplicación escribe en estas tablas NO es estándar: es
-- sintaxis de PostgreSQL que H2 admite en modo PostgreSQL.
-- ===================================================================

CREATE TABLE username_reservations (
    username   VARCHAR(50)              NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_username_reservations            PRIMARY KEY (username),
    CONSTRAINT ck_username_reservations_expires_at CHECK (expires_at > created_at)
);

-- Limpieza programada de las reservas caducadas
CREATE INDEX idx_username_reservations_expires_at ON username_reservations (expires_at);

CREATE TABLE username_reservation_owners (
    username   VARCHAR(50) NOT NULL,
    email_hash VARCHAR(64) NOT NULL,
    CONSTRAINT pk_username_reservation_owners PRIMARY KEY (username, email_hash),
    CONSTRAINT fk_username_reservation_owners_reservation
        FOREIGN KEY (username) REFERENCES username_reservations (username) ON DELETE CASCADE,
    -- Mismo truco que V4 y V9: cada carácter hexadecimal pasa a '0' y solo un
    -- hash válido queda en 64 ceros (las expresiones regulares no son
    -- portables entre PostgreSQL y H2)
    CONSTRAINT ck_username_reservation_owners_email_hash_format CHECK (
        CHAR_LENGTH(email_hash) = 64
        AND TRANSLATE(email_hash, '0123456789abcdef', '0000000000000000') = REPEAT('0', 64))
);
