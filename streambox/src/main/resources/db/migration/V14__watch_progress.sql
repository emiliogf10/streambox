-- ===================================================================
-- V14: progreso de visionado («Seguir viendo») de películas y episodios.
--
-- POR QUÉ
--   El reproductor integrado guarda por dónde va cada usuario en cada
--   película o episodio, para retomarlo («Reanudar») y para la fila «Seguir
--   viendo» del inicio. Una fila por (usuario, título) que se SOBRESCRIBE en
--   cada guardado: no es un historial, es «el último punto conocido».
--   Acceso: repository/WatchProgressRepository (SQL con JdbcClient, sin
--   entidades, como las reservas de nombre de la V13).
--
-- POR QUÉ DOS TABLAS Y NO UNA CON DOS CLAVES FORÁNEAS OPCIONALES
--   Mismo criterio que los «me gusta» (V8) y los favoritos (V1, V3):
--   * Cada fila tiene UNA clave foránea obligatoria (NOT NULL) hacia su
--     título, con su cascada. Con una sola tabla, movie_id y episode_id serían
--     opcionales y haría falta un CHECK de «exactamente una»; además, la
--     unicidad «una fila por usuario y título» no podría ser la clave primaria
--     (una PK no admite columnas nulas): necesitaría dos índices únicos
--     parciales (WHERE movie_id IS NOT NULL), que H2 no tiene.
--   * Las consultas de cada lado (el progreso de una película, los episodios
--     de una serie) no tienen que filtrar por un «tipo» ni saltarse filas del
--     otro. La única que mezcla los dos («Seguir viendo») lo hace con un
--     UNION ALL de dos lecturas por índice.
--
-- COLUMNAS
--   * position_seconds: segundo por el que va (entero; el cliente redondea
--     hacia abajo). 0 = acaba de empezar.
--   * duration_seconds: duración del VÍDEO REPRODUCIDO, que es la que conoce el
--     reproductor. No se deduce de movies.duration / episodes.duration: esas
--     van en MINUTOS, las escribe el administrador a mano y pueden no coincidir
--     con el archivo de vídeo (créditos, versión extendida...). Se guarda con
--     cada posición para poder calcular el porcentaje (la barra de la tarjeta)
--     y «terminado» sin depender de ellas.
--   * updated_at: instante del último guardado; ordena «Seguir viendo» (lo más
--     reciente primero). Lo pone la aplicación con su Clock (sin DEFAULT), como
--     el resto de fechas del esquema, para que los tests puedan fijarla.
--
-- RESTRICCIONES (en la base de datos, no solo en Java)
--   * ck_*_position: position_seconds >= 0.
--   * ck_*_duration: 1 <= duration_seconds <= 86400 (24 h). El tope solo evita
--     datos absurdos (un cliente que mande milisegundos en vez de segundos);
--     ningún título del catálogo dura un día.
--   * ck_*_position_within_duration: position_seconds <= duration_seconds.
--   «Terminado» (posición >= 95 % de la duración) NO es una restricción: un
--   título visto entero se sigue guardando (la aplicación decide si al
--   volver a abrirlo lo retoma o empieza de cero); solo deja de salir en
--   «Seguir viendo». El criterio vive en
--   repository/WatchProgress.FINISHED_PERCENT.
--
-- CLAVES Y BORRADO
--   * PK (user_id, movie_id) / (user_id, episode_id): una fila por usuario y
--     título. La aplicación hace un «upsert» (UPDATE y, si no había fila,
--     INSERT ... ON CONFLICT DO NOTHING): INSERT ... ON CONFLICT DO UPDATE no
--     existe en H2 (ni en modo PostgreSQL) y MERGE no es seguro ante dos
--     guardados simultáneos en PostgreSQL (los dos pueden intentar insertar y
--     uno falla con 23505). Ver WatchProgressRepository.
--   * ON DELETE CASCADE en todas las claves foráneas: borrar un usuario, una
--     película o un episodio borra su progreso, y borrar una serie también
--     (borra sus episodios en cascada, V3, y estos su progreso). Las tablas son
--     nuevas, así que la cascada existe también en las bases adoptadas.
--
-- ÍNDICES (cada uno, por la consulta que lo usa)
--   * La PK empieza por user_id: el progreso de un título concreto, el de los
--     episodios de una serie (junto con el índice único de episodes) y la
--     cascada al borrar un usuario.
--   * idx_movie_progress_movie_id / idx_episode_progress_episode_id: la PK
--     solo sirve para buscar por su PRIMERA columna; al borrar un título,
--     PostgreSQL busca su progreso por <título>_id para la cascada y sin este
--     índice leería la tabla entera (mismo motivo que en la V8). Borrar una
--     serie con muchos episodios hace una búsqueda por episodio.
--   * idx_movie_progress_user_updated_at / idx_episode_progress_user_updated_at
--     (user_id, updated_at DESC): «Seguir viendo» lee los de un usuario del más
--     reciente al más antiguo. Cuesta mantenerlo en cada guardado (que cambia
--     updated_at), pero un guardado cada pocos segundos por espectador es una
--     carga pequeña y la fila del inicio se pide en cada visita.
--   No hay índice parcial «solo los no terminados» porque H2 no los admite.
--
-- RIESGOS PARA DATOS EXISTENTES (incluida la base de desarrollo en Supabase)
--   - SOLO CREA dos tablas nuevas, vacías, con sus restricciones e índices. No
--     modifica ni borra ninguna fila ni columna existente; las claves foráneas
--     hacia users, movies y episodes no cambian esas tablas.
--   - Efecto nuevo para el futuro: borrar un usuario, una película, un episodio
--     o una serie borra también su progreso (intencionado).
--   - Bloqueos: crear una clave foránea toma un bloqueo breve sobre la tabla
--     referenciada; al estar vacía la tabla nueva, la validación es inmediata.
--   - Todo va en una transacción (Flyway; en PostgreSQL el DDL también es
--     transaccional): si algo falla, no queda ninguna de las dos tablas.
--   - Como en V3, V8, V11 y V13, sin IF NOT EXISTS: ninguna base anterior a
--     Flyway tenía estas tablas, y si alguien hubiera creado a mano una con
--     otra estructura es mejor que la migración falle de forma visible.
--   - Supabase: el callback afterMigrate__close_public_api.sql cierra las dos
--     tablas a la API pública en el mismo arranque (RLS sin políticas, sin
--     permisos para anon/authenticated). No hay que añadirlas a mano a
--     docs/supabase-seguridad.sql.
--   - Vuelta atrás: una versión ANTERIOR de la aplicación sigue arrancando
--     sobre este esquema (Hibernate no valida tablas que no conoce); estas
--     tablas se quedarían sin usar.
--
-- Las sentencias de este archivo son SQL estándar: funcionan igual en
-- PostgreSQL y en H2 (modo PostgreSQL). El INSERT ... ON CONFLICT DO NOTHING
-- con el que la aplicación escribe en estas tablas NO es estándar: es
-- sintaxis de PostgreSQL que H2 admite en modo PostgreSQL.
-- ===================================================================

CREATE TABLE user_movie_progress (
    user_id          BIGINT                   NOT NULL,
    movie_id         BIGINT                   NOT NULL,
    position_seconds INTEGER                  NOT NULL,
    duration_seconds INTEGER                  NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_user_movie_progress PRIMARY KEY (user_id, movie_id),
    CONSTRAINT fk_movie_progress_user  FOREIGN KEY (user_id)  REFERENCES users (id)  ON DELETE CASCADE,
    CONSTRAINT fk_movie_progress_movie FOREIGN KEY (movie_id) REFERENCES movies (id) ON DELETE CASCADE,
    CONSTRAINT ck_movie_progress_position CHECK (position_seconds >= 0),
    CONSTRAINT ck_movie_progress_duration CHECK (duration_seconds BETWEEN 1 AND 86400),
    CONSTRAINT ck_movie_progress_position_within_duration CHECK (position_seconds <= duration_seconds)
);

CREATE TABLE user_episode_progress (
    user_id          BIGINT                   NOT NULL,
    episode_id       BIGINT                   NOT NULL,
    position_seconds INTEGER                  NOT NULL,
    duration_seconds INTEGER                  NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_user_episode_progress PRIMARY KEY (user_id, episode_id),
    CONSTRAINT fk_episode_progress_user    FOREIGN KEY (user_id)    REFERENCES users (id)    ON DELETE CASCADE,
    CONSTRAINT fk_episode_progress_episode FOREIGN KEY (episode_id) REFERENCES episodes (id) ON DELETE CASCADE,
    CONSTRAINT ck_episode_progress_position CHECK (position_seconds >= 0),
    CONSTRAINT ck_episode_progress_duration CHECK (duration_seconds BETWEEN 1 AND 86400),
    CONSTRAINT ck_episode_progress_position_within_duration CHECK (position_seconds <= duration_seconds)
);

-- Cascada al borrar un título: localizar su progreso sin leer la tabla entera
CREATE INDEX idx_movie_progress_movie_id     ON user_movie_progress (movie_id);
CREATE INDEX idx_episode_progress_episode_id ON user_episode_progress (episode_id);

-- «Seguir viendo»: los de un usuario, del más reciente al más antiguo
CREATE INDEX idx_movie_progress_user_updated_at   ON user_movie_progress (user_id, updated_at DESC);
CREATE INDEX idx_episode_progress_user_updated_at ON user_episode_progress (user_id, updated_at DESC);
