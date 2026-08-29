package com.emilio.streambox.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.CreateGenreRequest;
import com.emilio.streambox.dto.GenreResponse;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.mapper.GenreMapper;
import com.emilio.streambox.service.GenreService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

/**
 * Controlador REST encargado de gestionar las operaciones relacionadas
 * con los géneros cinematográficos de Streambox.
 *
 * <p>
 * Recibe las peticiones HTTP relacionadas con los géneros y delega
 * la lógica de negocio en {@link GenreService}.
 * </p>
 *
 * <p>
 * Los endpoints de este controlador requieren autenticación mediante
 * un token JWT. La consulta de géneros está disponible para cualquier
 * usuario autenticado, mientras que la creación de géneros está
 * restringida a usuarios con rol {@code ADMIN}.
 * </p>
 */
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/genres")
public class GenreController {

    private final GenreService genreService;

    /**
     * Crea una instancia del controlador de géneros.
     *
     * @param genreService servicio encargado de gestionar
     *                     la lógica de negocio de los géneros
     */
    public GenreController(GenreService genreService) {
        this.genreService = genreService;
    }

    /**
     * Obtiene todos los géneros almacenados en Streambox.
     *
     * <p>
     * Este endpoint puede ser utilizado por cualquier usuario
     * autenticado.
     * </p>
     *
     * @return lista de géneros representados mediante {@link GenreResponse}
     */
    @GetMapping
    @Operation(summary = "Obtiene todos los géneros", description = "Devuelve la lista completa de géneros "
            + "cinematográficos disponibles en Streambox.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Géneros obtenidos correctamente"),
            @ApiResponse(responseCode = "403", description = "El usuario no está autenticado")
    })
    public List<GenreResponse> getGenres() {

        return GenreMapper.toResponseList(
                genreService.getAllGenres());
    }

    /**
     * Crea un nuevo género en Streambox.
     *
     * <p>
     * Los datos recibidos mediante la petición se convierten
     * en una entidad {@link Genre} antes de ser almacenados.
     * </p>
     *
     * <p>
     * Este endpoint está restringido a usuarios con rol
     * {@code ADMIN}.
     * </p>
     *
     * @param request datos del género que se desea crear
     * @return género creado
     */
    @PostMapping
    @Operation(summary = "Crea un género", description = "Crea un nuevo género cinematográfico. "
            + "Este endpoint requiere permisos de administrador.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Género creado correctamente"),
            @ApiResponse(responseCode = "400", description = "Los datos proporcionados no son válidos"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene permisos de administrador")
    })
    public GenreResponse createGenre(
            @Valid @RequestBody CreateGenreRequest request) {

        Genre genre = GenreMapper.toEntity(request);

        Genre savedGenre = genreService.saveGenre(genre);

        return GenreMapper.toResponse(savedGenre);
    }
}