-- ===================================================================
-- V16: imagen horizontal de cabecera (backdrop_url) e imagen propia del
--      episodio (episodes.image_url).
--
-- POR QUÉ: DOS IMÁGENES NUEVAS, LAS DOS OPCIONALES
--   A. Cabecera nítida. movies.backdrop_url y series.backdrop_url (NUEVAS):
--      una imagen HORIZONTAL para el banner del inicio y de la página de cada
--      título. Hasta ahora el banner ampliaba y difuminaba el póster vertical
--      (image_url, unos 600 px de ancho), que a 1280 px o más se ve borroso.
--      Sin backdrop_url el frontend sigue haciendo lo de siempre con el póster.
--   B. Miniatura del episodio. episodes.image_url (NUEVA): la imagen del
--      episodio para la lista de episodios y el «poster» del reproductor.
--      Hasta ahora los episodios usaban la portada de la serie; sin
--      image_url, la siguen usando.
--   Las columnas se llaman distinto a propósito: en episodes es la imagen
--   principal del episodio (como image_url en movies y series, pero
--   opcional), mientras que backdrop_url es una SEGUNDA imagen de un título
--   que ya tiene póster obligatorio.
--
-- RESTRICCIONES (defensa en profundidad: la API valida antes con
-- @HttpsUrl(allowLocalCovers = true) y da 400)
--   * ck_movies_backdrop_url / ck_series_backdrop_url / ck_episodes_image_url:
--     la columna es NULL, o empieza por 'https://' con algo detrás, o empieza
--     por '/covers/' (portadas propias que sirve el frontend) con algo detrás.
--     Se cierra 'http:' y cualquier otro esquema (javascript:, data:...), que
--     es lo que importa para la seguridad del cliente: el valor acaba en un
--     src o en un background-image.
--   * La comparación distingue mayúsculas ('HTTPS://' o '/Covers/' no valen),
--     igual que la API y que ck_*_subtitles_url de la V15.
--   * Los dos prefijos miden 8 caracteres, así que basta una sola longitud
--     (> 8 = «algo detrás») y un SUBSTRING de 8.
--   * SUBSTRING y no LIKE, por dos motivos: es el mismo estilo que la V15, y
--     en LIKE '_' y '%' son comodines (un patrón como '/covers/_%' funciona,
--     pero es fácil escribir uno que acepte más de lo que parece), y su
--     carácter de escape por defecto no es el mismo en todos los motores.
--     Una comparación literal del prefijo no tiene esas sorpresas y funciona
--     igual en PostgreSQL y en H2.
--   * NO se comprueba en SQL el nombre del archivo de /covers/ (la API exige
--     /covers/<letra o dígito>[letras, dígitos, '.', '_', '-']*): sin
--     expresiones regulares portables entre PostgreSQL y H2 sería frágil, y
--     cualquier ruta /covers/... es del mismo origen que la aplicación.
--   * Sin CHECK de «sin póster no hay backdrop»: image_url ya es NOT NULL en
--     movies y series desde la V1/V3.
--
-- RELLENO DE DATOS: NINGUNO
--   Las filas existentes quedan con las tres columnas a NULL y se ven
--   exactamente como hoy (banner con el póster, episodios con la portada de
--   la serie). No hay ningún dato del que derivar una imagen horizontal.
--
-- ÍNDICES: NINGUNO NUEVO
--   Nadie busca ni ordena por estas URLs: se leen con el resto del título.
--
-- RIESGOS PARA DATOS EXISTENTES (incluida la base de desarrollo en Supabase)
--   - Añade tres columnas que admiten NULL, sin valor por defecto: no cambia
--     ni borra ninguna fila ni columna. Todas las filas cumplen los CHECK
--     (NULL es válido).
--   - Bloqueos: ADD COLUMN sin valor por defecto solo cambia metadatos; cada
--     ADD CONSTRAINT CHECK recorre su tabla una vez con un bloqueo exclusivo.
--     Con miles de títulos son milisegundos.
--   - Todo va en una transacción (Flyway; en PostgreSQL el DDL también es
--     transaccional): si algo falla, no queda nada a medias.
--   - Vuelta atrás: una versión ANTERIOR de la aplicación arrancaría
--     (Hibernate no valida columnas que no conoce) y seguiría funcionando;
--     simplemente no vería estas imágenes.
--   - Supabase: no hay tablas nuevas; el callback
--     afterMigrate__close_public_api.sql sigue cerrándolas igual.
--
-- SQL estándar: funciona igual en PostgreSQL y en H2 (modo PostgreSQL).
-- ===================================================================

-- A. Imagen horizontal de cabecera de películas y series
ALTER TABLE movies ADD COLUMN backdrop_url VARCHAR(500);
ALTER TABLE series ADD COLUMN backdrop_url VARCHAR(500);

-- B. Imagen propia del episodio
ALTER TABLE episodes ADD COLUMN image_url VARCHAR(500);

ALTER TABLE movies ADD CONSTRAINT ck_movies_backdrop_url CHECK (
    backdrop_url IS NULL
    OR (CHAR_LENGTH(backdrop_url) > 8
        AND (SUBSTRING(backdrop_url FROM 1 FOR 8) = 'https://'
             OR SUBSTRING(backdrop_url FROM 1 FOR 8) = '/covers/')));
ALTER TABLE series ADD CONSTRAINT ck_series_backdrop_url CHECK (
    backdrop_url IS NULL
    OR (CHAR_LENGTH(backdrop_url) > 8
        AND (SUBSTRING(backdrop_url FROM 1 FOR 8) = 'https://'
             OR SUBSTRING(backdrop_url FROM 1 FOR 8) = '/covers/')));
ALTER TABLE episodes ADD CONSTRAINT ck_episodes_image_url CHECK (
    image_url IS NULL
    OR (CHAR_LENGTH(image_url) > 8
        AND (SUBSTRING(image_url FROM 1 FOR 8) = 'https://'
             OR SUBSTRING(image_url FROM 1 FOR 8) = '/covers/')));
