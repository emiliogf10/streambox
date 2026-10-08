package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.emilio.streambox.StreamboxApplication;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.UserRepository;

/**
 * Cierre automático de la API pública de Supabase (pista NV-4 de la auditoría
 * de seguridad) sobre un <b>PostgreSQL real</b>.
 *
 * <h2>Qué se prueba</h2>
 * <p>
 * El callback {@code db/callback/postgresql/afterMigrate__close_public_api.sql}
 * debe dejar todas las tablas de {@code public} con RLS activado y sin ningún
 * privilegio para los roles {@code anon} y {@code authenticated} de Supabase,
 * <b>tras cada {@code migrate}</b> (también el que no tiene nada que migrar), sin
 * romper a la aplicación, que conecta con el propietario de las tablas.
 * </p>
 *
 * <h2>Por qué un contenedor propio</h2>
 * <p>
 * Los roles son de todo el clúster, no de una base de datos. Crear {@code anon}
 * y {@code authenticated} en el contenedor compartido de
 * {@link PostgresIntegrationTestSupport} cambiaría el comportamiento del callback
 * en el resto de tests de esa suite. Por eso este test usa su propio contenedor
 * "estilo Supabase" y, para el caso sin roles, el compartido (donde no existen).
 * </p>
 *
 * <h2>Cómo imita a Supabase</h2>
 * <ul>
 *   <li>El dueño de la base es un rol <b>sin</b> privilegios de superusuario
 *       ({@code streambox_owner}), como {@code postgres} en Supabase; un
 *       superusuario se salta RLS siempre y no probaría nada.</li>
 *   <li>Antes de migrar, ese rol ejecuta
 *       {@code ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO anon, authenticated}
 *       (y lo mismo para secuencias y funciones), que es lo que hace Supabase:
 *       cada tabla nueva nace abierta.</li>
 * </ul>
 *
 * <p>
 * Cada test crea su propia base de datos dentro del contenedor.
 * </p>
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresPublicApiClosureIntegrationTest {

    private static final String OWNER = "streambox_owner";
    private static final String OWNER_PASSWORD = "owner-pass-solo-tests";

    /** Privilegios de tabla cuya presencia en cualquiera de ellos cuenta como "abierta". */
    private static final String TABLE_PRIVILEGES = "SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER";

    /** Tablas que crean V1 a V3 más el historial de Flyway. */
    private static final Set<String> KNOWN_TABLES = Set.of("users", "genres", "movies", "movie_genres",
            "user_favorite_movies", "series", "series_genres", "episodes", "user_favorite_series",
            "flyway_schema_history");

    private static PostgreSQLContainer supabaseLike;

    /**
     * Contenedor con los roles de Supabase. Se crea de forma perezosa (como en
     * {@link PostgresIntegrationTestSupport}) para no tocar Docker si la clase
     * se omite por no haberlo.
     */
    private static synchronized PostgreSQLContainer supabaseLike() {
        if (supabaseLike == null) {
            supabaseLike = new PostgreSQLContainer(PostgresIntegrationTestSupport.POSTGRES_IMAGE);
        }
        supabaseLike.start();
        return supabaseLike;
    }

    @BeforeAll
    static void createSupabaseRoles() throws SQLException {
        try (Connection connection = admin(supabaseLike(), "postgres");
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE anon NOLOGIN");
            statement.execute("CREATE ROLE authenticated NOLOGIN");
            statement.execute("CREATE ROLE otro_rol NOLOGIN");
            statement.execute("CREATE ROLE " + OWNER + " LOGIN PASSWORD '" + OWNER_PASSWORD + "'");
        }
    }

    // ------------------------------------------------------------------
    // Control: sin el callback el entorno SÍ está abierto
    // ------------------------------------------------------------------

    /**
     * Control del propio test: con solo las migraciones (sin el callback) las
     * tablas quedan abiertas a {@code anon}, que puede leer {@code users}. Si
     * este test fallara, el entorno no imitaría a Supabase y los demás no
     * demostrarían nada. Es también la demostración de que el resto de casos
     * fallan sin el callback.
     */
    @Test
    void sinElCallbackLasTablasQuedanAbiertasALaApiPublica() throws Exception {
        String db = createSupabaseLikeDatabase();

        flyway(db, false).migrate();

        assertTrue(openObjects(db, "anon").containsAll(KNOWN_TABLES.stream().map(t -> "tabla:" + t).toList()),
                "sin callback anon conserva privilegios: " + openObjects(db, "anon"));
        assertEquals(0, countAs(db, "anon", "users"), "anon llega a leer users (vacía, pero sin error de permisos)");
        assertFalse(tablesWithoutRls(db).isEmpty(), "sin callback no hay RLS en ninguna tabla");
    }

    // ------------------------------------------------------------------
    // (a) Todas las tablas de V1-V3 quedan cerradas
    // ------------------------------------------------------------------

    /**
     * Caso (a): tras migrar, todas las tablas de {@code public} (incluido el
     * historial de Flyway) tienen RLS activado y {@code anon} y
     * {@code authenticated} no conservan ningún privilegio sobre tablas,
     * secuencias ni funciones; y una consulta real como {@code anon} recibe
     * "permiso denegado".
     */
    @Test
    void trasMigrarTodasLasTablasTienenRlsYNingunPrivilegioParaLosRolesDeLaApi() throws Exception {
        String db = createSupabaseLikeDatabase();
        // Una función de otra "extensión de Supabase": nace con EXECUTE para PUBLIC y para anon
        ownerExecute(db, "CREATE FUNCTION public.rpc_expuesta() RETURNS int LANGUAGE sql AS 'SELECT 1'");

        flyway(db, true).migrate();

        assertEquals(List.of(), tablesWithoutRls(db), "todas las tablas de public deben tener RLS");
        assertTrue(allTables(db).containsAll(KNOWN_TABLES), "faltan tablas: " + allTables(db));
        assertEquals(List.of(), openObjects(db, "anon"));
        assertEquals(List.of(), openObjects(db, "authenticated"));
        for (String role : List.of("anon", "authenticated")) {
            for (String table : KNOWN_TABLES) {
                assertEquals("42501", sqlStateAs(db, role, "SELECT * FROM public." + table),
                        role + " no debe poder leer " + table);
            }
        }
    }

    // ------------------------------------------------------------------
    // (b) Tablas futuras
    // ------------------------------------------------------------------

    /**
     * Caso (b), primera barrera: una tabla creada DESPUÉS del callback nace sin
     * privilegios para la API porque el callback retiró los privilegios por
     * defecto del rol propietario (y Supabase los reponía en cada tabla nueva).
     */
    @Test
    void unaTablaCreadaDespuesNaceCerradaPorLosPrivilegiosPorDefecto() throws Exception {
        String db = createSupabaseLikeDatabase();
        flyway(db, true).migrate();

        ownerExecute(db, "CREATE TABLE public.creada_a_mano (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY)");

        assertEquals(List.of(), openObjects(db, "anon"), "ni la tabla ni su secuencia deben nacer abiertas");
        assertEquals(List.of(), openObjects(db, "authenticated"));
    }

    /**
     * Caso (b), segunda barrera: una migración futura (V4) crea una tabla
     * mientras los privilegios por defecto siguen siendo los de Supabase (aquí se
     * restablecen a propósito, como si alguien los hubiera cambiado desde el
     * panel). El callback del propio {@code migrate} que aplica V4 la cierra: es
     * la razón por la que ya no hay que acordarse de ejecutar un script a mano.
     */
    @Test
    void unaMigracionFuturaQuedaCerradaPorElCallbackDelMismoMigrate(@TempDir Path futureMigrations) throws Exception {
        String db = createSupabaseLikeDatabase();
        flyway(db, true).migrate();
        grantSupabaseDefaultPrivileges(db); // Supabase (o una persona) vuelve a dejar los privilegios por defecto abiertos
        Files.writeString(futureMigrations.resolve("V4__tabla_futura.sql"),
                "CREATE TABLE tabla_futura (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, nota VARCHAR(20));",
                StandardCharsets.UTF_8);

        var result = flywayWithExtra(db, futureMigrations).migrate();

        assertEquals(1, result.migrationsExecuted, "debe aplicarse V4");
        assertTrue(allTables(db).contains("tabla_futura"));
        assertEquals(List.of(), tablesWithoutRls(db), "la tabla de V4 debe tener RLS");
        assertEquals(List.of(), openObjects(db, "anon"));
        assertEquals(List.of(), openObjects(db, "authenticated"));
    }

    /**
     * Caso (b), tercera variante: una tabla creada "a mano" sin que haya
     * migración nueva y con los privilegios abiertos se cierra en el siguiente
     * {@code migrate} aunque Flyway no tenga NADA que aplicar (el callback
     * {@code afterMigrate} corre siempre, no solo cuando hay migraciones).
     */
    @Test
    void unMigrateSinNadaQueAplicarTambienCierraLoQueSeHayaEscapado() throws Exception {
        String db = createSupabaseLikeDatabase();
        flyway(db, true).migrate();
        grantSupabaseDefaultPrivileges(db);
        ownerExecute(db, "CREATE TABLE public.se_escapo (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY)");
        assertTrue(openObjects(db, "anon").contains("tabla:se_escapo"), "precondición: nació abierta");

        assertEquals(0, flyway(db, true).migrate().migrationsExecuted, "no hay nada que migrar");

        assertEquals(List.of(), tablesWithoutRls(db));
        assertEquals(List.of(), openObjects(db, "anon"));
    }

    // ------------------------------------------------------------------
    // (c) Sin los roles de Supabase no hace nada
    // ------------------------------------------------------------------

    /**
     * Caso (c): en un PostgreSQL sin {@code anon} ni {@code authenticated}
     * (docker compose, PostgreSQL local, el contenedor compartido de los demás
     * tests) el callback no hace nada y no falla: ninguna tabla recibe RLS.
     */
    @Test
    void sinLosRolesDeSupabaseNoHaceNadaYNoFalla() throws Exception {
        PostgreSQLContainer shared = PostgresIntegrationTestSupport.postgres();
        try (Connection connection = admin(shared, "postgres");
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(
                        "SELECT count(*) FROM pg_roles WHERE rolname IN ('anon', 'authenticated')")) {
            rs.next();
            assumeTrue(rs.getInt(1) == 0, "el contenedor compartido no debe tener los roles de Supabase");
        }
        String db = createDatabase(shared, "sinroles", shared.getUsername());

        var result = flyway(shared, db, shared.getUsername(), shared.getPassword(), true).migrate();

        assertEquals(3, result.migrationsExecuted);
        assertEquals(allTables(shared, db, shared.getUsername(), shared.getPassword()),
                tablesWithoutRls(shared, db), "sin los roles no se activa RLS en ninguna tabla");
        assertEquals(0, flyway(shared, db, shared.getUsername(), shared.getPassword(), true).migrate().migrationsExecuted);
    }

    // ------------------------------------------------------------------
    // (d) La aplicación sigue funcionando con RLS activado
    // ------------------------------------------------------------------

    /**
     * Caso (d): el propietario sigue leyendo y escribiendo con RLS activado (no
     * se usa FORCE), y la aplicación real arranca contra la base ya cerrada
     * (Flyway lee {@code flyway_schema_history}, que también tiene RLS,
     * Hibernate valida el esquema) y puede guardar y leer filas. Al arrancar
     * ella misma ejecuta el callback porque {@code {vendor}} se resuelve a
     * {@code postgresql}: se comprueba que la base queda cerrada.
     */
    @Test
    void laAplicacionSigueLeyendoYEscribiendoConRlsActivado() throws Exception {
        String db = createSupabaseLikeDatabase();
        flyway(db, true).migrate();
        assertEquals(List.of(), tablesWithoutRls(db), "precondición: RLS activado");

        // Como propietario, con JDBC puro
        JdbcTemplate owner = ownerJdbc(db);
        owner.update("INSERT INTO genres (name) VALUES ('Cierre')");
        assertEquals(1, owner.queryForObject("SELECT COUNT(*) FROM genres", Integer.class),
                "el propietario ve sus filas pese a RLS sin políticas");

        // Y la aplicación real (arranque completo, mismo camino que producción)
        var postgres = supabaseLike();
        try (ConfigurableApplicationContext context = new SpringApplication(StreamboxApplication.class).run(
                "--spring.profiles.active=test",
                "--spring.datasource.url=" + url(postgres, db),
                "--spring.datasource.username=" + OWNER,
                "--spring.datasource.password=" + OWNER_PASSWORD,
                "--spring.datasource.driver-class-name=org.postgresql.Driver",
                "--server.port=0")) {
            GenreRepository genres = context.getBean(GenreRepository.class);
            assertEquals(1, genres.count(), "lee lo que había");
            Genre nuevo = new Genre();
            nuevo.setName("Desde la app");
            genres.save(nuevo);
            assertEquals(2, genres.count());
            assertEquals(0, context.getBean(UserRepository.class).count());
        }
        assertEquals(2, owner.queryForObject("SELECT COUNT(*) FROM genres", Integer.class));
        assertEquals(List.of(), tablesWithoutRls(db));
        assertEquals(List.of(), openObjects(db, "anon"));
        assertEquals("42501", sqlStateAs(db, "anon", "SELECT * FROM public.genres"));
    }

    /**
     * Una base heredada (creada antes de Flyway, sin historial) también termina
     * cerrada cuando se adopta: el callback corre tras el baseline y V1-V3.
     */
    @Test
    void unaBaseHeredadaAdoptadaTambienQuedaCerrada() throws Exception {
        String db = createSupabaseLikeDatabase();
        ownerExecute(db, "CREATE TABLE public.genres (id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,"
                + " name VARCHAR(50) NOT NULL, CONSTRAINT uk_genres_name UNIQUE (name))");
        ownerExecute(db, "INSERT INTO public.genres (name) VALUES ('Heredado')");

        flyway(db, true).migrate();

        assertEquals(List.of(), tablesWithoutRls(db));
        assertEquals(List.of(), openObjects(db, "anon"));
        assertEquals(1, ownerJdbc(db).queryForObject("SELECT COUNT(*) FROM genres", Integer.class));
    }

    // ------------------------------------------------------------------
    // (e) Idempotencia y tolerancia a la falta de privilegios
    // ------------------------------------------------------------------

    /**
     * Caso (e): el callback es idempotente: varios {@code migrate} seguidos y la
     * ejecución directa del script varias veces no fallan ni cambian el
     * resultado.
     */
    @Test
    void elCallbackEsIdempotente() throws Exception {
        String db = createSupabaseLikeDatabase();
        flyway(db, true).migrate();

        flyway(db, true).migrate();
        flyway(db, true).migrate();
        List<String> warnings1 = runCallbackScriptAsOwner(db);
        List<String> warnings2 = runCallbackScriptAsOwner(db);

        assertEquals(List.of(), warnings1, "no debe avisar de nada cuando el propietario es quien ejecuta");
        assertEquals(List.of(), warnings2);
        assertEquals(List.of(), tablesWithoutRls(db));
        assertEquals(List.of(), openObjects(db, "anon"));
        assertEquals(List.of(), openObjects(db, "authenticated"));
    }

    /**
     * Si el rol que ejecuta Flyway no es propietario de una tabla, el callback
     * NO aborta (compromiso documentado en su cabecera): avisa con
     * {@code RAISE WARNING}, cierra todo lo que sí puede y la migración termina.
     * Un error que no sea de privilegios sí abortaría, pero no se puede provocar
     * a propósito sin editar el script.
     */
    @Test
    void siFaltanPrivilegiosAvisaPeroNoImpideElArranque() throws Exception {
        String db = createSupabaseLikeDatabase();
        // Una tabla ajena: otro rol la creó en public y el propietario de la base solo la usa
        try (Connection connection = admin(supabaseLike(), db); Statement statement = connection.createStatement()) {
            statement.execute("GRANT CREATE ON SCHEMA public TO otro_rol");
            statement.execute("SET ROLE otro_rol");
            statement.execute("CREATE TABLE public.ajena (id INT)");
            statement.execute("RESET ROLE");
            statement.execute("GRANT ALL ON public.ajena TO " + OWNER);
            statement.execute("GRANT ALL ON public.ajena TO anon");
        }

        flyway(db, true).migrate(); // no debe lanzar

        List<String> warnings = runCallbackScriptAsOwner(db);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("public.ajena")), "debe avisar de la tabla ajena: " + warnings);
        assertEquals(List.of("ajena"), tablesWithoutRls(db), "solo la ajena queda sin RLS (no es del rol que migra)");
        // Lo propio sí quedó cerrado
        assertFalse(openObjects(db, "anon").contains("tabla:users"));
        assertEquals("42501", sqlStateAs(db, "anon", "SELECT * FROM public.users"));
    }

    /**
     * El callback vive en la ubicación del motor, y esa ubicación está declarada
     * en {@code application.properties}: si alguien la quita o la renombra, la
     * aplicación dejaría de cerrar la API sin que ningún otro test lo notara.
     */
    @Test
    void laConfiguracionDeFlywayDeclaraLaUbicacionDelCallbackPorMotor() throws IOException {
        String locations = applicationProperties().getProperty("spring.flyway.locations");

        assertEquals(List.of("classpath:db/migration", "classpath:db/callback/{vendor}"),
                Arrays.stream(locations.split(",")).map(String::trim).toList());
        assertTrue(new ClassPathResource("db/callback/postgresql/afterMigrate__close_public_api.sql").exists());
    }

    /**
     * El respaldo manual {@code docs/supabase-seguridad.sql} contiene el mismo
     * bloque {@code DO} que el callback (si se editara uno y no el otro, el
     * respaldo quedaría obsoleto sin que nadie lo notara) y, ejecutado a mano
     * sobre una base que Flyway migró SIN callback, la deja igual de cerrada.
     */
    @Test
    void elScriptManualDeRespaldoEsLaMismaLogicaYCierraUnaBaseAbierta() throws Exception {
        Path manual = Path.of("..", "docs", "supabase-seguridad.sql");
        assumeTrue(Files.exists(manual), "solo se comprueba con el repositorio completo (carpeta docs/)");
        String manualScript = Files.readString(manual, StandardCharsets.UTF_8).replace("\r\n", "\n");
        String callbackScript = callbackScript().replace("\r\n", "\n");
        String marker = "DO $callback$";
        assertEquals(callbackScript.substring(callbackScript.indexOf(marker)).strip(),
                manualScript.substring(manualScript.indexOf(marker)).strip(),
                "el bloque DO de docs/supabase-seguridad.sql debe ser copia exacta del callback");

        String db = createSupabaseLikeDatabase();
        flyway(db, false).migrate(); // sin callback: todo abierto
        assertFalse(tablesWithoutRls(db).isEmpty());
        try (Connection connection = DriverManager.getConnection(url(supabaseLike(), db), OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute(manualScript);
            statement.execute(manualScript); // idempotente
        }
        assertEquals(List.of(), tablesWithoutRls(db));
        assertEquals(List.of(), openObjects(db, "anon"));
        assertEquals(List.of(), openObjects(db, "authenticated"));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static String callbackScript() throws IOException {
        return new ClassPathResource("db/callback/postgresql/afterMigrate__close_public_api.sql")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private static Properties applicationProperties() throws IOException {
        return PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
    }

    /** Conexión de administración (superusuario del contenedor) a una base. */
    private static Connection admin(PostgreSQLContainer postgres, String database) throws SQLException {
        return DriverManager.getConnection(url(postgres, database), postgres.getUsername(), postgres.getPassword());
    }

    private static String url(PostgreSQLContainer postgres, String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database
                + "?loggerLevel=OFF";
    }

    /** Crea una base de datos nueva cuyo propietario es {@code owner}. */
    private static String createDatabase(PostgreSQLContainer postgres, String prefix, String owner) throws SQLException {
        String name = prefix + "_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = admin(postgres, "postgres"); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + name + " OWNER " + owner);
        }
        return name;
    }

    /**
     * Base de datos "estilo Supabase" en el contenedor con roles: propiedad de un
     * rol sin superusuario, con los privilegios por defecto que concede Supabase.
     */
    private static String createSupabaseLikeDatabase() throws SQLException {
        String name = createDatabase(supabaseLike(), "closure", OWNER);
        grantSupabaseDefaultPrivileges(name);
        return name;
    }

    /** Lo que hace Supabase: cada objeto nuevo del propietario nace con todo para anon y authenticated. */
    private static void grantSupabaseDefaultPrivileges(String database) throws SQLException {
        ownerExecute(database, "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO anon, authenticated");
        ownerExecute(database, "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO anon, authenticated");
        ownerExecute(database, "ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON FUNCTIONS TO anon, authenticated");
    }

    private static void ownerExecute(String database, String sql) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url(supabaseLike(), database), OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static JdbcTemplate ownerJdbc(String database) {
        return new JdbcTemplate(new DriverManagerDataSource(url(supabaseLike(), database), OWNER, OWNER_PASSWORD));
    }

    private static JdbcTemplate adminJdbc(PostgreSQLContainer postgres, String database, String user, String password) {
        return new JdbcTemplate(new DriverManagerDataSource(url(postgres, database), user, password));
    }

    /** Flyway como el propietario de la base "estilo Supabase". */
    private static Flyway flyway(String database, boolean withCallback) throws IOException {
        return flyway(supabaseLike(), database, OWNER, OWNER_PASSWORD, withCallback);
    }

    /**
     * Flyway configurado como la aplicación: las ubicaciones y los valores de
     * baseline salen de {@code application.properties} (con {@code {vendor}}
     * resuelto a {@code postgresql}), para que el test no se desincronice. Sin
     * callback, solo la ubicación de las migraciones.
     */
    private static Flyway flyway(PostgreSQLContainer postgres, String database, String user, String password,
            boolean withCallback) throws IOException {
        return configure(postgres, database, user, password, withCallback, null).load();
    }

    /** Igual que {@link #flyway} con callback, más una carpeta con migraciones "futuras". */
    private static Flyway flywayWithExtra(String database, Path extraMigrations) throws IOException {
        return configure(supabaseLike(), database, OWNER, OWNER_PASSWORD, true, extraMigrations).load();
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration configure(PostgreSQLContainer postgres,
            String database, String user, String password, boolean withCallback, Path extraMigrations)
            throws IOException {
        Properties properties = applicationProperties();
        List<String> locations = new ArrayList<>();
        for (String location : properties.getProperty("spring.flyway.locations").split(",")) {
            String resolved = location.trim().replace("{vendor}", "postgresql");
            if (withCallback || !resolved.contains("db/callback")) {
                locations.add(resolved);
            }
        }
        if (extraMigrations != null) {
            locations.add("filesystem:" + extraMigrations.toAbsolutePath());
        }
        return Flyway.configure()
                .dataSource(url(postgres, database), user, password)
                .locations(locations.toArray(String[]::new))
                .baselineOnMigrate(Boolean.parseBoolean(properties.getProperty("spring.flyway.baseline-on-migrate")))
                .baselineVersion(properties.getProperty("spring.flyway.baseline-version"));
    }

    /** Nombres de todas las tablas de {@code public}. */
    private static List<String> allTables(String database) throws SQLException {
        return allTables(supabaseLike(), database, supabaseLike().getUsername(), supabaseLike().getPassword());
    }

    private static List<String> allTables(PostgreSQLContainer postgres, String database, String user, String password) {
        return adminJdbc(postgres, database, user, password).queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename", String.class);
    }

    /** Tablas de {@code public} con RLS desactivado (debe estar vacía tras el callback). */
    private static List<String> tablesWithoutRls(String database) throws SQLException {
        return tablesWithoutRls(supabaseLike(), database);
    }

    private static List<String> tablesWithoutRls(PostgreSQLContainer postgres, String database) {
        return adminJdbc(postgres, database, postgres.getUsername(), postgres.getPassword()).queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND NOT rowsecurity ORDER BY tablename",
                String.class);
    }

    /**
     * Objetos de {@code public} sobre los que el rol conserva algún privilegio:
     * {@code tabla:x}, {@code secuencia:x} o {@code funcion:x}. Vacío = cerrado.
     * Las funciones se miran con {@code has_function_privilege}, que cuenta
     * también lo heredado de PUBLIC.
     */
    private static List<String> openObjects(String database, String role) throws SQLException {
        PostgreSQLContainer postgres = supabaseLike();
        JdbcTemplate jdbc = adminJdbc(postgres, database, postgres.getUsername(), postgres.getPassword());
        List<String> open = new ArrayList<>();
        open.addAll(jdbc.queryForList(
                "SELECT 'tabla:' || c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = 'public' AND c.relkind IN ('r','p','v','m','f')"
                        + " AND has_table_privilege(?, c.oid, '" + TABLE_PRIVILEGES + "') ORDER BY 1",
                String.class, role));
        open.addAll(jdbc.queryForList(
                "SELECT 'secuencia:' || c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = 'public' AND c.relkind = 'S'"
                        // CASE: el planificador puede evaluar la función antes que el filtro de relkind
                        + " AND CASE WHEN c.relkind = 'S' THEN has_sequence_privilege(?, c.oid, 'USAGE,SELECT,UPDATE')"
                        + " ELSE false END ORDER BY 1",
                String.class, role));
        open.addAll(jdbc.queryForList(
                "SELECT 'funcion:' || p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace"
                        + " WHERE n.nspname = 'public' AND has_function_privilege(?, p.oid, 'EXECUTE') ORDER BY 1",
                String.class, role));
        return open;
    }

    /** SQLSTATE que recibe un rol de la API al ejecutar una consulta ({@code null} si funciona). */
    private static String sqlStateAs(String database, String role, String sql) throws SQLException {
        try (Connection connection = admin(supabaseLike(), database); Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE " + role);
            try {
                statement.execute(sql);
                return null;
            } catch (SQLException e) {
                return e.getSQLState();
            }
        }
    }

    /** Filas que ve un rol de la API en una tabla (falla si no tiene permiso). */
    private static int countAs(String database, String role, String table) throws SQLException {
        try (Connection connection = admin(supabaseLike(), database); Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE " + role);
            try (ResultSet rs = statement.executeQuery("SELECT count(*) FROM public." + table)) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Ejecuta el script del callback tal cual, con la conexión del propietario, y
     * devuelve los mensajes {@code RAISE WARNING} recibidos. Es la misma ruta de
     * ejecución que usa Flyway (un único comando enviado al servidor).
     */
    private static List<String> runCallbackScriptAsOwner(String database) throws IOException, SQLException {
        String script = callbackScript();
        List<String> warnings = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(url(supabaseLike(), database), OWNER, OWNER_PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute(script);
            for (SQLWarning w = statement.getWarnings(); w != null; w = w.getNextWarning()) {
                warnings.add(w.getMessage());
            }
        }
        return warnings;
    }
}
