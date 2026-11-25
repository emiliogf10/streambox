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
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.emilio.streambox.migration.WatchedAtMigrationScenario;

/**
 * La migración {@code V15} (subtítulos y {@code finished_at}) contra un
 * <b>PostgreSQL real</b>, partiendo de una base con progreso de visionado en
 * la {@code V14}: es lo que ocurrirá en la base del autor en Supabase la
 * primera vez que arranque la aplicación con la {@code V15} (lo terminado
 * queda visto con la fecha de su último guardado).
 *
 * <p>
 * Mismo escenario que en H2 ({@link WatchedAtMigrationScenario}) y mismo
 * montaje que {@link PostgresFavoriteGenresMigrationIntegrationTest}: cada test
 * crea su propia base ({@code CREATE DATABASE}) en el contenedor compartido y
 * aplica las migraciones con las ubicaciones reales de
 * {@code spring.flyway.locations} ({@code {vendor}} = {@code postgresql}).
 * </p>
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresWatchedAtMigrationIntegrationTest {

    /** Se migra hasta la V15 exacta: el test no cambia al añadir migraciones posteriores. */
    private static final MigrationVersion V15 = MigrationVersion.fromVersion("15");

    @Test
    void laV15MarcaComoVistoLoQueYaEstabaTerminadoYNadaMas() throws Exception {
        String db = createDatabase();
        flyway(db).target(MigrationVersion.fromVersion("14")).load().migrate();
        JdbcTemplate jdbc = jdbc(db);
        WatchedAtMigrationScenario.seedAtV14(jdbc);

        var result = flyway(db).target(V15).load().migrate();

        assertEquals(1, result.migrationsExecuted, "solo debe quedar por aplicar la V15");
        WatchedAtMigrationScenario.assertMigratedByV15(jdbc);
        assertEquals(0, flyway(db).target(V15).load().migrate().migrationsExecuted, "una segunda ejecución no hace nada");
        flyway(db).target(V15).load().validate();
    }

    /**
     * Si la V15 falla a mitad, PostgreSQL la deshace entera (el DDL también es
     * transaccional): ni columnas nuevas ni progreso rellenado. Se fuerza el
     * fallo en el último paso (ya existe una restricción con el nombre del
     * último {@code CHECK}), después de los {@code ALTER} y de los
     * {@code UPDATE}. Resuelta la causa, el siguiente arranque la aplica sin
     * {@code repair}.
     */
    @Test
    void unFalloDeLaV15NoDejaColumnasNiProgresoAMedias() throws Exception {
        String db = createDatabase();
        flyway(db).target(MigrationVersion.fromVersion("14")).load().migrate();
        JdbcTemplate jdbc = jdbc(db);
        WatchedAtMigrationScenario.seedAtV14(jdbc);
        jdbc.execute("ALTER TABLE user_episode_progress ADD CONSTRAINT ck_episode_progress_finished_has_finished_at"
                + " CHECK (position_seconds >= 0)");

        assertThrows(FlywayException.class, () -> flyway(db).target(V15).load().migrate());

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND column_name IN ('finished_at', 'subtitles_url')",
                Integer.class), "las columnas nuevas deben deshacerse");

        jdbc.execute("ALTER TABLE user_episode_progress DROP CONSTRAINT ck_episode_progress_finished_has_finished_at");
        assertEquals(1, flyway(db).target(V15).load().migrate().migrationsExecuted);
        WatchedAtMigrationScenario.assertMigratedByV15(jdbc);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static String createDatabase() throws SQLException {
        String name = "v15_" + UUID.randomUUID().toString().replace("-", "");
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
