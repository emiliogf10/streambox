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
 * <p>Centraliza las operaciones relacionadas con las películas,
 * evitando que los controladores tengan que acceder directamente
 * al repositorio.</p>
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
                .orElseThrow(() ->
                        new MovieNotFoundException("Película no encontrada"));
    }

    /**
     * Guarda una nueva película en la base de datos.
     *
     * <p>La fecha de creación se establece automáticamente en el
     * momento en el que se registra la película.</p>
     *
     * @param movie película que se desea guardar
     * @return película almacenada, incluyendo los datos generados
     *         por la base de datos
     */
    public Movie saveMovie(Movie movie) {

        movie.setCreatedAt(LocalDateTime.now());

        return movieRepository.save(movie);
    }
}
