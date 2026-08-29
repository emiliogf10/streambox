package com.emilio.streambox.service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import com.emilio.streambox.dto.UpdateMovieRequest;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.exception.GenreNotFoundException;
import com.emilio.streambox.exception.MovieNotFoundException;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.specification.MovieSpecification;

/**
 * Servicio encargado de gestionar la lógica de negocio relacionada
 * con las películas de Streambox.
 *
 * <p>
 * Centraliza las operaciones relacionadas con las películas,
 * evitando que los controladores tengan que acceder directamente
 * a los repositorios.
 * </p>
 *
 * <p>
 * También se encarga de gestionar la asociación entre películas
 * y géneros, así como las búsquedas mediante filtros y paginación.
 * </p>
 */
@Service
public class MovieService {

    private final MovieRepository movieRepository;

    private final GenreRepository genreRepository;

    /**
     * Crea una instancia del servicio de películas.
     *
     * @param movieRepository repositorio utilizado para acceder
     *                        a las películas almacenadas
     * @param genreRepository repositorio utilizado para consultar
     *                        los géneros asociados a las películas
     */
    public MovieService(
            MovieRepository movieRepository,
            GenreRepository genreRepository) {

        this.movieRepository = movieRepository;
        this.genreRepository = genreRepository;
    }

    /**
     * Obtiene todas las películas almacenadas en Streambox.
     *
     * <p>
     * El repositorio utiliza un {@code @EntityGraph} para cargar
     * los géneros asociados a las películas y evitar problemas
     * de inicialización diferida.
     * </p>
     *
     * @return lista de todas las películas con sus géneros cargados
     */
    public List<Movie> getAllMovies() {

        return movieRepository.findAll();
    }

    /**
     * Obtiene una película mediante su identificador.
     *
     * @param id identificador de la película que se desea obtener
     * @return película correspondiente al identificador proporcionado
     * @throws MovieNotFoundException si no existe una película
     *                                con el ID indicado
     */
    public Movie getMovieById(Long id) {

        return movieRepository.findById(id)
                .orElseThrow(() -> new MovieNotFoundException(
                        "Película no encontrada"));
    }

    /**
     * Guarda una nueva película y asocia los géneros indicados.
     *
     * <p>
     * Para cada identificador recibido se comprueba que el género
     * exista antes de establecer la relación con la película.
     * </p>
     *
     * <p>
     * La fecha de creación se establece automáticamente en el momento
     * de guardar la película.
     * </p>
     *
     * @param movie    película que se desea guardar
     * @param genreIds identificadores de los géneros que se asociarán
     *                 a la película
     * @return película almacenada con sus géneros asociados
     * @throws GenreNotFoundException si alguno de los identificadores
     *                                de género no existe
     */
    public Movie saveMovie(Movie movie, Set<Long> genreIds) {

        movie.setCreatedAt(LocalDateTime.now());

        Set<Genre> genres = new HashSet<>();

        for (Long genreId : genreIds) {

            Genre genre = genreRepository.findById(genreId)
                    .orElseThrow(() -> new GenreNotFoundException(
                            "Género no encontrado: " + genreId));

            genres.add(genre);
        }

        movie.setGenres(genres);

        return movieRepository.save(movie);
    }

    /**
     * Modifica los datos de una película existente y actualiza
     * sus géneros asociados.
     *
     * <p>
     * Primero se comprueba que la película exista. Después se actualizan
     * sus datos básicos y se sustituyen los géneros actuales por los
     * indicados en la petición.
     * </p>
     *
     * <p>
     * Los géneros recibidos se validan individualmente antes de
     * establecer las nuevas asociaciones.
     * </p>
     *
     * @param id      identificador de la película que se desea modificar
     * @param request datos actualizados de la película
     * @return película modificada y almacenada en la base de datos
     * @throws MovieNotFoundException si no existe una película
     *                                con el ID indicado
     * @throws GenreNotFoundException si alguno de los géneros indicados
     *                                no existe
     */
    public Movie updateMovie(Long id, UpdateMovieRequest request) {

        Movie existingMovie = movieRepository.findById(id)
                .orElseThrow(() -> new MovieNotFoundException(
                        "Película no encontrada"));

        existingMovie.setTitle(request.getTitle());
        existingMovie.setDescription(request.getDescription());
        existingMovie.setDuration(request.getDuration());
        existingMovie.setReleaseYear(request.getReleaseYear());
        existingMovie.setImageUrl(request.getImageUrl());
        existingMovie.setVideoUrl(request.getVideoUrl());

        Set<Genre> genres = new HashSet<>();

        for (Long genreId : request.getGenreIds()) {

            Genre genre = genreRepository.findById(genreId)
                    .orElseThrow(() -> new GenreNotFoundException(
                            "Género no encontrado: " + genreId));

            genres.add(genre);
        }

        existingMovie.setGenres(genres);

        return movieRepository.save(existingMovie);
    }

    /**
     * Elimina una película existente mediante su identificador.
     *
     * <p>
     * Antes de eliminar la película se comprueba que exista.
     * De esta forma, si se solicita eliminar un identificador
     * inexistente, se lanza {@link MovieNotFoundException} y la API
     * puede devolver correctamente un {@code 404 Not Found}.
     * </p>
     *
     * @param id identificador de la película que se desea eliminar
     * @throws MovieNotFoundException si no existe ninguna película
     *                                con el ID indicado
     */
    public void deleteMovie(Long id) {

        if (!movieRepository.existsById(id)) {
            throw new MovieNotFoundException(
                    "Película no encontrada");
        }

        movieRepository.deleteById(id);
    }

    /**
     * Busca películas cuyo título contenga el texto indicado,
     * ignorando diferencias entre mayúsculas y minúsculas.
     *
     * <p>
     * Esta operación se utiliza para realizar búsquedas directas
     * por título sin aplicar otros filtros.
     * </p>
     *
     * @param title texto que se desea buscar en el título
     * @return lista de películas cuyo título coincide con la búsqueda
     */
    public List<Movie> searchMoviesByTitle(String title) {

        return movieRepository.findByTitleContainingIgnoreCase(title);
    }

    /**
     * Busca películas aplicando opcionalmente diferentes filtros y
     * devuelve los resultados de forma paginada.
     *
     * <p>
     * Los filtros que tengan valor se combinan mediante una condición
     * {@code AND}. Los filtros que no se proporcionen no se aplican.
     * </p>
     *
     * <p>
     * Cuando no se proporciona ningún filtro se utiliza directamente
     * {@code findAll(Pageable)}. Cuando existen filtros, se construye
     * una {@link Specification} dinámica y se añade la especificación
     * encargada de cargar los géneros asociados.
     * </p>
     *
     * <p>
     * La paginación y el orden de los resultados se controlan mediante
     * el objeto {@link Pageable} recibido como parámetro.
     * </p>
     *
     * @param title       título o parte del título que se desea buscar
     * @param genreId     identificador del género por el que filtrar
     * @param releaseYear año de lanzamiento por el que filtrar
     * @param pageable    configuración de paginación y ordenación
     * @return página de películas que cumplen los filtros indicados
     */
    public Page<Movie> searchMovies(
            String title,
            Long genreId,
            Integer releaseYear,
            Pageable pageable) {

        Specification<Movie> specification = null;

        if (title != null && !title.isBlank()) {

            specification = MovieSpecification.hasTitle(title);
        }

        if (genreId != null) {

            Specification<Movie> genreSpecification = MovieSpecification.hasGenre(genreId);

            specification = specification == null
                    ? genreSpecification
                    : specification.and(genreSpecification);
        }

        if (releaseYear != null) {

            Specification<Movie> yearSpecification = MovieSpecification.hasReleaseYear(releaseYear);

            specification = specification == null
                    ? yearSpecification
                    : specification.and(yearSpecification);
        }

        /*
         * Si no hay filtros, utilizamos el findAll(Pageable)
         * del repositorio, que ya tiene @EntityGraph.
         */
        if (specification == null) {

            return movieRepository.findAll(pageable);
        }

        /*
         * Las consultas realizadas mediante JpaSpecificationExecutor
         * necesitan forzar la carga de los géneros.
         */
        specification = specification.and(
                MovieSpecification.fetchGenres());

        return movieRepository.findAll(
                specification,
                pageable);
    }
}