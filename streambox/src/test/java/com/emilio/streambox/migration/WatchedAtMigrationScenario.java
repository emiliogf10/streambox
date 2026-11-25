package com.emilio.streambox.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Escenario de datos para probar la migración
 * {@code V15__subtitles_and_finished_at.sql} sobre una base con progreso de
 * visionado, compartido por el test de H2 ({@link WatchedAtMigrationH2Test}) y
 * el de PostgreSQL real
 * ({@code postgres.PostgresWatchedAtMigrationIntegrationTest}).
 *
 * <p>
 * Se usa con una base migrada hasta la {@code V14}: {@link #seedAtV14} inserta
 * una cuenta, dos películas (una sin vídeo), una serie con dos episodios y un
 * progreso por título con posiciones a ambos lados del 95 %. Tras aplicar la
 * {@code V15}, {@link #assertMigratedByV15} comprueba que:
 * </p>
 * <ul>
 *   <li>las filas terminadas por posición (95 % exacto, 100 %) reciben
 *       {@code finished_at = updated_at}, y las demás (94,99 %, 0) quedan
 *       con {@code finished_at} nulo;</li>
 *   <li>no cambia nada más del progreso (posición, duración, fecha), ni el
 *       vídeo de ningún título, y todos quedan sin subtítulos.</li>
 * </ul>
 * <p>
 * El test falla si el relleno no existe (ninguna fila vista), si usa otro
 * umbral (p. ej. {@code >} en vez de {@code >=}, o el 94 %) o si pone otra fecha
 * (p. ej. la de la migración).
 * </p>
 */
public final class WatchedAtMigrationScenario {

    private static final OffsetDateTime T = OffsetDateTime.of(2026, 11, 1, 20, 0, 0, 0, ZoneOffset.UTC);

    /**
     * Progreso de películas: título, vídeo, posición, duración, fecha del
     * último guardado y si debe quedar visto tras la V15.
     */
    private static final Object[][] MOVIES = {
        { "Al 95 exacto", "https://example.com/a.mp4", 9500, 10000, T, true },
        { "Casi", "https://example.com/b.mp4", 9499, 10000, T.plusHours(1), false },
        { "Entera sin video", null, 30, 30, T.plusHours(2), true },
        { "Empezada", "https://example.com/c.mp4", 0, 6000, T.plusHours(3), false },
    };

    /** Progreso de episodios: número, posición, duración, fecha y si debe quedar visto. */
    private static final Object[][] EPISODES = {
        { 1, 2700, 2700, T.plusMinutes(10), true },
        { 2, 1, 2700, T.plusMinutes(20), false },
    };

    private WatchedAtMigrationScenario() {
    }

    /**
     * Inserta el escenario en una base en la {@code V14} (todavía sin
     * {@code finished_at} ni {@code subtitles_url}).
     *
     * @param jdbc acceso a la base de datos del test
     */
    public static void seedAtV14(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO users (username, email, password, role, created_at)"
                + " VALUES ('ana', 'ana@test.com', 'p', 'USER', CURRENT_TIMESTAMP)");
        long userId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'ana'", Long.class);
        for (Object[] movie : MOVIES) {
            jdbc.update("INSERT INTO movies (title, description, duration, release_year, image_url, video_url,"
                    + " created_at) VALUES (?, 'd', 100, 2000, 'u', ?, CURRENT_TIMESTAMP)", movie[0], movie[1]);
            long movieId = jdbc.queryForObject("SELECT id FROM movies WHERE title = ?", Long.class, movie[0]);
            jdbc.update("INSERT INTO user_movie_progress (user_id, movie_id, position_seconds, duration_seconds,"
                    + " updated_at) VALUES (?, ?, ?, ?, ?)", userId, movieId, movie[2], movie[3], movie[4]);
        }
        jdbc.update("INSERT INTO series (title, description, release_year, image_url, created_at)"
                + " VALUES ('Serie', 'd', 2010, 'u', CURRENT_TIMESTAMP)");
        long seriesId = jdbc.queryForObject("SELECT id FROM series WHERE title = 'Serie'", Long.class);
        for (Object[] episode : EPISODES) {
            jdbc.update("INSERT INTO episodes (series_id, season_number, episode_number, title, duration, video_url,"
                    + " created_at) VALUES (?, 1, ?, 't', 45, 'https://example.com/e.mp4', CURRENT_TIMESTAMP)",
                    seriesId, episode[0]);
            long episodeId = jdbc.queryForObject("SELECT id FROM episodes WHERE episode_number = ?", Long.class,
                    episode[0]);
            jdbc.update("INSERT INTO user_episode_progress (user_id, episode_id, position_seconds, duration_seconds,"
                    + " updated_at) VALUES (?, ?, ?, ?, ?)", userId, episodeId, episode[1], episode[2], episode[3]);
        }
    }

    /**
     * Comprueba el resultado de aplicar la {@code V15} sobre
     * {@link #seedAtV14}.
     *
     * @param jdbc acceso a la base de datos del test
     */
    public static void assertMigratedByV15(JdbcTemplate jdbc) {
        for (Object[] movie : MOVIES) {
            Map<String, Object> row = jdbc.queryForMap("SELECT p.position_seconds, p.duration_seconds, p.updated_at,"
                    + " p.finished_at, m.video_url, m.subtitles_url FROM user_movie_progress p"
                    + " JOIN movies m ON m.id = p.movie_id WHERE m.title = ?", movie[0]);
            String what = "película " + movie[0];
            assertProgress(what, row, (int) movie[2], (int) movie[3], (OffsetDateTime) movie[4], (boolean) movie[5]);
            assertEquals(movie[1], row.get("video_url"), what + ": el vídeo no cambia");
            assertEquals(null, row.get("subtitles_url"), what + ": nace sin subtítulos");
        }
        for (Object[] episode : EPISODES) {
            Map<String, Object> row = jdbc.queryForMap("SELECT p.position_seconds, p.duration_seconds, p.updated_at,"
                    + " p.finished_at, e.video_url, e.subtitles_url FROM user_episode_progress p"
                    + " JOIN episodes e ON e.id = p.episode_id WHERE e.episode_number = ?", episode[0]);
            String what = "episodio " + episode[0];
            assertProgress(what, row, (int) episode[1], (int) episode[2], (OffsetDateTime) episode[3],
                    (boolean) episode[4]);
            assertEquals("https://example.com/e.mp4", row.get("video_url"), what + ": el vídeo no cambia");
            assertEquals(null, row.get("subtitles_url"), what + ": nace sin subtítulos");
        }
        assertEquals(List.of(MOVIES.length, EPISODES.length), List.of(
                jdbc.queryForObject("SELECT COUNT(*) FROM user_movie_progress", Integer.class),
                jdbc.queryForObject("SELECT COUNT(*) FROM user_episode_progress", Integer.class)),
                "no se borra ni se crea progreso");
    }

    private static void assertProgress(String what, Map<String, Object> row, int position, int duration,
            OffsetDateTime updatedAt, boolean watched) {
        assertEquals(position, ((Number) row.get("position_seconds")).intValue(), what);
        assertEquals(duration, ((Number) row.get("duration_seconds")).intValue(), what);
        assertEquals(updatedAt.toInstant(), instant(row.get("updated_at")), what + ": la fecha no cambia");
        assertEquals(watched ? updatedAt.toInstant() : null, instant(row.get("finished_at")),
                what + (watched ? ": terminada, visto con la fecha de su último guardado" : ": a medias, sin visto"));
    }

    /**
     * Pasa a {@link java.time.Instant} lo que devuelva el driver para una
     * columna {@code TIMESTAMP WITH TIME ZONE} ({@link OffsetDateTime} en H2,
     * {@link java.sql.Timestamp} en PostgreSQL).
     */
    private static java.time.Instant instant(Object value) {
        return switch (value) {
            case null -> null;
            case OffsetDateTime odt -> odt.toInstant();
            case java.sql.Timestamp ts -> ts.toInstant();
            default -> throw new IllegalStateException("Tipo de fecha inesperado: " + value.getClass());
        };
    }
}
