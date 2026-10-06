package com.emilio.streambox.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.SeriesResponse;
import com.emilio.streambox.security.AuthenticatedUser;
import com.emilio.streambox.service.SeriesFavoriteService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

/**
 * Controlador REST de las series de "Mi lista" del usuario autenticado
 * ({@code /api/users/me/favorites/series}).
 *
 * <p>
 * Como {@link FavoriteController}, todas las operaciones usan el id del token
 * ({@code @AuthenticationPrincipal}); no hay ninguna ruta con el id de otro
 * usuario.
 * </p>
 *
 * <p>
 * <b>Convivencia con las rutas de películas.</b> {@link FavoriteController}
 * tiene {@code /api/users/me/favorites/{movieId}}, que encaja con
 * {@code .../favorites/series} si se lee {@code "series"} como id. Spring MVC
 * elige siempre la ruta más específica (un segmento literal gana a una
 * variable), así que {@code DELETE .../favorites/series} llega a
 * {@link #clearFavorites} y no al borrado de una película con id "series";
 * {@code .../favorites/series/{id}} tiene dos segmentos y nunca encaja con la
 * de películas. Lo comprueban tests explícitos.
 * </p>
 */
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/users/me/favorites/series")
public class SeriesFavoriteController {

    private final SeriesFavoriteService seriesFavoriteService;

    /**
     * Crea el controlador de favoritos de series.
     *
     * @param seriesFavoriteService servicio con la lógica de las series de "Mi lista"
     */
    public SeriesFavoriteController(SeriesFavoriteService seriesFavoriteService) {
        this.seriesFavoriteService = seriesFavoriteService;
    }

    /**
     * Obtiene las series de la lista del usuario, ordenadas por título.
     *
     * @param principal usuario autenticado
     * @return series de su lista que tienen episodios
     */
    @GetMapping
    @Operation(summary = "Obtiene las series de mi lista", description = "Devuelve las series favoritas del "
            + "usuario autenticado, ordenadas por título. Las series que se han quedado sin episodios no "
            + "aparecen (vuelven a aparecer si se les añade alguno).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista obtenida correctamente"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public List<SeriesResponse> getFavorites(@AuthenticationPrincipal AuthenticatedUser principal) {

        return seriesFavoriteService.getFavorites(principal.id());
    }

    /**
     * Añade una serie a la lista.
     *
     * @param seriesId  identificador de la serie
     * @param principal usuario autenticado
     */
    @PostMapping("/{seriesId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Añade una serie a mi lista", description = "Añade una serie con episodios a los "
            + "favoritos del usuario autenticado.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Serie añadida correctamente"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "404", description = "La serie no existe o no tiene episodios"),
            @ApiResponse(responseCode = "409", description = "La serie ya está en mi lista "
                    + "(código SERIES_ALREADY_IN_FAVORITES)")
    })
    public void addFavorite(
            @PathVariable Long seriesId,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        seriesFavoriteService.addFavorite(principal.id(), seriesId);
    }

    /**
     * Quita una serie de la lista.
     *
     * @param seriesId  identificador de la serie
     * @param principal usuario autenticado
     */
    @DeleteMapping("/{seriesId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Quita una serie de mi lista", description = "Elimina una serie de los favoritos del "
            + "usuario autenticado. Una serie que estaba en la lista se puede quitar aunque se haya quedado sin "
            + "episodios.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Serie quitada correctamente"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "404", description = "La serie no estaba en mi lista: "
                    + "SERIES_NOT_IN_FAVORITES si es una serie con episodios; RESOURCE_NOT_FOUND (\"Serie no "
                    + "encontrada\") si no existe o no tiene episodios, sin distinguir entre ambos casos")
    })
    public void removeFavorite(
            @PathVariable Long seriesId,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        seriesFavoriteService.removeFavorite(principal.id(), seriesId);
    }

    /**
     * Quita todas las series de la lista. Las películas no se tocan. Si ya
     * estaba vacía, la operación también termina correctamente.
     *
     * @param principal usuario autenticado
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Vacía las series de mi lista", description = "Elimina de una vez todas las series "
            + "favoritas del usuario autenticado; sus películas favoritas no cambian.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Series quitadas correctamente"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public void clearFavorites(@AuthenticationPrincipal AuthenticatedUser principal) {

        seriesFavoriteService.clearFavorites(principal.id());
    }
}
