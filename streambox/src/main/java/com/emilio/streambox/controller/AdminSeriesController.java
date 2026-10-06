package com.emilio.streambox.controller;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.SeriesDetailResponse;
import com.emilio.streambox.dto.SeriesPageResponse;
import com.emilio.streambox.service.SeriesService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Vistas de gestión de series para el panel de administración
 * ({@code /api/admin/series}), solo lectura y solo ADMIN (regla de
 * {@code /api/admin/**} en {@code SecurityConfig}).
 *
 * <p>
 * <b>Por qué existen.</b> El catálogo público ({@link SeriesController})
 * oculta las series sin episodios, pero el administrador necesita verlas: una
 * serie recién creada está vacía y es justo la que tiene que abrir para subirle
 * episodios. En lugar de que {@code /api/series} cambie su respuesta según el
 * rol (un mismo endpoint con dos contratos, fácil de romper y de cachear mal),
 * estas vistas viven en otra ruta con su propia regla de seguridad. Las
 * escrituras siguen en {@code /api/series}.
 * </p>
 */
@SecurityRequirement(name = "bearerAuth")
@RestController
@Validated
@RequestMapping("/api/admin/series")
public class AdminSeriesController {

    private final SeriesService seriesService;

    /**
     * Crea el controlador de las vistas de gestión de series.
     *
     * @param seriesService servicio con la lógica del catálogo de series
     */
    public AdminSeriesController(SeriesService seriesService) {
        this.seriesService = seriesService;
    }

    /**
     * Lista todas las series, incluidas las que no tienen episodios.
     *
     * @param title     texto que debe contener el título (opcional; sin él, todas)
     * @param page      número de página, comenzando desde 0 ({@code page × size} debe ser menor que 2.147.483.647)
     * @param size      número de series por página (1 a 100)
     * @param sort      campo de ordenación (mismos que {@code /api/series})
     * @param direction dirección de ordenación: {@code asc} (por defecto) o {@code desc}
     * @return página de series con sus recuentos (0 en las vacías)
     */
    @GetMapping
    @Operation(summary = "Lista las series para el panel", description = "Devuelve de forma paginada todas las "
            + "series, también las que aún no tienen episodios (episodeCount = 0, ocultas para los usuarios). "
            + "Filtro opcional por título. Requiere rol ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Series obtenidas correctamente"),
            @ApiResponse(responseCode = "400", description = "Parámetros de paginación u ordenación inválidos"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene permisos de administrador")
    })
    public SeriesPageResponse getSeries(
            @RequestParam(required = false) String title,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "La página no puede ser negativa") int page,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "El tamaño mínimo de página es 1")
            @Max(value = 100, message = "El tamaño máximo de página es 100") int size,
            @RequestParam(defaultValue = "title")
            @Parameter(description = SeriesController.SORT_DESCRIPTION) String sort,
            @RequestParam(defaultValue = "asc")
            @Parameter(description = SeriesController.DIRECTION_DESCRIPTION) String direction) {

        return SeriesPageResponse.from(seriesService.getAdminSeries(
                title, SeriesController.buildPageable(page, size, sort, direction)));
    }

    /**
     * Obtiene el detalle de cualquier serie, aunque no tenga episodios.
     *
     * @param id identificador de la serie
     * @return la serie con sus géneros y temporadas ({@code seasons} vacío si no tiene episodios)
     */
    @GetMapping("/{id}")
    @Operation(summary = "Obtiene una serie para el panel", description = "Devuelve la serie con sus géneros y "
            + "temporadas aunque no tenga episodios (seasons vacío). Requiere rol ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Serie encontrada"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene permisos de administrador"),
            @ApiResponse(responseCode = "404", description = "Serie no encontrada")
    })
    public SeriesDetailResponse getSeriesById(@PathVariable Long id) {

        return seriesService.getAdminSeriesById(id);
    }
}
