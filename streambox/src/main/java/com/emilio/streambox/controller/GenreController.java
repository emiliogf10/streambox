package com.emilio.streambox.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
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

import com.emilio.streambox.dto.GenreRequest;
import com.emilio.streambox.dto.GenreResponse;
import com.emilio.streambox.service.GenreService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

/**
 * Controlador REST de los géneros cinematográficos ({@code /api/genres}).
 *
 * <p>
 * Consultar los géneros está permitido a cualquier usuario autenticado;
 * crearlos, renombrarlos y borrarlos, solo a administradores (reglas
 * definidas en {@code SecurityConfig}).
 * </p>
 */
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/genres")
public class GenreController {

    private final GenreService genreService;

    /**
     * Crea el controlador de géneros.
     *
     * @param genreService servicio con la lógica de los géneros
     */
    public GenreController(GenreService genreService) {
        this.genreService = genreService;
    }

    /**
     * Obtiene los géneros: todos o, con {@code withTitles}, solo los que tienen
     * algún título visible de ese tipo.
     *
     * @param withTitles {@code movies}, {@code series} o ausente (todos)
     * @return lista de géneros
     */
    @GetMapping
    @Operation(summary = "Obtiene los géneros", description = "Sin parámetros, devuelve la lista completa de "
            + "géneros, sin orden garantizado. Con withTitles=movies, solo los que tienen alguna película; con "
            + "withTitles=series, solo los que tienen alguna serie con episodios (las series sin episodios no "
            + "cuentan). Con withTitles, la lista va ordenada por nombre. Pensado para los filtros de los "
            + "catálogos: así no se ofrece un género que solo llevaría a una página vacía.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Géneros obtenidos correctamente (con withTitles "
                    + "puede estar vacía)"),
            @ApiResponse(responseCode = "400", description = "withTitles no es movies ni series (código "
                    + "VALIDATION_ERROR, motivo en validationErrors.withTitles: \""
                    + GenreService.WITH_TITLES_MESSAGE + "\")"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public List<GenreResponse> getGenres(
            @RequestParam(name = GenreService.WITH_TITLES_PARAMETER, required = false)
            @Parameter(description = "Solo los géneros con algún título visible de este tipo: movies (alguna "
                    + "película) o series (alguna serie con episodios). En minúsculas; sin él, todos.",
                    schema = @Schema(type = "string",
                            allowableValues = { GenreService.WITH_MOVIES, GenreService.WITH_SERIES }))
            String withTitles) {

        return genreService.getGenres(withTitles);
    }

    /**
     * Crea un género (solo administradores).
     *
     * @param request datos del género
     * @return género creado
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Crea un género", description = "Crea un nuevo género cinematográfico. "
            + "Este endpoint requiere permisos de administrador.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Género creado correctamente"),
            @ApiResponse(responseCode = "400", description = "Los datos proporcionados no son válidos"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene permisos de administrador"),
            @ApiResponse(responseCode = "409", description = "Ya existe un género con ese nombre "
                    + "(código GENRE_ALREADY_EXISTS)")
    })
    public GenreResponse createGenre(@Valid @RequestBody GenreRequest request) {

        return genreService.createGenre(request);
    }

    /**
     * Renombra un género (solo administradores).
     *
     * <p>
     * Las películas que lo tienen asignado mantienen la relación, porque va
     * por identificador y no por nombre.
     * </p>
     *
     * @param id      identificador del género
     * @param request nombre nuevo
     * @return género con el nombre ya normalizado
     */
    @PutMapping("/{id}")
    @Operation(summary = "Renombra un género", description = "Cambia el nombre de un género existente. "
            + "El nombre se normaliza igual que en el alta (sin espacios exteriores, primera letra en "
            + "mayúscula y el resto en minúscula). Renombrarlo a su mismo nombre no es un error. "
            + "Requiere rol ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Género modificado correctamente"),
            @ApiResponse(responseCode = "400", description = "Los datos proporcionados no son válidos"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene permisos de administrador"),
            @ApiResponse(responseCode = "404", description = "Género no encontrado"),
            @ApiResponse(responseCode = "409", description = "Otro género ya tiene ese nombre "
                    + "(código GENRE_ALREADY_EXISTS)")
    })
    public GenreResponse updateGenre(
            @PathVariable Long id,
            @Valid @RequestBody GenreRequest request) {

        return genreService.updateGenre(id, request);
    }

    /**
     * Elimina un género que ninguna película ni serie tenga asignado (solo administradores).
     *
     * @param id identificador del género
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Elimina un género", description = "Elimina el género indicado. Solo se puede borrar "
            + "si ninguna película ni serie lo tiene asignado; si no, hay que quitarlo antes de esas "
            + "películas y series. "
            + "Requiere rol ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Género eliminado correctamente"),
            @ApiResponse(responseCode = "400", description = "El identificador no tiene un formato válido"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene permisos de administrador"),
            @ApiResponse(responseCode = "404", description = "Género no encontrado"),
            @ApiResponse(responseCode = "409", description = "Alguna película o serie tiene asignado el "
                    + "género; el mensaje indica cuántas de cada (código GENRE_IN_USE)")
    })
    public void deleteGenre(@PathVariable Long id) {

        genreService.deleteGenre(id);
    }
}
