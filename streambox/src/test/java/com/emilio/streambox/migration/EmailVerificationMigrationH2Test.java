package com.emilio.streambox.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * La migración {@code V9} (verificación del correo) sobre una base <b>H2</b>
 * con cuentas en la {@code V8}: solo las ADMIN quedan verificadas; las USER,
 * pendientes (decisión del autor, ver la cabecera de la migración).
 *
 * <p>
 * Mismo patrón que {@link TrailerUrlMigrationH2Test}: base H2 en memoria propia
 * (modo PostgreSQL), Flyway hasta la {@code V8} ({@code target}), escenario de
 * {@link EmailVerificationMigrationScenario}, la {@code V9} y comprobación. El
 * mismo escenario se repite contra PostgreSQL real en
 * {@code postgres.PostgresEmailVerificationMigrationIntegrationTest}.
 * </p>
 */
class EmailVerificationMigrationH2Test {

    /** Se migra hasta la V9 exacta: el test no cambia al añadir migraciones posteriores. */
    private static final MigrationVersion V9 = MigrationVersion.fromVersion("9");

    private final String url = "jdbc:h2:mem:v9_" + UUID.randomUUID().toString().replace("-", "")
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";

    /**
     * Una sola conexión para Flyway y para el test (como el pool de la
     * aplicación, que mantiene viva la conexión con la que migró Flyway).
     *
     * <p>
     * Con una conexión nueva por operación, H2 2.x falla al comprobar
     * {@code ck_users_role} en el primer {@code INSERT} en {@code users}
     * («Check constraint invalid ... The database has been closed»): el
     * {@code IN ('USER', 'ADMIN')} de la restricción guarda una referencia a
     * la sesión que lo creó (la de Flyway), y esa sesión ya está cerrada. Es un
     * detalle de H2 sin efecto en la aplicación ni en PostgreSQL.
     * </p>
     */
    private final SingleConnectionDataSource dataSource = new SingleConnectionDataSource(url, "sa", "", true);
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    /** Libera la base en memoria (con {@code DB_CLOSE_DELAY=-1} viviría hasta el final de la JVM). */
    @AfterEach
    void shutdown() {
        jdbc.execute("SHUTDOWN");
        dataSource.destroy();
    }

    @Test
    void laV9VerificaSoloLasCuentasAdminQueYaExistianYDejaPendientesLasDemas() throws IOException {
        flyway().target(MigrationVersion.fromVersion("8")).load().migrate();
        EmailVerificationMigrationScenario.seedAtV8(jdbc);

        var result = flyway().target(V9).load().migrate();

        assertEquals(1, result.migrationsExecuted, "solo debe quedar por aplicar la V9");
        EmailVerificationMigrationScenario.assertMigratedByV9(jdbc);
        assertEquals(0, flyway().target(V9).load().migrate().migrationsExecuted, "una segunda ejecución no hace nada");
        EmailVerificationMigrationScenario.assertMigratedByV9(jdbc);
    }

    /** Sobre una base sin cuentas la V9 se aplica igual (es lo que hacen los tests y el E2E al arrancar). */
    @Test
    void laV9SeAplicaSobreUnaBaseSinCuentas() throws IOException {
        assertEquals(9, flyway().target(V9).load().migrate().migrationsExecuted, "V1 a V9 (la V5, la de H2)");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM account_tokens", Integer.class));
    }

    private FluentConfiguration flyway() throws IOException {
        Properties properties = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        String[] locations = Arrays.stream(properties.getProperty("spring.flyway.locations").split(","))
                .map(location -> location.trim().replace("{vendor}", "h2"))
                .toArray(String[]::new);
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(locations)
                // No existe db/callback/h2: Flyway avisaría (o fallaría) por una ubicación vacía
                .failOnMissingLocations(false);
    }
}
