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

import com.emilio.streambox.dto.SeriesDetailResponse;
import com.emilio.streambox.dto.SeriesPageResponse;
import com.emilio.streambox.dto.SeriesRequest;
import com.emilio.streambox.exception.InvalidParameterException;
import com.emilio.streambox.service.SeriesService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Controlador REST del catálogo de series ({@code /api/series}).
 *
 * <p>
 * Las lecturas están permitidas a cualquier usuario autenticado y solo
 * muestran series <strong>con episodios</strong>: una serie vacía no sale en
 * el listado ni en la búsqueda y su detalle responde 404, igual que si no
 * existiera. Crear, modificar y borrar series es solo para administradores
 * (regla de {@code SecurityConfig}); el panel ve las series vacías a través de
 * {@link AdminSeriesController}. Los episodios se gestionan en
 * {@link EpisodeController}.
 * </p>
 */
@SecurityRequirement(name = "bearerAuth")
@RestController
@Validated
@RequestMapping("/api/series")
public class SeriesController {

    /**
     * Campos por los que se permite ordenar las series (lista blanca, como en
     * películas; las series no tienen duración).
     */
    static final Set<String> SORTABLE_FIELDS = Set.of("id", "title", "releaseYear", "createdAt");

    /** Texto OpenAPI del parámetro {@code sort} (lo comparten varios endpoints). */
    static final String SORT_DESCRIPTION = "Campo de ordenación: id, title, releaseYear o createdAt";

    /** Texto OpenAPI del parámetro {@code direction} (lo comparten varios endpoints). */
    static final String DIRECTION_DESCRIPTION =
            "Dirección de ordenación: asc (ascendente, por defecto) o desc (descendente), sin distinguir "
                    + "mayúsculas. El desempate por id sigue la misma dirección. Por ejemplo, "
                    + "sort=createdAt&direction=desc devuelve primero las series más recientes";

    private final SeriesService seriesService;

    /**
     * Crea el controlador de series.
     *
     * @param seriesService servicio con la lógica del catálogo de series
     */
    public SeriesController(SeriesService seriesService) {
        this.seriesService = seriesService;
    }

    /**
     * Obtiene las series visibles (con episodios) de forma paginada.
     *
     * @param page      número de página, comenzando desde 0 ({@code page × size} debe ser menor que 2.147.483.647)
     * @param size      número de series por página (1 a 100)
     * @param sort      campo de ordenación (ver {@link #SORTABLE_FIELDS})
     * @param direction dirección de ordenación: {@code asc} (por defecto) o {@code desc}
     * @return página de series
     */
    @GetMapping
    @Operation(summary = "Obtiene las series", description = "Devuelve de forma paginada las series que tienen "
            + "al menos un episodio, con sus recuentos de temporadas y episodios. Las series sin episodios "
            + "no aparecen.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Series obtenidas correctamente"),
            @ApiResponse(responseCode = "400", description = "Parámetros de paginación u ordenación inválidos"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public SeriesPageResponse getSeries(
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "La página no puede ser negativa") int page,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "El tamaño mínimo de página es 1")
            @Max(value = 100, message = "El tamaño máximo de página es 100") int size,
            @RequestParam(defaultValue = "title") @Parameter(description = SORT_DESCRIPTION) String sort,
            @RequestParam(defaultValue = "asc") @Parameter(description = DIRECTION_DESCRIPTION) String direction) {

        return SeriesPageResponse.from(seriesService.getSeries(buildPageable(page, size, sort, direction)));
    }

    /**
     * Busca series visibles combinando filtros opcionales, de forma paginada.
     *
     * @param title       texto que debe contener el título
     * @param genreId     identificador del género
     * @param releaseYear año de estreno
     * @param page        número de página, comenzando desde 0 ({@code page × size} debe ser menor que 2.147.483.647)
     * @param size        número de series por página (1 a 100)
     * @param sort        campo de ordenación (ver {@link #SORTABLE_FIELDS})
     * @param direction   dirección de ordenación: {@code asc} (por defecto) o {@code desc}
     * @return página de series encontradas
     */
    @GetMapping("/search")
    @Operation(summary = "Busca series", description = "Busca series con episodios aplicando opcionalmente "
            + "filtros por título (contiene, sin distinguir mayúsculas; % y _ se tratan como texto), género "
            + "y año de estreno. Los resultados se devuelven de forma paginada y ordenada.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Búsqueda realizada correctamente"),
            @ApiResponse(responseCode = "400", description = "Parámetros de paginación u ordenación inválidos"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public SeriesPageResponse searchSeries(
            @RequestParam(required = false) String title,
            @RequestParam(required = false) Long genreId,
            @RequestParam(required = false) Integer releaseYear,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "La página no puede ser negativa") int page,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "El tamaño mínimo de página es 1")
            @Max(value = 100, message = "El tamaño máximo de página es 100") int size,
            @RequestParam(defaultValue = "title") @Parameter(description = SORT_DESCRIPTION) String sort,
            @RequestParam(defaultValue = "asc") @Parameter(description = DIRECTION_DESCRIPTION) String direction) {

        return SeriesPageResponse.from(seriesService.searchSeries(
                title, genreId, releaseYear, buildPageable(page, size, sort, direction)));
    }

    /**
     * Obtiene el detalle de una serie visible, con sus temporadas y episodios.
     *
     * @param id identificador de la serie
     * @return la serie con sus géneros y temporadas
     */
    @GetMapping("/{id}")
    @Operation(summary = "Obtiene una serie por ID", description = "Devuelve la serie con sus géneros y sus "
            + "temporadas (cada una con sus episodios, ordenados por número). Una serie sin episodios "
            + "responde 404, igual que una inexistente.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Serie encontrada"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "404", description = "La serie no existe o no tiene episodios")
    })
    public SeriesDetailResponse getSeriesById(@PathVariable Long id) {

        return seriesService.getSeriesById(id);
    }

    /**
     * Crea una serie sin episodios (solo administradores).
     *
     * @param request datos de la serie y de sus géneros
     * @return la serie creada, con {@code seasons} vacío
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crea una serie", description = "Crea una serie asociada a los géneros indicados. "
            + "Nace sin episodios, así que los usuarios no la verán hasta que se le añada el primero. "
            + "Requiere rol ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Serie creada correctamente"),
            @ApiResponse(responseCode = "400", description = "Los datos proporcionados no son válidos"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene permisos de administrador"),
            @ApiResponse(responseCode = "404", description = "Uno o más géneros no existen")
    })
    public SeriesDetailResponse createSeries(@Valid @RequestBody SeriesRequest request) {

        return seriesService.createSeries(request);
    }

    /**
     * Modifica una serie existente (solo administradores). Sus episodios no
     * cambian.
     *
     * @param id      identificador de la serie
     * @param request datos nuevos (sustituyen a los anteriores)
     * @return la serie modificada con sus temporadas
     */
    @PutMapping("/{id}")
    @Operation(summary = "Modifica una serie", description = "Actualiza los datos de una serie y sus géneros. "
            + "Los episodios no se tocan. Requiere rol ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Serie modificada correctamente"),
            @ApiResponse(responseCode = "400", description = "Los datos proporcionados no son válidos"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene permisos de administrador"),
            @ApiResponse(responseCode = "404", description = "Serie o género no encontrado")
    })
    public SeriesDetailResponse updateSeries(
            @PathVariable Long id,
            @Valid @RequestBody SeriesRequest request) {

        return seriesService.updateSeries(id, request);
    }

    /**
     * Elimina una serie con todos sus episodios (solo administradores).
     *
     * @param id identificador de la serie
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Elimina una serie", description = "Elimina la serie, todos sus episodios y la retira "
            + "de las listas de todos los usuarios. Requiere rol ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Serie eliminada correctamente"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene permisos de administrador"),
            @ApiResponse(responseCode = "404", description = "Serie no encontrada")
    })
    public void deleteSeries(@PathVariable Long id) {

        seriesService.deleteSeries(id);
    }

    /**
     * Construye la paginación con la lista blanca de campos de series (reglas
     * comunes en {@link PageableFactory}).
     *
     * @throws InvalidParameterException si el campo, la dirección o la página no están permitidos
     */
    static Pageable buildPageable(int page, int size, String sort, String direction) {

        return PageableFactory.build(page, size, sort, direction, SORTABLE_FIELDS);
    }
}
