-- =============================================================================
-- supabase-seguridad.sql  (OPCIONAL: no es una migración de Flyway)
-- =============================================================================
--
-- QUE HACE
--   Cierra el acceso de la API publica de Supabase (PostgREST) a las tablas de
--   StreamBox. Supabase publica automaticamente una API REST sobre el esquema
--   `public`, accesible con la clave publica ("anon"). Las tablas que crea
--   Flyway viven en `public`, asi que, sin este script, esa clave permitiria
--   leer la tabla `users` (emails y hashes de contrasena).
--
--   Hace dos cosas, las dos a la vez (defensa en profundidad):
--     1. Activa RLS (Row Level Security) SIN politicas: con RLS activado y sin
--        ninguna politica, los roles "anon" y "authenticated" no ven ninguna fila.
--     2. Les retira todos los permisos sobre las tablas.
--
--   La aplicacion NO se ve afectada: Spring Boot conecta con el rol `postgres`
--   (o `postgres.<id-del-proyecto>` por el pooler), que en Supabase se salta
--   RLS y conserva todos los permisos.
--
-- CUANDO EJECUTARLO
--   Despues de que Flyway haya creado las tablas (arrancando la aplicacion una
--   vez contra Supabase) y ANTES de importar los datos. Es idempotente: se puede
--   ejecutar mas veces sin problema. Si anades tablas nuevas con una migracion
--   (V3, V4...), vuelve a ejecutarlo o anade sus lineas aqui.
--
-- COMO EJECUTARLO
--   Pegalo en el "SQL Editor" del panel de Supabase y pulsa "Run".
--
-- COMO COMPROBARLO
--   En el panel: Advisors > Security Advisor no debe mostrar tablas sin RLS.
--   O con SQL (todas deben salir con rls_activado = true):
--     SELECT tablename, rowsecurity AS rls_activado
--     FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename;
--
-- NO ES PORTABLE: ENABLE ROW LEVEL SECURITY es de PostgreSQL (H2 no lo
-- entiende), por eso no es una migracion de Flyway: rompería los tests con H2.
-- =============================================================================

ALTER TABLE public.users                 ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.genres                ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.movies                ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.movie_genres          ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_favorite_movies  ENABLE ROW LEVEL SECURITY;

-- Series (V3__create_series.sql, 2026-10). Tras arrancar la app con V3,
-- vuelve a ejecutar este script entero: las tablas nuevas nacen sin RLS.
ALTER TABLE public.series                ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.series_genres         ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.episodes              ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.user_favorite_series  ENABLE ROW LEVEL SECURITY;

-- Historial de Flyway (lo crea Flyway en `public`): tambien se cierra.
ALTER TABLE public.flyway_schema_history ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON TABLE
    public.users,
    public.genres,
    public.movies,
    public.movie_genres,
    public.user_favorite_movies,
    public.series,
    public.series_genres,
    public.episodes,
    public.user_favorite_series,
    public.flyway_schema_history
FROM anon, authenticated;
