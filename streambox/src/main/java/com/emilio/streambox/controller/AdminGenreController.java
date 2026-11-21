package com.emilio.streambox.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.AdminGenreResponse;
import com.emilio.streambox.service.GenreService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

/**
 * Vista de gestión de géneros para el panel de administración
 * ({@code /api/admin/genres}), solo lectura y solo ADMIN (regla de
 * {@code /api/admin/**} en {@code SecurityConfig}, que cubre cualquier método).
 *
 * <p>
 * <b>Por qué existe.</b> El panel ofrecía borrar cualquier género y solo
 * después el 409 {@code GENRE_IN_USE} decía que lo usaban N películas y M
 * series. Con los recuentos de antemano puede desactivar «Borrar» y explicar
 * por qué. Esos recuentos incluyen las series sin episodios, que para los
 * usuarios no existen, así que no pueden ir en {@code GET /api/genres}
 * (abierto a cualquier autenticado): viven en otra ruta con su propia regla,
 * igual que {@link AdminSeriesController}. Las escrituras siguen en
 * {@code /api/genres}.
 * </p>
 */
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/genres")
public class AdminGenreController {

    private final GenreService genreService;

    /**
     * Crea el controlador de la vista de gestión de géneros.
     *
     * @param genreService servicio con la lógica de los géneros
     */
    public AdminGenreController(GenreService genreService) {
        this.genreService = genreService;
    }

    /**
     * Lista todos los géneros con cuántas películas y series los usan.
     *
     * @return géneros por nombre, con sus recuentos
     */
    @GetMapping
    @Operation(summary = "Lista los géneros para el panel", description = "Devuelve todos los géneros, ordenados "
            + "por nombre, con cuántas películas (movieCount) y cuántas series (seriesCount) los tienen asignados. "
            + "seriesCount incluye las series sin episodios (ocultas para los usuarios). Son los mismos recuentos "
            + "que daría el 409 GENRE_IN_USE al borrarlo: solo se puede borrar un género con los dos a 0. "
            + "Requiere rol ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Géneros obtenidos correctamente (puede estar vacía)"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene permisos de administrador")
    })
    public List<AdminGenreResponse> getAdminGenres() {

        return genreService.getAdminGenres();
    }
}
