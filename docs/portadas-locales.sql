-- =============================================================================
-- portadas-locales.sql  (OPCIONAL: no forma parte de las migraciones de Flyway)
-- =============================================================================
--
-- QUE HACE
--   Apunta las peliculas de ejemplo a las portadas WebP que ahora viven en el
--   frontend (frontend/public/covers/*.webp, servidas como /covers/<archivo>.webp).
--   Sustituye al antiguo `imageMap` de frontend/src/lib/utils.ts, que asignaba una
--   imagen local segun el titulo. Ahora la interfaz usa SIEMPRE el campo
--   `image_url` que devuelve la API, asi que esa asignacion pasa a vivir en los
--   datos. Los titulos y alias son los mismos que tenia el `imageMap`.
--
-- POR QUE ES OPCIONAL
--   Si NO lo ejecutas, la aplicacion funciona igual: las peliculas cuyo
--   `image_url` no apunte a una imagen alcanzable muestran un hueco con el
--   titulo (placeholder) en lugar de la portada.
--
-- COMO EJECUTARLO (a mano, sobre TU base de datos de desarrollo)
--   psql -U <usuario> -d streambox -f docs/portadas-locales.sql
--   Es SQL estandar (UPDATE con lower/trim), portable a PostgreSQL y H2. Es
--   idempotente: ejecutarlo varias veces deja el mismo resultado. Hazlo en una
--   transaccion si quieres comprobar antes cuantas filas cambia:
--   BEGIN; \i docs/portadas-locales.sql  -- revisa los "UPDATE n" -- y COMMIT/ROLLBACK.
--
-- ADVERTENCIA: RUTA RELATIVA vs. VALIDACION DE LA API
--   El backend valida `imageUrl` con @URL en las altas y ediciones por API
--   (POST /api/movies y PUT /api/movies/{id}). Una ruta relativa como
--   '/covers/x.webp' NO es una URL valida para ese validador, asi que solo se
--   puede escribir por SQL (como hace este script). Consecuencia: si despues
--   editas una de estas peliculas con PUT y reenvias su `imageUrl` tal cual, la
--   API la rechazara con 400 (VALIDATION_ERROR).
--   Alternativa si quieres poder editar por API: guardar una URL ABSOLUTA del
--   host que sirva el frontend, p. ej.
--     'http://localhost:5173/covers/blade-runner.webp'   (desarrollo)
--     'https://tu-dominio.com/covers/blade-runner.webp'  (produccion)
--   Eso pasa @URL, pero acopla los datos al dominio (cambiarlo exige otro
--   UPDATE). Tambien hay una tercera via: subir las imagenes a un almacen/CDN y
--   guardar esas URLs. La decision es tuya; este script usa rutas relativas
--   porque funcionan en cualquier entorno sin tocar nada.
--
-- NOTA: el archivo esta en UTF-8 (hay un titulo con 'ñ'); si psql en Windows lo
-- lee mal, ejecuta antes `\encoding UTF8`. `lower()` y `trim()` se aplican a la columna, asi que no se usan indices
-- sobre `title`; con un catalogo pequeno no importa.
-- =============================================================================

-- Al filo del manana
UPDATE movies SET image_url = '/covers/al-filo-del-manana.webp' WHERE lower(trim(title)) = 'al filo del mañana';

-- Blade Runner (1982) y Blade Runner 2049
UPDATE movies SET image_url = '/covers/blade-runner.webp'      WHERE lower(trim(title)) = 'blade runner';
UPDATE movies SET image_url = '/covers/blade-runner-2049.webp' WHERE lower(trim(title)) = 'blade runner 2049';

-- Dredd
UPDATE movies SET image_url = '/covers/dredd.webp' WHERE lower(trim(title)) = 'dredd';

-- El marciano (y su titulo original)
UPDATE movies SET image_url = '/covers/el-marciano.webp' WHERE lower(trim(title)) = 'el marciano';
UPDATE movies SET image_url = '/covers/el-marciano.webp' WHERE lower(trim(title)) = 'the martian';

-- Ex Machina
UPDATE movies SET image_url = '/covers/ex-machina.webp' WHERE lower(trim(title)) = 'ex machina';

-- Interstellar
UPDATE movies SET image_url = '/covers/interstellar.webp' WHERE lower(trim(title)) = 'interstellar';

-- Dune: Parte dos (con y sin dos puntos)
UPDATE movies SET image_url = '/covers/dune-parte-dos.webp' WHERE lower(trim(title)) = 'dune: parte dos';
UPDATE movies SET image_url = '/covers/dune-parte-dos.webp' WHERE lower(trim(title)) = 'dune parte dos';

-- Guardianes de la galaxia
UPDATE movies SET image_url = '/covers/guardianes-de-la-galaxia.webp' WHERE lower(trim(title)) = 'guardianes de la galaxia';

-- Mad Max (y sus titulos alternativos)
UPDATE movies SET image_url = '/covers/mad-max.webp' WHERE lower(trim(title)) = 'mad max';
UPDATE movies SET image_url = '/covers/mad-max.webp' WHERE lower(trim(title)) = 'mad max: fury road';
UPDATE movies SET image_url = '/covers/mad-max.webp' WHERE lower(trim(title)) = 'mad max: furia en la carretera';
