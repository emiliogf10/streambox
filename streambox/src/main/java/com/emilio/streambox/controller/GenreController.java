package com.emilio.streambox.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.CreateGenreRequest;
import com.emilio.streambox.dto.GenreResponse;
import com.emilio.streambox.service.GenreService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

/**
 * Controlador REST de los géneros cinematográficos ({@code /api/genres}).
 *
 * <p>
 * Consultar los géneros está permitido a cualquier usuario autenticado;
 * crearlos, solo a administradores (regla definida en {@code SecurityConfig}).
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
     * Obtiene todos los géneros.
     *
     * @return lista de géneros
     */
    @GetMapping
    @Operation(summary = "Obtiene todos los géneros", description = "Devuelve la lista completa de géneros "
            + "cinematográficos disponibles en Streambox.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Géneros obtenidos correctamente"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public List<GenreResponse> getGenres() {

        return genreService.getAllGenres();
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
            @ApiResponse(responseCode = "409", description = "Ya existe un género con ese nombre")
    })
    public GenreResponse createGenre(@Valid @RequestBody CreateGenreRequest request) {

        return genreService.createGenre(request);
    }
}
