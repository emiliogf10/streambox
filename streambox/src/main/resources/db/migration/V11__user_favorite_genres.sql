-- ===================================================================
-- V11: géneros favoritos del usuario («Tus gustos») y pregunta de bienvenida.
--
-- POR QUÉ
--   Las recomendaciones por géneros (MovieRepository.GENRE_WEIGHTS) solo
--   partían de los «me gusta»: una cuenta nueva no veía ninguna recomendación
--   hasta marcar alguno (el «arranque en frío»). Si al empezar elige sus
--   géneros favoritos, tiene recomendaciones desde el primer minuto. En la
--   fórmula, cada género favorito pesa como 2 «me gusta» (decisión del autor).
--
--   Los favoritos van en una tabla de unión propia (user_favorite_genres) y no
--   en una columna de users (p. ej. una lista de ids en texto) para que cada
--   fila tenga una clave foránea de verdad hacia genres: la base de datos
--   garantiza que no hay ids inventados y borra el gusto si se borra el género.
--
--   created_at guarda cuándo se eligió cada género. Hoy no ordena nada (los
--   favoritos se listan por nombre), pero es el mismo dato que guardan los
--   «me gusta» (V8) y el que necesitaría un recomendador futuro para pesar
--   más lo reciente. Lo escribe la aplicación (sin DEFAULT, como el resto de
--   created_at del esquema), para que use su Clock y los tests fijen la hora.
--
-- LÍMITE DE 3 A 5 GÉNEROS: NO ESTÁ EN LA BASE DE DATOS
--   Es una regla de producto (la valida el servicio y puede cambiar), no de
--   integridad. Exigirla en la base de datos obligaría a un trigger (un CHECK
--   no puede contar filas de otra tabla), que además no sería portable a H2.
--   Lo que sí evita que dos peticiones simultáneas mezclen sus listas (y se
--   supere el máximo) es que el servicio bloquee antes la fila del usuario
--   (UserRepository.markGenresOnboarded, que siempre toca esa fila).
--
-- users.genres_onboarded_at
--   Instante en que se cerró la pregunta de bienvenida («¿qué géneros te
--   gustan?»), tanto si eligió géneros como si pulsó «Ahora no». NULL = aún no
--   se le ha hecho: es lo que la API usa (junto con role = 'USER') para mandar
--   a la página de bienvenida tras el login, una sola vez. Es una fecha y no
--   un booleano por lo mismo que email_verified_at (V9): dice cuándo, y una
--   sola columna no puede contradecirse con otra.
--
-- AVISO: TODAS LAS CUENTAS QUE YA EXISTEN QUEDAN CON LA PREGUNTA CERRADA
--   Decisión expresa del autor: los usuarios que ya usaban la aplicación no
--   deben encontrarse una pantalla de bienvenida al volver; solo verán una
--   tarjeta en el inicio que los invita a elegir sus gustos desde el perfil.
--   Por eso el UPDATE de abajo rellena genres_onboarded_at con
--   CURRENT_TIMESTAMP en TODAS las filas de users, sean USER o ADMIN y estén
--   verificadas o pendientes. Consecuencia que hay que conocer: una cuenta
--   registrada justo antes de esta migración y aún sin verificar tampoco verá
--   la pregunta al confirmar su correo (verá la tarjeta). Las cuentas creadas
--   después nacen con NULL (sin DEFAULT) y sí la verán.
--
--   Se usa CURRENT_TIMESTAMP (el instante de la migración) y no created_at:
--   created_at afirmaría que contestaron la pregunta el día del alta, cuando
--   la pregunta ni existía. En una misma sentencia es un único valor, así que
--   todas las cuentas cerradas por esta migración comparten el mismo instante
--   y se pueden identificar con un solo WHERE si algún día hiciera falta.
--
-- CLAVES Y BORRADO
--   - PK (user_id, genre_id): un usuario no puede tener dos veces el mismo
--     género. Al empezar por user_id también sirve para «los favoritos de un
--     usuario», para sustituir su lista (DELETE ... WHERE user_id) y para la
--     cascada al borrar el usuario.
--   - ON DELETE CASCADE en las dos claves foráneas: borrar un usuario borra
--     sus gustos, y borrar un género lo quita de los gustos de todos SIN
--     bloquear su borrado (GenreService solo impide borrar un género que usan
--     películas o series, no uno que alguien tiene entre sus gustos). La tabla
--     es nueva, así que la cascada existe también en las bases adoptadas.
--
-- ÍNDICE
--   - idx_favorite_genres_genre_id: la PK solo sirve para buscar por su
--     PRIMERA columna. Al borrar un género, la cascada busca sus filas por
--     genre_id; sin este índice leería la tabla entera en cada borrado (mismo
--     motivo que idx_liked_movies_movie_id de la V8).
--
-- RIESGOS PARA DATOS EXISTENTES (incluida la base de desarrollo en Supabase)
--   - CREA una tabla nueva, vacía, con sus restricciones e índice, y AÑADE una
--     columna a users. No borra ninguna fila ni columna.
--   - MODIFICA todas las filas de users, pero solo para rellenar la columna
--     nueva (ver el AVISO). Ningún otro dato de las cuentas cambia. En
--     Supabase, todos los usuarios existentes (incluido el ADMIN) quedan con
--     la pregunta cerrada y con el instante en que arranque la aplicación con
--     esta versión.
--   - Bloqueos: ADD COLUMN sin DEFAULT solo cambia metadatos; el UPDATE
--     reescribe una vez cada fila de users (bloqueo de fila mientras dura la
--     transacción de Flyway): con las pocas cuentas de un proyecto así, son
--     milisegundos. Crear las claves foráneas toma un bloqueo breve sobre
--     users y genres; al estar vacía la tabla nueva, la validación es
--     instantánea.
--   - Todo va en una transacción (Flyway; en PostgreSQL el DDL también es
--     transaccional): si algo falla, no queda ni la columna ni la tabla.
--   - Como en V3 y V8, no se usa IF NOT EXISTS: ninguna base anterior a Flyway
--     tenía esta tabla, y si alguien hubiera creado a mano una con otra
--     estructura es mejor que la migración falle de forma visible.
--   - Supabase: el callback afterMigrate__close_public_api.sql cierra la tabla
--     nueva a la API pública en el mismo arranque (RLS sin políticas).
--   - Una versión ANTERIOR de la aplicación sigue arrancando sobre el esquema
--     nuevo (Hibernate no valida columnas ni tablas que no conoce).
--
-- SQL estándar: funciona igual en PostgreSQL y en H2 (modo PostgreSQL).
-- ===================================================================

ALTER TABLE users ADD COLUMN genres_onboarded_at TIMESTAMP WITH TIME ZONE;

-- Las cuentas que ya existen nunca ven la pregunta (ver el AVISO de la cabecera)
UPDATE users SET genres_onboarded_at = CURRENT_TIMESTAMP WHERE genres_onboarded_at IS NULL;

CREATE TABLE user_favorite_genres (
    user_id    BIGINT                   NOT NULL,
    genre_id   BIGINT                   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_user_favorite_genres  PRIMARY KEY (user_id, genre_id),
    CONSTRAINT fk_favorite_genres_user  FOREIGN KEY (user_id)  REFERENCES users (id)  ON DELETE CASCADE,
    CONSTRAINT fk_favorite_genres_genre FOREIGN KEY (genre_id) REFERENCES genres (id) ON DELETE CASCADE
);

-- Cascada al borrar un género: localizar sus filas sin leer la tabla entera
CREATE INDEX idx_favorite_genres_genre_id ON user_favorite_genres (genre_id);
