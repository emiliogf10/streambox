package com.emilio.streambox.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Escenario de datos para probar el movimiento de datos de la migración
 * {@code V6__trailer_url_and_optional_video.sql}, compartido por el test de H2
 * ({@link TrailerUrlMigrationH2Test}) y el de PostgreSQL real
 * ({@code postgres.PostgresTrailerUrlMigrationIntegrationTest}).
 *
 * <p>
 * Se usa con una base recién migrada hasta la {@code V5} (Flyway con
 * {@code target}): {@link #seedAtV5(JdbcTemplate)} inserta películas, series y
 * episodios con vídeos de YouTube en la forma normalizada, con otras formas de
 * YouTube y sin YouTube; después se aplica la {@code V6} y
 * {@link #assertMovedByV6(JdbcTemplate)} comprueba el resultado exacto, fila a
 * fila. Todo con SQL estándar para que valga en los dos motores.
 * </p>
 *
 * <p>
 * Los casos que <b>no</b> deben moverse son tan importantes como los que sí: la
 * {@code V6} solo mueve lo que su {@code CHECK} acepta, y si moviera una URL con
 * otra forma la migración fallaría en la base real del autor y la aplicación no
 * arrancaría.
 * </p>
 */
public final class TrailerUrlMigrationScenario {

    /** Tráiler normalizado (la forma que tienen los datos del autor tras ampliar el catálogo). */
    static final String YT_RICK = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    /** Id con {@code -} y {@code _}: caracteres válidos de un id de YouTube. */
    static final String YT_SYMBOLS = "https://www.youtube.com/watch?v=-PtU49Aol_0";
    static final String YT_SERIES = "https://www.youtube.com/watch?v=aaaaaaaaaaa";
    /** {@code A} va antes que {@code B} alfabéticamente, pero está en un episodio posterior. */
    static final String YT_A = "https://www.youtube.com/watch?v=AAAAAAAAAAA";
    static final String YT_B = "https://www.youtube.com/watch?v=BBBBBBBBBBB";
    static final String OWN_VIDEO = "https://cdn.example.com/video.mp4";

    /**
     * Películas: título, {@code video_url} en V5, {@code video_url} esperado
     * tras la V6 y {@code trailer_url} esperado.
     */
    private static final String[][] MOVIES = {
        { "Normalizada", YT_RICK, null, YT_RICK },
        { "Con guiones", YT_SYMBOLS, null, YT_SYMBOLS },
        { "Video propio", OWN_VIDEO, OWN_VIDEO, null },
        { "Con parametros", YT_RICK + "&t=10", YT_RICK + "&t=10", null },
        { "Enlace corto", "https://youtu.be/dQw4w9WgXcQ", "https://youtu.be/dQw4w9WgXcQ", null },
        { "Sin https", "http://www.youtube.com/watch?v=dQw4w9WgXcQ", "http://www.youtube.com/watch?v=dQw4w9WgXcQ", null },
        { "Id corto", "https://www.youtube.com/watch?v=dQw4w9WgXc", "https://www.youtube.com/watch?v=dQw4w9WgXc", null },
        { "Id raro", "https://www.youtube.com/watch?v=dQw4w9WgX%Q", "https://www.youtube.com/watch?v=dQw4w9WgX%Q", null },
        // Signo Kelvin (U+212A): misma longitud, pero no es un carácter válido del id
        { "Kelvin", "https://www.youtube.com/watch?v=dQw4w9WgXKQ",
            "https://www.youtube.com/watch?v=dQw4w9WgXKQ", null },
    };

    /**
     * Episodios: serie, temporada, número, {@code video_url} en V5 y
     * {@code video_url} esperado tras la V6. El orden de inserción NO es el de
     * temporada y número (en "Distintos" el 2x1 se inserta primero), para que
     * el test falle si la V6 eligiera el tráiler por id o por orden de llegada.
     */
    private static final Object[][] EPISODES = {
        { "Todos iguales", 1, 1, YT_SERIES, null },
        { "Todos iguales", 1, 2, YT_SERIES, null },
        { "Todos iguales", 2, 1, YT_SERIES, null },
        { "Distintos", 2, 1, YT_A, YT_A },
        { "Distintos", 1, 3, YT_B, null },
        { "Distintos", 1, 2, YT_B, null },
        { "Distintos", 1, 1, OWN_VIDEO, OWN_VIDEO },
        { "Sin youtube", 1, 1, OWN_VIDEO, OWN_VIDEO },
        { "Youtube con parametros", 1, 1, YT_RICK + "&t=10", YT_RICK + "&t=10" },
    };

    /** Series y su {@code trailer_url} esperado tras la V6. */
    private static final String[][] SERIES = {
        { "Todos iguales", YT_SERIES },
        // El episodio de YouTube con temporada y número más bajos es el 1x2 (el 1x1 no es de YouTube)
        { "Distintos", YT_B },
        { "Sin youtube", null },
        { "Sin episodios", null },
        { "Youtube con parametros", null },
    };

    /**
     * Formas que la API acepta al recibirlas (y normaliza) pero que la base de
     * datos no debe guardar tal cual, y enlaces que no son de YouTube. Las usan
     * {@code FlywaySchemaIntegrationTest} (H2) y
     * {@code PostgresTrailerUrlMigrationIntegrationTest} (PostgreSQL).
     */
    public static final List<String> INVALID_TRAILERS = List.of(
            "",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://www.youtube.com/embed/dQw4w9WgXcQ",
            "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtube.com/watch?v=dQw4w9WgXcQ",
            "http://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10",
            "https://www.youtube.com/watch?v=dQw4w9WgXc",
            "https://www.youtube.com/watch?v=dQw4w9WgX%Q",
            "https://www.youtube.com/watch?v=dQw4w9WgX Q",
            "https://www.youtube.com/watch?v=dQw4w9WgXñQ",
            // Signo Kelvin (U+212A): en minúscula es una «k» ASCII; por eso el CHECK no usa LOWER
            "https://www.youtube.com/watch?v=dQw4w9WgXKQ",
            "https://WWW.YOUTUBE.COM/watch?v=dQw4w9WgXcQ",
            "https://evil.example/watch?v=dQw4w9WgXcQ",
            "javascript:alert(1)//youtube.com/watch?v=");

    private TrailerUrlMigrationScenario() {
    }

    /**
     * Inserta el escenario en una base que está en la {@code V5} (sin las
     * columnas {@code trailer_url} y con {@code video_url} obligatorio).
     *
     * @param jdbc conexión a la base en V5
     */
    public static void seedAtV5(JdbcTemplate jdbc) {
        for (String[] movie : MOVIES) {
            jdbc.update("INSERT INTO movies (title, description, duration, release_year, image_url, video_url, created_at)"
                    + " VALUES (?, 'd', 100, 2000, 'https://example.com/i.jpg', ?, CURRENT_TIMESTAMP)", movie[0], movie[1]);
        }
        for (String[] series : SERIES) {
            jdbc.update("INSERT INTO series (title, description, release_year, image_url, created_at)"
                    + " VALUES (?, 'd', 2010, 'https://example.com/s.jpg', CURRENT_TIMESTAMP)", series[0]);
        }
        for (Object[] episode : EPISODES) {
            jdbc.update("INSERT INTO episodes (series_id, season_number, episode_number, title, duration, video_url,"
                    + " created_at) SELECT id, ?, ?, 'Episodio', 45, ?, CURRENT_TIMESTAMP FROM series WHERE title = ?",
                    episode[1], episode[2], episode[3], episode[0]);
        }
    }

    /**
     * Comprueba, fila a fila, lo que la {@code V6} debe haber movido y lo que
     * debe haber dejado intacto, y que no ha creado ni borrado filas.
     *
     * @param jdbc conexión a la base ya migrada a la {@code V6}
     */
    public static void assertMovedByV6(JdbcTemplate jdbc) {
        assertEquals(MOVIES.length, count(jdbc, "movies"), "la V6 no crea ni borra películas");
        assertEquals(SERIES.length, count(jdbc, "series"), "la V6 no crea ni borra series");
        assertEquals(EPISODES.length, count(jdbc, "episodes"), "la V6 no crea ni borra episodios");

        // Por id (= orden de inserción): no depende de la collation de cada motor
        assertEquals(expectedMovies(), jdbc.query(
                "SELECT title, video_url, trailer_url FROM movies ORDER BY id",
                (rs, i) -> Arrays.asList(rs.getString(1), rs.getString(2), rs.getString(3))));

        List<List<String>> expectedSeries = new ArrayList<>();
        Arrays.stream(SERIES).forEach(s -> expectedSeries.add(Arrays.asList(s[0], s[1])));
        assertEquals(expectedSeries, jdbc.query("SELECT title, trailer_url FROM series ORDER BY id",
                (rs, i) -> Arrays.asList(rs.getString(1), rs.getString(2))));

        for (Object[] episode : EPISODES) {
            String actual = jdbc.queryForObject("SELECT e.video_url FROM episodes e JOIN series s ON s.id = e.series_id"
                    + " WHERE s.title = ? AND e.season_number = ? AND e.episode_number = ?",
                    String.class, episode[0], episode[1], episode[2]);
            assertEquals(episode[4], actual, "video_url de " + episode[0] + " " + episode[1] + "x" + episode[2]);
        }

        // Las demás columnas no se tocan
        assertEquals(MOVIES.length, jdbc.queryForObject(
                "SELECT COUNT(*) FROM movies WHERE image_url = 'https://example.com/i.jpg' AND duration = 100",
                Integer.class));
    }

    private static List<List<String>> expectedMovies() {
        List<List<String>> expected = new ArrayList<>();
        Arrays.stream(MOVIES).forEach(m -> expected.add(Arrays.asList(m[0], m[2], m[3])));
        return expected;
    }

    private static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
