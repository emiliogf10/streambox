package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.emilio.streambox.migration.TrailerUrlMigrationScenario;

/**
 * Movimiento de datos de la migración {@code V6} (tráiler a {@code trailer_url}
 * y vídeo opcional) contra un <b>PostgreSQL real</b>, partiendo de una base con
 * datos en la {@code V5}: es lo que ocurrirá en la base del autor en Supabase
 * la primera vez que arranque la aplicación con la {@code V6}.
 *
 * <p>
 * Mismo escenario que en H2 ({@link TrailerUrlMigrationScenario}). Cada test crea
 * su propia base ({@code CREATE DATABASE}) en el contenedor compartido, como
 * {@link PostgresTitleSearchMigrationQaIntegrationTest}, y aplica las
 * migraciones con las ubicaciones reales de {@code spring.flyway.locations}
 * ({@code {vendor}} = {@code postgresql}: entran la V5 de PostgreSQL y el
 * callback, que sin los roles de Supabase no hace nada).
 * </p>
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresTrailerUrlMigrationIntegrationTest {

    /** SQLSTATE de PostgreSQL para una violación de {@code CHECK}. */
    private static final String CHECK_VIOLATION = "23514";

    /**
     * Versión hasta la que se migra al probar la V6: así el test mide solo la
     * V6 y no cambia cada vez que se añade una migración posterior.
     */
    private static final MigrationVersion V6 = MigrationVersion.fromVersion("6");

    @Test
    void laV6MueveLosTrailersDeYoutubeYDejaIntactoLoDemas() throws Exception {
        String db = createDatabase();
        flyway(db).target(MigrationVersion.fromVersion("5")).load().migrate();
        JdbcTemplate jdbc = jdbc(db);
        TrailerUrlMigrationScenario.seedAtV5(jdbc);

        var result = flyway(db).target(V6).load().migrate();

        assertEquals(1, result.migrationsExecuted, "solo debe quedar por aplicar la V6");
        TrailerUrlMigrationScenario.assertMovedByV6(jdbc);
        assertEquals(0, flyway(db).target(V6).load().migrate().migrationsExecuted, "una segunda ejecución no hace nada");
        flyway(db).target(V6).load().validate();
    }

    /**
     * Si la V6 falla a mitad, PostgreSQL la deshace entera (el DDL también es
     * transaccional): ni columnas nuevas ni datos movidos. Se fuerza el fallo en
     * la ÚLTIMA sentencia (la restricción de series ya existe con ese nombre),
     * después de que los UPDATE ya se hayan ejecutado.
     */
    @Test
    void unFalloDeLaV6NoDejaDatosMovidosAMedias() throws Exception {
        String db = createDatabase();
        flyway(db).target(MigrationVersion.fromVersion("5")).load().migrate();
        JdbcTemplate jdbc = jdbc(db);
        TrailerUrlMigrationScenario.seedAtV5(jdbc);
        jdbc.execute("ALTER TABLE series ADD CONSTRAINT ck_series_trailer_url CHECK (true)");

        assertThrows(org.flywaydb.core.api.FlywayException.class, () -> flyway(db).target(V6).load().migrate());

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND column_name = 'trailer_url'", Integer.class),
                "las columnas nuevas deben deshacerse");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM movies WHERE video_url IS NULL", Integer.class),
                "ninguna película puede quedar sin su vídeo");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM episodes WHERE video_url IS NULL", Integer.class),
                "ningún episodio puede quedar sin su vídeo");

        // Resuelta la causa, el siguiente arranque la aplica sin repair
        jdbc.execute("ALTER TABLE series DROP CONSTRAINT ck_series_trailer_url");
        assertEquals(1, flyway(db).target(V6).load().migrate().migrationsExecuted);
        TrailerUrlMigrationScenario.assertMovedByV6(jdbc);
    }

    /**
     * El {@code CHECK} del tráiler en PostgreSQL: rechaza (23514) las mismas
     * formas que en H2 ({@link TrailerUrlMigrationScenario#INVALID_TRAILERS}) y
     * acepta la normalizada. Importa probarlo aquí porque {@code TRANSLATE},
     * {@code SUBSTRING} y {@code CHAR_LENGTH} se comportan con texto Unicode
     * según el motor.
     */
    @Test
    void elCheckDelTrailerRechazaLoQueNoEsYoutubeNormalizado() throws Exception {
        String db = createDatabase();
        flyway(db).load().migrate();
        JdbcTemplate jdbc = jdbc(db);
        jdbc.update("INSERT INTO movies (title, description, duration, release_year, image_url, video_url, created_at)"
                + " VALUES ('t', 'd', 100, 2000, 'u', NULL, now())");
        jdbc.update("INSERT INTO series (title, description, release_year, image_url, created_at)"
                + " VALUES ('t', 'd', 2010, 'u', now())");

        for (String table : new String[] { "movies", "series" }) {
            jdbc.update("UPDATE " + table + " SET trailer_url = ?", "https://www.youtube.com/watch?v=-PtU49Aol_0");
            for (String invalid : TrailerUrlMigrationScenario.INVALID_TRAILERS) {
                DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                        () -> jdbc.update("UPDATE " + table + " SET trailer_url = ?", invalid), table + ": " + invalid);
                assertEquals(CHECK_VIOLATION, PostgresIntegrationTestSupport.sqlState(error), table + ": " + invalid);
            }
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static String createDatabase() throws SQLException {
        String name = "v6_" + UUID.randomUUID().toString().replace("-", "");
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

    /** Flyway configurado como la aplicación (ubicaciones y {@code baseline-*} de {@code application.properties}). */
    private static FluentConfiguration flyway(String database) throws IOException {
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
