package com.emilio.streambox;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.emilio.streambox.h2.H2SearchFunctions;

/**
 * Comprueba que todo lo que Flyway necesita para crear el esquema sale de
 * {@code src/main} (es decir, de {@code target/classes}) y nada de
 * {@code src/test}.
 *
 * <h2>Por qué importa</h2>
 * <p>
 * El backend del E2E ({@code frontend/playwright.config.ts}) y el de la pila del
 * navegador arrancan con H2 mediante
 * {@code spring-boot:run -Dspring-boot.run.useTestClasspath=true}. Esa opción
 * añade las <em>dependencias</em> de scope {@code test} (el driver de H2), pero
 * no {@code target/test-classes}. Cuando la V5 de H2 y {@code H2SearchFunctions}
 * vivían en {@code src/test}, la suite de Maven pasaba (ella sí ve
 * {@code test-classes}) mientras que ese backend aplicaba solo V1-V4 y cada
 * búsqueda por título respondía 500 («Función STREAMBOX_UNACCENT no
 * encontrada»). Este test hace visible ese error sin necesidad de arrancar el
 * E2E.
 * </p>
 */
class VendorMigrationsLocationTest {

    /** Ruta de la V5 de H2 dentro del classpath. */
    private static final String H2_V5 = "db/vendor/h2/V5__accent_insensitive_title_search.sql";

    /**
     * Cada script SQL bajo {@code db/} (migraciones comunes, las de cada motor y
     * los callbacks) está en la salida de {@code src/main}: si alguno estuviera
     * en {@code src/test}, el backend del E2E no lo vería.
     */
    @Test
    void todosLosScriptsDeFlywayEstanEnSrcMain() throws IOException, URISyntaxException {

        Path mainOutput = mainOutputDirectory();
        Resource[] scripts = new PathMatchingResourcePatternResolver().getResources("classpath*:db/**/*.sql");

        List<Path> locations = Arrays.stream(scripts).map(VendorMigrationsLocationTest::toPath).toList();

        assertThat(locations)
                .as("debe haber scripts, entre ellos la V5 de H2")
                .anyMatch(path -> path.endsWith(Path.of(H2_V5)));
        assertThat(locations)
                .as("ningún script de Flyway puede vivir en src/test (el E2E no ve target/test-classes)")
                .allMatch(path -> path.startsWith(mainOutput));
    }

    /**
     * La clase a la que apuntan los {@code CREATE ALIAS} de la V5 de H2 está en
     * la misma salida que la aplicación; si no, el alias se crea pero falla al
     * usarse (H2 carga la clase en la primera llamada).
     */
    @Test
    void lasFuncionesDeH2EstanEnSrcMain() throws URISyntaxException {

        assertThat(codeSource(H2SearchFunctions.class)).isEqualTo(mainOutputDirectory());
    }

    /** Directorio de clases de {@code src/main} ({@code target/classes}). */
    private static Path mainOutputDirectory() throws URISyntaxException {
        return codeSource(StreamboxApplication.class);
    }

    private static Path codeSource(Class<?> type) throws URISyntaxException {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static Path toPath(Resource resource) {
        try {
            URL url = resource.getURL();
            return Path.of(url.toURI());
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException("No se pudo resolver " + resource, e);
        }
    }
}
