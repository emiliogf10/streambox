package com.emilio.streambox.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.repository.ContinueWatchingItem.EpisodeItem;
import com.emilio.streambox.repository.ContinueWatchingItem.MovieItem;
import com.emilio.streambox.service.SqlStates;

/**
 * Casos de prueba del <b>progreso de visionado</b> ({@code V14}:
 * {@code user_movie_progress} y {@code user_episode_progress}), escritos
 * <strong>una sola vez</strong> y ejecutados en los dos motores: H2
 * ({@link WatchProgressRepositoryIntegrationTest}) y PostgreSQL real
 * ({@code PostgresWatchProgressIntegrationTest}, que además prueba la carrera
 * de dos primeros guardados). Mismo patrón que {@link LikesQueryContract}.
 *
 * <p>
 * Qué se prueba de {@link WatchProgressRepository}: el <i>upsert</i> (primer
 * guardado inserta, los siguientes sobrescriben, sin tocar a otros usuarios),
 * las lecturas, los {@code CHECK} con su {@code SQLSTATE} y su nombre, las
 * claves foráneas, las cascadas al borrar usuario, película, episodio y serie,
 * los borrados de «Quitar de Seguir viendo» y la consulta de «Seguir viendo»
 * (mezcla, orden, desempate, límite, terminados, sin vídeo y una tarjeta por
 * serie: su último episodio tocado o, si está terminado, el siguiente con
 * vídeo, criterio de QA-4). Las fechas se pasan explícitas (el servicio las tomará de su
 * {@code Clock}) para que el orden no dependa de la velocidad del test.
 * </p>
 */
public interface WatchProgressQueryContract {

    /** Instante base de las fechas fijadas a mano. */
    Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    // ------------------------------------------------------------------
    // Lo que aporta cada motor
    // ------------------------------------------------------------------

    /** @return el repositorio bajo prueba */
    WatchProgressRepository progress();

    /** @return acceso JDBC directo, para preparar datos y contar filas */
    JdbcTemplate jdbc();

    /** @return plantilla de transacción, como la que abrirá el servicio */
    TransactionTemplate tx();

    /** @return {@code SQLSTATE} de una violación de {@code CHECK} en este motor */
    String checkViolationState();

    /** @return {@code SQLSTATE} de una clave foránea hacia una fila inexistente en este motor */
    String foreignKeyViolationState();

    // ------------------------------------------------------------------
    // Utilidades comunes (datos con SQL directo, confirmados de verdad)
    // ------------------------------------------------------------------

    /** Inserta un usuario y devuelve su id. */
    default long user(String name) {
        jdbc().update("INSERT INTO users (username, email, password, role, created_at)"
                + " VALUES (?, ?, 'p', 'USER', CURRENT_TIMESTAMP)", name, name + "@test.com");
        return jdbc().queryForObject("SELECT id FROM users WHERE username = ?", Long.class, name);
    }

    /** Inserta una película con vídeo y devuelve su id. */
    default long movie(String title) {
        return movie(title, "https://example.com/video.mp4");
    }

    /** Inserta una película con el vídeo indicado (puede ser nulo) y devuelve su id. */
    default long movie(String title, String videoUrl) {
        jdbc().update("INSERT INTO movies (title, description, duration, release_year, image_url, video_url,"
                + " created_at) VALUES (?, 'd', 100, 2000, ?, ?, CURRENT_TIMESTAMP)",
                title, "https://example.com/" + title + ".jpg", videoUrl);
        return jdbc().queryForObject("SELECT id FROM movies WHERE title = ?", Long.class, title);
    }

    /** Inserta una serie (sin episodios) y devuelve su id. */
    default long serie(String title) {
        jdbc().update("INSERT INTO series (title, description, release_year, image_url, created_at)"
                + " VALUES (?, 'd', 2010, ?, CURRENT_TIMESTAMP)", title, "https://example.com/" + title + ".jpg");
        return jdbc().queryForObject("SELECT id FROM series WHERE title = ?", Long.class, title);
    }

    /** Inserta el episodio temporada × número (con vídeo) y devuelve su id. */
    default long episode(long seriesId, int season, int number) {
        return episode(seriesId, season, number, "https://example.com/episodio.mp4");
    }

    /** Inserta el episodio temporada × número con el vídeo indicado (puede ser nulo). */
    default long episode(long seriesId, int season, int number, String videoUrl) {
        jdbc().update("INSERT INTO episodes (series_id, season_number, episode_number, title, duration, video_url,"
                + " created_at) VALUES (?, ?, ?, ?, 45, ?, CURRENT_TIMESTAMP)",
                seriesId, season, number, "Episodio " + season + "x" + number, videoUrl);
        return jdbc().queryForObject("SELECT id FROM episodes WHERE series_id = ? AND season_number = ?"
                + " AND episode_number = ?", Long.class, seriesId, season, number);
    }

    /** Guarda el progreso de una película en su propia transacción. */
    default boolean saveMovie(long userId, long movieId, int position, int duration, Instant at) {
        return tx().execute(status -> progress().saveMovieProgress(userId, movieId, position, duration, at));
    }

    /** Guarda el progreso de un episodio en su propia transacción. */
    default boolean saveEpisode(long userId, long episodeId, int position, int duration, Instant at) {
        return tx().execute(status -> progress().saveEpisodeProgress(userId, episodeId, position, duration, at));
    }

    /** Filas de una tabla de progreso que cumplen {@code column = value}. */
    default int rows(String table, String column, long value) {
        return jdbc().queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, value);
    }

    /** «Seguir viendo» con un límite amplio. */
    default List<ContinueWatchingItem> continueWatching(long userId) {
        return progress().findContinueWatching(userId, 50);
    }

    /** Identificación compacta de cada elemento, para comparar listas: {@code M:<id>} o {@code E:<id>}. */
    default List<String> keys(List<ContinueWatchingItem> items) {
        return items.stream().map(item -> switch (item) {
            case MovieItem m -> "M:" + m.movieId();
            case EpisodeItem e -> "E:" + e.episodeId();
        }).toList();
    }

    /** Lanza el guardado y comprueba que lo rechaza el {@code CHECK} indicado, con su {@code SQLSTATE}. */
    default void assertRejectedByCheck(String constraint, Runnable save) {
        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class, save::run,
                constraint);
        assertEquals(checkViolationState(), SqlStates.find(error), constraint + ": " + error.getMessage());
        assertTrue(error.getMessage().toLowerCase(Locale.ROOT).contains(constraint), error.getMessage());
    }

    // ------------------------------------------------------------------
    // Guardar y leer
    // ------------------------------------------------------------------

    /**
     * El primer guardado inserta ({@code true}) y los siguientes sobrescriben
     * ({@code false}) posición, duración y fecha, dejando una sola fila por
     * usuario y título; no se mezcla con el progreso de otro usuario.
     */
    @Test
    default void elPrimerGuardadoInsertaYLosSiguientesSobrescriben() {
        long ana = user("ana");
        long bea = user("bea");
        long movie = movie("Pelicula");
        long episode = episode(serie("Serie"), 1, 1);

        assertTrue(saveMovie(ana, movie, 10, 6000, BASE));
        assertFalse(saveMovie(ana, movie, 300, 6001, BASE.plusSeconds(60)));
        assertTrue(saveMovie(bea, movie, 5, 6000, BASE));
        assertTrue(saveEpisode(ana, episode, 20, 2700, BASE));
        assertFalse(saveEpisode(ana, episode, 25, 2700, BASE.plusSeconds(5)));

        assertEquals(Optional.of(new WatchProgress(300, 6001, BASE.plusSeconds(60))),
                progress().findMovieProgress(ana, movie));
        assertEquals(Optional.of(new WatchProgress(5, 6000, BASE)), progress().findMovieProgress(bea, movie));
        assertEquals(Optional.of(new WatchProgress(25, 2700, BASE.plusSeconds(5))),
                progress().findEpisodeProgress(ana, episode));
        assertEquals(Optional.empty(), progress().findEpisodeProgress(bea, episode));
        assertEquals(2, rows("user_movie_progress", "movie_id", movie));
        assertEquals(1, rows("user_episode_progress", "episode_id", episode));
    }

    /** Leer el progreso de algo que nunca se empezó (o que ni existe) da vacío, sin fallar. */
    @Test
    default void leerUnProgresoInexistenteDaVacio() {
        long ana = user("ana");

        assertEquals(Optional.empty(), progress().findMovieProgress(ana, movie("Sin empezar")));
        assertEquals(Optional.empty(), progress().findMovieProgress(ana, Long.MAX_VALUE));
        assertEquals(Optional.empty(), progress().findEpisodeProgress(ana, Long.MAX_VALUE));
        assertEquals(Map.of(), progress().findEpisodeProgressBySeries(ana, Long.MAX_VALUE));
    }

    /**
     * El progreso de los episodios de una serie llega en una sola consulta,
     * ordenado por temporada y número, solo de esa serie y de ese usuario y con
     * los terminados incluidos (la página de la serie pinta todas las barras).
     */
    @Test
    default void elProgresoDeUnaSerieLlegaOrdenadoYSoloDeEsaSerieYUsuario() {
        long ana = user("ana");
        long bea = user("bea");
        long serie = serie("Serie");
        long s2e1 = episode(serie, 2, 1);
        long s1e2 = episode(serie, 1, 2);
        long s1e1 = episode(serie, 1, 1);
        long other = episode(serie("Otra"), 1, 1);
        saveEpisode(ana, s2e1, 10, 100, BASE);
        saveEpisode(ana, s1e2, 100, 100, BASE.plusSeconds(1));
        saveEpisode(ana, s1e1, 50, 100, BASE.plusSeconds(2));
        saveEpisode(ana, other, 10, 100, BASE);
        saveEpisode(bea, s1e1, 70, 100, BASE);

        Map<Long, WatchProgress> bySeries = progress().findEpisodeProgressBySeries(ana, serie);

        assertEquals(List.of(s1e1, s1e2, s2e1), List.copyOf(bySeries.keySet()));
        assertEquals(new WatchProgress(50, 100, BASE.plusSeconds(2)), bySeries.get(s1e1));
        assertTrue(bySeries.get(s1e2).finished());
    }

    // ------------------------------------------------------------------
    // Restricciones
    // ------------------------------------------------------------------

    /**
     * Los límites admitidos se guardan: posición 0, posición igual a la
     * duración, duración de 1 s y de 24 h exactas.
     */
    @Test
    default void losLimitesAdmitidosSeGuardan() {
        long ana = user("ana");
        long movie = movie("Pelicula");
        long episode = episode(serie("Serie"), 1, 1);

        saveMovie(ana, movie, 0, 1, BASE);
        assertEquals(Optional.of(new WatchProgress(0, 1, BASE)), progress().findMovieProgress(ana, movie));
        saveMovie(ana, movie, 1, 1, BASE);
        saveMovie(ana, movie, WatchProgress.MAX_DURATION_SECONDS, WatchProgress.MAX_DURATION_SECONDS, BASE);
        saveEpisode(ana, episode, 0, WatchProgress.MAX_DURATION_SECONDS, BASE);

        assertEquals(Optional.of(new WatchProgress(86_400, 86_400, BASE)), progress().findMovieProgress(ana, movie));
    }

    /**
     * Cada {@code CHECK} de la {@code V14} rechaza lo suyo, en las dos tablas,
     * con el {@code SQLSTATE} de violación de {@code CHECK} y el nombre de la
     * restricción (lo que permitiría al servicio reconocerla). Ninguno deja
     * fila.
     */
    @Test
    default void laBaseDeDatosRechazaPosicionesYDuracionesImposibles() {
        long ana = user("ana");
        long movie = movie("Pelicula");
        long episode = episode(serie("Serie"), 1, 1);

        assertRejectedByCheck("ck_movie_progress_position", () -> saveMovie(ana, movie, -1, 100, BASE));
        assertRejectedByCheck("ck_movie_progress_duration", () -> saveMovie(ana, movie, 0, 0, BASE));
        assertRejectedByCheck("ck_movie_progress_duration", () -> saveMovie(ana, movie, 0, 86_401, BASE));
        assertRejectedByCheck("ck_movie_progress_position_within_duration",
                () -> saveMovie(ana, movie, 101, 100, BASE));
        assertRejectedByCheck("ck_episode_progress_position", () -> saveEpisode(ana, episode, -1, 100, BASE));
        assertRejectedByCheck("ck_episode_progress_duration", () -> saveEpisode(ana, episode, 0, 0, BASE));
        assertRejectedByCheck("ck_episode_progress_duration", () -> saveEpisode(ana, episode, 0, 86_401, BASE));
        assertRejectedByCheck("ck_episode_progress_position_within_duration",
                () -> saveEpisode(ana, episode, 101, 100, BASE));

        assertEquals(0, rows("user_movie_progress", "user_id", ana));
        assertEquals(0, rows("user_episode_progress", "user_id", ana));
    }

    /**
     * Una fila que ya existe tampoco se puede sobrescribir con valores
     * imposibles: el {@code UPDATE} del <i>upsert</i> también pasa por los
     * {@code CHECK}, y el valor anterior se conserva.
     */
    @Test
    default void tampocoSeSobrescribeConValoresImposibles() {
        long ana = user("ana");
        long movie = movie("Pelicula");
        saveMovie(ana, movie, 10, 100, BASE);

        assertRejectedByCheck("ck_movie_progress_position_within_duration",
                () -> saveMovie(ana, movie, 200, 100, BASE.plusSeconds(1)));

        assertEquals(Optional.of(new WatchProgress(10, 100, BASE)), progress().findMovieProgress(ana, movie));
    }

    /**
     * Las claves foráneas impiden guardar el progreso de una película, un
     * episodio o un usuario inexistentes, con el {@code SQLSTATE} de clave
     * foránea y el nombre de cada una (el servicio distingue así «usuario
     * borrado» de «título borrado», como en los «me gusta»).
     */
    @Test
    default void noSeGuardaElProgresoDeAlgoQueNoExiste() {
        long ana = user("ana");
        long movie = movie("Pelicula");
        long episode = episode(serie("Serie"), 1, 1);

        Map<String, Runnable> cases = Map.of(
                WatchProgressRepository.MOVIE_PROGRESS_MOVIE_FK, () -> saveMovie(ana, Long.MAX_VALUE, 1, 10, BASE),
                WatchProgressRepository.MOVIE_PROGRESS_USER_FK, () -> saveMovie(Long.MAX_VALUE, movie, 1, 10, BASE),
                WatchProgressRepository.EPISODE_PROGRESS_EPISODE_FK,
                () -> saveEpisode(ana, Long.MAX_VALUE, 1, 10, BASE),
                WatchProgressRepository.EPISODE_PROGRESS_USER_FK,
                () -> saveEpisode(Long.MAX_VALUE, episode, 1, 10, BASE));
        cases.forEach((constraint, save) -> {
            DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class, save::run,
                    constraint);
            assertEquals(foreignKeyViolationState(), SqlStates.find(error), constraint);
            assertTrue(error.getMessage().toLowerCase(Locale.ROOT).contains(constraint), error.getMessage());
        });
    }

    // ------------------------------------------------------------------
    // Cascadas
    // ------------------------------------------------------------------

    /**
     * Borrar una película o un episodio borra su progreso (de todos los
     * usuarios) y nada más; borrar una serie borra el de todos sus episodios
     * (cascada serie → episodios → progreso); borrar un usuario borra todo el
     * suyo y nada del de otros.
     */
    @Test
    default void borrarUnTituloUnaSerieOUnUsuarioBorraSuProgresoYNadaMas() {
        long ana = user("ana");
        long bea = user("bea");
        long keptMovie = movie("Sigue");
        long doomedMovie = movie("Se borra");
        long keptSerie = serie("Serie que sigue");
        long keptEpisode = episode(keptSerie, 1, 1);
        long doomedEpisode = episode(keptSerie, 1, 2);
        long doomedSerie = serie("Serie que se borra");
        long doomedSerieEpisode1 = episode(doomedSerie, 1, 1);
        long doomedSerieEpisode2 = episode(doomedSerie, 1, 2);
        for (long user : new long[] { ana, bea }) {
            saveMovie(user, keptMovie, 1, 10, BASE);
            saveMovie(user, doomedMovie, 1, 10, BASE);
            saveEpisode(user, keptEpisode, 1, 10, BASE);
            saveEpisode(user, doomedEpisode, 1, 10, BASE);
            saveEpisode(user, doomedSerieEpisode1, 1, 10, BASE);
            saveEpisode(user, doomedSerieEpisode2, 1, 10, BASE);
        }

        jdbc().update("DELETE FROM movies WHERE id = ?", doomedMovie);
        jdbc().update("DELETE FROM episodes WHERE id = ?", doomedEpisode);
        jdbc().update("DELETE FROM series WHERE id = ?", doomedSerie);

        assertEquals(0, rows("user_movie_progress", "movie_id", doomedMovie));
        assertEquals(2, rows("user_movie_progress", "movie_id", keptMovie));
        assertEquals(0, rows("user_episode_progress", "episode_id", doomedEpisode));
        assertEquals(0, rows("user_episode_progress", "episode_id", doomedSerieEpisode1));
        assertEquals(0, rows("user_episode_progress", "episode_id", doomedSerieEpisode2));
        assertEquals(2, rows("user_episode_progress", "episode_id", keptEpisode));

        jdbc().update("DELETE FROM users WHERE id = ?", ana);

        assertEquals(0, rows("user_movie_progress", "user_id", ana));
        assertEquals(0, rows("user_episode_progress", "user_id", ana));
        assertEquals(1, rows("user_movie_progress", "user_id", bea));
        assertEquals(1, rows("user_episode_progress", "user_id", bea));
    }

    // ------------------------------------------------------------------
    // «Quitar de Seguir viendo»
    // ------------------------------------------------------------------

    /**
     * Los tres borrados son idempotentes (0 si no había nada, también de
     * títulos inexistentes) y solo tocan lo del usuario: el de una serie se
     * lleva todos sus episodios con progreso y ninguno de otra serie.
     */
    @Test
    default void quitarDeSeguirViendoBorraSoloLoDeEseUsuarioYTitulo() {
        long ana = user("ana");
        long bea = user("bea");
        long movie = movie("Pelicula");
        long serie = serie("Serie");
        long e1 = episode(serie, 1, 1);
        long e2 = episode(serie, 1, 2);
        long e3 = episode(serie, 1, 3);
        long other = episode(serie("Otra"), 1, 1);
        for (long user : new long[] { ana, bea }) {
            saveMovie(user, movie, 1, 10, BASE);
            saveEpisode(user, e1, 1, 10, BASE);
            saveEpisode(user, e2, 1, 10, BASE);
            saveEpisode(user, e3, 1, 10, BASE);
            saveEpisode(user, other, 1, 10, BASE);
        }

        assertEquals(1, (int) tx().execute(s -> progress().deleteMovieProgress(ana, movie)));
        assertEquals(0, (int) tx().execute(s -> progress().deleteMovieProgress(ana, movie)));
        assertEquals(0, (int) tx().execute(s -> progress().deleteMovieProgress(ana, Long.MAX_VALUE)));
        assertEquals(1, (int) tx().execute(s -> progress().deleteEpisodeProgress(ana, e3)));
        assertEquals(0, (int) tx().execute(s -> progress().deleteEpisodeProgress(ana, e3)));
        assertEquals(2, (int) tx().execute(s -> progress().deleteSeriesProgress(ana, serie)));
        assertEquals(0, (int) tx().execute(s -> progress().deleteSeriesProgress(ana, serie)));
        assertEquals(0, (int) tx().execute(s -> progress().deleteSeriesProgress(ana, Long.MAX_VALUE)));

        assertEquals(0, rows("user_movie_progress", "user_id", ana));
        assertEquals(List.of(other), jdbc().queryForList(
                "SELECT episode_id FROM user_episode_progress WHERE user_id = ?", Long.class, ana));
        assertEquals(1, rows("user_movie_progress", "user_id", bea));
        assertEquals(4, rows("user_episode_progress", "user_id", bea));
    }

    // ------------------------------------------------------------------
    // «Seguir viendo»
    // ------------------------------------------------------------------

    /**
     * La lista mezcla películas y episodios del más reciente al más antiguo,
     * con los datos de cada tarjeta (película: id, título, imagen; episodio:
     * id, temporada, número, título y la serie con su id, título e imagen) y
     * su progreso; no incluye nada de otro usuario.
     */
    @Test
    default void seguirViendoMezclaPeliculasYEpisodiosDelMasRecienteAlMasAntiguo() {
        long ana = user("ana");
        long bea = user("bea");
        long older = movie("Antigua");
        long newer = movie("Reciente");
        long serie = serie("Serie");
        long episode = episode(serie, 2, 3);
        saveMovie(ana, older, 10, 100, BASE);
        saveEpisode(ana, episode, 30, 2700, BASE.plusSeconds(10));
        saveMovie(ana, newer, 20, 100, BASE.plusSeconds(20));
        saveMovie(bea, movie("De otra persona"), 1, 100, BASE.plusSeconds(99));

        List<ContinueWatchingItem> items = continueWatching(ana);

        assertEquals(List.of("M:" + newer, "E:" + episode, "M:" + older), keys(items));
        assertEquals(new MovieItem(newer, "Reciente", "https://example.com/Reciente.jpg",
                new WatchProgress(20, 100, BASE.plusSeconds(20))), items.get(0));
        assertEquals(new EpisodeItem(episode, 2, 3, "Episodio 2x3", serie, "Serie", "https://example.com/Serie.jpg",
                new WatchProgress(30, 2700, BASE.plusSeconds(10)), false, BASE.plusSeconds(10)), items.get(1));
    }

    /**
     * Guardar otra vez un título lo sube al principio (la fecha que ordena es
     * la del último guardado, no la del primero).
     */
    @Test
    default void volverAGuardarUnTituloLoSubeAlPrincipio() {
        long ana = user("ana");
        long first = movie("Primera");
        long second = movie("Segunda");
        saveMovie(ana, first, 10, 100, BASE);
        saveMovie(ana, second, 10, 100, BASE.plusSeconds(1));

        saveMovie(ana, first, 11, 100, BASE.plusSeconds(2));

        assertEquals(List.of("M:" + first, "M:" + second), keys(continueWatching(ana)));
    }

    /**
     * El límite corta la lista después de ordenar (se quedan los más
     * recientes) y un límite no positivo es un error de programación.
     */
    @Test
    default void elLimiteSeQuedaConLosMasRecientes() {
        long ana = user("ana");
        for (int i = 0; i < 4; i++) {
            saveMovie(ana, movie("Pelicula " + i), 10, 100, BASE.plusSeconds(i * 2L));
            saveEpisode(ana, episode(serie("Serie " + i), 1, 1), 10, 100, BASE.plusSeconds(i * 2L + 1));
        }

        List<ContinueWatchingItem> top3 = progress().findContinueWatching(ana, 3);

        assertEquals(keys(continueWatching(ana)).subList(0, 3), keys(top3));
        assertEquals(8, continueWatching(ana).size());
        assertEquals(BASE.plusSeconds(7), top3.get(0).progress().updatedAt());
        assertEquals(1, progress().findContinueWatching(ana, 1).size());
        assertThrows(InvalidDataAccessApiUsageException.class, () -> progress().findContinueWatching(ana, 0));
    }

    /**
     * «Terminado» es llegar al 95 % de la duración: en el 95 % exacto ya no
     * sale, un segundo antes sí. La fila de lo terminado se conserva (para
     * reanudar o pintar la barra), solo desaparece de la lista. Coincide con
     * {@link WatchProgress#isFinished(int, int)}.
     */
    @Test
    default void loTerminadoNoSaleEnSeguirViendo() {
        long ana = user("ana");
        long almost = movie("Casi");
        long done = movie("Al 95");
        long fully = movie("Entera");
        long serie = serie("Serie");
        long almostEpisode = episode(serie, 1, 1);
        long doneEpisode = episode(serie("Otra"), 1, 1);
        saveMovie(ana, almost, 949, 1000, BASE);
        saveMovie(ana, done, 950, 1000, BASE.plusSeconds(1));
        saveMovie(ana, fully, 1000, 1000, BASE.plusSeconds(2));
        saveEpisode(ana, almostEpisode, 1899, 2000, BASE.plusSeconds(3));
        saveEpisode(ana, doneEpisode, 1900, 2000, BASE.plusSeconds(4));

        assertEquals(List.of("E:" + almostEpisode, "M:" + almost), keys(continueWatching(ana)));
        assertFalse(WatchProgress.isFinished(949, 1000));
        assertTrue(WatchProgress.isFinished(950, 1000));
        assertTrue(progress().findMovieProgress(ana, done).orElseThrow().finished());
        assertTrue(progress().findEpisodeProgress(ana, doneEpisode).isPresent());
    }

    /**
     * Un título sin vídeo (el administrador lo quitó) no sale: su tarjeta
     * prometería un «Reanudar» imposible. Si el vídeo vuelve, vuelve a salir
     * con su progreso, que no se ha perdido.
     */
    @Test
    default void loQueNoTieneVideoNoSaleEnSeguirViendo() {
        long ana = user("ana");
        long movie = movie("Sin video luego");
        long serie = serie("Serie");
        long episode = episode(serie, 1, 1);
        long olderEpisode = episode(serie, 1, 2);
        saveMovie(ana, movie, 10, 100, BASE);
        saveEpisode(ana, olderEpisode, 10, 100, BASE.plusSeconds(1));
        saveEpisode(ana, episode, 10, 100, BASE.plusSeconds(2));

        jdbc().update("UPDATE movies SET video_url = NULL WHERE id = ?", movie);
        jdbc().update("UPDATE episodes SET video_url = NULL WHERE id = ?", episode);

        assertEquals(List.of("E:" + olderEpisode), keys(continueWatching(ana)),
                "de la serie sale el episodio con vídeo; la película, no");

        jdbc().update("UPDATE movies SET video_url = 'https://example.com/v.mp4' WHERE id = ?", movie);

        assertEquals(List.of("E:" + olderEpisode, "M:" + movie), keys(continueWatching(ana)));
    }

    // ------------------------------------------------------------------
    // «Seguir viendo»: la tarjeta de cada serie (criterio de QA-4)
    // ------------------------------------------------------------------

    /** La única tarjeta de la serie en la lista (falla si hay cero o más de una). */
    default EpisodeItem seriesCard(long userId, long seriesId) {
        List<EpisodeItem> cards = continueWatching(userId).stream()
                .filter(EpisodeItem.class::isInstance).map(EpisodeItem.class::cast)
                .filter(e -> e.seriesId() == seriesId).toList();
        assertEquals(1, cards.size(), "una tarjeta por serie: " + cards);
        return cards.get(0);
    }

    /**
     * Una tarjeta por serie: es el <b>último episodio tocado</b> (el guardado
     * más reciente), si está a medias, aunque haya otros a medias más
     * antiguos; cada serie ocupa su sitio según ese episodio. Quitar solo ese
     * episodio deja como último tocado el anterior; quitar la serie la saca.
     */
    @Test
    default void laTarjetaDeUnaSerieEsSuUltimoEpisodioTocadoSiEstaAMedias() {
        long ana = user("ana");
        long serie = serie("Serie");
        long e1 = episode(serie, 1, 1);
        long e2 = episode(serie, 1, 2);
        long otherSerie = serie("Otra");
        long o1 = episode(otherSerie, 1, 1);
        long movie = movie("Pelicula");
        saveEpisode(ana, e1, 10, 100, BASE);
        saveEpisode(ana, e2, 20, 100, BASE.plusSeconds(2));
        saveEpisode(ana, o1, 10, 100, BASE.plusSeconds(1));
        saveMovie(ana, movie, 10, 100, BASE.plusSeconds(3));

        assertEquals(List.of("M:" + movie, "E:" + e2, "E:" + o1), keys(continueWatching(ana)));
        EpisodeItem card = seriesCard(ana, serie);
        assertFalse(card.upNext());
        assertEquals(new WatchProgress(20, 100, BASE.plusSeconds(2)), card.progress());
        assertEquals(BASE.plusSeconds(2), card.updatedAt());

        tx().execute(s -> progress().deleteEpisodeProgress(ana, e2));
        assertEquals(List.of("M:" + movie, "E:" + o1, "E:" + e1), keys(continueWatching(ana)),
                "al quitar solo el episodio, el último tocado pasa a ser el anterior");

        tx().execute(s -> progress().deleteSeriesProgress(ana, serie));
        assertEquals(List.of("M:" + movie, "E:" + o1), keys(continueWatching(ana)));
    }

    /**
     * El ejemplo de QA-4: 1x01 a medias y después 1x02 terminado. El último
     * tocado es el 1x02, así que la tarjeta es el <b>siguiente</b>, el 1x03
     * ({@code upNext}), sin progreso porque nunca lo empezó, y ordenada por la
     * fecha del 1x02. Lo mismo que dice la página de la serie («Ver T1:E3»),
     * no el 1x01 a medias.
     */
    @Test
    default void siElUltimoTocadoEstaTerminadoLaTarjetaEsElSiguiente() {
        long ana = user("ana");
        long serie = serie("Serie");
        long e1 = episode(serie, 1, 1);
        long e2 = episode(serie, 1, 2);
        long e3 = episode(serie, 1, 3);
        saveEpisode(ana, e1, 10, 100, BASE);
        saveEpisode(ana, e2, 95, 100, BASE.plusSeconds(5));

        assertEquals(List.of("E:" + e3), keys(continueWatching(ana)));
        assertEquals(new EpisodeItem(e3, 1, 3, "Episodio 1x3", serie, "Serie", "https://example.com/Serie.jpg",
                null, true, BASE.plusSeconds(5)), seriesCard(ana, serie));
        assertTrue(progress().findEpisodeProgress(ana, e1).isPresent(), "el 1x01 conserva su progreso");
    }

    /**
     * El siguiente va por temporada y número (no por id ni por fecha de alta):
     * tras el último de una temporada viene el primero de la siguiente aunque
     * se diera de alta antes que otros; los episodios sin vídeo se saltan.
     */
    @Test
    default void elSiguientePuedeSerDeOtraTemporadaYSaltaLosQueNoTienenVideo() {
        long ana = user("ana");
        long serie = serie("Serie");
        long s2e1 = episode(serie, 2, 1);
        long s1e2 = episode(serie, 1, 2);
        episode(serie, 2, 2);
        long s1e1 = episode(serie, 1, 1);
        long other = serie("Otra");
        long o1 = episode(other, 1, 1);
        episode(other, 1, 2, null);
        episode(other, 1, 3, null);
        long o4 = episode(other, 2, 1);
        saveEpisode(ana, s1e1, 100, 100, BASE);
        saveEpisode(ana, s1e2, 100, 100, BASE.plusSeconds(1));
        saveEpisode(ana, o1, 100, 100, BASE.plusSeconds(2));

        assertEquals(List.of("E:" + o4, "E:" + s2e1), keys(continueWatching(ana)),
                "el 1x02 sin más episodios en la temporada 1 pasa al 2x01 (dado de alta antes que el 1x01);"
                        + " el 1x01 de la otra salta el 1x02 y el 1x03 sin vídeo y pasa al 2x01");
        assertTrue(seriesCard(ana, serie).upNext());
        assertTrue(seriesCard(ana, other).upNext());
    }

    /**
     * Si el último tocado está terminado y no queda ningún episodio con vídeo
     * después (el último de la serie, o los que quedan no tienen vídeo), la
     * serie no sale: el usuario está al día. Si el administrador añade (o da
     * vídeo a) un episodio posterior, vuelve a salir con ese.
     */
    @Test
    default void siElUltimoTocadoEstaTerminadoYNoHaySiguienteLaSerieNoSale() {
        long ana = user("ana");
        long serie = serie("Serie");
        long e1 = episode(serie, 1, 1);
        long e2 = episode(serie, 1, 2);
        long e3 = episode(serie, 1, 3, null);
        long movie = movie("Pelicula");
        saveEpisode(ana, e1, 10, 100, BASE);
        saveEpisode(ana, e2, 100, 100, BASE.plusSeconds(1));
        saveMovie(ana, movie, 10, 100, BASE);

        assertEquals(List.of("M:" + movie), keys(continueWatching(ana)),
                "ni el 1x01 a medias ni el 1x03 sin vídeo");

        jdbc().update("UPDATE episodes SET video_url = 'https://example.com/v.mp4' WHERE id = ?", e3);

        assertEquals(List.of("E:" + e3, "M:" + movie), keys(continueWatching(ana)));
    }

    /**
     * El progreso del siguiente: si lo tiene a medias (lo empezó antes) la
     * tarjeta lo muestra para reanudarlo; si lo tiene terminado (lo vio antes
     * entero), progreso nulo, porque se vuelve a ver desde el principio. En los
     * dos casos la tarjeta se ordena por la fecha del episodio terminado, no
     * por la del siguiente.
     */
    @Test
    default void elSiguienteLlevaSuProgresoSoloSiLoTieneAMedias() {
        long ana = user("ana");
        long halfSerie = serie("A medias");
        long h1 = episode(halfSerie, 1, 1);
        long h2 = episode(halfSerie, 1, 2);
        long doneSerie = serie("Terminado");
        long d1 = episode(doneSerie, 1, 1);
        long d2 = episode(doneSerie, 1, 2);
        long movie = movie("Pelicula");
        saveEpisode(ana, h2, 30, 100, BASE);
        saveEpisode(ana, h1, 100, 100, BASE.plusSeconds(10));
        saveEpisode(ana, d2, 99, 100, BASE.plusSeconds(1));
        saveEpisode(ana, d1, 96, 100, BASE.plusSeconds(8));
        saveMovie(ana, movie, 10, 100, BASE.plusSeconds(9));

        assertEquals(List.of("E:" + h2, "M:" + movie, "E:" + d2), keys(continueWatching(ana)));
        EpisodeItem half = seriesCard(ana, halfSerie);
        assertTrue(half.upNext());
        assertEquals(new WatchProgress(30, 100, BASE), half.progress());
        assertEquals(BASE.plusSeconds(10), half.updatedAt());
        EpisodeItem done = seriesCard(ana, doneSerie);
        assertTrue(done.upNext());
        assertNull(done.progress());
        assertEquals(BASE.plusSeconds(8), done.updatedAt());
    }

    /**
     * Un episodio sin vídeo no cuenta como «tocado», aunque sea el guardado más
     * reciente: si el usuario tiene a medias otro con vídeo, la tarjeta es
     * ese; y si el último con vídeo está terminado, su siguiente (saltándose
     * el que no tiene vídeo).
     */
    @Test
    default void unEpisodioSinVideoNoCuentaComoUltimoTocado() {
        long ana = user("ana");
        long halfSerie = serie("A medias");
        long h1 = episode(halfSerie, 1, 1);
        long h2 = episode(halfSerie, 1, 2);
        long doneSerie = serie("Terminado");
        long d1 = episode(doneSerie, 1, 1);
        long d2 = episode(doneSerie, 1, 2);
        long d3 = episode(doneSerie, 1, 3);
        saveEpisode(ana, h1, 10, 100, BASE);
        saveEpisode(ana, h2, 100, 100, BASE.plusSeconds(5));
        saveEpisode(ana, d1, 100, 100, BASE.plusSeconds(1));
        saveEpisode(ana, d2, 10, 100, BASE.plusSeconds(6));
        jdbc().update("UPDATE episodes SET video_url = NULL WHERE id IN (?, ?)", h2, d2);

        assertEquals(List.of("E:" + d3, "E:" + h1), keys(continueWatching(ana)));
        EpisodeItem half = seriesCard(ana, halfSerie);
        assertFalse(half.upNext());
        assertEquals(BASE, half.updatedAt());
        EpisodeItem next = seriesCard(ana, doneSerie);
        assertTrue(next.upNext());
        assertNull(next.progress());
        assertEquals(BASE.plusSeconds(1), next.updatedAt(), "la fecha es la del 1x01, el último tocado con vídeo");
    }

    /**
     * Dos episodios de la misma serie guardados en el mismo instante: el
     * último tocado es el de id mayor, también si es el terminado (la tarjeta
     * pasa entonces a su siguiente). El resultado no cambia de una petición a
     * otra.
     */
    @Test
    default void elEmpateDelUltimoTocadoSeDeshacePorIdTambienSiEstaTerminado() {
        long ana = user("ana");
        long serie = serie("Serie");
        long e1 = episode(serie, 1, 1);
        long e2 = episode(serie, 1, 2);
        long e3 = episode(serie, 1, 3);
        saveEpisode(ana, e1, 10, 100, BASE); // id menor (dado de alta antes), a medias
        saveEpisode(ana, e2, 100, 100, BASE); // id mayor, terminado: gana el empate

        assertEquals(List.of("E:" + e3), keys(continueWatching(ana)));
        assertEquals(List.of("E:" + e3), keys(continueWatching(ana)), "la misma petición da lo mismo");
    }

    /**
     * Revisión QA de QA-4: el progreso del <b>siguiente</b> ({@code LEFT JOIN
     * np}) es solo el del propio usuario. Si otra persona tiene ese episodio a
     * medias (o más reciente), la tarjeta de Ana sigue sin progreso y con su
     * propia fecha; y la de Bea no se ve afectada por lo que hizo Ana. Protege
     * el filtro {@code np.user_id = :userId}: sin él, Ana vería «Reanudar» con
     * la posición de Bea.
     */
    @Test
    default void elProgresoDelSiguienteEsSoloElDelPropioUsuario() {
        long ana = user("ana");
        long bea = user("bea");
        long serie = serie("Serie");
        long e1 = episode(serie, 1, 1);
        long e2 = episode(serie, 1, 2);
        saveEpisode(ana, e1, 100, 100, BASE.plusSeconds(1));
        saveEpisode(bea, e2, 30, 100, BASE.plusSeconds(5));

        EpisodeItem anaCard = seriesCard(ana, serie);
        assertEquals(e2, anaCard.episodeId());
        assertTrue(anaCard.upNext());
        assertNull(anaCard.progress(), "el progreso de Bea en el 1x02 no es de Ana");
        assertEquals(BASE.plusSeconds(1), anaCard.updatedAt());

        EpisodeItem beaCard = seriesCard(bea, serie);
        assertEquals(e2, beaCard.episodeId());
        assertFalse(beaCard.upNext(), "Bea no ha terminado nada: su tarjeta es su propio 1x02 a medias");
        assertEquals(new WatchProgress(30, 100, BASE.plusSeconds(5)), beaCard.progress());
    }

    /**
     * Revisión QA de QA-4: el umbral de «terminado» del siguiente es el mismo
     * 95 % que el del último tocado ({@code notFinished("np")}): al 94,9 % la
     * tarjeta lleva su progreso para reanudarlo; al 95 % exacto, progreso nulo
     * (se verá desde el principio). Coincide con {@link WatchProgress#isFinished(int, int)}.
     */
    @Test
    default void elUmbralDeTerminadoDelSiguienteEsElMismoNoventaYCincoPorCiento() {
        long ana = user("ana");
        long almostSerie = serie("Casi");
        long a1 = episode(almostSerie, 1, 1);
        long a2 = episode(almostSerie, 1, 2);
        long doneSerie = serie("Al 95");
        long b1 = episode(doneSerie, 1, 1);
        long b2 = episode(doneSerie, 1, 2);
        // El siguiente se guardó ANTES que el terminado (si no, sería él el último tocado).
        saveEpisode(ana, a2, 949, 1000, BASE);
        saveEpisode(ana, b2, 950, 1000, BASE.plusSeconds(1));
        saveEpisode(ana, a1, 1000, 1000, BASE.plusSeconds(2));
        saveEpisode(ana, b1, 1000, 1000, BASE.plusSeconds(3));

        EpisodeItem almost = seriesCard(ana, almostSerie);
        assertEquals(a2, almost.episodeId());
        assertTrue(almost.upNext());
        assertEquals(new WatchProgress(949, 1000, BASE), almost.progress());

        EpisodeItem done = seriesCard(ana, doneSerie);
        assertEquals(b2, done.episodeId());
        assertTrue(done.upNext());
        assertNull(done.progress(), "al 95 % el siguiente ya está visto: no hay nada que reanudar");
    }

    /**
     * Revisión QA de QA-4: una serie de un solo episodio. A medias, la tarjeta
     * es ese episodio; terminado, no hay siguiente y la serie sale de la fila
     * (la fila de progreso se conserva).
     */
    @Test
    default void unaSerieDeUnSoloEpisodioSaleAMediasYDesapareceAlTerminarla() {
        long ana = user("ana");
        long serie = serie("Miniserie");
        long only = episode(serie, 1, 1);
        saveEpisode(ana, only, 10, 100, BASE);

        EpisodeItem card = seriesCard(ana, serie);
        assertEquals(only, card.episodeId());
        assertFalse(card.upNext());

        saveEpisode(ana, only, 100, 100, BASE.plusSeconds(1));

        assertEquals(List.of(), continueWatching(ana));
        assertTrue(progress().findEpisodeProgress(ana, only).isPresent());
    }

    /**
     * Revisión QA de QA-4: el límite se aplica <b>después</b> de quitar las
     * series terminadas sin siguiente. Una serie al día más reciente no puede
     * ocupar el único hueco y dejar la fila vacía (o con una tarjeta de menos).
     */
    @Test
    default void elLimiteNoLoGastanLasSeriesQueNoSalen() {
        long ana = user("ana");
        long movie = movie("Pelicula");
        long serie = serie("Al dia");
        long last = episode(serie, 1, 1);
        saveMovie(ana, movie, 10, 100, BASE);
        saveEpisode(ana, last, 100, 100, BASE.plusSeconds(5));

        assertEquals(List.of("M:" + movie), keys(progress().findContinueWatching(ana, 1)));
    }

    /**
     * Revisión QA de QA-4: si el administrador <b>borra</b> episodios, la
     * tarjeta se recalcula con lo que queda (las cascadas de la {@code V14} se
     * llevan su progreso): borrado el último tocado, manda el anterior; borrado
     * el siguiente previsto, pasa al que venga después.
     */
    @Test
    default void borrarEpisodiosRecalculaLaTarjetaConLosQueQuedan() {
        long ana = user("ana");
        long serie = serie("Serie");
        long e1 = episode(serie, 1, 1);
        long e2 = episode(serie, 1, 2);
        long e3 = episode(serie, 1, 3);
        long e4 = episode(serie, 1, 4);
        saveEpisode(ana, e1, 100, 100, BASE);
        saveEpisode(ana, e3, 50, 100, BASE.plusSeconds(1));

        assertEquals(List.of("E:" + e3), keys(continueWatching(ana)), "el último tocado, a medias");

        jdbc().update("DELETE FROM episodes WHERE id = ?", e3);
        assertEquals(List.of("E:" + e2), keys(continueWatching(ana)),
                "sin el 1x03, el último tocado es el 1x01 terminado: su siguiente es el 1x02");
        assertEquals(BASE, seriesCard(ana, serie).updatedAt());

        jdbc().update("DELETE FROM episodes WHERE id = ?", e2);
        assertEquals(List.of("E:" + e4), keys(continueWatching(ana)), "y sin el 1x02, el 1x04");
    }

    /**
     * Empates de fecha (mismo instante): orden estable, películas antes que
     * episodios y, dentro de cada tipo, id descendente; dos episodios de la
     * misma serie empatados dejan el de id mayor.
     */
    @Test
    default void losEmpatesDeFechaTienenUnOrdenEstable() {
        long ana = user("ana");
        long m1 = movie("Uno");
        long m2 = movie("Dos");
        long serie = serie("Serie");
        long e1 = episode(serie, 1, 1);
        long e2 = episode(serie, 1, 2);
        long o1 = episode(serie("Otra"), 1, 1);
        for (long movie : new long[] { m1, m2 }) {
            saveMovie(ana, movie, 10, 100, BASE);
        }
        for (long episode : new long[] { e1, e2, o1 }) {
            saveEpisode(ana, episode, 10, 100, BASE);
        }

        List<String> expected = List.of("M:" + Math.max(m1, m2), "M:" + Math.min(m1, m2),
                "E:" + Math.max(e2, o1), "E:" + Math.min(e2, o1));
        assertEquals(expected, keys(continueWatching(ana)));
        assertEquals(expected, keys(continueWatching(ana)), "la misma petición da el mismo orden");
    }

    /** Sin progreso, o con todo terminado, la lista está vacía (no falla). */
    @Test
    default void sinNadaAMedioVerLaListaEstaVacia() {
        long ana = user("ana");
        assertEquals(List.of(), continueWatching(ana));

        saveMovie(ana, movie("Vista"), 100, 100, BASE);

        assertEquals(List.of(), continueWatching(ana));
    }
}
