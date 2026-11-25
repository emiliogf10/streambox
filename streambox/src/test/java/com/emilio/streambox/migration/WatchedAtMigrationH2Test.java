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
 * La migración {@code V15} (subtítulos y {@code finished_at}) sobre una base
 * <b>H2</b> con progreso de visionado en la {@code V14}: lo que ya estaba
 * terminado queda visto con la fecha de su último guardado y nada más cambia.
 *
 * <p>
 * Mismo patrón que {@link FavoriteGenresMigrationH2Test} (incluida la conexión
 * única, por el detalle de H2 con {@code ck_users_role} que se explica en
 * {@link EmailVerificationMigrationH2Test}). El mismo escenario
 * ({@link WatchedAtMigrationScenario}) se repite contra PostgreSQL real en
 * {@code postgres.PostgresWatchedAtMigrationIntegrationTest}.
 * </p>
 */
class WatchedAtMigrationH2Test {

    /** Se migra hasta la V15 exacta: el test no cambia al añadir migraciones posteriores. */
    private static final MigrationVersion V15 = MigrationVersion.fromVersion("15");

    private final String url = "jdbc:h2:mem:v15_" + UUID.randomUUID().toString().replace("-", "")
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    private final SingleConnectionDataSource dataSource = new SingleConnectionDataSource(url, "sa", "", true);
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    /** Libera la base en memoria (con {@code DB_CLOSE_DELAY=-1} viviría hasta el final de la JVM). */
    @AfterEach
    void shutdown() {
        jdbc.execute("SHUTDOWN");
        dataSource.destroy();
    }

    @Test
    void laV15MarcaComoVistoLoQueYaEstabaTerminadoYNadaMas() throws IOException {
        flyway().target(MigrationVersion.fromVersion("14")).load().migrate();
        WatchedAtMigrationScenario.seedAtV14(jdbc);

        var result = flyway().target(V15).load().migrate();

        assertEquals(1, result.migrationsExecuted, "solo debe quedar por aplicar la V15");
        WatchedAtMigrationScenario.assertMigratedByV15(jdbc);
        assertEquals(0, flyway().target(V15).load().migrate().migrationsExecuted, "una segunda ejecución no hace nada");
        WatchedAtMigrationScenario.assertMigratedByV15(jdbc);
    }

    /** Sobre una base vacía la V15 se aplica igual (es lo que hacen los tests y el E2E al arrancar). */
    @Test
    void laV15SeAplicaSobreUnaBaseSinProgreso() throws IOException {
        assertEquals(15, flyway().target(V15).load().migrate().migrationsExecuted, "V1 a V15 (la V5, la de H2)");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_movie_progress WHERE finished_at IS NOT NULL",
                Integer.class));
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
