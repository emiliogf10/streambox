package com.emilio.streambox.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.CreateMovieRequest;
import com.emilio.streambox.dto.MovieResponse;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.mapper.MovieMapper;
import com.emilio.streambox.service.MovieService;

import jakarta.validation.Valid;

/**
 * Controlador REST encargado de gestionar las operaciones relacionadas
 * con las películas de Streambox.
 *
 * <p>Recibe las peticiones HTTP relacionadas con las películas y delega
 * la lógica de negocio en {@link MovieService}.</p>
 */
@RestController
@RequestMapping("/api/movies")
public class MovieController {

    private final MovieService movieService;

    /**
     * Crea una instancia del controlador de películas.
     *
     * @param movieService servicio encargado de gestionar la lógica
     *                     de negocio de las películas
     */
    public MovieController(MovieService movieService) {
        this.movieService = movieService;
    }

    /**
     * Obtiene todas las películas almacenadas en Streambox.
     *
     * @return lista de películas representadas mediante {@link MovieResponse}
     */
    @GetMapping
    public List<MovieResponse> getMovies() {

        return MovieMapper.toResponseList(
                movieService.getAllMovies()
        );
    }

    /**
     * Obtiene una película mediante su identificador.
     *
     * @param id identificador de la película que se desea consultar
     * @return película correspondiente al identificador proporcionado
     */
    @GetMapping("/{id}")
    public MovieResponse getMovieById(@PathVariable Long id) {

        Movie movie = movieService.getMovieById(id);

        return MovieMapper.toResponse(movie);
    }

    /**
     * Crea una nueva película en Streambox.
     *
     * <p>Los datos recibidos mediante la petición se convierten
     * en una entidad {@link Movie} antes de ser almacenados.</p>
     *
     * @param request datos de la película que se desea crear
     * @return película creada
     */
    @PostMapping
    public MovieResponse createMovie(
            @Valid @RequestBody CreateMovieRequest request) {

        Movie movie = MovieMapper.toEntity(request);

        Movie savedMovie = movieService.saveMovie(movie);

        return MovieMapper.toResponse(savedMovie);
    }
}
