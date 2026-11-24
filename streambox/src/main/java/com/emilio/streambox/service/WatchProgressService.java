package com.emilio.streambox.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.dto.ContinueWatchingItemResponse;
import com.emilio.streambox.dto.EpisodeProgressResponse;
import com.emilio.streambox.dto.WatchHistoryItemResponse;
import com.emilio.streambox.dto.WatchProgressRequest;
import com.emilio.streambox.dto.WatchProgressResponse;
import com.emilio.streambox.dto.WatchStatsResponse;
import com.emilio.streambox.exception.EpisodeNotFoundException;
import com.emilio.streambox.exception.MovieNotFoundException;
import com.emilio.streambox.exception.NoVideoAvailableException;
import com.emilio.streambox.exception.SeriesNotFoundException;
import com.emilio.streambox.exception.UserNotFoundException;
import com.emilio.streambox.mapper.WatchProgressMapper;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.WatchProgressRepository;

/**
 * Servicio del <b>progreso de visionado</b>, de la fila <b>«Seguir viendo»</b>
 * y del <b>historial y las cifras del perfil</b> del usuario autenticado
 * ({@code /api/users/me/progress/...}, {@code /api/users/me/continue-watching/...},
 * {@code /api/users/me/watch-history} y {@code /api/users/me/watch-stats}).
 * Todo sale de las mismas tablas de progreso ({@code V14}): el historial no
 * tiene tabla propia.
 *
 * <p><b>Contrato</b></p>
 * <ul>
 * <li><b>Guardar es idempotente</b> (204): el reproductor envía la posición
 * cada pocos segundos y al pausar o salir; cada envío sobrescribe el anterior
 * (gana el último) y repetirlo no es un error. Por eso es un {@code PUT}.</li>
 * <li><b>Solo se guarda lo que se puede reproducir:</b> un título sin vídeo da
 * 409 {@code NO_VIDEO_AVAILABLE} y uno inexistente, el 404 de siempre.</li>
 * <li><b>Leer el progreso de un título sin progreso es 204</b>, no 404: el
 * título existe y «nunca lo empezaste» no es un error (el 404 queda para el
 * título inexistente, con el mismo cuerpo que su detalle).</li>
 * <li><b>Quitar de «Seguir viendo» es idempotente y nunca da 404</b>, como
 * quitar un «me gusta»: el resultado pedido ya se cumple, y como la respuesta
 * es siempre la misma tampoco revela qué series ocultas existen.</li>
 * <li>El id del usuario sale siempre del token: no hay ninguna ruta con el id
 * de otro usuario.</li>
 * </ul>
 *
 * <p><b>Coste del guardado</b> (la operación más frecuente de toda la API
 * mientras alguien ve algo): una consulta de existencia
 * ({@code existsByIdAndVideoUrlIsNotNull}) y un {@code UPDATE}; el
 * {@code INSERT} solo la primera vez. La segunda consulta de existencia
 * ({@code existsById}) solo se hace en el camino del error, para distinguir el
 * 404 del 409.</p>
 *
 * <p><b>Carreras.</b> Si el título se borra entre la comprobación y el
 * guardado, la clave foránea rechaza el {@code INSERT} y se traduce al mismo
 * 404 (ver {@link #translateSaveViolation}); si se borra después, la cascada
 * de la {@code V14} se lleva el progreso. Si el administrador le quita el
 * vídeo justo entre medias, el progreso se guarda pero no sale en «Seguir
 * viendo» (la consulta exige vídeo) y el siguiente guardado ya da 409: no
 * merece un bloqueo en la operación más frecuente.</p>
 *
 * <p>La fecha de cada guardado sale del {@link Clock} de la aplicación, no de
 * la base de datos, para que los tests la controlen, y se guarda truncada a
 * milisegundos, la precisión con la que ordena el cliente (ver {@link #now()}).</p>
 */
@Service
public class WatchProgressService {

    /** Elementos de «Seguir viendo» si el cliente no indica {@code limit}. */
    public static final int DEFAULT_CONTINUE_WATCHING_LIMIT = 10;

    /** Límite mínimo de «Seguir viendo». */
    public static final int MIN_CONTINUE_WATCHING_LIMIT = 1;

    /**
     * Límite máximo de «Seguir viendo»: el mismo que las películas similares y
     * las recomendaciones, el tope de una fila horizontal del inicio.
     */
    public static final int MAX_CONTINUE_WATCHING_LIMIT = 24;

    /**
     * Entradas del historial del perfil si el cliente no indica {@code limit}:
     * las que pinta la sección «Vistos recientemente» (el frontend pide 12).
     */
    public static final int DEFAULT_WATCH_HISTORY_LIMIT = 12;

    /** Límite mínimo del historial. */
    public static final int MIN_WATCH_HISTORY_LIMIT = 1;

    /**
     * Límite máximo del historial: el mismo tope que «Seguir viendo». Acota el
     * tamaño de la respuesta; la consulta lee igualmente todo el progreso del
     * usuario (ver {@link WatchProgressRepository#findWatchHistory}).
     */
    public static final int MAX_WATCH_HISTORY_LIMIT = 24;

    private static final String MOVIE_NOT_FOUND = "Película no encontrada";
    private static final String MOVIE_WITHOUT_VIDEO =
            "Esta película no tiene vídeo: no se puede guardar por dónde vas";
    private static final String EPISODE_WITHOUT_VIDEO =
            "Este episodio no tiene vídeo: no se puede guardar por dónde vas";

    private final WatchProgressRepository watchProgressRepository;
    private final MovieRepository movieRepository;
    private final EpisodeRepository episodeRepository;
    private final Clock clock;

    /**
     * Crea el servicio del progreso de visionado.
     *
     * @param watchProgressRepository acceso a las tablas de progreso ({@code V14})
     * @param movieRepository         existencia y vídeo de las películas
     * @param episodeRepository       existencia y vídeo de los episodios, y visibilidad de las series
     * @param clock                   reloj de la aplicación (fecha de cada guardado)
     */
    public WatchProgressService(
            WatchProgressRepository watchProgressRepository,
            MovieRepository movieRepository,
            EpisodeRepository episodeRepository,
            Clock clock) {

        this.watchProgressRepository = watchProgressRepository;
        this.movieRepository = movieRepository;
        this.episodeRepository = episodeRepository;
        this.clock = clock;
    }

    // ------------------------------------------------------------------
    // Guardar
    // ------------------------------------------------------------------

    /**
     * Guarda por dónde va el usuario en una película.
     *
     * @param userId  identificador del usuario autenticado
     * @param movieId identificador de la película
     * @param request posición y duración (ya validadas)
     * @throws MovieNotFoundException     si la película no existe
     * @throws NoVideoAvailableException  si existe pero no tiene vídeo
     * @throws UserNotFoundException      si la cuenta se borró durante la petición
     */
    @Transactional
    public void saveMovieProgress(Long userId, Long movieId, WatchProgressRequest request) {

        Supplier<RuntimeException> notFound = () -> new MovieNotFoundException(MOVIE_NOT_FOUND);
        if (!movieRepository.existsByIdAndVideoUrlIsNotNull(movieId)) {
            throw movieRepository.existsById(movieId)
                    ? new NoVideoAvailableException(MOVIE_WITHOUT_VIDEO)
                    : notFound.get();
        }

        try {
            watchProgressRepository.saveMovieProgress(userId, movieId, request.positionSeconds(),
                    request.durationSeconds(), now());
        } catch (DataIntegrityViolationException e) {
            throw translateSaveViolation(e, WatchProgressRepository.MOVIE_PROGRESS_USER_FK, notFound);
        }
    }

    /**
     * Guarda por dónde va el usuario en un episodio.
     *
     * <p>
     * Un episodio siempre es visible (su existencia hace visible a su serie),
     * así que no hay que comprobar la serie.
     * </p>
     *
     * @param userId    identificador del usuario autenticado
     * @param episodeId identificador del episodio
     * @param request   posición y duración (ya validadas)
     * @throws EpisodeNotFoundException   si el episodio no existe
     * @throws NoVideoAvailableException  si existe pero no tiene vídeo
     * @throws UserNotFoundException      si la cuenta se borró durante la petición
     */
    @Transactional
    public void saveEpisodeProgress(Long userId, Long episodeId, WatchProgressRequest request) {

        Supplier<RuntimeException> notFound = () -> new EpisodeNotFoundException(EpisodeService.EPISODE_NOT_FOUND);
        if (!episodeRepository.existsByIdAndVideoUrlIsNotNull(episodeId)) {
            throw episodeRepository.existsById(episodeId)
                    ? new NoVideoAvailableException(EPISODE_WITHOUT_VIDEO)
                    : notFound.get();
        }

        try {
            watchProgressRepository.saveEpisodeProgress(userId, episodeId, request.positionSeconds(),
                    request.durationSeconds(), now());
        } catch (DataIntegrityViolationException e) {
            throw translateSaveViolation(e, WatchProgressRepository.EPISODE_PROGRESS_USER_FK, notFound);
        }
    }

    /**
     * Fecha de un guardado: el instante del {@link Clock} <b>truncado a
     * milisegundos</b>.
     *
     * <p>
     * El servidor y el cliente deben ordenar los episodios de una serie igual
     * (el «último episodio tocado» decide qué propone la tarjeta de «Seguir
     * viendo» y qué propone la página de la serie). El cliente ordena con
     * {@code Date.parse}, que solo ve milisegundos, mientras que
     * {@code Instant} y PostgreSQL guardan hasta microsegundos: dos guardados
     * en el mismo milisegundo empatarían en el navegador (y se desempatarían
     * por id) pero no en la base de datos, y cada lado podría elegir un
     * episodio distinto. Guardando ya con la precisión común, los dos ven
     * exactamente las mismas fechas y el mismo desempate.
     * </p>
     *
     * @return el instante actual sin fracción por debajo del milisegundo
     */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    // ------------------------------------------------------------------
    // Leer
    // ------------------------------------------------------------------

    /**
     * Progreso del usuario en una película, para «Reanudar desde X».
     *
     * <p>
     * Una consulta si hay progreso; dos si no (la segunda decide entre «nunca
     * la empezaste», vacío, y «no existe», 404). No exige vídeo: si se lo
     * quitaron, el progreso sigue siendo verdad y el cliente ya no ofrece
     * reproducirla.
     * </p>
     *
     * @param userId  identificador del usuario autenticado
     * @param movieId identificador de la película
     * @return su progreso (terminado incluido), o vacío si no hay
     * @throws MovieNotFoundException si la película no existe
     */
    @Transactional(readOnly = true)
    public Optional<WatchProgressResponse> getMovieProgress(Long userId, Long movieId) {

        Optional<WatchProgressResponse> progress = watchProgressRepository.findMovieProgress(userId, movieId)
                .map(WatchProgressMapper::toResponse);
        if (progress.isEmpty() && !movieRepository.existsById(movieId)) {
            throw new MovieNotFoundException(MOVIE_NOT_FOUND);
        }
        return progress;
    }

    /**
     * Progreso del usuario en un episodio. Mismo criterio que
     * {@link #getMovieProgress}.
     *
     * @param userId    identificador del usuario autenticado
     * @param episodeId identificador del episodio
     * @return su progreso (terminado incluido), o vacío si no hay
     * @throws EpisodeNotFoundException si el episodio no existe
     */
    @Transactional(readOnly = true)
    public Optional<WatchProgressResponse> getEpisodeProgress(Long userId, Long episodeId) {

        Optional<WatchProgressResponse> progress = watchProgressRepository.findEpisodeProgress(userId, episodeId)
                .map(WatchProgressMapper::toResponse);
        if (progress.isEmpty() && !episodeRepository.existsById(episodeId)) {
            throw new EpisodeNotFoundException(EpisodeService.EPISODE_NOT_FOUND);
        }
        return progress;
    }

    /**
     * Progreso del usuario en los episodios de una serie <b>visible</b>, para
     * pintar las barras de su página. Dos consultas, tenga los episodios que
     * tenga.
     *
     * <p>
     * Una serie sin episodios da exactamente el mismo 404 que una inexistente
     * (las series ocultas son indistinguibles en todas las rutas públicas). En
     * la práctica una serie oculta no puede tener progreso (el progreso es de
     * sus episodios), pero responder «lista vacía» delataría que existe.
     * </p>
     *
     * @param userId   identificador del usuario autenticado
     * @param seriesId identificador de la serie
     * @return un elemento por episodio con progreso (terminados incluidos), en
     *         orden de temporada y número; vacía si no ha empezado ninguno
     * @throws SeriesNotFoundException si la serie no existe o no tiene episodios
     */
    @Transactional(readOnly = true)
    public List<EpisodeProgressResponse> getSeriesProgress(Long userId, Long seriesId) {

        if (!episodeRepository.existsBySeriesId(seriesId)) {
            throw new SeriesNotFoundException(SeriesService.SERIES_NOT_FOUND);
        }
        return WatchProgressMapper.toEpisodeResponses(
                watchProgressRepository.findEpisodeProgressBySeries(userId, seriesId));
    }

    /**
     * La fila «Seguir viendo»: películas a medio ver y una tarjeta por serie,
     * del más reciente al más antiguo. Una sola consulta (ver
     * {@link WatchProgressRepository#findContinueWatching}, donde está el
     * criterio completo), que ya deja fuera lo que no tiene vídeo.
     *
     * <p>
     * Lo terminado (≥ 95 %) nunca sale como tarjeta, pero en una serie no
     * desaparece sin más: si el último episodio que tocó el usuario está
     * terminado, la tarjeta pasa al <b>siguiente con vídeo</b>
     * ({@code upNext = true}, con progreso nulo si no lo ha empezado) y la
     * serie solo sale de la fila cuando no queda ninguno. Una película
     * terminada sí sale de la fila.
     * </p>
     *
     * @param userId identificador del usuario autenticado
     * @param limit  máximo de elementos (el controlador lo valida:
     *               {@value #MIN_CONTINUE_WATCHING_LIMIT}..{@value #MAX_CONTINUE_WATCHING_LIMIT})
     * @return sus tarjetas; vacía si no hay nada que seguir viendo
     */
    @Transactional(readOnly = true)
    public List<ContinueWatchingItemResponse> getContinueWatching(Long userId, int limit) {

        return WatchProgressMapper.toResponseList(watchProgressRepository.findContinueWatching(userId, limit));
    }

    // ------------------------------------------------------------------
    // Historial y cifras del perfil
    // ------------------------------------------------------------------

    /**
     * El historial de visionado del perfil («Vistos recientemente»): una
     * entrada por película y una por serie (su último episodio tocado), del
     * guardado más reciente al más antiguo. Una sola consulta (el criterio
     * completo está en {@link WatchProgressRepository#findWatchHistory}).
     *
     * <p>
     * A diferencia de «Seguir viendo», incluye lo terminado y lo que ya no
     * tiene vídeo: es lo que se vio, no lo que se puede reanudar. Como sale de
     * las mismas filas de progreso, «Quitar de Seguir viendo» (que las borra)
     * también lo quita del historial y de las cifras.
     * </p>
     *
     * @param userId identificador del usuario autenticado
     * @param limit  máximo de entradas (el controlador lo valida:
     *               {@value #MIN_WATCH_HISTORY_LIMIT}..{@value #MAX_WATCH_HISTORY_LIMIT})
     * @return sus entradas; vacía si no ha empezado nada
     */
    @Transactional(readOnly = true)
    public List<WatchHistoryItemResponse> getWatchHistory(Long userId, int limit) {

        return WatchProgressMapper.toHistoryResponseList(watchProgressRepository.findWatchHistory(userId, limit));
    }

    /**
     * Las cifras de visionado del perfil: películas y episodios terminados
     * (≥ 95 %) y el tiempo visto estimado (duración de lo terminado más
     * posición de lo que está a medias; ver
     * {@link WatchProgressRepository#getWatchStats}). Una sola consulta.
     *
     * @param userId identificador del usuario autenticado
     * @return las cifras; todo ceros si no ha empezado nada
     */
    @Transactional(readOnly = true)
    public WatchStatsResponse getWatchStats(Long userId) {

        return WatchProgressMapper.toStatsResponse(watchProgressRepository.getWatchStats(userId));
    }

    // ------------------------------------------------------------------
    // Quitar de «Seguir viendo»
    // ------------------------------------------------------------------

    /**
     * Quita una película de «Seguir viendo» borrando su progreso (al volver a
     * abrirla empieza desde el principio). Como el historial y las cifras del
     * perfil salen de la misma fila, también deja de contar en ellos.
     * Idempotente y sin 404.
     *
     * @param userId  identificador del usuario autenticado
     * @param movieId identificador de la película
     */
    @Transactional
    public void removeMovie(Long userId, Long movieId) {

        watchProgressRepository.deleteMovieProgress(userId, movieId);
    }

    /**
     * Quita una serie de «Seguir viendo» borrando el progreso de <b>todos</b>
     * sus episodios: si solo se borrara el de la tarjeta, otro episodio a
     * medias de la misma serie ocuparía su lugar y la serie no desaparecería.
     * También la quita del historial y de las cifras del perfil (las mismas
     * filas).
     * Idempotente y sin 404 (también para series ocultas o inexistentes: la
     * respuesta es siempre la misma, así que no revela nada).
     *
     * @param userId   identificador del usuario autenticado
     * @param seriesId identificador de la serie
     */
    @Transactional
    public void removeSeries(Long userId, Long seriesId) {

        watchProgressRepository.deleteSeriesProgress(userId, seriesId);
    }

    /**
     * Traduce la violación de integridad de un guardado.
     *
     * <p>
     * Mismo criterio que {@code LikeService}: la validación del DTO ya cumple
     * los {@code CHECK}, así que lo esperable es una <b>clave foránea</b>
     * ({@code 23503}; {@code 23506} en H2) porque algo se borró durante la
     * petición. La del usuario es «Usuario no encontrado» (decir «Película no
     * encontrada» sería falso); cualquier otra, el 404 del título. Lo demás se
     * relanza y acaba en un 500 genérico con la traza en el log, porque sería
     * un fallo de programación.
     * </p>
     *
     * <p>
     * En PostgreSQL esa excepción deja la transacción abortada: aquí no se
     * consulta nada más, se decide solo con el {@code SQLSTATE} y el nombre de
     * la restricción (que con {@code JdbcClient} sale del mensaje del driver;
     * ver {@link SqlStates#isForeignKeyViolation}).
     * </p>
     *
     * @param error          excepción lanzada por el repositorio
     * @param userConstraint nombre de la clave foránea hacia {@code users}
     * @param titleNotFound  404 del título
     * @return la excepción a lanzar; si no es reconocida, el propio {@code error}
     */
    private static RuntimeException translateSaveViolation(DataIntegrityViolationException error,
            String userConstraint, Supplier<RuntimeException> titleNotFound) {

        if (SqlStates.isForeignKeyViolation(error, userConstraint)) {
            return new UserNotFoundException("Usuario no encontrado");
        }
        String sqlState = SqlStates.find(error);
        if (SqlStates.FOREIGN_KEY_VIOLATION.equals(sqlState)
                || SqlStates.H2_FOREIGN_KEY_PARENT_MISSING.equals(sqlState)) {
            return titleNotFound.get();
        }
        return error;
    }
}
