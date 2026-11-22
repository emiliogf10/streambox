package com.emilio.streambox.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * serie). Las fechas se pasan explícitas (el servicio las tomará de su
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
                new WatchProgress(30, 2700, BASE.plusSeconds(10))), items.get(1));
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

    /**
     * Una tarjeta por serie: de cada serie solo sale su episodio no terminado
     * guardado más recientemente (los terminados no cuentan, aunque sean más
     * recientes); cada serie ocupa su sitio según ese episodio.
     */
    @Test
    default void deCadaSerieSoloSaleSuEpisodioAMedioVerMasReciente() {
        long ana = user("ana");
        long serie = serie("Serie");
        long e1 = episode(serie, 1, 1);
        long e2 = episode(serie, 1, 2);
        long e3 = episode(serie, 1, 3);
        long otherSerie = serie("Otra");
        long o1 = episode(otherSerie, 1, 1);
        long movie = movie("Pelicula");
        saveEpisode(ana, e1, 10, 100, BASE);
        saveEpisode(ana, e2, 10, 100, BASE.plusSeconds(2));
        saveEpisode(ana, e3, 100, 100, BASE.plusSeconds(4)); // terminado: no cuenta
        saveEpisode(ana, o1, 10, 100, BASE.plusSeconds(1));
        saveMovie(ana, movie, 10, 100, BASE.plusSeconds(3));

        assertEquals(List.of("M:" + movie, "E:" + e2, "E:" + o1), keys(continueWatching(ana)));

        tx().execute(s -> progress().deleteEpisodeProgress(ana, e2));
        assertEquals(List.of("M:" + movie, "E:" + o1, "E:" + e1), keys(continueWatching(ana)),
                "al quitar solo el episodio, sale el anterior a medias de la misma serie");

        tx().execute(s -> progress().deleteSeriesProgress(ana, serie));
        assertEquals(List.of("M:" + movie, "E:" + o1), keys(continueWatching(ana)));
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
