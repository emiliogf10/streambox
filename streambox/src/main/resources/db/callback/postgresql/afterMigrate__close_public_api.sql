-- ===================================================================
-- CALLBACK afterMigrate (SOLO PostgreSQL): cierra la API publica de Supabase.
--
-- NO es una migracion versionada: no lleva version, no queda en
-- flyway_schema_history, no tiene checksum y Flyway lo ejecuta tras CADA
-- `migrate`, incluidos los arranques en los que no hay nada que migrar. Por eso
-- tambien protege las tablas que cree una migracion futura (V4, V5...) sin que
-- nadie tenga que acordarse de nada. Vive en una ubicacion propia del motor
-- (`classpath:db/callback/{vendor}`, ver spring.flyway.locations): con H2, la
-- base de los tests, Spring Boot busca db/callback/h2, que no existe, y no se
-- ejecuta nada. ENABLE ROW LEVEL SECURITY y los roles de Supabase no existen en H2.
--
-- EL PROBLEMA
--   Supabase publica automaticamente una API REST (PostgREST) sobre el esquema
--   `public` con los roles `anon` (clave publica) y `authenticated` (usuarios de
--   Supabase Auth). Las tablas que crea Flyway viven en `public`, y Supabase les
--   concede privilegios a esos roles por defecto (ALTER DEFAULT PRIVILEGES). La
--   aplicacion NO usa esa API: conecta con el rol propietario (`postgres`), que
--   se salta RLS. Pero la API es una segunda puerta a las mismas tablas (`users`
--   con emails y hashes incluidos) y hasta ahora solo se cerraba a mano con
--   docs/supabase-seguridad.sql, que protege unicamente las tablas existentes
--   el dia que se ejecuta: las tablas nuevas de una migracion nacian abiertas.
--
-- QUE HACE (defensa en profundidad, en este orden)
--   1. Si NO existen los roles `anon` ni `authenticated` (docker compose,
--      PostgreSQL local, Testcontainers) no hace nada y no falla.
--   2. En cada tabla de `public` (incluida flyway_schema_history): activa RLS
--      SIN politicas (con RLS y sin politicas esos roles no ven ninguna fila) y
--      les retira todos los privilegios. Tambien retira los de las vistas.
--      NO usa FORCE ROW LEVEL SECURITY: el propietario debe seguir saltandose
--      RLS, porque es el rol con el que conecta la aplicacion y Flyway.
--   3. Retira sus privilegios sobre secuencias y funciones/procedimientos de
--      `public`. En funciones se retira tambien a PUBLIC, porque PostgreSQL
--      concede EXECUTE a PUBLIC por defecto y, sin eso, revocar solo a `anon`
--      no cierra nada (heredan de PUBLIC).
--   4. ALTER DEFAULT PRIVILEGES: los objetos que se creen DESPUES por el rol que
--      ejecuta Flyway, por los propietarios de las tablas y por `postgres`
--      nacen sin privilegios para esos roles. Es una segunda barrera: el propio
--      callback del siguiente arranque cierra lo que se haya escapado.
--   No toca objetos que pertenecen a una extension (p. ej. pgcrypto instalada en
--   `public`): no son de StreamBox y revocar sus funciones romperia la extension.
--
-- IDEMPOTENTE: se puede ejecutar todas las veces que haga falta. Solo hace
-- ALTER TABLE donde RLS aun no esta activo (ese comando pide un bloqueo
-- exclusivo de la tabla, y no queremos pedirlo en cada arranque).
--
-- COMPROMISO: FALLAR CERRADO O NO BLOQUEAR EL ARRANQUE
--   Si una sentencia falla por falta de privilegios (42501: p. ej. el rol que
--   ejecuta Flyway no es propietario de una tabla heredada, o es un rol de
--   pooler sin permiso para cambiar los privilegios por defecto de otro rol),
--   NO se aborta: se emite un RAISE WARNING que Flyway muestra en el log y se
--   sigue con el resto de objetos. Se prefiere esto a "fallar cerrado" (abortar
--   y que la aplicacion no arranque) porque este callback endurece algo que ya
--   estaba abierto antes de el, no es un requisito de funcionamiento, y un fallo
--   de permisos NO se arregla reintentando: una aplicacion que no arranca por
--   esto convertiria un fallo de seguridad en una caida total sin ningun
--   beneficio. A cambio hay que VIGILAR el log: un "WARNING" de este callback
--   significa que algo sigue abierto. Solo se captura el error 42501; cualquier
--   otro (un error de verdad) SI aborta el arranque. Para comprobar el
--   resultado: docs/supabase-seguridad.sql (apartado COMO COMPROBARLO).
--
-- IMPACTO EN DATOS EXISTENTES: ninguno. No modifica filas ni columnas. La
-- aplicacion no se ve afectada porque conecta con el propietario de las tablas.
-- Si algun dia se conecta con un rol que NO sea propietario ni tenga BYPASSRLS,
-- dejara de ver filas: RLS sin politicas lo bloquea todo para ese rol.
--
-- Cuidado al editar: Flyway sustituye los marcadores de posicion de la forma
-- dolar + llave (tambien dentro de comentarios), asi que no los escribas; y el
-- cuerpo de DO va entre $callback$ para que sus `;` no corten la sentencia.
-- ===================================================================

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
    -- (ver la cabecera). StreamBox no define ninguna: es solo para lo futuro.
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
