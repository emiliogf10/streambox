package com.emilio.streambox.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptException;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import com.emilio.streambox.dto.MovieRequest;
import com.emilio.streambox.validation.HttpsUrlValidator;
import com.emilio.streambox.validation.WebVttUrlValidator;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Casos de prueba del script opcional {@code docs/catalogo-demostracion.sql}
 * (tres cortometrajes libres de la Blender Foundation con vídeo directo y
 * subtítulos), escritos una sola vez y ejecutados en los dos motores: H2
 * ({@link DemoCatalogScriptTest}) y PostgreSQL real
 * ({@code PostgresDemoCatalogScriptIntegrationTest}). Mismo patrón que
 * {@code LikesQueryContract}.
 *
 * <p>
 * <b>Por qué hay tests de un script que no es una migración.</b> El autor lo
 * ejecuta a mano en su base (Supabase), quizá meses después de escribirlo. Si
 * una migración futura añade una columna obligatoria, un {@code CHECK} o
 * cambia un nombre, el script fallaría justo entonces, lejos de la causa. Al
 * ejecutarlo la suite sobre el esquema de Flyway, la migración que lo rompa
 * hace fallar los tests en el mismo cambio.
 * </p>
 *
 * <p>
 * Se ejecuta el archivo <b>tal cual</b>, con su {@code BEGIN}/{@code COMMIT},
 * como lo haría {@code psql}, con {@link ScriptUtils} sobre una conexión
 * propia en modo autocommit. Qué se comprueba: los datos de cada película y
 * sus géneros, que todos pasan la validación de la API ({@link MovieRequest},
 * para poder editarlos desde el panel), que el vídeo es un archivo directo
 * para el reproductor integrado, que cada cartel existe en
 * {@code frontend/public/covers/}, que repetirlo no duplica nada, que no toca
 * lo que ya existía y que un fallo a mitad no deja nada guardado.
 * </p>
 */
public interface DemoCatalogScriptContract {

    /** El script, relativo al directorio del backend (donde Maven ejecuta los tests). */
    Path SCRIPT = Path.of("..", "docs", "catalogo-demostracion.sql");

    /**
     * Reglas del reproductor integrado en el frontend: de aquí se leen las
     * terminaciones que considera vídeo directo, para no mantener una copia.
     */
    Path PLAYBACK_TS = Path.of("..", "frontend", "src", "lib", "playback.ts");

    /**
     * Carpeta de la que el frontend sirve las portadas locales
     * ({@code /covers/<archivo>}): Vite en desarrollo y nginx en Docker.
     */
    Path COVERS_DIR = Path.of("..", "frontend", "public", "covers");

    /** Prefijo de las portadas locales en {@code image_url}. */
    String COVER_PREFIX = "/covers/";

    /** Prefijo común de los vídeos (versiones transcodificadas de Wikimedia Commons). */
    String VIDEO_PREFIX = "https://upload.wikimedia.org/wikipedia/commons/transcoded/";

    /** Prefijo común de los subtítulos (jsDelivr sobre el repositorio público). */
    String SUBTITLES_PREFIX = "https://cdn.jsdelivr.net/gh/emiliogf10/streambox@main/docs/demo/subtitulos/";

    /**
     * Lo que debe quedar en la base de datos por cada película.
     *
     * @param title        título
     * @param year         año de estreno
     * @param duration     duración en minutos
     * @param imageUrl     cartel
     * @param videoUrl     vídeo directo
     * @param subtitlesUrl subtítulos, o {@code null}
     * @param site         web de la película en blender.org (va en la atribución de la sinopsis)
     * @param genres       nombres de sus géneros
     */
    record ExpectedMovie(String title, int year, int duration, String imageUrl, String videoUrl,
            String subtitlesUrl, String site, Set<String> genres) {
    }

    /** Las tres películas del script, por año. */
    List<ExpectedMovie> EXPECTED = List.of(
            new ExpectedMovie("Big Buck Bunny", 2008, 11,
                    COVER_PREFIX + "big-buck-bunny.webp",
                    VIDEO_PREFIX + "c/c0/Big_Buck_Bunny_4K.webm/Big_Buck_Bunny_4K.webm.480p.vp9.webm",
                    null, "peach.blender.org", Set.of("Animación", "Comedia")),
            new ExpectedMovie("Sintel", 2010, 15,
                    COVER_PREFIX + "sintel.webp",
                    VIDEO_PREFIX + "f/f1/Sintel_movie_4K.webm/Sintel_movie_4K.webm.480p.vp9.webm",
                    SUBTITLES_PREFIX + "sintel.es.vtt", "durian.blender.org",
                    Set.of("Animación", "Fantasía", "Aventura")),
            new ExpectedMovie("Tears of Steel", 2012, 12,
                    COVER_PREFIX + "tears-of-steel.webp",
                    VIDEO_PREFIX + "1/10/Tears_of_Steel_in_4k_-_Official_Blender_Foundation_release.webm/"
                            + "Tears_of_Steel_in_4k_-_Official_Blender_Foundation_release.webm.480p.vp9.webm",
                    SUBTITLES_PREFIX + "tears-of-steel.es.vtt", "mango.blender.org",
                    Set.of("Ciencia ficción", "Acción")));

    /** Géneros que el script crea en una base sin ninguno. */
    Set<String> CREATED_GENRES = Set.of("Animación", "Comedia", "Fantasía", "Aventura", "Ciencia ficción", "Acción");

    /** @return acceso JDBC para preparar datos y leer el resultado */
    JdbcTemplate jdbc();

    /** @return el pool de conexiones del contexto, para ejecutar el script en una conexión propia */
    DataSource dataSource();

    // ------------------------------------------------------------------
    // Datos
    // ------------------------------------------------------------------

    /**
     * En una base vacía crea las tres películas con exactamente sus datos,
     * sin tráiler y con fecha de alta, y los seis géneros.
     */
    @Test
    default void creaLasTresPeliculasConSusDatosYSusGeneros() {
        runScript();

        List<Map<String, Object>> rows = jdbc().queryForList(
                "SELECT id, title, description, duration, release_year, image_url, video_url, trailer_url, "
                        + "subtitles_url, created_at FROM movies ORDER BY release_year");
        assertEquals(EXPECTED.size(), rows.size());
        for (int i = 0; i < EXPECTED.size(); i++) {
            ExpectedMovie expected = EXPECTED.get(i);
            Map<String, Object> row = rows.get(i);
            assertEquals(expected.title(), row.get("title"));
            assertEquals(expected.year(), ((Number) row.get("release_year")).intValue());
            assertEquals(expected.duration(), ((Number) row.get("duration")).intValue());
            assertEquals(expected.imageUrl(), row.get("image_url"));
            assertEquals(expected.videoUrl(), row.get("video_url"));
            assertEquals(expected.subtitlesUrl(), row.get("subtitles_url"));
            assertNull(row.get("trailer_url"), "sin tráiler: sería la propia película");
            assertNotNull(row.get("created_at"));
            String description = (String) row.get("description");
            assertTrue(description.endsWith("Cortometraje libre © Blender Foundation (" + expected.site()
                    + "), licencia CC BY 3.0."), "la sinopsis termina con la atribución: " + description);
            assertEquals(expected.genres(), genreNames(((Number) row.get("id")).longValue()));
        }
        assertEquals(CREATED_GENRES, new HashSet<>(jdbc().queryForList("SELECT name FROM genres", String.class)));
    }

    /**
     * Todo lo insertado pasa la validación de la API, para que el autor pueda
     * abrir cada película en «Editar» y guardarla sin tocar nada: sin error en
     * ningún campo de {@link MovieRequest}, y los subtítulos, en concreto, con
     * {@code @WebVttUrl} ({@code https://} y archivo {@code .vtt}).
     */
    @Test
    default void todosLosDatosPasanLaValidacionDeLaApi() {
        runScript();

        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            for (Map<String, Object> row : jdbc().queryForList("SELECT * FROM movies")) {
                long id = ((Number) row.get("id")).longValue();
                MovieRequest request = new MovieRequest(
                        (String) row.get("title"),
                        (String) row.get("description"),
                        ((Number) row.get("duration")).intValue(),
                        ((Number) row.get("release_year")).intValue(),
                        (String) row.get("image_url"),
                        (String) row.get("video_url"),
                        (String) row.get("trailer_url"),
                        (String) row.get("subtitles_url"),
                        new HashSet<>(jdbc().queryForList(
                                "SELECT genre_id FROM movie_genres WHERE movie_id = ?", Long.class, id)));

                Set<ConstraintViolation<MovieRequest>> violations = validator.validate(request);
                assertTrue(violations.isEmpty(), row.get("title") + ": " + violations);

                String subtitles = request.subtitlesUrl();
                if (subtitles != null) {
                    assertTrue(WebVttUrlValidator.isWebVttUrl(subtitles), subtitles);
                }
            }
        }
    }

    /**
     * El vídeo de cada película es un <b>archivo directo</b> que el reproductor
     * integrado abre ({@code getDirectVideoUrl} de {@code lib/playback.ts}):
     * {@code https:} y una ruta que termina en una de las extensiones que el
     * frontend reconoce. Si no lo fuera, «Ver ahora» abriría otra pestaña y
     * no habría progreso ni subtítulos, que es justo lo que se quiere enseñar.
     */
    @Test
    default void elVideoEsUnArchivoDirectoParaElReproductorIntegrado() throws IOException {
        List<String> extensions = directVideoExtensions();
        runScript();

        for (String video : jdbc().queryForList("SELECT video_url FROM movies", String.class)) {
            assertTrue(HttpsUrlValidator.isHttpsUrl(video), video);
            URI uri = URI.create(video);
            assertEquals("https", uri.getScheme(), video);
            String path = uri.getPath().toLowerCase(Locale.ROOT);
            assertTrue(extensions.stream().anyMatch(path::endsWith),
                    video + " debe terminar en una de " + extensions);
        }
    }

    /**
     * Cada cartel es una portada local ({@code /covers/<archivo>}) y ese
     * archivo <b>existe</b> en {@code frontend/public/covers/}. La validación
     * de la API solo mira el formato de la ruta: si alguien renombra o borra
     * un cartel, la base seguiría apuntando a un archivo que no está y la
     * portada saldría con el respaldo del título sin que nada fallara. Este
     * test hace que falle la suite en ese mismo cambio.
     */
    @Test
    default void cadaCartelEsUnaPortadaLocalQueExisteEnElFrontend() {
        assertTrue(Files.isDirectory(COVERS_DIR), "No se encuentra " + COVERS_DIR.toAbsolutePath());
        runScript();

        List<String> images = jdbc().queryForList("SELECT image_url FROM movies ORDER BY release_year", String.class);
        assertEquals(EXPECTED.size(), images.size());
        for (String image : images) {
            assertTrue(image.startsWith(COVER_PREFIX), "el cartel debe ser una portada local: " + image);
            String fileName = image.substring(COVER_PREFIX.length());
            // Nada de subcarpetas ni «..»: el nombre debe quedarse dentro de covers/.
            assertFalse(fileName.contains("/") || fileName.contains("\\") || fileName.contains(".."), image);
            Path cover = COVERS_DIR.resolve(fileName);
            assertTrue(Files.isRegularFile(cover), image + " no existe en " + COVERS_DIR.toAbsolutePath());
        }
    }

    // ------------------------------------------------------------------
    // Idempotencia y datos existentes
    // ------------------------------------------------------------------

    /**
     * Ejecutarlo dos veces deja exactamente lo mismo: ni películas, ni
     * géneros, ni asignaciones duplicadas, y las mismas filas (mismos ids).
     */
    @Test
    default void ejecutarloDosVecesNoDuplicaNada() {
        runScript();
        List<Map<String, Object>> movies = jdbc().queryForList("SELECT * FROM movies ORDER BY id");
        List<Map<String, Object>> genres = jdbc().queryForList("SELECT * FROM genres ORDER BY id");
        List<Map<String, Object>> links = jdbc().queryForList(
                "SELECT movie_id, genre_id FROM movie_genres ORDER BY movie_id, genre_id");

        runScript();

        assertEquals(3, movies.size());
        assertEquals(6, genres.size());
        assertEquals(7, links.size());
        assertEquals(movies, jdbc().queryForList("SELECT * FROM movies ORDER BY id"));
        assertEquals(genres, jdbc().queryForList("SELECT * FROM genres ORDER BY id"));
        assertEquals(links, jdbc().queryForList(
                "SELECT movie_id, genre_id FROM movie_genres ORDER BY movie_id, genre_id"));
    }

    /**
     * Lo que ya existía no se toca:
     * <ul>
     * <li>una película con el mismo título (con otras mayúsculas y espacios) y
     * año se queda como estaba, con sus géneros, y no se crea otra;</li>
     * <li>un género que ya existe sin acento y con otras mayúsculas
     * («Ciencia Ficcion») se reutiliza en lugar de crear «Ciencia
     * ficción».</li>
     * </ul>
     */
    @Test
    default void noTocaLasPeliculasNiLosGenerosQueYaExisten() {
        jdbc().update("INSERT INTO genres (name) VALUES ('Drama'), ('Ciencia Ficcion')");
        long drama = jdbc().queryForObject("SELECT id FROM genres WHERE name = 'Drama'", Long.class);
        long scienceFiction = jdbc().queryForObject("SELECT id FROM genres WHERE name = 'Ciencia Ficcion'", Long.class);
        jdbc().update("INSERT INTO movies (title, description, duration, release_year, image_url, video_url, created_at) "
                + "VALUES (' SINTEL ', 'Mi sinopsis', 14, 2010, '/covers/sintel.webp', 'https://example.com/sintel.mp4', "
                + "CURRENT_TIMESTAMP)");
        long sintel = jdbc().queryForObject("SELECT id FROM movies", Long.class);
        jdbc().update("INSERT INTO movie_genres (movie_id, genre_id) VALUES (?, ?)", sintel, drama);
        Map<String, Object> sintelBefore = jdbc().queryForMap("SELECT * FROM movies WHERE id = ?", sintel);

        runScript();

        assertEquals(sintelBefore, jdbc().queryForMap("SELECT * FROM movies WHERE id = ?", sintel));
        assertEquals(Set.of("Drama"), genreNames(sintel));
        assertEquals(List.of("Big Buck Bunny", "Tears of Steel"), jdbc().queryForList(
                "SELECT title FROM movies WHERE id <> ? ORDER BY release_year", String.class, sintel));

        long tearsOfSteel = jdbc().queryForObject("SELECT id FROM movies WHERE title = 'Tears of Steel'", Long.class);
        assertEquals(Set.of("Ciencia Ficcion", "Acción"), genreNames(tearsOfSteel));
        assertEquals(scienceFiction, jdbc().queryForObject(
                "SELECT genre_id FROM movie_genres mg JOIN genres g ON g.id = mg.genre_id "
                        + "WHERE mg.movie_id = ? AND g.name = 'Ciencia Ficcion'", Long.class, tearsOfSteel));
        assertFalse(jdbc().queryForList("SELECT name FROM genres", String.class).contains("Ciencia ficción"));
    }

    /**
     * El script va en una transacción: si una sentencia falla a mitad, no
     * queda nada guardado. Se prueba con una copia en la que el último
     * {@code INSERT} pide un género que no existe; según explica el script,
     * {@code MIN} da {@code NULL} y el {@code NOT NULL} de
     * {@code movie_genres.genre_id} lo rechaza, después de haber creado
     * géneros y películas.
     */
    @Test
    default void unFalloAMitadNoDejaNadaGuardado() throws IOException {
        String script = Files.readString(SCRIPT, StandardCharsets.UTF_8);
        String broken = script.replace("('Tears of Steel', 'accion')", "('Tears of Steel', 'genero inexistente')");
        assertFalse(script.equals(broken), "la copia rota debe diferir del script");

        assertThrows(ScriptException.class,
                () -> runScript(new ByteArrayResource(broken.getBytes(StandardCharsets.UTF_8))));

        assertEquals(0, count("movies"));
        assertEquals(0, count("genres"));
        assertEquals(0, count("movie_genres"));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Ejecuta {@code docs/catalogo-demostracion.sql} tal cual. */
    default void runScript() {
        assertTrue(Files.exists(SCRIPT), "No se encuentra el script: " + SCRIPT.toAbsolutePath());
        runScript(new FileSystemResource(SCRIPT));
    }

    /**
     * Ejecuta un script SQL en una conexión propia en modo autocommit, como
     * {@code psql}: el {@code BEGIN}/{@code COMMIT} del propio script decide
     * la transacción.
     *
     * <p>
     * Si falla, se envía un {@code ROLLBACK} antes de devolver la conexión al
     * pool: el {@code BEGIN} lo abrió el SQL y el pool no lo sabe, así que sin
     * él la conexión volvería con la transacción abierta (en PostgreSQL,
     * abortada) y el siguiente test que la usara fallaría.
     * </p>
     *
     * @param script el script en UTF-8
     */
    default void runScript(Resource script) {
        try (Connection connection = dataSource().getConnection()) {
            try {
                ScriptUtils.executeSqlScript(connection, new EncodedResource(script, StandardCharsets.UTF_8));
            } catch (ScriptException failure) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("ROLLBACK");
                }
                connection.setAutoCommit(true);
                throw failure;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("No se pudo usar la conexión del script", e);
        }
    }

    /**
     * Nombres de los géneros de una película.
     *
     * @param movieId id de la película
     * @return sus géneros
     */
    default Set<String> genreNames(long movieId) {
        return new HashSet<>(jdbc().queryForList(
                "SELECT g.name FROM movie_genres mg JOIN genres g ON g.id = mg.genre_id WHERE mg.movie_id = ?",
                String.class, movieId));
    }

    /**
     * Filas de una tabla.
     *
     * @param table nombre de la tabla (constante del test, nunca un dato externo)
     * @return número de filas
     */
    default long count(String table) {
        return jdbc().queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    /**
     * Las terminaciones de vídeo directo que reconoce el frontend
     * ({@code DIRECT_VIDEO_EXTENSIONS} de {@code lib/playback.ts}).
     *
     * @return las extensiones, en minúsculas y con el punto
     * @throws IOException si no se puede leer el archivo
     */
    static List<String> directVideoExtensions() throws IOException {
        assertTrue(Files.exists(PLAYBACK_TS), "No se encuentra " + PLAYBACK_TS.toAbsolutePath());
        String source = Files.readString(PLAYBACK_TS, StandardCharsets.UTF_8);
        Matcher list = Pattern.compile("DIRECT_VIDEO_EXTENSIONS\\s*=\\s*\\[([^\\]]*)\\]").matcher(source);
        assertTrue(list.find(), "No se encuentra DIRECT_VIDEO_EXTENSIONS en " + PLAYBACK_TS);
        List<String> extensions = new ArrayList<>();
        Matcher item = Pattern.compile("'([^']+)'").matcher(list.group(1));
        while (item.find()) {
            extensions.add(item.group(1).toLowerCase(Locale.ROOT));
        }
        assertFalse(extensions.isEmpty(), "DIRECT_VIDEO_EXTENSIONS está vacía");
        return extensions;
    }
}
