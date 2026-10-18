package com.emilio.streambox.service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.dto.MovieResponse;
import com.emilio.streambox.dto.SeriesResponse;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.exception.MovieNotFoundException;
import com.emilio.streambox.exception.SeriesNotFoundException;
import com.emilio.streambox.exception.UserNotFoundException;
import com.emilio.streambox.mapper.MovieMapper;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesEpisodeStats;
import com.emilio.streambox.repository.SeriesRepository;

/**
 * Servicio de los «me gusta» de películas y series del usuario autenticado
 * ({@code /api/users/me/likes/...}).
 *
 * <p>
 * «Mi lista» ({@link FavoriteService}, {@link SeriesFavoriteService}) significa
 * «quiero verla»; «me gusta», «la vi y me gustó». Funcionan igual por dentro
 * (un {@code INSERT} o {@code DELETE} directo sobre la tabla de unión, sin
 * cargar la colección entera; el id del usuario sale siempre del token), pero
 * con un contrato distinto:
 * </p>
 * <ul>
 * <li><b>Dar y quitar son idempotentes</b> (204 siempre que el título exista).
 * El botón corazón es un interruptor: un doble clic o dos pestañas no deben
 * acabar en un error. Por eso no hay 409 ni «no te gustaba»: el {@code INSERT}
 * usa {@code ON CONFLICT DO NOTHING} y el {@code DELETE} no mira cuántas filas
 * borró. Repetir un «me gusta» conserva su fecha original.</li>
 * <li><b>Quitar nunca da 404</b>, ni siquiera si el título no existe: el
 * resultado pedido («que no me guste») ya se cumple, y como la respuesta es la
 * misma en todos los casos tampoco revela qué series ocultas existen.</li>
 * <li>Películas y series en un solo servicio: las seis operaciones son
 * simétricas y comparten la traducción de errores; separarlas solo duplicaría
 * código.</li>
 * </ul>
 *
 * <p>
 * La fecha de cada «me gusta» sale del {@link Clock} de la aplicación (el mismo
 * bean que usa la seguridad), no de la base de datos, para que los tests puedan
 * fijarla y para que un recomendador futuro tenga una hora coherente con el
 * resto de la aplicación.
 * </p>
 */
@Service
public class LikeService {

    /** Restricciones de clave foránea hacia {@code users} (V8). */
    private static final String MOVIE_LIKE_USER_FK = "fk_liked_movies_user";
    private static final String SERIES_LIKE_USER_FK = "fk_liked_series_user";

    private final MovieRepository movieRepository;
    private final SeriesRepository seriesRepository;
    private final EpisodeRepository episodeRepository;
    private final Clock clock;

    /**
     * Crea el servicio de «me gusta».
     *
     * @param movieRepository   repositorio de películas (consultas de «me gusta» de películas)
     * @param seriesRepository  repositorio de series (consultas de «me gusta» de series)
     * @param episodeRepository repositorio de episodios (visibilidad y recuentos de las series)
     * @param clock             reloj de la aplicación (fecha de cada «me gusta»)
     */
    public LikeService(
            MovieRepository movieRepository,
            SeriesRepository seriesRepository,
            EpisodeRepository episodeRepository,
            Clock clock) {

        this.movieRepository = movieRepository;
        this.seriesRepository = seriesRepository;
        this.episodeRepository = episodeRepository;
        this.clock = clock;
    }

    /**
     * Películas que le gustan al usuario, la que le gustó más recientemente
     * primero. Dos sentencias: la lista y los géneros por lotes (ninguna más si
     * la lista está vacía).
     *
     * @param userId identificador del usuario autenticado
     * @return sus películas con «me gusta»
     */
    @Transactional(readOnly = true)
    public List<MovieResponse> getLikedMovies(Long userId) {

        return MovieMapper.toResponseList(movieRepository.findLikedByUserId(userId));
    }

    /**
     * Series <strong>con episodios</strong> que le gustan al usuario, la más
     * reciente primero.
     *
     * <p>
     * Tres sentencias, como «Mi lista»: la lista (que ya descarta las series
     * ocultas), los recuentos de todas de una vez y los géneros por lotes. Los
     * recuentos vuelven a filtrar: si a una serie le borran el último episodio
     * entre las dos consultas, no se muestra (con 0 episodios sería una serie
     * oculta a la vista).
     * </p>
     *
     * @param userId identificador del usuario autenticado
     * @return sus series visibles con «me gusta»
     */
    @Transactional(readOnly = true)
    public List<SeriesResponse> getLikedSeries(Long userId) {

        List<Series> liked = seriesRepository.findVisibleLikedByUserId(userId);
        Map<Long, SeriesEpisodeStats> stats =
                SeriesEpisodeCounts.load(episodeRepository, SeriesEpisodeCounts.ids(liked));

        return liked.stream()
                .filter(series -> stats.containsKey(series.getId()))
                .map(series -> SeriesEpisodeCounts.toResponse(series, stats))
                .toList();
    }

    /**
     * Da «me gusta» a una película. Idempotente: si ya le gustaba no cambia
     * nada (ni la fecha).
     *
     * <p>
     * Se comprueba antes que la película existe ({@code existsById}) para dar
     * un 404 claro. Si se borra justo entre esa comprobación y el
     * {@code INSERT}, la clave foránea lo rechaza y se traduce al mismo 404
     * (ver {@link #translateInsertViolation}).
     * </p>
     *
     * @param userId  identificador del usuario autenticado
     * @param movieId identificador de la película
     * @throws MovieNotFoundException si la película no existe
     */
    @Transactional
    public void likeMovie(Long userId, Long movieId) {

        Supplier<RuntimeException> notFound = () -> new MovieNotFoundException("Película no encontrada");
        if (!movieRepository.existsById(movieId)) {
            throw notFound.get();
        }

        try {
            movieRepository.addLike(userId, movieId, clock.instant());
        } catch (DataIntegrityViolationException e) {
            throw translateInsertViolation(e, MOVIE_LIKE_USER_FK, notFound);
        }
    }

    /**
     * Quita el «me gusta» de una película. Idempotente y sin 404: si no le
     * gustaba o la película no existe, no hace nada.
     *
     * @param userId  identificador del usuario autenticado
     * @param movieId identificador de la película
     */
    @Transactional
    public void unlikeMovie(Long userId, Long movieId) {

        movieRepository.removeLike(userId, movieId);
    }

    /**
     * Da «me gusta» a una serie. Idempotente.
     *
     * <p>
     * Solo a series <strong>visibles</strong>: {@code existsBySeriesId} responde
     * a la vez si existe y si tiene episodios (un episodio implica su serie, por
     * la clave foránea). Una serie sin episodios da exactamente el mismo 404
     * que una inexistente, como en el resto de rutas públicas.
     * </p>
     *
     * @param userId   identificador del usuario autenticado
     * @param seriesId identificador de la serie
     * @throws SeriesNotFoundException si la serie no existe o no tiene episodios
     */
    @Transactional
    public void likeSeries(Long userId, Long seriesId) {

        Supplier<RuntimeException> notFound = () -> new SeriesNotFoundException(SeriesService.SERIES_NOT_FOUND);
        if (!episodeRepository.existsBySeriesId(seriesId)) {
            throw notFound.get();
        }

        try {
            seriesRepository.addLike(userId, seriesId, clock.instant());
        } catch (DataIntegrityViolationException e) {
            throw translateInsertViolation(e, SERIES_LIKE_USER_FK, notFound);
        }
    }

    /**
     * Quita el «me gusta» de una serie. Idempotente y sin 404, también si la
     * serie no existe o está oculta: la respuesta es siempre la misma, así que
     * no revela nada.
     *
     * @param userId   identificador del usuario autenticado
     * @param seriesId identificador de la serie
     */
    @Transactional
    public void unlikeSeries(Long userId, Long seriesId) {

        seriesRepository.removeLike(userId, seriesId);
    }

    /**
     * Traduce la violación de integridad del {@code INSERT} de un «me gusta».
     *
     * <p>
     * Con {@code ON CONFLICT DO NOTHING} el duplicado no es un error, así que la
     * única violación esperable es la de <b>clave foránea</b> ({@code 23503};
     * {@code 23506} en H2): algo se borró entre la comprobación y el
     * {@code INSERT}.
     * </p>
     * <ul>
     * <li>Si es la del usuario (su cuenta se borró durante la petición), 404
     * «Usuario no encontrado»: decir «Película no encontrada» sería falso.</li>
     * <li>Cualquier otra clave foránea es la del título: su 404 de siempre.</li>
     * <li>Lo demás no se disfraza: se relanza y acaba en un 500 genérico con la
     * traza en el log, porque sería un fallo de programación.</li>
     * </ul>
     *
     * <p>
     * En PostgreSQL esa excepción deja la transacción abortada (no admite más
     * consultas): por eso aquí no se consulta nada para averiguar qué se borró,
     * sino que se decide solo con el {@code SQLSTATE} y el nombre de la
     * restricción, y la transacción termina con la excepción de dominio.
     * </p>
     *
     * @param error          excepción lanzada por el repositorio
     * @param userConstraint nombre de la clave foránea hacia {@code users}
     * @param titleNotFound  404 del título
     * @return la excepción a lanzar; si no es reconocida, el propio {@code error}
     */
    private static RuntimeException translateInsertViolation(DataIntegrityViolationException error,
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
