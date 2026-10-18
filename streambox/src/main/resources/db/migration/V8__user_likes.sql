-- ===================================================================
-- V8: «Me gusta» de películas y de series.
--
-- POR QUÉ
--   «Mi lista» (user_favorite_movies / user_favorite_series) significa «quiero
--   verla»; «Me gusta» significa «la vi y me gustó». Son señales distintas y
--   por eso van en tablas propias: mezclarlas en una columna de los favoritos
--   obligaría a tener un título en «Mi lista» para poder darle «me gusta».
--   Con estas tablas se muestra la sección «Te gusta» del perfil y se calculan
--   las recomendaciones por géneros (MovieRepository/SeriesRepository,
--   findRecommended*Ids).
--
--   created_at guarda CUÁNDO le gustó: ordena la sección del perfil (lo más
--   reciente primero) y es el dato que necesitará un recomendador futuro (una
--   IA puede pesar más lo reciente). Lo escribe la aplicación (no hay DEFAULT,
--   como en el resto de created_at del esquema), así puede usar su Clock y los
--   tests pueden fijar la hora.
--
--   Películas y series van en dos tablas, como sus favoritos (V1 y V3), y no
--   en una sola con un "tipo": así cada fila tiene una clave foránea de verdad
--   hacia su título y la base de datos garantiza la integridad y la cascada.
--
-- CLAVES Y BORRADO
--   - PK (user_id, <título>_id): un usuario no puede dar dos veces «me gusta»
--     al mismo título. La aplicación inserta con ON CONFLICT DO NOTHING (dar
--     «me gusta» es idempotente y conserva la fecha original).
--   - ON DELETE CASCADE en las dos claves foráneas (decisión del autor):
--     borrar un usuario borra sus «me gusta», y borrar una película o una serie
--     borra los «me gusta» que tenía. No hay esquema heredado que conservar:
--     las tablas son nuevas, así que la cascada existe también en las bases
--     adoptadas (a diferencia de user_favorite_movies, ver V1).
--
-- ÍNDICES (cada uno, por la consulta que lo usa)
--   - La PK empieza por user_id: sirve para «los me gusta de un usuario», para
--     comprobar uno concreto (NOT EXISTS de las recomendaciones) y para la
--     cascada al borrar un usuario.
--   - idx_liked_movies_movie_id / idx_liked_series_series_id: la PK solo sirve
--     para buscar por su PRIMERA columna. Al borrar un título, PostgreSQL busca
--     sus «me gusta» por <título>_id para la cascada; sin este índice leería
--     la tabla entera en cada borrado (mismo motivo que idx_favorites_movie_id).
--   - idx_liked_movies_user_created_at / idx_liked_series_user_created_at
--     (user_id, created_at DESC): la sección «Te gusta» lista los de un usuario
--     del más reciente al más antiguo. La PK ordena por <título>_id, no por
--     fecha; con este índice las filas salen ya ordenadas y «los N más
--     recientes» (lo que pediría un recomendador futuro) se lee sin ordenar
--     nada. Cuesta una escritura más por «me gusta», que es una acción manual
--     e infrecuente.
--
-- RIESGOS PARA DATOS EXISTENTES (incluida la base de desarrollo en Supabase)
--   - SOLO CREA dos tablas nuevas, vacías, con sus restricciones e índices. No
--     modifica ni borra ninguna fila ni columna existente. Las claves foráneas
--     hacia users, movies y series no cambian esas tablas.
--   - Efecto nuevo para el futuro: a partir de ahora, borrar un usuario, una
--     película o una serie borra también sus «me gusta» (intencionado).
--   - Bloqueos: crear una clave foránea toma un bloqueo breve sobre la tabla
--     referenciada (users, movies, series); al estar vacía la tabla nueva, la
--     validación es instantánea.
--   - Como en V3, no se usa IF NOT EXISTS: ninguna base anterior a Flyway tenía
--     estas tablas, y si alguien hubiera creado a mano una con otra estructura
--     es mejor que la migración falle de forma visible.
--   - Supabase: el callback afterMigrate__close_public_api.sql cierra estas
--     tablas a la API pública en el mismo arranque (RLS sin políticas).
--   - Una versión ANTERIOR de la aplicación sigue arrancando sobre el esquema
--     nuevo (Hibernate no valida tablas que no conoce).
--
-- SQL estándar: funciona igual en PostgreSQL y en H2 (modo PostgreSQL).
-- ===================================================================

CREATE TABLE user_liked_movies (
    user_id    BIGINT                   NOT NULL,
    movie_id   BIGINT                   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_user_liked_movies  PRIMARY KEY (user_id, movie_id),
    CONSTRAINT fk_liked_movies_user  FOREIGN KEY (user_id)  REFERENCES users (id)  ON DELETE CASCADE,
    CONSTRAINT fk_liked_movies_movie FOREIGN KEY (movie_id) REFERENCES movies (id) ON DELETE CASCADE
);

CREATE TABLE user_liked_series (
    user_id    BIGINT                   NOT NULL,
    series_id  BIGINT                   NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_user_liked_series   PRIMARY KEY (user_id, series_id),
    CONSTRAINT fk_liked_series_user   FOREIGN KEY (user_id)   REFERENCES users (id)  ON DELETE CASCADE,
    CONSTRAINT fk_liked_series_series FOREIGN KEY (series_id) REFERENCES series (id) ON DELETE CASCADE
);

-- Cascada al borrar un título: localizar sus «me gusta» sin leer la tabla entera
CREATE INDEX idx_liked_movies_movie_id   ON user_liked_movies (movie_id);
CREATE INDEX idx_liked_series_series_id  ON user_liked_series (series_id);

-- Sección «Te gusta»: los de un usuario, del más reciente al más antiguo
CREATE INDEX idx_liked_movies_user_created_at  ON user_liked_movies (user_id, created_at DESC);
CREATE INDEX idx_liked_series_user_created_at  ON user_liked_series (user_id, created_at DESC);
