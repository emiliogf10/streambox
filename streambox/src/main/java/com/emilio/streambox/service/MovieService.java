package com.emilio.streambox.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.repository.MovieRepository;

/**
 * Servicio encargado de gestionar la lógica de negocio relacionada
 * con las películas de Streambox.
 *
 * <p>Actúa como intermediario entre los controladores y el repositorio,
 * evitando que los controladores tengan que acceder directamente
 * a la base de datos.</p>
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
     * Guarda una nueva película en la base de datos.
     *
     * <p>Antes de persistir la película se establece automáticamente
     * la fecha de creación.</p>
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
