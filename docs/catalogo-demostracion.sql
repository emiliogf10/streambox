-- =============================================================================
-- catalogo-demostracion.sql  (OPCIONAL: no forma parte de las migraciones de Flyway)
-- =============================================================================
--
-- QUÉ HACE
--   Añade al catálogo tres cortometrajes libres de la Blender Foundation con
--   un VÍDEO DIRECTO que se reproduce dentro de StreamBox (reproductor
--   integrado, «Seguir viendo», historial y cifras del perfil) y, en dos de
--   ellos, subtítulos en español:
--     * Big Buck Bunny (2008), 11 min: Animación, Comedia. Sin subtítulos
--       (no tiene diálogos).
--     * Sintel (2010), 15 min: Animación, Fantasía, Aventura. Subtítulos.
--     * Tears of Steel (2012), 12 min: Ciencia ficción, Acción. Subtítulos.
--   Sin tráiler (trailer_url = NULL): el «tráiler» sería la propia película.
--   Si falta alguno de esos géneros, lo crea.
--   Los carteles son portadas locales (image_url = '/covers/<archivo>.webp',
--   archivos en frontend/public/covers/): los sirve el propio frontend (Vite
--   en desarrollo y nginx en Docker), igual que docs/portadas-locales.sql, y
--   la API los acepta (@HttpsUrl(allowLocalCovers = true)). Son WebP de 500 px
--   de ancho (44-79 KB): el cartel de la película más reciente suele acabar en
--   el banner del inicio, lo primero que se pinta, y el PNG original de Tears
--   of Steel pesaba 674 KB.
--
-- POR QUÉ ES OPCIONAL
--   La aplicación funciona igual sin él. Existe porque StreamBox no aloja
--   vídeos: sin un título con vídeo directo (.webm/.mp4/.ogg/.ogv por
--   https://), quien vea el portfolio solo encuentra tráileres de YouTube y
--   no puede probar el reproductor. Estas películas son de licencia libre y
--   sus archivos están en servidores públicos que permiten reproducirlos
--   desde otra web (Wikimedia Commons responde a peticiones Range y envía
--   Access-Control-Allow-Origin: *; jsDelivr sirve los .vtt como text/vtt y
--   con CORS).
--
-- REQUISITOS
--   El esquema debe estar en la V15 o posterior (columna subtitles_url) y
--   tener la V5 (función streambox_unaccent). Basta con haber arrancado la
--   aplicación una vez con la versión actual. Si falta algo, el script falla
--   al principio y, al ir en una transacción, no deja nada a medias.
--
-- CÓMO EJECUTARLO (a mano, sobre TU base de datos)
--   * Supabase: Dashboard -> SQL Editor -> New query, pega el archivo entero
--     y pulsa «Run». El editor se conecta como el dueño de las tablas, así
--     que RLS (activada en todas, sin políticas) no le afecta. La consulta
--     del final enseña las tres películas con su número de géneros.
--   * psql: psql -U <usuario> -d streambox -f docs/catalogo-demostracion.sql
--     (en Windows, si los acentos salen mal, ejecuta antes \encoding UTF8).
--   * Desde el panel de administración también se puede hacer a mano: estos
--     datos pasan la misma validación que la API (lo comprueba el test), así
--     que después se pueden editar desde «Editar» como cualquier película.
--
-- TRANSACCIÓN E IDEMPOTENCIA
--   Todo va entre BEGIN y COMMIT: si una sentencia falla, no se guarda nada.
--   Ejecutarlo varias veces deja el mismo resultado:
--     * Una película se inserta solo si NO hay ya otra con el mismo título
--       (sin distinguir mayúsculas ni espacios en los extremos) y el mismo
--       año. Si ya la tenías (aunque sea con otros datos), no se toca.
--     * Un género se crea solo si no existe ya con ese nombre sin distinguir
--       mayúsculas, acentos ni espacios en los extremos (la misma clave que el
--       buscador, streambox_unaccent(lower(...))): si tienes «Ciencia
--       Ficcion», se usa ese y no se crea «Ciencia ficción».
--     * Los géneros se asignan solo a las películas de este script (mismo
--       título, año y vídeo) que todavía no tienen NINGÚN género, es decir,
--       a las que acaba de crear. Si después cambias sus géneros desde el
--       panel, volver a ejecutarlo no los toca. Va en una sola sentencia: en
--       PostgreSQL la subconsulta NOT EXISTS ve la tabla como estaba al
--       empezar la sentencia, así que cada película recibe todos sus géneros.
--   No modifica ni borra ninguna fila existente.
--
-- RIESGOS
--   * Añade hasta 3 películas y hasta 6 géneros. Las películas salen las
--     primeras en «Novedades» (created_at = ahora) y sus géneros pasan a
--     ofrecerse en «Tus gustos» y en los filtros.
--   * Los vídeos dependen de servidores ajenos (los carteles no: van con el
--     frontend). Si Wikimedia Commons cambia una ruta, el reproductor dirá
--     que no puede abrir el vídeo; se corrige editando la URL desde el panel.
--     Si se renombra o borra un cartel de frontend/public/covers/, la portada
--     mostrará el respaldo con el título (la suite lo detecta: el test
--     comprueba que cada /covers/<archivo> del script existe).
--     Wikimedia limita el ritmo de peticiones a quien abusa (muchas
--     descargas seguidas desde la misma IP, scripts que bajan los vídeos):
--     para ver las películas con normalidad no es un problema, pero no lo
--     uses para pruebas de carga.
--   * Los vídeos son WebM (VP9) a 480p: los reproduce cualquier navegador
--     actual; un Safari antiguo (anterior a 14.1 en macOS) puede no hacerlo.
--   * Para deshacerlo (BORRA las 3 películas y, en cascada, sus géneros
--     asignados, favoritos, «me gusta» y progreso de visionado):
--       DELETE FROM movies
--       WHERE video_url LIKE 'https://upload.wikimedia.org/wikipedia/commons/transcoded/%'
--         AND title IN ('Big Buck Bunny', 'Sintel', 'Tears of Steel');
--     Los géneros creados se quedan; bórralos desde el panel si no los usas.
--
-- SUBTÍTULOS: SOLO FUNCIONAN DESPUÉS DEL PUSH
--   Los .vtt están en el repositorio (docs/demo/subtitulos/) y se sirven por
--   jsDelivr desde GitHub. Hasta que no estén en la rama main del repositorio
--   público, jsDelivr responde 404: el vídeo se ve igual y el reproductor
--   avisa de que no se han podido cargar los subtítulos.
--
--   FIJAR LA VERSIÓN DE LOS SUBTÍTULOS (recomendado una vez publicados)
--   Las URLs usan @main, que tiene dos inconvenientes: jsDelivr guarda en
--   caché la respuesta de una rama hasta unas 12 h (un cambio en el .vtt
--   tarda en verse) y el contenido cambia o desaparece con la rama (si
--   mueves o borras el archivo, los subtítulos dejan de cargar). Para que no
--   cambien nunca, sustituye @main por un commit o una etiqueta, que jsDelivr
--   cachea de forma permanente:
--     https://cdn.jsdelivr.net/gh/emiliogf10/streambox@<sha del commit>/docs/demo/subtitulos/sintel.es.vtt
--     https://cdn.jsdelivr.net/gh/emiliogf10/streambox@v1.0.0/docs/demo/subtitulos/sintel.es.vtt
--   Hazlo editando la película desde el panel o cambiando aquí las URLs
--   antes de ejecutarlo (si ya lo ejecutaste, el script no las actualiza).
--
-- CRÉDITOS Y LICENCIAS
--   * Películas y carteles: Creative Commons Attribution 3.0 (CC BY 3.0),
--     © Blender Foundation.
--       Big Buck Bunny: peach.blender.org
--       Sintel:         durian.blender.org
--       Tears of Steel: mango.blender.org
--     Cada sinopsis termina con esa atribución, para que también se vea en
--     la aplicación (la licencia exige reconocer la autoría).
--   * Vídeos: versiones de 480p transcodificadas alojadas en Wikimedia
--     Commons (commons.wikimedia.org).
--   * Carteles: conversiones a WebP (500 px de ancho) de los de Wikimedia
--     Commons «File:Big buck bunny poster big.jpg», «File:Sintel poster.jpg»
--     y «File:Tos-poster.png»; CC BY 3.0, © Blender Foundation.
--   * Subtítulos de Sintel: colaboradores de Wikimedia Commons
--     (TimedText:Sintel_movie_4K.webm.es.srt), licencia CC BY-SA.
--   * Subtítulos de Tears of Steel: la traducción al español que distribuye
--     la Blender Foundation con la película (download.blender.org).
--   Los dos .vtt llevan su atribución en un bloque NOTE al principio.
--
-- SQL estándar: funciona igual en PostgreSQL y en H2 (modo PostgreSQL). Lo
-- ejecuta la suite en los dos motores (DemoCatalogScriptTest y
-- PostgresDemoCatalogScriptIntegrationTest), así que una migración futura que
-- lo rompa hace fallar los tests.
-- =============================================================================

BEGIN;

-- 1. Géneros que faltan. Nombres con la normalización de la API (primera
--    letra en mayúscula, el resto en minúsculas), como si los creara el panel.
INSERT INTO genres (name)
SELECT v.name
FROM (VALUES
        ('Animación'),
        ('Comedia'),
        ('Fantasía'),
        ('Aventura'),
        ('Ciencia ficción'),
        ('Acción')
     ) AS v (name)
WHERE NOT EXISTS (
        SELECT 1
        FROM genres g
        WHERE streambox_unaccent(lower(trim(g.name))) = streambox_unaccent(lower(v.name)));

-- 2. Películas. created_at no tiene valor por defecto en la base de datos (lo
--    pone Hibernate al insertar desde la API), así que se indica aquí.
INSERT INTO movies (title, description, duration, release_year, image_url, video_url, subtitles_url, created_at)
SELECT d.title, d.description, d.duration, d.release_year, d.image_url, d.video_url, d.subtitles_url, CURRENT_TIMESTAMP
FROM (VALUES
        ('Big Buck Bunny',
         'Un conejo enorme y bonachón disfruta de una mañana tranquila en el bosque hasta que tres roedores abusones la toman con él y con las mariposas del prado. Harto de sus burlas, prepara una venganza tan ingeniosa como desternillante. Cortometraje libre © Blender Foundation (peach.blender.org), licencia CC BY 3.0.',
         11, 2008,
         '/covers/big-buck-bunny.webp',
         'https://upload.wikimedia.org/wikipedia/commons/transcoded/c/c0/Big_Buck_Bunny_4K.webm/Big_Buck_Bunny_4K.webm.480p.vp9.webm',
         CAST(NULL AS VARCHAR(500))),
        ('Sintel',
         'Sintel cruza montañas heladas y desiertos en busca de la cría de dragón que rescató y crio como a una hermana, y que un dragón adulto le arrebató. Un viaje solitario y peligroso que la pondrá a prueba hasta el final. Cortometraje libre © Blender Foundation (durian.blender.org), licencia CC BY 3.0.',
         15, 2010,
         '/covers/sintel.webp',
         'https://upload.wikimedia.org/wikipedia/commons/transcoded/f/f1/Sintel_movie_4K.webm/Sintel_movie_4K.webm.480p.vp9.webm',
         'https://cdn.jsdelivr.net/gh/emiliogf10/streambox@main/docs/demo/subtitulos/sintel.es.vtt'),
        ('Tears of Steel',
         'En una Ámsterdam futura, un grupo de científicos se atrinchera para salvar el mundo de un ejército de robots. Su plan es tan extraño como arriesgado: volver a vivir la ruptura sentimental con la que empezó todo. Cortometraje libre © Blender Foundation (mango.blender.org), licencia CC BY 3.0.',
         12, 2012,
         '/covers/tears-of-steel.webp',
         'https://upload.wikimedia.org/wikipedia/commons/transcoded/1/10/Tears_of_Steel_in_4k_-_Official_Blender_Foundation_release.webm/Tears_of_Steel_in_4k_-_Official_Blender_Foundation_release.webm.480p.vp9.webm',
         'https://cdn.jsdelivr.net/gh/emiliogf10/streambox@main/docs/demo/subtitulos/tears-of-steel.es.vtt')
     ) AS d (title, description, duration, release_year, image_url, video_url, subtitles_url)
WHERE NOT EXISTS (
        SELECT 1
        FROM movies m
        WHERE lower(trim(m.title)) = lower(d.title)
          AND m.release_year = d.release_year);

-- 3. Géneros de cada película, solo para las de este script (mismo título,
--    año y vídeo) que aún no tienen ninguno: las recién creadas. Si hubiera
--    dos géneros con la misma clave (filas antiguas como «Acción» y
--    «acción»), se usa el más antiguo. Si un género no existiera, MIN daría
--    NULL, el INSERT fallaría por el NOT NULL y no se guardaría nada.
INSERT INTO movie_genres (movie_id, genre_id)
SELECT m.id,
       (SELECT MIN(g.id)
        FROM genres g
        WHERE streambox_unaccent(lower(trim(g.name))) = dg.genre_key)
FROM (VALUES
        ('Big Buck Bunny', 2008,
         'https://upload.wikimedia.org/wikipedia/commons/transcoded/c/c0/Big_Buck_Bunny_4K.webm/Big_Buck_Bunny_4K.webm.480p.vp9.webm'),
        ('Sintel', 2010,
         'https://upload.wikimedia.org/wikipedia/commons/transcoded/f/f1/Sintel_movie_4K.webm/Sintel_movie_4K.webm.480p.vp9.webm'),
        ('Tears of Steel', 2012,
         'https://upload.wikimedia.org/wikipedia/commons/transcoded/1/10/Tears_of_Steel_in_4k_-_Official_Blender_Foundation_release.webm/Tears_of_Steel_in_4k_-_Official_Blender_Foundation_release.webm.480p.vp9.webm')
     ) AS d (title, release_year, video_url)
JOIN movies m
  ON lower(trim(m.title)) = lower(d.title)
 AND m.release_year = d.release_year
 AND m.video_url = d.video_url
JOIN (VALUES
        ('Big Buck Bunny', 'animacion'),
        ('Big Buck Bunny', 'comedia'),
        ('Sintel', 'animacion'),
        ('Sintel', 'fantasia'),
        ('Sintel', 'aventura'),
        ('Tears of Steel', 'ciencia ficcion'),
        ('Tears of Steel', 'accion')
     ) AS dg (title, genre_key)
  ON dg.title = d.title
WHERE NOT EXISTS (
        SELECT 1
        FROM movie_genres mg
        WHERE mg.movie_id = m.id);

COMMIT;

-- Comprobación (solo lectura): las tres películas con su número de géneros
-- (2, 3 y 2 si las creó este script) y si tienen subtítulos.
SELECT m.id,
       m.title,
       m.release_year,
       (SELECT COUNT(*) FROM movie_genres mg WHERE mg.movie_id = m.id) AS genres,
       m.subtitles_url IS NOT NULL AS has_subtitles
FROM movies m
WHERE m.video_url LIKE 'https://upload.wikimedia.org/wikipedia/commons/transcoded/%'
  AND m.title IN ('Big Buck Bunny', 'Sintel', 'Tears of Steel')
ORDER BY m.release_year;
