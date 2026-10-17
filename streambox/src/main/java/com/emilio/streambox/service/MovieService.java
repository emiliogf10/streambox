package com.emilio.streambox.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.dto.MoviePageResponse;
import com.emilio.streambox.dto.MovieRequest;
import com.emilio.streambox.dto.MovieResponse;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.exception.GenreNotFoundException;
import com.emilio.streambox.exception.InvalidParameterException;
import com.emilio.streambox.exception.MovieNotFoundException;
import com.emilio.streambox.mapper.MovieMapper;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.specification.MovieSpecification;

/**
 * Servicio con la lógica de negocio del catálogo de películas.
 *
 * <p>
 * Todos los métodos públicos devuelven DTOs ({@link MovieResponse}), nunca
 * entidades. La conversión se hace aquí, <strong>dentro de la transacción</strong>,
 * porque los géneros de una película se cargan de forma diferida y solo se
 * pueden leer mientras la sesión de Hibernate está abierta. Así los
 * controladores no dependen de cómo el repositorio carga los datos.
 * </p>
 */
@Service
public class MovieService {

    /** Mínimo de películas similares que se pueden pedir. */
    public static final int MIN_SIMILAR_LIMIT = 1;

    /**
     * Máximo de películas similares que se pueden pedir. Es bajo a propósito:
     * es una fila de recomendaciones, no un listado, y la carga de géneros de
     * esas películas va en un {@code IN} sin paginar.
     */
    public static final int MAX_SIMILAR_LIMIT = 24;

    /** Número de películas similares si el cliente no indica {@code limit}. */
    public static final int DEFAULT_SIMILAR_LIMIT = 12;

    /**
     * Mensaje del 404 cuando un género se borra mientras se guarda una película
     * o una serie (lo comparte {@link SeriesService}). No dice cuál: tras el
     * error, la transacción ya no admite consultas para averiguarlo.
     */
    public static final String GENRE_REMOVED_MESSAGE =
            "Alguno de los géneros indicados ya no existe";

    private final MovieRepository movieRepository;
    private final GenreRepository genreRepository;

    /**
     * Crea el servicio del catálogo.
     *
     * @param movieRepository repositorio de películas
     * @param genreRepository repositorio de géneros
     */
    public MovieService(
            MovieRepository movieRepository,
            GenreRepository genreRepository) {

        this.movieRepository = movieRepository;
        this.genreRepository = genreRepository;
    }

    /**
     * Obtiene una página del catálogo completo.
     *
     * @param pageable página, tamaño y ordenación solicitados
     * @return página de películas
     */
    @Transactional(readOnly = true)
    public Page<MovieResponse> getMovies(Pageable pageable) {

        return movieRepository.findAll(pageable).map(MovieMapper::toResponse);
    }

    /**
     * Obtiene una película por su identificador.
     *
     * @param id identificador de la película
     * @return la película con sus géneros
     * @throws MovieNotFoundException si no existe
     */
    @Transactional(readOnly = true)
    public MovieResponse getMovieById(Long id) {

        return MovieMapper.toResponse(findMovie(id));
    }

    /**
     * Crea una película asociándola a los géneros indicados.
     *
     * @param request datos de la película y de sus géneros
     * @return la película creada
     * @throws GenreNotFoundException si algún género no existe
     */
    @Transactional
    public MovieResponse createMovie(MovieRequest request) {

        Movie movie = MovieMapper.toEntity(request);
        movie.setGenres(resolveGenres(request.genreIds()));

        Movie saved = movieRepository.save(movie);
        writeTranslatingGenreRace(movieRepository::flush);
        return MovieMapper.toResponse(saved);
    }

    /**
     * Sustituye los datos y los géneros de una película existente.
     *
     * @param id      identificador de la película
     * @param request datos nuevos
     * @return la película actualizada
     * @throws MovieNotFoundException si la película no existe
     * @throws GenreNotFoundException si algún género no existe
     */
    @Transactional
    public MovieResponse updateMovie(Long id, MovieRequest request) {

        Movie movie = findMovie(id);

        MovieMapper.updateEntity(request, movie);
        movie.setGenres(resolveGenres(request.genreIds()));

        // La entidad está gestionada y Hibernate guardaría los cambios al
        // confirmar la transacción, pero entonces una violación saltaría fuera
        // de este método, donde ya no se puede traducir: se escriben aquí con
        // un flush (sin save(), que haría un merge innecesario).
        writeTranslatingGenreRace(movieRepository::flush);
        return MovieMapper.toResponse(movie);
    }

    /**
     * Elimina una película y la retira de las listas de favoritos.
     *
     * @param id identificador de la película
     * @throws MovieNotFoundException si no existe
     */
    @Transactional
    public void deleteMovie(Long id) {

        if (!movieRepository.existsById(id)) {
            throw new MovieNotFoundException("Película no encontrada");
        }

        movieRepository.deleteFromAllFavorites(id);
        movieRepository.deleteById(id);
    }

    /**
     * Busca películas combinando filtros opcionales.
     *
     * <p>
     * Los filtros {@code null} (o un título en blanco) se ignoran; sin
     * ningún filtro devuelve el catálogo completo paginado.
     * </p>
     *
     * <p>
     * El título se busca en dos intentos ({@link TypoTolerantTitleSearch}):
     * primero "contiene" (sin mayúsculas ni acentos) con el orden pedido; si no
     * hay ninguna coincidencia y el texto tiene 5 letras o más, títulos
     * <em>parecidos</em> con los mismos filtros de género y año, ordenados por
     * parecido (se ignora el orden pedido) y con {@code approximate = true}.
     * </p>
     *
     * @param title       texto que debe contener el título
     * @param genreId     identificador del género
     * @param releaseYear año de estreno
     * @param pageable    página, tamaño y ordenación solicitados
     * @return página de películas que cumplen todos los filtros indicados
     */
    @Transactional(readOnly = true)
    public MoviePageResponse searchMovies(
            String title,
            Long genreId,
            Integer releaseYear,
            Pageable pageable) {

        List<Specification<Movie>> filters = new ArrayList<>();

        if (genreId != null) {
            filters.add(MovieSpecification.hasGenre(genreId));
        }
        if (releaseYear != null) {
            filters.add(MovieSpecification.hasReleaseYear(releaseYear));
        }

        TypoTolerantTitleSearch.Result<Movie> result = TypoTolerantTitleSearch.find(movieRepository, filters, title,
                MovieSpecification::hasTitle, MovieSpecification::hasSimilarTitle, pageable);

        // El mapeo a DTO, aquí dentro: los géneros son LAZY y open-in-view=false.
        return MoviePageResponse.from(result.page().map(MovieMapper::toResponse), result.approximate());
    }

    /**
     * Devuelve las películas <strong>similares</strong> a una dada, de la más
     * parecida a la menos parecida (criterio en
     * {@link MovieRepository#findSimilarMovieIds(Long, int)}: géneros en común,
     * cercanía del año de estreno, más reciente y, por último, id).
     *
     * <p>
     * <strong>Por qué en tres pasos y no en una sola consulta.</strong>
     * </p>
     * <ol>
     * <li>Se comprueba que la película existe con {@code existsById} (un
     * {@code count} por clave primaria, sin cargar la película ni sus géneros):
     * si no existe, el mismo 404 que {@link #getMovieById(Long)}. Sin este paso
     * una película inexistente devolvería {@code []}, indistinguible de una que
     * no se parece a ninguna.</li>
     * <li>Se piden solo los <em>ids</em> ya ordenados y limitados. El
     * {@code LIMIT} tiene que aplicarse en la base de datos sobre películas, y
     * eso no es posible si la misma consulta hace {@code JOIN FETCH} de los
     * géneros (cada película ocuparía tantas filas como géneros tenga, y
     * Hibernate acabaría limitando en memoria).</li>
     * <li>Se cargan esas películas con sus géneros en una sola consulta. Como
     * {@code IN} no conserva ningún orden, el orden se reconstruye recorriendo la
     * lista de ids del paso anterior. Si una película se borra entre los dos
     * pasos, simplemente no aparece.</li>
     * </ol>
     *
     * <p>
     * Coste fijo: tres sentencias SQL (dos si no hay ninguna similar, porque se
     * evita la carga con una lista vacía), sea cual sea el límite. El mapeo a
     * DTO se hace aquí dentro, con la transacción abierta
     * ({@code open-in-view=false}).
     * </p>
     *
     * @param id    identificador de la película de referencia
     * @param limit número máximo de películas ({@value #MIN_SIMILAR_LIMIT} a
     *              {@value #MAX_SIMILAR_LIMIT}; el controlador ya lo valida)
     * @return películas similares ordenadas, sin la propia; vacía si no comparte
     *         géneros con ninguna
     * @throws MovieNotFoundException     si la película no existe
     * @throws InvalidParameterException si el límite está fuera de rango
     */
    @Transactional(readOnly = true)
    public List<MovieResponse> getSimilarMovies(Long id, int limit) {

        // Defensa en profundidad: el controlador ya valida el rango, pero el
        // límite llega tal cual a un FETCH FIRST y el servicio puede tener
        // otros llamantes en el futuro.
        if (limit < MIN_SIMILAR_LIMIT || limit > MAX_SIMILAR_LIMIT) {
            throw new InvalidParameterException("limit",
                    "El límite debe estar entre " + MIN_SIMILAR_LIMIT + " y " + MAX_SIMILAR_LIMIT);
        }

        if (!movieRepository.existsById(id)) {
            throw new MovieNotFoundException("Película no encontrada");
        }

        List<Long> ids = movieRepository.findSimilarMovieIds(id, limit);
        if (ids.isEmpty()) {
            return List.of();
        }

        Map<Long, Movie> byId = movieRepository.findAllWithGenresByIdIn(ids).stream()
                .collect(Collectors.toMap(Movie::getId, Function.identity()));

        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(MovieMapper::toResponse)
                .toList();
    }

    private Movie findMovie(Long id) {

        return movieRepository.findById(id)
                .orElseThrow(() -> new MovieNotFoundException("Película no encontrada"));
    }

    /**
     * Ejecuta la escritura pendiente (el {@code flush}) y traduce la única
     * violación de integridad que pueden provocar unos datos ya validados.
     *
     * <p>
     * <strong>La carrera con el borrado de un género.</strong>
     * {@link #resolveGenres} comprueba que los géneros existen, pero otro
     * administrador puede borrar uno entre esa consulta y el {@code INSERT} en
     * {@code movie_genres}; entonces salta la clave foránea
     * {@code fk_movie_genres_genre}. El resultado debe ser el mismo 404 de
     * género que da la comprobación previa (el otro lado de la carrera, el
     * borrado, ya lo traduce {@code GenreService.deleteGenre}). Antes llegaba
     * sin traducir a {@code GlobalExceptionHandler} como un 409 genérico; hoy
     * allí sería un 500. Por eso el {@code flush}: sin él, el {@code INSERT}
     * de los géneros se haría al confirmar la transacción, ya fuera de este
     * método.
     * </p>
     *
     * <p>
     * Cualquier otra violación se relanza tal cual (500 y registro en el log):
     * con la validación de {@code MovieRequest} ninguna debería ocurrir, y si
     * ocurre es un fallo que no conviene disfrazar.
     * </p>
     *
     * @param flush la escritura (el {@code flush} del repositorio)
     * @throws GenreNotFoundException si un género se borró mientras se guardaba
     */
    private static void writeTranslatingGenreRace(Runnable flush) {

        try {
            flush.run();
        } catch (DataIntegrityViolationException e) {
            if (SqlStates.isForeignKeyViolation(e, "fk_movie_genres_genre")) {
                throw new GenreNotFoundException(GENRE_REMOVED_MESSAGE);
            }
            throw e;
        }
    }

    /**
     * Carga los géneros indicados con una sola consulta.
     *
     * @param genreIds identificadores solicitados
     * @return géneros encontrados
     * @throws GenreNotFoundException si alguno no existe (indica el de menor id)
     */
    private Set<Genre> resolveGenres(Set<Long> genreIds) {

        List<Genre> found = genreRepository.findAllById(genreIds);

        if (found.size() != genreIds.size()) {

            Set<Long> foundIds = found.stream()
                    .map(Genre::getId)
                    .collect(Collectors.toSet());

            Long missing = genreIds.stream()
                    .filter(id -> !foundIds.contains(id))
                    .sorted()
                    .findFirst()
                    .orElseThrow();

            throw new GenreNotFoundException("Género no encontrado: " + missing);
        }

        return new HashSet<>(found);
    }
}
