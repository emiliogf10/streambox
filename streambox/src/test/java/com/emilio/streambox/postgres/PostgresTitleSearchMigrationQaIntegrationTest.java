package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Revisión de QA de la migración V5 de PostgreSQL
 * ({@code db/vendor/postgresql/V5__accent_insensitive_title_search.sql}) en los
 * escenarios que no cubrían los tests del autor.
 *
 * <ul>
 * <li><b>Un fallo no deja la base a medias.</b> La cabecera de la V5 promete
 * que, si algo falla, "la transacción deshace todo". Se provoca un fallo en la
 * <em>última</em> sentencia del bloque {@code DO} (el segundo índice ya existe)
 * y se comprueba que no queda nada de lo anterior: ni las extensiones, ni el
 * esquema {@code extensions}, ni las funciones, ni el primer índice. Y que, una
 * vez resuelta la causa, basta volver a arrancar: no hace falta
 * {@code flyway repair} ni limpiar a mano.</li>
 * <li><b>Extensiones ya instaladas en otro esquema.</b> La V5 dice que, si
 * {@code unaccent} o {@code pg_trgm} ya estaban instaladas (p. ej. en
 * {@code public}, lo habitual en un PostgreSQL propio), no se mueven y se usan
 * donde estén. Se prueba con una y con las dos en {@code public}.</li>
 * </ul>
 *
 * <p>
 * Cada test crea su propia base ({@code CREATE DATABASE}) en el contenedor
 * compartido, como {@link PostgresBaselineAdoptionIntegrationTest}, y aplica las
 * migraciones con las ubicaciones reales de {@code spring.flyway.locations}.
 * </p>
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresTitleSearchMigrationQaIntegrationTest {

    /**
     * Si la V5 falla a mitad, la transacción la deshace entera y un segundo
     * {@code migrate}, ya sin la causa del fallo, la aplica sin intervención.
     */
    @Test
    void unFalloDeLaV5NoDejaNadaAMediasYSePuedeReintentar() throws Exception {
        String db = createDatabase();
        flyway(db).target(MigrationVersion.fromVersion("4")).load().migrate();
        JdbcTemplate jdbc = jdbc(db);
        // Choca con el ÚLTIMO CREATE INDEX de la V5: todo lo anterior ya se habrá ejecutado
        jdbc.execute("CREATE INDEX idx_series_title_search ON series (title)");

        assertThrows(FlywayException.class, () -> flyway(db).load().migrate());

        assertEquals(List.of(), jdbc.queryForList(
                "SELECT extname FROM pg_extension WHERE extname IN ('unaccent', 'pg_trgm')", String.class),
                "las extensiones creadas por la V5 deben deshacerse");
        assertEquals(0, count(jdbc, "SELECT count(*) FROM pg_namespace WHERE nspname = 'extensions'"),
                "el esquema extensions creado por la V5 debe deshacerse");
        assertEquals(0, count(jdbc, "SELECT count(*) FROM pg_proc WHERE proname LIKE 'streambox\\_%'"),
                "las funciones de la V5 deben deshacerse");
        assertEquals(0, count(jdbc, "SELECT count(*) FROM pg_class WHERE relname = 'idx_movies_title_search'"),
                "el primer índice de la V5 debe deshacerse");
        assertEquals(0, count(jdbc, "SELECT count(*) FROM flyway_schema_history WHERE version = '5' AND success"),
                "la V5 no puede quedar registrada como aplicada");
        assertEquals("4", jdbc.queryForObject(
                "SELECT max(version::int)::text FROM flyway_schema_history WHERE success", String.class));

        // Resuelta la causa, el siguiente arranque la aplica sin repair
        jdbc.execute("DROP INDEX idx_series_title_search");
        assertEquals(1, flyway(db).load().migrate().migrationsExecuted);
        assertEquals("Manana", jdbc.queryForObject("SELECT streambox_unaccent('Mañana')", String.class));
    }

    /**
     * {@code unaccent} ya instalada en {@code public} (y {@code pg_trgm} no): la
     * V5 la deja donde está, instala {@code pg_trgm} en {@code extensions} y las
     * funciones de StreamBox usan cada una desde su esquema.
     */
    @Test
    void conUnaccentYaInstaladaEnPublicLaUsaSinMoverla() throws Exception {
        String db = createDatabase();
        JdbcTemplate jdbc = jdbc(db);
        jdbc.execute("CREATE EXTENSION unaccent");

        flyway(db).load().migrate();

        assertEquals(List.of("pg_trgm:extensions", "unaccent:public"), extensionSchemas(jdbc));
        assertSearchFunctionsWork(jdbc);
    }

    /**
     * Las dos extensiones ya en {@code public}: la V5 no crea el esquema
     * {@code extensions} y todo funciona con las instaladas.
     */
    @Test
    void conLasDosExtensionesYaEnPublicNoCreaElEsquemaExtensions() throws Exception {
        String db = createDatabase();
        JdbcTemplate jdbc = jdbc(db);
        jdbc.execute("CREATE EXTENSION unaccent");
        jdbc.execute("CREATE EXTENSION pg_trgm");

        flyway(db).load().migrate();

        assertEquals(List.of("pg_trgm:public", "unaccent:public"), extensionSchemas(jdbc));
        assertEquals(0, count(jdbc, "SELECT count(*) FROM pg_namespace WHERE nspname = 'extensions'"));
        assertSearchFunctionsWork(jdbc);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Las dos funciones responden y la búsqueda usa la expresión del índice. */
    private static void assertSearchFunctionsWork(JdbcTemplate jdbc) {
        assertEquals("manana", jdbc.queryForObject("SELECT streambox_unaccent(lower('Mañana'))", String.class));
        assertEquals(1.0, jdbc.queryForObject(
                "SELECT streambox_word_similarity('matrix', 'the matrix')", Double.class), 0.0001);
        jdbc.execute("INSERT INTO movies (title, description, duration, release_year, image_url, video_url, created_at)"
                + " VALUES ('Mañana', 'd', 100, 1999, 'https://e.com/i.jpg', 'https://e.com/v.mp4', now())");
        assertEquals(List.of("Mañana"), jdbc.queryForList(
                "SELECT title FROM movies WHERE streambox_unaccent(lower(title)) LIKE '%manana%'", String.class));
    }

    private static List<String> extensionSchemas(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT e.extname || ':' || n.nspname FROM pg_extension e"
                + " JOIN pg_namespace n ON n.oid = e.extnamespace"
                + " WHERE e.extname IN ('unaccent', 'pg_trgm') ORDER BY e.extname", String.class);
    }

    private static int count(JdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    private static String createDatabase() throws SQLException {
        String name = "v5qa_" + UUID.randomUUID().toString().replace("-", "");
        var postgres = PostgresIntegrationTestSupport.postgres();
        try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
                Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return name;
    }

    private static String url(String database) {
        var postgres = PostgresIntegrationTestSupport.postgres();
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database
                + "?loggerLevel=OFF";
    }

    private static JdbcTemplate jdbc(String database) {
        var postgres = PostgresIntegrationTestSupport.postgres();
        return new JdbcTemplate(new DriverManagerDataSource(url(database), postgres.getUsername(), postgres.getPassword()));
    }

    /**
     * Flyway configurado como la aplicación: ubicaciones de
     * {@code spring.flyway.locations} con {@code {vendor}} = {@code postgresql}
     * (así entra la V5 de PostgreSQL) y los valores {@code baseline-*}.
     */
    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(String database) throws IOException {
        Properties properties = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        String[] locations = Arrays.stream(properties.getProperty("spring.flyway.locations").split(","))
                .map(location -> location.trim().replace("{vendor}", "postgresql"))
                .toArray(String[]::new);
        var postgres = PostgresIntegrationTestSupport.postgres();
        return Flyway.configure()
                .dataSource(url(database), postgres.getUsername(), postgres.getPassword())
                .locations(locations)
                .baselineOnMigrate(Boolean.parseBoolean(properties.getProperty("spring.flyway.baseline-on-migrate")))
                .baselineVersion(properties.getProperty("spring.flyway.baseline-version"));
    }
}
