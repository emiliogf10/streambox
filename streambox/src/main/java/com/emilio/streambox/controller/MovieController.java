package com.emilio.streambox.controller;

import java.util.Set;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.MoviePageResponse;
import com.emilio.streambox.dto.MovieRequest;
import com.emilio.streambox.dto.MovieResponse;
import com.emilio.streambox.exception.InvalidParameterException;
import com.emilio.streambox.service.MovieService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Controlador REST del catálogo de películas ({@code /api/movies}).
 *
 * <p>
 * Consultar el catálogo está permitido a cualquier usuario autenticado;
 * crear, modificar o borrar películas, solo a administradores (regla definida
 * en {@code SecurityConfig}). Recibe y devuelve únicamente DTOs: la lógica y
 * el acceso a datos están en {@link MovieService}.
 * </p>
 */
@SecurityRequirement(name = "bearerAuth")
@RestController
@Validated
@RequestMapping("/api/movies")
public class MovieController {

    /**
     * Campos por los que se permite ordenar el catálogo. Se limita a una
     * lista blanca para que el cliente no pueda ordenar por relaciones
     * (como {@code genres}) ni por propiedades internas de la entidad. La
     * dirección (parámetro {@code direction}) se aplica a cualquiera de ellos.
     */
    private static final Set<String> SORTABLE_FIELDS =
            Set.of("id", "title", "releaseYear", "duration", "createdAt");

    /** Texto OpenAPI del parámetro {@code sort} (constante porque lo comparten dos endpoints). */
    private static final String SORT_DESCRIPTION =
            "Campo de ordenación: id, title, releaseYear, duration o createdAt";

    /** Texto OpenAPI del parámetro {@code direction} (compartido por dos endpoints). */
    private static final String DIRECTION_DESCRIPTION =
            "Dirección de ordenación: asc (ascendente, por defecto) o desc (descendente), sin distinguir "
                    + "mayúsculas. El desempate por id sigue la misma dirección. Por ejemplo, "
                    + "sort=createdAt&direction=desc devuelve primero las películas más recientes";

    private final MovieService movieService;

    /**
     * Crea el controlador de películas.
     *
     * @param movieService servicio con la lógica del catálogo
     */
    public MovieController(MovieService movieService) {
        this.movieService = movieService;
    }

    /**
     * Obtiene el catálogo completo de forma paginada.
     *
     * @param page número de página, comenzando desde 0 ({@code page × size} debe ser menor que 2.147.483.647)
     * @param size número de películas por página (1 a 100)
     * @param sort campo de ordenación (ver {@link #SORTABLE_FIELDS})
     * @param direction dirección de ordenación: {@code asc} (por defecto) o {@code desc}
     * @return página de películas
     */
    @GetMapping
    @Operation(summary = "Obtiene todas las películas", description = "Devuelve de forma paginada todas las películas almacenadas "
            + "en Streambox.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Películas obtenidas correctamente"),
            @ApiResponse(responseCode = "400", description = "Parámetros de paginación u ordenación inválidos"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public MoviePageResponse getMovies(
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "La página no puede ser negativa") int page,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "El tamaño mínimo de página es 1")
            @Max(value = 100, message = "El tamaño máximo de página es 100") int size,
            @RequestParam(defaultValue = "title") @Parameter(description = SORT_DESCRIPTION) String sort,
            @RequestParam(defaultValue = "asc") @Parameter(description = DIRECTION_DESCRIPTION) String direction) {

        return MoviePageResponse.from(
                movieService.getMovies(buildPageable(page, size, sort, direction)));
    }

    /**
     * Obtiene una película por su identificador.
     *
     * @param id identificador de la película
     * @return la película con sus géneros
     */
    @GetMapping("/{id}")
    @Operation(summary = "Obtiene una película por ID", description = "Devuelve la información completa de una película "
            + "incluyendo los géneros asociados.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Película encontrada"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "404", description = "Película no encontrada")
    })
    public MovieResponse getMovieById(@PathVariable Long id) {

        return movieService.getMovieById(id);
    }

    /**
     * Crea una película (solo administradores).
     *
     * @param request datos de la película y de sus géneros
     * @return película creada
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crea una película", description = "Crea una nueva película en Streambox, asociándola a "
            + "los géneros indicados. Este endpoint requiere permisos de administrador.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Película creada correctamente"),
            @ApiResponse(responseCode = "400", description = "Los datos proporcionados no son válidos"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene permisos de administrador"),
            @ApiResponse(responseCode = "404", description = "Uno o más géneros no existen")
    })
    public MovieResponse createMovie(@Valid @RequestBody MovieRequest request) {

        return movieService.createMovie(request);
    }

    /**
     * Modifica una película existente (solo administradores).
     *
     * @param id      identificador de la película
     * @param request datos nuevos (sustituyen a los anteriores)
     * @return película modificada
     */
    @PutMapping("/{id}")
    @Operation(summary = "Modifica una película", description = "Actualiza los datos de una película existente "
            + "y sus géneros asociados. Requiere rol ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Película modificada correctamente"),
            @ApiResponse(responseCode = "400", description = "Los datos proporcionados no son válidos"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene permisos de administrador"),
            @ApiResponse(responseCode = "404", description = "Película o género no encontrado")
    })
    public MovieResponse updateMovie(
            @PathVariable Long id,
            @Valid @RequestBody MovieRequest request) {

        return movieService.updateMovie(id, request);
    }

    /**
     * Elimina una película (solo administradores).
     *
     * @param id identificador de la película
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Elimina una película", description = "Elimina de Streambox la película correspondiente "
            + "al ID indicado. La película se retira automáticamente de todos los favoritos. Requiere rol ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Película eliminada correctamente"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene permisos de administrador"),
            @ApiResponse(responseCode = "404", description = "Película no encontrada")
    })
    public void deleteMovie(@PathVariable Long id) {

        movieService.deleteMovie(id);
    }

    /**
     * Busca películas combinando filtros opcionales, de forma paginada.
     *
     * <p>
     * Los parámetros que no se indican no se usan como criterio.
     * </p>
     *
     * @param title       texto que debe contener el título
     * @param genreId     identificador del género
     * @param releaseYear año de lanzamiento
     * @param page        número de página, comenzando desde 0 ({@code page × size} debe ser menor que 2.147.483.647)
     * @param size        número de películas por página (1 a 100)
     * @param sort        campo de ordenación (ver {@link #SORTABLE_FIELDS})
     * @param direction   dirección de ordenación: {@code asc} (por defecto) o {@code desc}
     * @return página de películas encontradas
     */
    @GetMapping("/search")
    @Operation(summary = "Busca películas", description = "Busca películas aplicando opcionalmente filtros "
            + "por título, género y año de lanzamiento. "
            + "Los resultados se devuelven de forma paginada y ordenada.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Búsqueda realizada correctamente"),
            @ApiResponse(responseCode = "400", description = "Parámetros de paginación u ordenación inválidos"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public MoviePageResponse searchMovies(
            @RequestParam(required = false) String title,
            @RequestParam(required = false) Long genreId,
            @RequestParam(required = false) Integer releaseYear,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "La página no puede ser negativa") int page,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "El tamaño mínimo de página es 1")
            @Max(value = 100, message = "El tamaño máximo de página es 100") int size,
            @RequestParam(defaultValue = "title") @Parameter(description = SORT_DESCRIPTION) String sort,
            @RequestParam(defaultValue = "asc") @Parameter(description = DIRECTION_DESCRIPTION) String direction) {

        return MoviePageResponse.from(
                movieService.searchMovies(title, genreId, releaseYear, buildPageable(page, size, sort, direction)));
    }

    /**
     * Construye la paginación con la lista blanca de campos de películas. Las
     * reglas (desempate por id en la misma dirección, dirección sin distinguir
     * mayúsculas, desbordamiento de {@code page × size}) están en
     * {@link PageableFactory}, compartidas con el catálogo de series.
     *
     * @param page      número de página
     * @param size      tamaño de página
     * @param sort      campo de ordenación solicitado por el cliente
     * @param direction dirección solicitada ({@code asc} o {@code desc})
     * @return paginación ordenada por el campo indicado en la dirección pedida
     * @throws InvalidParameterException si el campo, la dirección o la página no están permitidos
     */
    private static Pageable buildPageable(int page, int size, String sort, String direction) {

        return PageableFactory.build(page, size, sort, direction, SORTABLE_FIELDS);
    }
}
