package com.emilio.streambox.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.EpisodeRequest;
import com.emilio.streambox.dto.EpisodeResponse;
import com.emilio.streambox.service.EpisodeService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

/**
 * Controlador REST de los episodios de una serie
 * ({@code /api/series/{seriesId}/episodes}).
 *
 * <p>
 * Solo escritura y solo administradores (regla de {@code SecurityConfig} para
 * cualquier escritura bajo {@code /api/series/**}). Los usuarios leen los
 * episodios dentro del detalle de la serie ({@code GET /api/series/{id}}), así
 * que no hace falta un listado propio. Está separado de
 * {@link SeriesController} para que cada controlador tenga un solo recurso.
 * </p>
 */
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/series/{seriesId}/episodes")
public class EpisodeController {

    private final EpisodeService episodeService;

    /**
     * Crea el controlador de episodios.
     *
     * @param episodeService servicio con la gestión de episodios
     */
    public EpisodeController(EpisodeService episodeService) {
        this.episodeService = episodeService;
    }

    /**
     * Añade un episodio a una serie (solo administradores).
     *
     * @param seriesId identificador de la serie
     * @param request  datos del episodio
     * @return el episodio creado
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Añade un episodio a una serie", description = "Crea un episodio en la temporada y "
            + "con el número indicados. Con el primer episodio la serie pasa a ser visible para los "
            + "usuarios. Requiere rol ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Episodio creado correctamente"),
            @ApiResponse(responseCode = "400", description = "Los datos proporcionados no son válidos"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene permisos de administrador"),
            @ApiResponse(responseCode = "404", description = "Serie no encontrada"),
            @ApiResponse(responseCode = "409", description = "Ya existe un episodio con esa temporada y número "
                    + "(código EPISODE_ALREADY_EXISTS)")
    })
    public EpisodeResponse createEpisode(
            @PathVariable Long seriesId,
            @Valid @RequestBody EpisodeRequest request) {

        return episodeService.createEpisode(seriesId, request);
    }

    /**
     * Modifica un episodio de una serie (solo administradores).
     *
     * @param seriesId  identificador de la serie
     * @param episodeId identificador del episodio
     * @param request   datos nuevos (sustituyen a los anteriores)
     * @return el episodio modificado
     */
    @PutMapping("/{episodeId}")
    @Operation(summary = "Modifica un episodio", description = "Actualiza los datos de un episodio de la serie, "
            + "incluida su temporada y número. Requiere rol ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Episodio modificado correctamente"),
            @ApiResponse(responseCode = "400", description = "Los datos proporcionados no son válidos"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene permisos de administrador"),
            @ApiResponse(responseCode = "404", description = "La serie no existe, o el episodio no existe o es "
                    + "de otra serie"),
            @ApiResponse(responseCode = "409", description = "Otro episodio ya ocupa esa temporada y número "
                    + "(código EPISODE_ALREADY_EXISTS)")
    })
    public EpisodeResponse updateEpisode(
            @PathVariable Long seriesId,
            @PathVariable Long episodeId,
            @Valid @RequestBody EpisodeRequest request) {

        return episodeService.updateEpisode(seriesId, episodeId, request);
    }

    /**
     * Elimina un episodio de una serie (solo administradores).
     *
     * @param seriesId  identificador de la serie
     * @param episodeId identificador del episodio
     */
    @DeleteMapping("/{episodeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Elimina un episodio", description = "Elimina un episodio de la serie. Si era el "
            + "último, la serie vuelve a quedar oculta para los usuarios. Requiere rol ADMIN.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Episodio eliminado correctamente"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene permisos de administrador"),
            @ApiResponse(responseCode = "404", description = "La serie no existe, o el episodio no existe o es "
                    + "de otra serie")
    })
    public void deleteEpisode(
            @PathVariable Long seriesId,
            @PathVariable Long episodeId) {

        episodeService.deleteEpisode(seriesId, episodeId);
    }
}
