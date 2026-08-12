package com.emilio.streambox.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.exception.MovieNotFoundException;
import com.emilio.streambox.repository.MovieRepository;

/**
 * Servicio encargado de gestionar la lógica de negocio relacionada
 * con las películas de Streambox.
 *
 * <p>
 * Centraliza las operaciones relacionadas con las películas,
 * evitando que los controladores tengan que acceder directamente
 * al repositorio.
 * </p>
 */
@Service
public class MovieService {

    private final MovieRepository movieRepository;

    /**
     * Crea una instancia del servicio de películas.
     *
     * @param movieRepository repositorio utilizado para acceder
     *                        a las películas almacenadas
     */
    public MovieService(MovieRepository movieRepository) {
        this.movieRepository = movieRepository;
    }

    /**
     * Obtiene todas las películas almacenadas en Streambox.
     *
     * @return lista de todas las películas
     */
    public List<Movie> getAllMovies() {
        return movieRepository.findAll();
    }

    /**
     * Obtiene una película mediante su identificador.
     *
     * @param id identificador de la película que se desea obtener
     * @return película correspondiente al identificador proporcionado
     * @throws RuntimeException si no existe ninguna película con el ID indicado
     */
    public Movie getMovieById(Long id) {

        return movieRepository.findById(id)
                .orElseThrow(() -> new MovieNotFoundException("Película no encontrada"));
    }

    /**
     * Guarda una nueva película en la base de datos.
     *
     * <p>
     * La fecha de creación se establece automáticamente en el
     * momento en el que se registra la película.
     * </p>
     *
     * @param movie película que se desea guardar
     * @return película almacenada, incluyendo los datos generados
     *         por la base de datos
     */
    public Movie saveMovie(Movie movie) {

        movie.setCreatedAt(LocalDateTime.now());

        return movieRepository.save(movie);
    }

    /**
     * Modifica los datos de una película existente.
     *
     * <p>
     * Primero se comprueba que la película exista. Si existe, se actualizan
     * sus datos y se conserva la fecha original de creación.
     * </p>
     *
     * @param id    identificador de la película que se desea modificar
     * @param movie datos actualizados de la película
     * @return película modificada y almacenada en la base de datos
     * @throws MovieNotFoundException si no existe una película con el ID indicado
     */
    public Movie updateMovie(Long id, Movie movie) {

        Movie existingMovie = movieRepository.findById(id)
                .orElseThrow(() -> new MovieNotFoundException("Película no encontrada"));

        existingMovie.setTitle(movie.getTitle());
        existingMovie.setDescription(movie.getDescription());
        existingMovie.setReleaseYear(movie.getReleaseYear());

        return movieRepository.save(existingMovie);
    }

    /**
     * Elimina una película existente mediante su identificador.
     *
     * <p>
     * Antes de eliminar la película se comprueba que exista. De esta
     * forma, si se solicita eliminar un identificador inexistente, se
     * lanza {@link MovieNotFoundException} y la API puede devolver
     * correctamente un {@code 404 Not Found}.
     * </p>
     *
     * @param id identificador de la película que se desea eliminar
     * @throws MovieNotFoundException si no existe ninguna película
     *                                con el ID indicado
     */
    public void deleteMovie(Long id) {

        if (!movieRepository.existsById(id)) {
            throw new MovieNotFoundException("Película no encontrada");
        }

        movieRepository.deleteById(id);
    }
}
