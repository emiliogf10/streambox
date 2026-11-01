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
 * La migración {@code V11} (géneros favoritos) sobre una base <b>H2</b> con
 * cuentas en la {@code V10}: todas quedan con la pregunta de bienvenida cerrada
 * (decisión del autor, ver la cabecera de la migración) y las creadas después
 * nacen con ella pendiente.
 *
 * <p>
 * Mismo patrón que {@link EmailVerificationMigrationH2Test} (incluida la
 * conexión única, por el detalle de H2 con {@code ck_users_role} que se explica
 * allí). El mismo escenario ({@link FavoriteGenresMigrationScenario}) se
 * repite contra PostgreSQL real en
 * {@code postgres.PostgresFavoriteGenresMigrationIntegrationTest}.
 * </p>
 */
class FavoriteGenresMigrationH2Test {

    /** Se migra hasta la V11 exacta: el test no cambia al añadir migraciones posteriores. */
    private static final MigrationVersion V11 = MigrationVersion.fromVersion("11");

    private final String url = "jdbc:h2:mem:v11_" + UUID.randomUUID().toString().replace("-", "")
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
    void laV11CierraLaPreguntaDeTodasLasCuentasQueYaExistianYNadaMas() throws IOException {
        flyway().target(MigrationVersion.fromVersion("10")).load().migrate();
        FavoriteGenresMigrationScenario.seedAtV10(jdbc);

        var result = flyway().target(V11).load().migrate();

        assertEquals(1, result.migrationsExecuted, "solo debe quedar por aplicar la V11");
        FavoriteGenresMigrationScenario.assertMigratedByV11(jdbc);
        assertEquals(0, flyway().target(V11).load().migrate().migrationsExecuted, "una segunda ejecución no hace nada");
        FavoriteGenresMigrationScenario.assertMigratedByV11(jdbc);
    }

    /** Sobre una base sin cuentas la V11 se aplica igual (es lo que hacen los tests y el E2E al arrancar). */
    @Test
    void laV11SeAplicaSobreUnaBaseSinCuentas() throws IOException {
        assertEquals(11, flyway().target(V11).load().migrate().migrationsExecuted, "V1 a V11 (la V5, la de H2)");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_favorite_genres", Integer.class));
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
