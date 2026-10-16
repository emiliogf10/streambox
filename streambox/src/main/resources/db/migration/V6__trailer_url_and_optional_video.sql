-- ===================================================================
-- V6: tráiler propio (trailer_url) y vídeo del contenido opcional.
--
-- POR QUÉ
--   StreamBox no aloja vídeos. En la práctica, movies.video_url y
--   episodes.video_url acabaron guardando el TRÁILER oficial de YouTube de cada
--   título (docs/catalogo-ampliado.sql), así que el botón «Ver ahora» abría un
--   tráiler y su texto mentía. Se separan los dos conceptos:
--     * movies.trailer_url y series.trailer_url (NUEVAS, opcionales): el
--       tráiler, siempre de YouTube y normalizado como
--       https://www.youtube.com/watch?v=<id de 11 caracteres>.
--     * movies.video_url y episodes.video_url pasan a ADMITIR NULL: el vídeo
--       del contenido es opcional (un título puede tener solo tráiler).
--   Las series no tenían vídeo propio; su tráiler sale de sus episodios.
--
-- QUÉ DATOS MUEVE (en este orden)
--   1. Película cuyo video_url tiene EXACTAMENTE la forma normalizada
--      (https://www.youtube.com/watch?v= + 11 caracteres de [A-Za-z0-9_-]):
--      trailer_url = video_url y video_url = NULL.
--   2. Serie: trailer_url = el video_url con esa misma forma del episodio de
--      temporada y número más bajos entre los que la tienen. En los datos del
--      autor todos los episodios de una serie llevan el mismo tráiler; si
--      difirieran, se elige así para que el resultado sea determinista.
--   3. Episodio cuyo video_url es igual al trailer_url de su serie:
--      video_url = NULL (era el tráiler, no el episodio). Un episodio con OTRO
--      enlace de YouTube distinto del elegido NO se toca: podría ser un vídeo
--      propio de ese episodio, y no hay forma segura de saberlo.
--   Cualquier otro video_url (un .mp4 propio, YouTube con parámetros extra
--   como &t=10, youtu.be, http:, un id mal formado...) se queda tal cual: la
--   migración solo mueve lo que el CHECK de abajo acepta, así que nunca puede
--   fallar por él. Esas URLs las puede corregir el administrador desde el panel.
--
-- DEFENSA EN PROFUNDIDAD: ck_movies_trailer_url y ck_series_trailer_url
--   La API ya valida y normaliza el tráiler (@YouTubeUrl), pero la base de
--   datos también exige la forma normalizada: así ningún camino (un script, un
--   INSERT a mano, un fallo futuro del validador) puede guardar un enlace a
--   otro sitio que el frontend acabaría incrustando en un <iframe>. Como en
--   V4, el juego de caracteres del id se comprueba con TRANSLATE (cada carácter
--   válido pasa a '0' y solo un id válido queda en once ceros) y no con
--   expresiones regulares, que no son portables entre PostgreSQL y H2. No se
--   usa LOWER para acortar la lista: hay caracteres Unicode (el signo Kelvin,
--   U+212A) que en minúscula se convierten en una letra ASCII y se
--   colarían.
--
-- RIESGOS PARA DATOS EXISTENTES (incluida la base de desarrollo en Supabase)
--   - SÍ MODIFICA FILAS: mueve los tráileres como se describe arriba. No borra
--     ninguna fila ni columna y no pierde información: cada URL que deja de
--     estar en video_url queda en trailer_url (la de la película o la de su
--     serie). Para deshacerlo bastaría copiarla de vuelta.
--   - Es segura e idempotente en su efecto: Flyway la ejecuta una sola vez y en
--     una transacción (en PostgreSQL el DDL también es transaccional: si algo
--     fallara, no quedaría nada a medias). Además cada UPDATE solo actúa sobre
--     filas que aún no se han movido, así que repetir sus sentencias no
--     cambiaría nada.
--   - En una base adoptada (anterior a Flyway), video_url tenía un NOT NULL sin
--     nombre; DROP NOT NULL lo quita igual.
--   - Bloqueos: ADD COLUMN sin valor por defecto y DROP NOT NULL solo cambian
--     metadatos; ADD CONSTRAINT CHECK recorre la tabla una vez para validarla.
--     Con un catálogo de miles de títulos son milisegundos.
--   - Tras aplicarla, una versión ANTERIOR de la aplicación seguiría
--     arrancando (Hibernate no valida la nulabilidad ni las columnas que no
--     conoce), pero no sabe nada de trailer_url: los títulos movidos se verían
--     sin vídeo ni tráiler. No se debe volver atrás de versión.
--
-- SQL estándar: funciona igual en PostgreSQL y en H2 (modo PostgreSQL).
-- ===================================================================

-- 1. Columnas nuevas (opcionales) y vídeo del contenido opcional
ALTER TABLE movies ADD COLUMN trailer_url VARCHAR(500);
ALTER TABLE series ADD COLUMN trailer_url VARCHAR(500);

ALTER TABLE movies ALTER COLUMN video_url DROP NOT NULL;
ALTER TABLE episodes ALTER COLUMN video_url DROP NOT NULL;

-- 2. Películas: el tráiler de YouTube pasa de video_url a trailer_url.
--    En un UPDATE todas las expresiones del SET ven los valores ANTERIORES de
--    la fila, así que trailer_url recibe la URL antes de que video_url se vacíe.
UPDATE movies
SET trailer_url = video_url,
    video_url = NULL
WHERE trailer_url IS NULL
  AND CHAR_LENGTH(video_url) = 43
  AND SUBSTRING(video_url FROM 1 FOR 32) = 'https://www.youtube.com/watch?v='
  AND TRANSLATE(SUBSTRING(video_url FROM 33),
                'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-',
                REPEAT('0', 64)) = REPEAT('0', 11);

-- 3. Series: el tráiler es el de su episodio de temporada y número más bajos
--    entre los que tienen un enlace de YouTube normalizado. El índice único
--    (series_id, season_number, episode_number) resuelve cada subconsulta.
UPDATE series
SET trailer_url = (
        SELECT e.video_url
        FROM episodes e
        WHERE e.series_id = series.id
          AND CHAR_LENGTH(e.video_url) = 43
          AND SUBSTRING(e.video_url FROM 1 FOR 32) = 'https://www.youtube.com/watch?v='
          AND TRANSLATE(SUBSTRING(e.video_url FROM 33),
                        'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-',
                        REPEAT('0', 64)) = REPEAT('0', 11)
        ORDER BY e.season_number, e.episode_number
        FETCH FIRST 1 ROW ONLY)
WHERE trailer_url IS NULL;

-- 4. Episodios: el que llevaba como vídeo el tráiler de su serie se queda sin
--    vídeo (el tráiler ya está en la serie).
UPDATE episodes
SET video_url = NULL
WHERE video_url IS NOT NULL
  AND EXISTS (SELECT 1
              FROM series s
              WHERE s.id = episodes.series_id
                AND s.trailer_url = episodes.video_url);

-- 5. Restricciones: el tráiler, si existe, solo puede ser un vídeo de YouTube
--    en la forma normalizada. Se añaden al final; los pasos 2 y 3 solo han
--    copiado valores que ya la cumplen.
ALTER TABLE movies ADD CONSTRAINT ck_movies_trailer_url CHECK (
    trailer_url IS NULL
    OR (CHAR_LENGTH(trailer_url) = 43
        AND SUBSTRING(trailer_url FROM 1 FOR 32) = 'https://www.youtube.com/watch?v='
        AND TRANSLATE(SUBSTRING(trailer_url FROM 33),
                      'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-',
                      REPEAT('0', 64)) = REPEAT('0', 11)));

ALTER TABLE series ADD CONSTRAINT ck_series_trailer_url CHECK (
    trailer_url IS NULL
    OR (CHAR_LENGTH(trailer_url) = 43
        AND SUBSTRING(trailer_url FROM 1 FOR 32) = 'https://www.youtube.com/watch?v='
        AND TRANSLATE(SUBSTRING(trailer_url FROM 33),
                      'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-',
                      REPEAT('0', 64)) = REPEAT('0', 11)));
