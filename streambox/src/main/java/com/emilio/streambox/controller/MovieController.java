package com.emilio.streambox.controller;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.CreateMovieRequest;
import com.emilio.streambox.dto.MoviePageResponse;
import com.emilio.streambox.dto.MovieResponse;
import com.emilio.streambox.dto.UpdateMovieRequest;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.exception.MovieNotFoundException;
import com.emilio.streambox.mapper.MovieMapper;
import com.emilio.streambox.service.MovieService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

/**
 * Controlador REST encargado de gestionar las operaciones relacionadas
 * con las películas de Streambox.
 *
 * <p>
 * Recibe las peticiones HTTP relacionadas con las películas y delega
 * la lógica de negocio en {@link MovieService}.
 * </p>
 */
@SecurityRequirement(name = "bearerAuth")
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
    @Operation(summary = "Obtiene todas las películas", description = "Devuelve una lista con todas las películas almacenadas "
            + "en Streambox.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Películas obtenidas correctamente"),
            @ApiResponse(responseCode = "403", description = "El usuario no está autenticado")
    })
    public List<MovieResponse> getMovies() {

        return MovieMapper.toResponseList(
                movieService.getAllMovies());
    }

    /**
     * Obtiene una película mediante su identificador.
     *
     * @param id identificador de la película que se desea consultar
     * @return película correspondiente al identificador proporcionado
     */
    @GetMapping("/{id}")
    @Operation(summary = "Obtiene una película por ID", description = "Devuelve la información completa de una película "
            + "incluyendo los géneros asociados.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Película encontrada"),
            @ApiResponse(responseCode = "404", description = "Película no encontrada"),
            @ApiResponse(responseCode = "403", description = "El usuario no está autenticado")
    })
    public MovieResponse getMovieById(@PathVariable Long id) {

        Movie movie = movieService.getMovieById(id);

        return MovieMapper.toResponse(movie);
    }

    /**
     * Crea una nueva película en Streambox.
     *
     * <p>
     * Los datos recibidos mediante la petición se convierten
     * en una entidad {@link Movie} antes de ser almacenados.
     * </p>
     *
     * @param request datos de la película que se desea crear
     * @return película creada
     */
    @PostMapping
    @Operation(summary = "Crea una película", description = "Crea una nueva película y la asocia con los géneros "
            + "indicados en la petición.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Película creada correctamente"),
            @ApiResponse(responseCode = "400", description = "Los datos proporcionados no son válidos"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene permisos de administrador"),
            @ApiResponse(responseCode = "404", description = "Uno de los géneros indicados no existe")
    })
    public MovieResponse createMovie(
            @Valid @RequestBody CreateMovieRequest request) {

        Movie movie = MovieMapper.toEntity(request);

        Movie savedMovie = movieService.saveMovie(movie, request.getGenreIds());

        return MovieMapper.toResponse(savedMovie);
    }

    /**
     * Elimina una película existente.
     *
     * <p>
     * La eliminación se realiza mediante el identificador proporcionado
     * en la URL.
     * </p>
     *
     * @param id identificador de la película que se desea eliminar
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "Elimina una película", description = "Elimina de forma permanente una película existente "
            + "mediante su identificador.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Película eliminada correctamente"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene permisos de administrador"),
            @ApiResponse(responseCode = "404", description = "Película no encontrada")
    })
    public void deleteMovie(@PathVariable Long id) {

        movieService.deleteMovie(id);
    }

    /**
     * Modifica una película existente.
     *
     * <p>
     * Los datos recibidos se utilizan para actualizar la película
     * identificada mediante el ID proporcionado en la URL.
     * </p>
     *
     * @param id      identificador de la película que se desea modificar
     * @param request datos actualizados de la película
     * @return película modificada
     * @throws MovieNotFoundException si no existe la película indicada
     */
    @PutMapping("/{id}")
    @Operation(summary = "Modifica una película", description = "Actualiza los datos de una película existente "
            + "y sus géneros asociados.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Película modificada correctamente"),
            @ApiResponse(responseCode = "400", description = "Los datos proporcionados no son válidos"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene permisos de administrador"),
            @ApiResponse(responseCode = "404", description = "Película o género no encontrado")
    })
    public MovieResponse updateMovie(
            @PathVariable Long id,
            @Valid @RequestBody UpdateMovieRequest request) {

        Movie updatedMovie = movieService.updateMovie(id, request);

        return MovieMapper.toResponse(updatedMovie);
    }

    /**
     * Busca películas utilizando filtros opcionales y devuelve
     * los resultados de forma paginada.
     *
     * <p>
     * Se pueden combinar los filtros de título, género y año
     * de lanzamiento. Los parámetros que no se proporcionen
     * no se utilizan como criterio de búsqueda.
     * </p>
     *
     * @param title       texto que debe contener el título
     * @param genreId     identificador del género
     * @param releaseYear año de lanzamiento
     * @param page        número de página, comenzando desde 0
     * @param size        número máximo de películas por página
     * @param sort        campo utilizado para ordenar los resultados
     * @return respuesta paginada con las películas encontradas
     */
    @GetMapping("/search")
    @Operation(summary = "Busca películas", description = "Busca películas aplicando opcionalmente filtros "
            + "por título, género y año de lanzamiento. "
            + "Los resultados se devuelven de forma paginada y ordenada.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Búsqueda realizada correctamente"),
            @ApiResponse(responseCode = "403", description = "El usuario no está autenticado")
    })
    public MoviePageResponse searchMovies(
            @RequestParam(required = false) String title,
            @RequestParam(required = false) Long genreId,
            @RequestParam(required = false) Integer releaseYear,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "title") String sort) {

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(sort).ascending());

        Page<Movie> moviePage = movieService.searchMovies(
                title,
                genreId,
                releaseYear,
                pageable);

        return MoviePageResponse.from(moviePage);
    }

}
