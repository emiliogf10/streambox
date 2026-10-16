package com.emilio.streambox.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Collectors;

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
 * Revisión de QA de la {@code V6}: la migración presume de que «cada UPDATE
 * solo actúa sobre filas que aún no se han movido, así que repetir sus
 * sentencias no cambiaría nada». Este test lo comprueba de verdad.
 *
 * <p>
 * <b>Por qué importa.</b> Flyway ejecuta la {@code V6} una sola vez, pero hay
 * caminos reales en los que sus sentencias de datos se repiten: alguien que la
 * reaplica a mano en Supabase tras un fallo parcial, o un {@code repair} mal
 * hecho. Si un {@code UPDATE} no fuera idempotente, la segunda pasada podría,
 * por ejemplo, vaciar el {@code video_url} de un episodio que ya era un vídeo
 * propio, o cambiar el tráiler elegido de una serie, y se perderían URLs.
 * </p>
 *
 * <p>
 * Se leen los {@code UPDATE} directamente del archivo de la migración (no una
 * copia), se aplican una segunda vez sobre el escenario ya migrado de
 * {@link TrailerUrlMigrationScenario} y el estado debe quedar idéntico. H2
 * basta: el SQL es portable y el escenario ya se repite en PostgreSQL en
 * {@code PostgresTrailerUrlMigrationIntegrationTest}.
 * </p>
 */
class TrailerUrlMigrationIdempotenceQaTest {

    private static final String V6 = "db/migration/V6__trailer_url_and_optional_video.sql";

    private final String url = "jdbc:h2:mem:v6qa_" + UUID.randomUUID().toString().replace("-", "")
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    private final JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));

    /** Libera la base en memoria. */
    @AfterEach
    void shutdown() {
        jdbc.execute("SHUTDOWN");
    }

    /** Repetir los tres {@code UPDATE} de datos de la {@code V6} deja la base exactamente igual. */
    @Test
    void repetirLosUpdatesDeLaV6NoCambiaNada() throws IOException {
        flyway().target(MigrationVersion.fromVersion("5")).load().migrate();
        TrailerUrlMigrationScenario.seedAtV5(jdbc);
        flyway().load().migrate();
        TrailerUrlMigrationScenario.assertMovedByV6(jdbc);

        List<String> updates = dataUpdates();
        assertEquals(3, updates.size(), "la V6 tiene tres UPDATE de datos (películas, series y episodios): " + updates);

        updates.forEach(jdbc::update);

        TrailerUrlMigrationScenario.assertMovedByV6(jdbc);
    }

    /**
     * Los {@code UPDATE} de la migración, sin comentarios. Los comentarios
     * {@code --} se quitan antes de partir por {@code ;} porque contienen
     * puntos y coma en el texto explicativo.
     */
    private static List<String> dataUpdates() throws IOException {
        String sql = new ClassPathResource(V6).getContentAsString(StandardCharsets.UTF_8);
        String withoutComments = sql.lines()
                .map(line -> line.contains("--") ? line.substring(0, line.indexOf("--")) : line)
                .collect(Collectors.joining("\n"));
        List<String> updates = Arrays.stream(withoutComments.split(";"))
                .map(String::strip)
                .filter(statement -> statement.regionMatches(true, 0, "UPDATE", 0, 6))
                .toList();
        assertTrue(updates.stream().allMatch(statement -> statement.contains("SET")), "UPDATE mal partido: " + updates);
        return updates;
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
