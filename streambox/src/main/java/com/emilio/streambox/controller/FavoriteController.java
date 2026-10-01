package com.emilio.streambox.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.MovieResponse;
import com.emilio.streambox.security.AuthenticatedUser;
import com.emilio.streambox.service.FavoriteService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

/**
 * Controlador REST de la lista de favoritos del usuario autenticado
 * ({@code /api/users/me/favorites}).
 *
 * <p>
 * Todas las operaciones se aplican a la lista del propio usuario, que se
 * obtiene del token JWT: no existe ninguna ruta para ver o modificar la lista
 * de otra persona.
 * </p>
 */
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/users/me/favorites")
public class FavoriteController {

    private final FavoriteService favoriteService;

    /**
     * Crea el controlador de favoritos.
     *
     * @param favoriteService servicio con la lógica de la lista de favoritos
     */
    public FavoriteController(FavoriteService favoriteService) {
        this.favoriteService = favoriteService;
    }

    /**
     * Obtiene las películas de la lista del usuario, ordenadas por título.
     *
     * @param principal usuario autenticado
     * @return películas de su lista
     */
    @GetMapping
    @Operation(summary = "Obtiene mi lista", description = "Devuelve las películas favoritas del usuario autenticado.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista obtenida correctamente"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public List<MovieResponse> getFavorites(@AuthenticationPrincipal AuthenticatedUser principal) {

        return favoriteService.getFavorites(principal.id());
    }

    /**
     * Añade una película a la lista por su identificador.
     *
     * @param movieId   identificador de la película
     * @param principal usuario autenticado
     */
    @PostMapping("/{movieId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Añade una película a mi lista", description = "Añade una película a los favoritos del usuario autenticado.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Película añadida correctamente"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "404", description = "Película no encontrada"),
            @ApiResponse(responseCode = "409", description = "La película ya está incluida en mi lista")
    })
    public void addFavorite(
            @PathVariable Long movieId,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        favoriteService.addFavorite(principal.id(), movieId);
    }

    /**
     * Añade una película a la lista por su título exacto (sin distinguir
     * mayúsculas). El título es un parámetro de consulta para conservar
     * espacios y caracteres especiales sin meterlos en la ruta.
     *
     * @param title     título exacto de la película
     * @param principal usuario autenticado
     */
    @PostMapping("/by-title")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Añade una película a mi lista por título", description = "Añade a favoritos una película cuyo título coincide exactamente con el parámetro title, sin distinguir mayúsculas.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Película añadida correctamente"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "404", description = "No existe una película con el título indicado"),
            @ApiResponse(responseCode = "409", description = "La película ya está en mi lista, o el título es ambiguo")
    })
    public void addFavoriteByTitle(
            @RequestParam String title,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        favoriteService.addFavoriteByTitle(principal.id(), title);
    }

    /**
     * Quita una película de la lista por su identificador.
     *
     * @param movieId   identificador de la película
     * @param principal usuario autenticado
     */
    @DeleteMapping("/{movieId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Elimina una película de mi lista", description = "Elimina una película de los favoritos del usuario autenticado.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Película eliminada correctamente"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "404", description = "La película no existe o no está en mi lista")
    })
    public void removeFavorite(
            @PathVariable Long movieId,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        favoriteService.removeFavorite(principal.id(), movieId);
    }

    /**
     * Quita una película de la lista por su título exacto (sin distinguir
     * mayúsculas).
     *
     * @param title     título exacto de la película
     * @param principal usuario autenticado
     */
    @DeleteMapping("/by-title")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Elimina una película de mi lista por título", description = "Elimina de favoritos una película cuyo título coincide exactamente con el parámetro title, sin distinguir mayúsculas.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Película eliminada correctamente"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "404", description = "La película no existe o no está en mi lista"),
            @ApiResponse(responseCode = "409", description = "El título es ambiguo")
    })
    public void removeFavoriteByTitle(
            @RequestParam String title,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        favoriteService.removeFavoriteByTitle(principal.id(), title);
    }

    /**
     * Vacía la lista del usuario. Si ya estaba vacía, la operación también
     * termina correctamente.
     *
     * @param principal usuario autenticado
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Vacía mi lista", description = "Elimina de una vez todas las películas favoritas del usuario autenticado.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Lista vaciada correctamente"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public void clearFavorites(@AuthenticationPrincipal AuthenticatedUser principal) {

        favoriteService.clearFavorites(principal.id());
    }
}
