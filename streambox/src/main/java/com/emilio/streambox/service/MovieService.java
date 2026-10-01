package com.emilio.streambox.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.dto.MovieRequest;
import com.emilio.streambox.dto.MovieResponse;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.exception.GenreNotFoundException;
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

        return MovieMapper.toResponse(movieRepository.save(movie));
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

        // La entidad está gestionada: Hibernate guarda los cambios al
        // confirmar la transacción, no hace falta llamar a save().
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
     * @param title       texto que debe contener el título
     * @param genreId     identificador del género
     * @param releaseYear año de estreno
     * @param pageable    página, tamaño y ordenación solicitados
     * @return página de películas que cumplen todos los filtros indicados
     */
    @Transactional(readOnly = true)
    public Page<MovieResponse> searchMovies(
            String title,
            Long genreId,
            Integer releaseYear,
            Pageable pageable) {

        List<Specification<Movie>> filters = new ArrayList<>();

        if (title != null && !title.isBlank()) {
            filters.add(MovieSpecification.hasTitle(title));
        }
        if (genreId != null) {
            filters.add(MovieSpecification.hasGenre(genreId));
        }
        if (releaseYear != null) {
            filters.add(MovieSpecification.hasReleaseYear(releaseYear));
        }

        return movieRepository.findAll(Specification.allOf(filters), pageable)
                .map(MovieMapper::toResponse);
    }

    private Movie findMovie(Long id) {

        return movieRepository.findById(id)
                .orElseThrow(() -> new MovieNotFoundException("Película no encontrada"));
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
