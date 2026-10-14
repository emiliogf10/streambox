-- =============================================================================
-- supabase-seguridad.sql  (RESPALDO MANUAL: no es una migración de Flyway)
-- =============================================================================
--
-- AHORA LO HACE LA APLICACION SOLA
--   Desde la correccion de la pista NV-4 de la auditoria de seguridad, el
--   backend cierra la API publica de Supabase automaticamente tras CADA
--   `migrate` de Flyway (tambien en los arranques sin migraciones nuevas):
--     streambox/src/main/resources/db/callback/postgresql/afterMigrate__close_public_api.sql
--   Las tablas que cree una migracion futura quedan cerradas en ese mismo
--   arranque, sin que nadie tenga que acordarse de repetir este paso.
--
--   Este script SOLO sirve para aplicar lo mismo SIN arrancar la aplicacion
--   (p. ej. para cerrar una base ya existente antes de la primera vez que la
--   app se conecte, o para repararla si el callback avisó de que no pudo). El
--   bloque DO de abajo es una copia EXACTA del cuerpo del callback; un test
--   (PostgresPublicApiClosureIntegrationTest) comprueba que siguen iguales, asi
--   que si editas uno edita el otro.
--
-- EL PROBLEMA QUE RESUELVE
--   Supabase publica automaticamente una API REST (PostgREST) sobre el esquema
--   `public`, accesible con la clave publica ("anon") y con la de los usuarios
--   de Supabase Auth ("authenticated"). Las tablas que crea Flyway viven en
--   `public` y Supabase les concede privilegios por defecto a esos roles, asi
--   que, sin esto, esa clave permitiria leer la tabla `users` (emails y hashes
--   de contrasena).
--
-- QUE HACE (defensa en profundidad)
--   1. Si no existen los roles `anon` ni `authenticated` (PostgreSQL local,
--      docker compose) no hace nada y no falla.
--   2. En TODAS las tablas de `public` que existan (incluida
--      flyway_schema_history; no hay lista que mantener ni falla si falta una,
--      p. ej. en una base sin las series de V3): activa RLS SIN politicas (con
--      RLS y sin politicas esos roles no ven ninguna fila; NO se usa FORCE, el
--      propietario sigue saltandose RLS) y les retira todos los privilegios.
--   3. Retira los privilegios sobre secuencias y funciones de `public` (en
--      funciones tambien a PUBLIC, que es quien concede EXECUTE por defecto).
--   4. ALTER DEFAULT PRIVILEGES: lo que se cree despues nace sin privilegios.
--   No toca objetos que pertenezcan a una extension.
--   La aplicacion NO se ve afectada: Spring Boot conecta con el rol `postgres`
--   (o `postgres.<id-del-proyecto>` por el pooler), que es propietario de las
--   tablas y en Supabase se salta RLS.
--
-- COMO EJECUTARLO
--   Pegalo ENTERO en el "SQL Editor" del panel de Supabase, con el rol
--   `postgres` (el que usa por defecto), y pulsa "Run". Es idempotente: se puede
--   ejecutar cuantas veces haga falta. Si algo no se puede cambiar por falta de
--   privilegios, avisa con un WARNING (pestana "Messages") en vez de fallar: un
--   WARNING significa que ese objeto sigue abierto.
--
-- COMO COMPROBARLO
--   En el panel: Advisors > Security Advisor no debe mostrar tablas sin RLS.
--   O con SQL (ambas consultas deben salir vacias):
--     SELECT tablename FROM pg_tables
--      WHERE schemaname = 'public' AND NOT rowsecurity;
--     SELECT table_name, grantee, privilege_type
--       FROM information_schema.role_table_grants
--      WHERE table_schema = 'public' AND grantee IN ('anon', 'authenticated');
--
-- LO QUE NO CUBRE (y hay que revisar en el panel de Supabase)
--   * Es privilegio de objeto, no de esquema: `anon` y `authenticated` conservan
--     USAGE sobre el esquema `public` (hace falta para otros usos de Supabase).
--   * Otros esquemas expuestos por la Data API (Settings > API > Exposed
--     schemas) y las funciones que se creen en el futuro: PostgreSQL concede
--     EXECUTE a PUBLIC por defecto y eso no se puede revocar solo para
--     `public`; el siguiente arranque de la app las cierra.
--   * Lo mas robusto es desactivar la Data API del proyecto si no se usa
--     (Settings > API), porque StreamBox accede por conexion directa.
--
-- NO ES PORTABLE: ENABLE ROW LEVEL SECURITY y los roles de Supabase son de
-- PostgreSQL (H2 no los entiende), por eso no es una migracion versionada: rompería
-- los tests con H2.
-- =============================================================================

DO $callback$
DECLARE
    api_roles  text;    -- roles de la API publica que existen, ya citados: anon, authenticated
    obj        record;
    owner_role text;
BEGIN
    SELECT string_agg(quote_ident(rolname), ', ' ORDER BY rolname)
      INTO api_roles
      FROM pg_roles
     WHERE rolname IN ('anon', 'authenticated');

    -- Paso 1: sin roles de Supabase no hay API publica que cerrar.
    IF api_roles IS NULL THEN
        RETURN;
    END IF;

    -- Paso 2: tablas (y vistas, materializadas y foraneas) de `public`.
    FOR obj IN
        SELECT c.relname, c.relkind, c.relrowsecurity
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relkind IN ('r', 'p', 'v', 'm', 'f')
           AND NOT EXISTS (SELECT 1 FROM pg_depend d
                            WHERE d.classid = 'pg_class'::regclass
                              AND d.objid = c.oid
                              AND d.deptype = 'e')
         ORDER BY c.relname
    LOOP
        -- RLS solo existe en tablas (r) y tablas particionadas (p), no en vistas
        IF obj.relkind IN ('r', 'p') AND NOT obj.relrowsecurity THEN
            BEGIN
                EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', obj.relname);
            EXCEPTION WHEN insufficient_privilege THEN
                RAISE WARNING 'StreamBox: no se pudo activar RLS en public.% (el rol % no es su propietario): sigue ABIERTA a la API publica de Supabase', obj.relname, current_user;
            END;
        END IF;

        BEGIN
            -- REVOKE ... ON TABLE vale tambien para vistas y tablas foraneas
            EXECUTE format('REVOKE ALL ON TABLE public.%I FROM %s', obj.relname, api_roles);
        EXCEPTION WHEN insufficient_privilege THEN
            RAISE WARNING 'StreamBox: no se pudieron retirar los privilegios de public.% a % (rol %): puede seguir ABIERTA a la API publica de Supabase', obj.relname, api_roles, current_user;
        END;
    END LOOP;

    -- Paso 3a: secuencias (las identidades de los id; con INSERT/UPDATE sobre
    -- la tabla, quien tuviera USAGE podria avanzar el contador).
    FOR obj IN
        SELECT c.relname
          FROM pg_class c
          JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'public'
           AND c.relkind = 'S'
           AND NOT EXISTS (SELECT 1 FROM pg_depend d
                            WHERE d.classid = 'pg_class'::regclass
                              AND d.objid = c.oid
                              AND d.deptype = 'e')
         ORDER BY c.relname
    LOOP
        BEGIN
            EXECUTE format('REVOKE ALL ON SEQUENCE public.%I FROM %s', obj.relname, api_roles);
        EXCEPTION WHEN insufficient_privilege THEN
            RAISE WARNING 'StreamBox: no se pudieron retirar los privilegios de la secuencia public.% a % (rol %)', obj.relname, api_roles, current_user;
        END;
    END LOOP;

    -- Paso 3b: funciones, procedimientos y agregados. Se revoca tambien a PUBLIC
    -- (ver la cabecera). Desde la V5, StreamBox define dos: streambox_unaccent y
    -- streambox_word_similarity (busqueda por titulo); este paso las cierra.
    FOR obj IN
        SELECT p.oid::regprocedure::text AS signature
          FROM pg_proc p
          JOIN pg_namespace n ON n.oid = p.pronamespace
         WHERE n.nspname = 'public'
           AND NOT EXISTS (SELECT 1 FROM pg_depend d
                            WHERE d.classid = 'pg_proc'::regclass
                              AND d.objid = p.oid
                              AND d.deptype = 'e')
         ORDER BY 1
    LOOP
        BEGIN
            EXECUTE format('REVOKE ALL ON ROUTINE %s FROM PUBLIC, %s', obj.signature, api_roles);
        EXCEPTION WHEN insufficient_privilege THEN
            RAISE WARNING 'StreamBox: no se pudieron retirar los privilegios de la funcion % (rol %)', obj.signature, current_user;
        END;
    END LOOP;

    -- Paso 4: privilegios por defecto para lo que se cree en el futuro. Un
    -- ALTER DEFAULT PRIVILEGES afecta solo a los objetos que cree el rol
    -- indicado, asi que se aplica a cada rol que pueda crearlos: el que ejecuta
    -- Flyway, los propietarios de las tablas actuales y `postgres` (el rol que
    -- Supabase configura con ALTER DEFAULT PRIVILEGES ... GRANT ALL TO anon...).
    -- Cambiar los de OTRO rol exige ser miembro suyo: si no, 42501 y aviso.
    FOR owner_role IN
        SELECT r FROM (
            SELECT current_user::text AS r
            UNION
            SELECT pg_get_userbyid(c.relowner)::text
              FROM pg_class c
              JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
            UNION
            SELECT rolname::text FROM pg_roles WHERE rolname = 'postgres'
        ) roles
        ORDER BY r
    LOOP
        BEGIN
            EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public REVOKE ALL ON TABLES FROM %s', owner_role, api_roles);
            EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public REVOKE ALL ON SEQUENCES FROM %s', owner_role, api_roles);
            EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public REVOKE ALL ON FUNCTIONS FROM %s', owner_role, api_roles);
        EXCEPTION WHEN insufficient_privilege THEN
            RAISE WARNING 'StreamBox: no se pudieron cambiar los privilegios por defecto del rol % (ejecuta %): sus futuras tablas en public podrian nacer abiertas a la API publica de Supabase hasta el proximo arranque', owner_role, current_user;
        END;
    END LOOP;
END
$callback$;
