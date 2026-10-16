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
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Movimiento de datos de la migración {@code V6} (tráiler a {@code trailer_url}
 * y vídeo opcional) en <b>H2</b>, partiendo de una base con datos en la
 * {@code V5}.
 *
 * <p>
 * No levanta Spring: crea una base H2 en memoria propia (modo PostgreSQL, como
 * el perfil {@code test}), aplica con Flyway las migraciones hasta la
 * {@code V5} ({@code target}), inserta el escenario de
 * {@link TrailerUrlMigrationScenario}, aplica la {@code V6} y comprueba el
 * resultado. Las ubicaciones se leen de {@code spring.flyway.locations} con
 * {@code {vendor}} = {@code h2}, igual que hace Spring Boot. El mismo escenario
 * se repite contra PostgreSQL real en
 * {@code postgres.PostgresTrailerUrlMigrationIntegrationTest}.
 * </p>
 */
class TrailerUrlMigrationH2Test {

    private final String url = "jdbc:h2:mem:v6_" + UUID.randomUUID().toString().replace("-", "")
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));

    /** Libera la base en memoria (con {@code DB_CLOSE_DELAY=-1} viviría hasta el final de la JVM). */
    @AfterEach
    void shutdown() {
        jdbc.execute("SHUTDOWN");
    }

    @Test
    void laV6MueveLosTrailersDeYoutubeYDejaIntactoLoDemas() throws IOException {
        flyway().target(MigrationVersion.fromVersion("5")).load().migrate();
        TrailerUrlMigrationScenario.seedAtV5(jdbc);

        var result = flyway().load().migrate();

        assertEquals(1, result.migrationsExecuted, "solo debe quedar por aplicar la V6");
        TrailerUrlMigrationScenario.assertMovedByV6(jdbc);
        assertEquals(0, flyway().load().migrate().migrationsExecuted, "una segunda ejecución no hace nada");
        TrailerUrlMigrationScenario.assertMovedByV6(jdbc);
    }

    /** Sobre una base vacía la V6 se aplica igual (es lo que hacen los tests y el E2E al arrancar). */
    @Test
    void laV6SeAplicaSobreUnaBaseSinDatos() throws IOException {
        assertEquals(6, flyway().load().migrate().migrationsExecuted);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM movies", Integer.class));
    }

    private FluentConfiguration flyway() throws IOException {
        Properties properties = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        String[] locations = Arrays.stream(properties.getProperty("spring.flyway.locations").split(","))
                .map(location -> location.trim().replace("{vendor}", "h2"))
                .toArray(String[]::new);
        return Flyway.configure()
                .dataSource(url, "sa", "")
                .locations(locations)
                // No existe db/callback/h2: Flyway avisaría (o fallaría) por una ubicación vacía
                .failOnMissingLocations(false);
    }
}
