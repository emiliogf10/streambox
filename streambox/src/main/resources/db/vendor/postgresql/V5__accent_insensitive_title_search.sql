-- ===================================================================
-- V5 (SOLO PostgreSQL): búsqueda por título sin acentos y con índice.
--
-- POR QUÉ
--   La búsqueda era lower(title) LIKE '%texto%': ignoraba mayúsculas pero no
--   acentos ("manana" no encontraba "Mañana") y ningún índice B-tree sirve para
--   un LIKE con comodín al principio, así que cada búsqueda recorría la tabla.
--   Esta migración prepara la base para la búsqueda nueva (ver
--   specification/TitleSearch.java):
--     1. Extensiones unaccent (quitar acentos) y pg_trgm (trigramas).
--     2. public.streambox_unaccent(text): envoltorio IMMUTABLE de unaccent.
--        Un índice de expresión exige funciones IMMUTABLE y unaccent(text) es
--        solo STABLE (busca el diccionario por nombre en el search_path en cada
--        llamada). El envoltorio fija el diccionario y la función por OID.
--     3. public.streambox_word_similarity(text, text): envoltorio de
--        word_similarity de pg_trgm, para la tolerancia a erratas.
--     4. Índices GIN de trigramas sobre streambox_unaccent(lower(title)) en
--        movies y series: aceleran el LIKE '%texto%' (con 3 o más letras).
--
-- POR QUÉ ESTÁ EN db/vendor/postgresql (y no en db/migration)
--   H2, la base de los tests, no tiene unaccent ni pg_trgm ni bloques DO. Spring
--   Boot sustituye {vendor} por el motor conectado (spring.flyway.locations), así
--   que esta versión solo la ve PostgreSQL; H2 tiene su propia V5 con el mismo
--   nombre de función (src/main/resources/db/vendor/h2). No puede ir en una
--   subcarpeta de db/migration: Flyway recorre esa ubicación de forma
--   recursiva y encontraría las dos V5.
--
-- DÓNDE QUEDAN LAS EXTENSIONES
--   En el esquema `extensions`, como hace Supabase (lo crea si no existe, p. ej.
--   en docker compose o Testcontainers). Así `public` solo contiene objetos de
--   StreamBox y el callback afterMigrate__close_public_api.sql sigue pudiendo
--   cerrar todo lo de `public`. Si una extensión ya estaba instalada en otro
--   esquema, no se mueve: se localiza en pg_extension y se usa ahí.
--   Ambas son extensiones "trusted" desde PostgreSQL 13: las puede instalar el
--   propietario de la base sin ser superusuario (el rol postgres de Supabase).
--
-- POR QUÉ BEGIN ATOMIC (PostgreSQL 14+; el proyecto exige 16)
--   El cuerpo SQL estándar se analiza AL CREAR la función y guarda referencias
--   por OID, no por nombre: no depende del search_path de quien la ejecute (ni
--   del search_path restringido que usan VACUUM/ANALYZE desde PostgreSQL 17, que
--   rompe los índices con funciones mal cualificadas) y nadie puede suplantar
--   unaccent creando otra función con ese nombre en un esquema anterior. Además
--   registra la dependencia: DROP EXTENSION unaccent falla en lugar de dejar el
--   índice roto. SET search_path = '' es una segunda barrera (y lo que comprueba
--   el Security Advisor de Supabase, aviso "Function Search Path Mutable"):
--   cuesta un 5-10 % por llamada y solo impide que PostgreSQL "despliegue" la
--   función dentro de la consulta, que aquí no aporta nada.
--
-- RIESGOS PARA DATOS EXISTENTES
--   - No modifica filas ni columnas.
--   - CREATE INDEX (no CONCURRENTLY: Flyway ejecuta la migración en una
--     transacción) bloquea las escrituras en movies y series mientras se
--     construye. Con un catálogo de miles de títulos son milisegundos; con
--     millones habría que hacerlo con CONCURRENTLY en una migración aparte sin
--     transacción.
--   - Si el servidor no tiene las extensiones (PostgreSQL sin el paquete
--     contrib) o el rol no puede instalarlas, la migración falla, la transacción
--     deshace todo (el DDL de PostgreSQL es transaccional) y la aplicación no
--     arranca, con el error de PostgreSQL en el log. Es intencionado: la
--     búsqueda depende de estas funciones.
--   - Las bases adoptadas (anteriores a Flyway) la reciben igual que las nuevas.
--   - IMMUTABLE es una promesa: el resultado depende de las reglas de unaccent y
--     de lower() (la ctype de la base). Si cambiaran (otra versión de unaccent,
--     de glibc o de la collation), habría que ejecutar REINDEX de estos índices,
--     igual que con cualquier índice sobre lower().
--
-- Cuidado al editar: Flyway sustituye los marcadores de posición de la forma
-- dólar + llave (también dentro de comentarios), así que no los escribas.
-- ===================================================================

DO $v5$
DECLARE
    unaccent_schema text;
    trgm_schema     text;
BEGIN
    -- 1. Extensiones (en `extensions`, creando el esquema solo si hace falta)
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'unaccent')
            OR NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        IF NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'extensions') THEN
            CREATE SCHEMA extensions;
        END IF;
        CREATE EXTENSION IF NOT EXISTS unaccent WITH SCHEMA extensions;
        CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA extensions;
    END IF;

    SELECT n.nspname INTO STRICT unaccent_schema
      FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
     WHERE e.extname = 'unaccent';
    SELECT n.nspname INTO STRICT trgm_schema
      FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
     WHERE e.extname = 'pg_trgm';

    -- 2. unaccent con diccionario fijo: IMMUTABLE, apta para índices
    EXECUTE format($sql$
        CREATE FUNCTION public.streambox_unaccent(text) RETURNS text
            LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
            SET search_path = ''
        BEGIN ATOMIC
            SELECT %1$I.unaccent(%2$L::regdictionary, $1);
        END
        $sql$, unaccent_schema, quote_ident(unaccent_schema) || '.unaccent');

    -- 3. word_similarity(busqueda, titulo): parecido entre la búsqueda y el
    --    fragmento del título que más se le parece (0 = nada, 1 = idéntico)
    EXECUTE format($sql$
        CREATE FUNCTION public.streambox_word_similarity(text, text) RETURNS real
            LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
            SET search_path = ''
        BEGIN ATOMIC
            SELECT %1$I.word_similarity($1, $2);
        END
        $sql$, trgm_schema);

    -- 4. Índices GIN de trigramas sobre la misma expresión que usa la consulta
    EXECUTE format('CREATE INDEX idx_movies_title_search ON public.movies'
            ' USING gin (public.streambox_unaccent(lower(title)) %I.gin_trgm_ops)', trgm_schema);
    EXECUTE format('CREATE INDEX idx_series_title_search ON public.series'
            ' USING gin (public.streambox_unaccent(lower(title)) %I.gin_trgm_ops)', trgm_schema);
END
$v5$;

COMMENT ON FUNCTION public.streambox_unaccent(text) IS
    'StreamBox: unaccent con diccionario fijo (IMMUTABLE) para los índices de búsqueda por título';
COMMENT ON FUNCTION public.streambox_word_similarity(text, text) IS
    'StreamBox: word_similarity de pg_trgm para la búsqueda tolerante a erratas';
