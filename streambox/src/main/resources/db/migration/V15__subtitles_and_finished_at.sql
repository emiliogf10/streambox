-- ===================================================================
-- V15: subtítulos WebVTT (subtitles_url) y «visto alguna vez» (finished_at).
--
-- POR QUÉ: DOS MEJORAS DEL REPRODUCTOR
--   A. Subtítulos. movies.subtitles_url y episodes.subtitles_url (NUEVAS,
--      opcionales): la URL de UN archivo WebVTT (.vtt) en español que el
--      reproductor carga como <track>. Las series no tienen vídeo propio, así
--      que tampoco subtítulos. Varios idiomas serían una tabla aparte
--      (subtitles(título, idioma, url)); hoy no hace falta.
--   B. Volver a ver algo terminado ya no lo «desmarca». El progreso (V14)
--      solo guarda el ÚLTIMO punto de cada título: si alguien reabría una
--      película vista y paraba en el minuto 2, dejaba de contar como vista en
--      el historial y en las cifras del perfil. user_movie_progress.finished_at
--      y user_episode_progress.finished_at (NUEVAS, opcionales) guardan el
--      instante en que el título se terminó POR PRIMERA VEZ, con el mismo
--      criterio de siempre (posición >= 95 % de la duración, en aritmética
--      entera: position_seconds * 100 >= duration_seconds * 95; ver
--      repository/WatchProgress.FINISHED_PERCENT). La aplicación la pone al
--      guardar una posición terminada si era nula y NUNCA la borra al guardar
--      una posición menor (repository/WatchProgressRepository). Solo
--      desaparece con la fila («Quitar de Seguir viendo» o las cascadas).
--      Dos conceptos distintos, los dos derivados de la base de datos:
--        * «terminado» (finished): la posición ACTUAL llega al 95 %. Decide
--          «Seguir viendo» y si el reproductor empieza de cero. No cambia.
--        * «visto» (watched): finished_at no es nulo. Lo usan el historial y
--          las cifras del perfil.
--
-- RESTRICCIONES (defensa en profundidad: la API valida antes y da 400)
--   * ck_movies_subtitles_url / ck_episodes_subtitles_url: si hay subtítulos,
--     la URL empieza por 'https://' (en minúsculas, como exige @HttpsUrl) y
--     tiene algo detrás. NO se comprueba en SQL que acabe en .vtt (con
--     parámetros de consulta o fragmentos, y sin expresiones regulares
--     portables entre PostgreSQL y H2, sería frágil): eso lo valida la API.
--     Sí se cierra 'http:' y cualquier otro esquema (javascript:, data:...),
--     que es lo que importa para la seguridad del cliente.
--   * ck_movies_subtitles_require_video / ck_episodes_subtitles_require_video:
--     no hay subtítulos sin vídeo (subtitles_url IS NULL OR video_url IS NOT
--     NULL). Quitar el vídeo de un título con subtítulos exige quitarlos
--     también; la API lo detecta antes con un error en el campo.
--   * ck_movie_progress_finished_at_not_after_updated_at (y la de episodios):
--     finished_at IS NULL OR finished_at <= updated_at. Terminar es un
--     guardado, así que no puede ser posterior al último guardado. Si el reloj
--     del servidor retrocediera entre dos guardados (ajuste de NTP), el
--     upsert recorta finished_at al nuevo updated_at en vez de fallar (ver
--     WatchProgressRepository).
--   * ck_movie_progress_finished_has_finished_at (y la de episodios):
--     finished_at IS NOT NULL OR position_seconds * 100 < duration_seconds * 95.
--     Una posición terminada siempre tiene finished_at: «terminado» implica
--     «visto», y ningún camino (un fallo futuro del upsert, un INSERT a mano)
--     puede romperlo. El caso contrario (visto pero ahora a medias) es
--     justo el que esta migración permite.
--
-- RELLENO DE DATOS
--   Las filas que HOY están terminadas por posición reciben
--   finished_at = updated_at: es el mejor dato disponible (el último guardado
--   ya estaba terminado; el instante real en que se terminó por primera vez
--   no se guardó nunca, así que puede ser anterior). Lo que ya se
--   «desmarcó» antes de esta migración (se terminó, se volvió a ver y se
--   paró antes del 95 %) NO SE PUEDE RECUPERAR: la V14 solo guardaba el
--   último punto y esa información no existe en ninguna parte. Esas filas
--   siguen como «a medias» y vuelven a contar como vistas en cuanto se
--   terminen otra vez.
--
-- ÍNDICES: NINGUNO NUEVO
--   * El historial y las cifras leen las filas de UN usuario por la PK, que
--     empieza por user_id (o por idx_*_progress_user_updated_at) y miran
--     finished_at en esas filas: un índice con finished_at no evitaría leerlas.
--   * Nadie busca por subtitles_url: se lee con el resto del título.
--   * Cada índice cuesta en cada guardado del reproductor (uno cada pocos
--     segundos por espectador) sin que ninguna consulta lo aproveche.
--
-- RIESGOS PARA DATOS EXISTENTES (incluida la base de desarrollo en Supabase)
--   - Añade cuatro columnas que admiten NULL, sin valor por defecto: no
--     cambia ninguna fila de movies ni de episodes (todas quedan sin
--     subtítulos, que cumple los dos CHECK de subtítulos).
--   - SÍ MODIFICA FILAS de progreso: rellena finished_at en las terminadas,
--     como se explica arriba. No borra ninguna fila ni columna y no cambia
--     posición, duración ni updated_at (el orden de «Seguir viendo» y del
--     historial es el mismo). Para deshacerlo bastaría poner finished_at a
--     NULL.
--   - Los CHECK se añaden DESPUÉS del relleno, que los deja cumplidos: el
--     relleno pone finished_at en exactamente las filas terminadas, y con el
--     valor de su propio updated_at.
--   - Bloqueos: ADD COLUMN sin valor por defecto solo cambia metadatos;
--     el UPDATE toca las filas terminadas y cada ADD CONSTRAINT CHECK recorre
--     su tabla una vez con un bloqueo exclusivo. Con miles de títulos y de
--     progresos son milisegundos.
--   - Todo va en una transacción (Flyway; en PostgreSQL el DDL también es
--     transaccional): si algo falla, no queda nada a medias.
--   - Vuelta atrás: una versión ANTERIOR de la aplicación arrancaría
--     (Hibernate no valida columnas que no conoce), pero NO podría guardar
--     una posición terminada: no rellena finished_at y lo rechazaría
--     ck_*_progress_finished_has_finished_at. No se debe volver atrás de
--     versión sin quitar antes esos dos CHECK. Los subtítulos simplemente no
--     se verían.
--   - Supabase: las tablas no cambian de nombre ni hay tablas nuevas; el
--     callback afterMigrate__close_public_api.sql sigue cerrándolas igual.
--
-- SQL estándar: funciona igual en PostgreSQL y en H2 (modo PostgreSQL).
-- ===================================================================

-- A. Subtítulos: columnas opcionales y sus restricciones
ALTER TABLE movies ADD COLUMN subtitles_url VARCHAR(500);
ALTER TABLE episodes ADD COLUMN subtitles_url VARCHAR(500);

ALTER TABLE movies ADD CONSTRAINT ck_movies_subtitles_url CHECK (
    subtitles_url IS NULL
    OR (CHAR_LENGTH(subtitles_url) > 8 AND SUBSTRING(subtitles_url FROM 1 FOR 8) = 'https://'));
ALTER TABLE episodes ADD CONSTRAINT ck_episodes_subtitles_url CHECK (
    subtitles_url IS NULL
    OR (CHAR_LENGTH(subtitles_url) > 8 AND SUBSTRING(subtitles_url FROM 1 FOR 8) = 'https://'));

ALTER TABLE movies ADD CONSTRAINT ck_movies_subtitles_require_video CHECK (
    subtitles_url IS NULL OR video_url IS NOT NULL);
ALTER TABLE episodes ADD CONSTRAINT ck_episodes_subtitles_require_video CHECK (
    subtitles_url IS NULL OR video_url IS NOT NULL);

-- B. «Visto alguna vez»: columna, relleno de lo que hoy está terminado y restricciones
ALTER TABLE user_movie_progress ADD COLUMN finished_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE user_episode_progress ADD COLUMN finished_at TIMESTAMP WITH TIME ZONE;

UPDATE user_movie_progress
SET finished_at = updated_at
WHERE finished_at IS NULL
  AND position_seconds * 100 >= duration_seconds * 95;

UPDATE user_episode_progress
SET finished_at = updated_at
WHERE finished_at IS NULL
  AND position_seconds * 100 >= duration_seconds * 95;

ALTER TABLE user_movie_progress ADD CONSTRAINT ck_movie_progress_finished_at_not_after_updated_at CHECK (
    finished_at IS NULL OR finished_at <= updated_at);
ALTER TABLE user_episode_progress ADD CONSTRAINT ck_episode_progress_finished_at_not_after_updated_at CHECK (
    finished_at IS NULL OR finished_at <= updated_at);

ALTER TABLE user_movie_progress ADD CONSTRAINT ck_movie_progress_finished_has_finished_at CHECK (
    finished_at IS NOT NULL OR position_seconds * 100 < duration_seconds * 95);
ALTER TABLE user_episode_progress ADD CONSTRAINT ck_episode_progress_finished_has_finished_at CHECK (
    finished_at IS NOT NULL OR position_seconds * 100 < duration_seconds * 95);
