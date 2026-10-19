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

import com.emilio.streambox.migration.EmailVerificationMigrationScenario;

/**
 * La migración {@code V9} (verificación del correo) contra un <b>PostgreSQL
 * real</b>, partiendo de una base con cuentas en la {@code V8}: es lo que
 * ocurrirá en la base del autor en Supabase la primera vez que arranque la
 * aplicación con la {@code V9} (solo las cuentas ADMIN quedan verificadas; las
 * USER, pendientes).
 *
 * <p>
 * Mismo escenario que en H2 ({@link EmailVerificationMigrationScenario}) y
 * mismo montaje que {@link PostgresTrailerUrlMigrationIntegrationTest}: cada
 * test crea su propia base ({@code CREATE DATABASE}) en el contenedor
 * compartido y aplica las migraciones con las ubicaciones reales de
 * {@code spring.flyway.locations} ({@code {vendor}} = {@code postgresql}).
 * </p>
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresEmailVerificationMigrationIntegrationTest {

    /** Se migra hasta la V9 exacta: el test no cambia al añadir migraciones posteriores. */
    private static final MigrationVersion V9 = MigrationVersion.fromVersion("9");

    @Test
    void laV9VerificaSoloLasCuentasAdminQueYaExistianYDejaPendientesLasDemas() throws Exception {
        String db = createDatabase();
        flyway(db).target(MigrationVersion.fromVersion("8")).load().migrate();
        JdbcTemplate jdbc = jdbc(db);
        EmailVerificationMigrationScenario.seedAtV8(jdbc);

        var result = flyway(db).target(V9).load().migrate();

        assertEquals(1, result.migrationsExecuted, "solo debe quedar por aplicar la V9");
        EmailVerificationMigrationScenario.assertMigratedByV9(jdbc);
        assertEquals(0, flyway(db).target(V9).load().migrate().migrationsExecuted, "una segunda ejecución no hace nada");
        flyway(db).target(V9).load().validate();
    }

    /**
     * Si la V9 falla a mitad, PostgreSQL la deshace entera (el DDL también es
     * transaccional): ni la columna nueva ni cuentas ADMIN marcadas. Se fuerza el
     * fallo en el {@code CREATE TABLE} (ya existe una tabla con ese nombre),
     * después del {@code ALTER} y del {@code UPDATE} de {@code users}. Resuelta
     * la causa, el siguiente arranque la aplica sin {@code repair}.
     */
    @Test
    void unFalloDeLaV9NoDejaCuentasMarcadasNiLaColumnaAMedias() throws Exception {
        String db = createDatabase();
        flyway(db).target(MigrationVersion.fromVersion("8")).load().migrate();
        JdbcTemplate jdbc = jdbc(db);
        EmailVerificationMigrationScenario.seedAtV8(jdbc);
        jdbc.execute("CREATE TABLE account_tokens (id BIGINT)");

        assertThrows(FlywayException.class, () -> flyway(db).target(V9).load().migrate());

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns"
                + " WHERE table_schema = 'public' AND table_name = 'users' AND column_name = 'email_verified_at'",
                Integer.class), "la columna nueva debe deshacerse");

        jdbc.execute("DROP TABLE account_tokens");
        assertEquals(1, flyway(db).target(V9).load().migrate().migrationsExecuted);
        EmailVerificationMigrationScenario.assertMigratedByV9(jdbc);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static String createDatabase() throws SQLException {
        String name = "v9_" + UUID.randomUUID().toString().replace("-", "");
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
