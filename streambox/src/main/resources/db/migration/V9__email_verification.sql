-- ===================================================================
-- V9: verificación del correo al registrarse.
--
-- POR QUÉ
--   Una auditoría de seguridad detectó dos problemas del registro: cualquiera
--   puede crear una cuenta con el email de otra persona (nadie comprueba que
--   el buzón es suyo) y el registro revela qué emails ya tienen cuenta. La
--   solución es que una cuenta nueva quede PENDIENTE hasta que su dueño pulse
--   un enlace de un solo uso que se le envía por correo. Esta migración crea
--   lo que necesita la base de datos para eso:
--
--   1. users.email_verified_at: instante desde el que el email de la cuenta
--      cuenta como verificado. NULL = cuenta pendiente (no puede iniciar
--      sesión). No hay columna booleana aparte: la fecha ya dice si está
--      verificada y además cuándo, y dos columnas podrían contradecirse.
--
--   2. account_tokens: tokens de un solo uso ligados a una cuenta. Es una
--      tabla GENÉRICA con una columna purpose (hoy solo 'EMAIL_VERIFICATION')
--      para que «He olvidado mi contraseña» reutilice la misma tabla, el mismo
--      hash y el mismo consumo atómico: bastará otra migración que amplíe el
--      CHECK de purpose (DROP CONSTRAINT + ADD CONSTRAINT, SQL estándar).
--
-- AVISO: LAS CUENTAS QUE YA EXISTEN QUEDAN SIN VERIFICAR, SALVO LAS ADMIN
--   Decisión expresa del autor: quiere probar el flujo de verificación con
--   las cuentas reales de su base de desarrollo, así que el UPDATE de abajo
--   SOLO marca como verificadas las filas con role = 'ADMIN' (los valores del
--   rol son 'USER' y 'ADMIN', ck_users_role de V1). Todas las demás quedan
--   con email_verified_at = NULL, es decir, PENDIENTES.
--
--   Una cuenta pendiente no queda bloqueada para siempre: el login le responde
--   el mismo 401 que a una contraseña incorrecta (para no revelar qué emails
--   tienen cuenta), la pantalla de login le sugiere confirmar el correo y su
--   dueño puede pedir un enlace nuevo (POST
--   /api/auth/email-verification/resend). En el perfil dev, sin servidor de
--   correo, el enlace se escribe en el log de la aplicación.
--
--   Por qué el ADMIN sí queda verificado: si no, el administrador no podría
--   entrar a gestionar la aplicación hasta verificar su correo, y en una
--   instalación sin correo configurado eso sería un bloqueo real.
--
--   RIESGO EN UN DESPLIEGUE REAL: aplicada sobre una base con usuarios de
--   verdad, esta migración obligaría a TODOS ellos a verificar su correo la
--   próxima vez que entraran (y quien tuviera un email mal escrito o un buzón
--   que ya no existe se quedaría fuera). Es aceptable aquí porque StreamBox es
--   un proyecto de portfolio cuya única base con datos es la de desarrollo del
--   autor. En producción lo normal sería verificar a todas las cuentas
--   existentes (quitar el WHERE role = 'ADMIN') o hacerlo en una migración
--   posterior; nunca editando esta una vez aplicada.
--
--   Se usa CURRENT_TIMESTAMP (el instante de la migración) y no created_at
--   para los ADMIN:
--     - created_at afirmaría que el email se verificó el día del registro,
--       cosa que nunca ocurrió. CURRENT_TIMESTAMP dice la verdad: la cuenta
--       pasó a contar como verificada cuando se aplicó esta migración, por
--       decisión del proyecto y no por un clic del usuario.
--     - Dentro de una misma sentencia CURRENT_TIMESTAMP es un único valor
--       (en PostgreSQL, el inicio de la transacción), así que todos los ADMIN
--       verificados por esta migración comparten exactamente el mismo instante
--       y quedan identificados con un solo WHERE.
--   Las cuentas creadas a partir de ahora nacen con NULL (no hay DEFAULT): un
--   DEFAULT verificaría por error cualquier cuenta que alguien insertara sin
--   pasar por el registro.
--
--   No hay CHECK email_verified_at >= created_at: created_at lo pone Hibernate
--   (@CreationTimestamp, reloj del sistema) y email_verified_at lo pondrá el
--   servicio con su Clock (fijo en los tests). Mezclar relojes haría fallar
--   ese CHECK por microsegundos al crear el ADMIN ya verificado.
--
-- DECISIONES DE account_tokens
--   * token_hash: SHA-256 del token en hexadecimal en minúsculas (64
--     caracteres), igual que refresh_tokens (V4). NUNCA el token en claro: quien
--     lea la tabla o una copia de seguridad no puede verificar cuentas ajenas.
--     El mismo CHECK de formato que V4 (TRANSLATE, porque las expresiones
--     regulares no son portables entre PostgreSQL y H2) hace que guardar el
--     token en claro por error falle en vez de pasar desapercibido. UNIQUE:
--     es la búsqueda de cada clic en el enlace (la cubre el índice de la
--     restricción).
--   * created_at y expires_at los asigna la aplicación con el mismo Clock
--     (como en V4): ck_account_tokens_expires_at los compara.
--   * used_at: NULL mientras el token se puede usar. Se rellena al consumirlo
--     (verificar el email) y también al invalidarlo (reenviar el correo deja
--     inservibles los enlaces anteriores). El consumo es un único UPDATE ...
--     WHERE used_at IS NULL AND expires_at > :now: la base de datos garantiza
--     que dos clics simultáneos no lo consumen dos veces (solo uno ve 1 fila
--     afectada). ck_account_tokens_used_before_expiry (used_at < expires_at)
--     documenta en la base de datos esa regla: un token caducado no se usa.
--   * Sin UNIQUE de «un único token vigente por usuario y propósito»: «vigente»
--     depende de la hora y un índice parcial (WHERE used_at IS NULL) no existe
--     en H2. Lo garantiza el servicio invalidando los anteriores al emitir uno.
--   * ON DELETE CASCADE hacia users: los tokens no tienen sentido sin su
--     cuenta, y así sustituir una cuenta pendiente caducada (DELETE del
--     usuario) se lleva sus tokens sin otra sentencia.
--
-- ÍNDICES
--   * uk_account_tokens_token_hash (de la restricción UNIQUE): buscar y
--     consumir un token por su hash.
--   * idx_account_tokens_user_purpose (user_id, purpose): invalidar los
--     tokens vigentes de una cuenta al reenviar, saber si tiene alguno vigente
--     y contar los emitidos recientemente. Como empieza por user_id, sirve
--     también a la cascada al borrar un usuario (PostgreSQL no indexa solas
--     las claves foráneas).
--   * Sin índice para la limpieza de caducados (expires_at), por lo mismo que
--     en V4: tarea esporádica sobre una tabla pequeña; un índice más encarecería
--     cada registro y cada reenvío. Si creciera, se añade en otra migración.
--
-- RIESGOS PARA DATOS EXISTENTES (incluida la base de desarrollo en Supabase)
--   - Las cuentas USER existentes pasan a estar pendientes (ver el AVISO de
--     arriba): no pierden ningún dato, pero no podrán iniciar sesión hasta
--     verificar su correo.
--   - ADD COLUMN sin DEFAULT solo cambia metadatos. El UPDATE reescribe una vez
--     las filas ADMIN de users (bloqueo de fila mientras dura la transacción):
--     son una o dos, milisegundos. No borra ni modifica ningún otro dato.
--   - Todo va en una transacción (Flyway; en PostgreSQL el DDL también es
--     transaccional): si algo falla, no queda ni la columna ni cuentas a medio
--     marcar, y el siguiente arranque la vuelve a intentar entera.
--   - Base adoptada (anterior a Flyway): users existe desde siempre y no tenía
--     esta columna; sus cuentas siguen la misma regla (solo las ADMIN quedan
--     verificadas). account_tokens es
--     nueva en todas las bases, así que la cascada existe también en ellas.
--     Como en V3, V4 y V8, sin IF NOT EXISTS: si alguien hubiera creado a mano
--     una tabla con este nombre, mejor que la migración falle de forma visible.
--   - Supabase: el callback afterMigrate__close_public_api.sql cierra
--     account_tokens a la API pública en el mismo arranque (RLS sin políticas).
--   - Vuelta atrás: una versión ANTERIOR de la aplicación sigue arrancando
--     sobre este esquema (Hibernate no valida columnas ni tablas que no
--     conoce), pero crearía cuentas con email_verified_at = NULL, que la
--     versión nueva trataría como pendientes. Si se vuelve atrás y luego
--     adelante, hay que decidir qué hacer con esas cuentas.
--
-- SQL estándar: funciona igual en PostgreSQL y en H2 (modo PostgreSQL).
-- ===================================================================

ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMP WITH TIME ZONE;

-- Solo los ADMIN existentes quedan verificados; el resto, pendientes (ver el
-- AVISO de la cabecera: decisión del autor para probar el flujo)
UPDATE users SET email_verified_at = CURRENT_TIMESTAMP WHERE role = 'ADMIN' AND email_verified_at IS NULL;

CREATE TABLE account_tokens (
    id         BIGINT                   GENERATED BY DEFAULT AS IDENTITY,
    user_id    BIGINT                   NOT NULL,
    purpose    VARCHAR(32)              NOT NULL,
    token_hash VARCHAR(64)              NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at    TIMESTAMP WITH TIME ZONE,
    CONSTRAINT pk_account_tokens            PRIMARY KEY (id),
    CONSTRAINT uk_account_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_account_tokens_user       FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    -- Al añadir un propósito (p. ej. 'PASSWORD_RESET'), una migración nueva
    -- sustituye esta restricción; nunca se edita esta.
    CONSTRAINT ck_account_tokens_purpose    CHECK (purpose IN ('EMAIL_VERIFICATION')),
    -- Mismo truco que V4: cada carácter hexadecimal pasa a '0' y solo un hash
    -- válido queda en 64 ceros
    CONSTRAINT ck_account_tokens_token_hash_format CHECK (
        CHAR_LENGTH(token_hash) = 64
        AND TRANSLATE(token_hash, '0123456789abcdef', '0000000000000000') = REPEAT('0', 64)),
    CONSTRAINT ck_account_tokens_expires_at        CHECK (expires_at > created_at),
    -- Un token solo se consume o se invalida mientras está vigente
    CONSTRAINT ck_account_tokens_used_before_expiry CHECK (used_at IS NULL OR used_at < expires_at)
);

-- Tokens de una cuenta para un propósito (reenviar, comprobar si hay uno
-- vigente) y cascada al borrar el usuario
CREATE INDEX idx_account_tokens_user_purpose ON account_tokens (user_id, purpose);
