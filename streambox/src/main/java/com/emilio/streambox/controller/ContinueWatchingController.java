package com.emilio.streambox.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.ContinueWatchingItemResponse;
import com.emilio.streambox.security.AuthenticatedUser;
import com.emilio.streambox.service.WatchProgressService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Controlador REST de la fila «Seguir viendo» del usuario autenticado
 * ({@code /api/users/me/continue-watching}).
 *
 * <p>
 * Va aparte de {@link WatchProgressController} porque es otro recurso para el
 * cliente (una fila del inicio, con sus propias acciones), aunque los dos usen
 * {@link WatchProgressService} y las mismas tablas. Mismo modelo de seguridad:
 * id del token, {@code anyRequest().authenticated()} y {@code X-Requested-With}
 * en el {@code DELETE} con la cookie de sesión.
 * </p>
 *
 * <p>
 * Lleva {@code @Validated} para que {@code @Min}/{@code @Max} del límite den un
 * 400 {@code VALIDATION_ERROR} con el motivo en {@code validationErrors.limit},
 * como en las recomendaciones y las películas similares.
 * </p>
 */
@SecurityRequirement(name = "bearerAuth")
@RestController
@Validated
@RequestMapping("/api/users/me/continue-watching")
public class ContinueWatchingController {

    /** Respuesta 400 de las rutas con un id en la ruta. */
    private static final String INVALID_ID_DESCRIPTION = "El id no es un número entero válido";

    private final WatchProgressService watchProgressService;

    /**
     * Crea el controlador de «Seguir viendo».
     *
     * @param watchProgressService servicio con la lógica del progreso
     */
    public ContinueWatchingController(WatchProgressService watchProgressService) {
        this.watchProgressService = watchProgressService;
    }

    /**
     * Obtiene la fila «Seguir viendo».
     *
     * @param principal usuario autenticado
     * @param limit     máximo de elementos (1 a 24, por defecto 10)
     * @return películas a medio ver y una tarjeta por serie, del más reciente al más antiguo
     */
    @GetMapping
    @Operation(summary = "Obtiene mi fila «Seguir viendo»", description = "Devuelve, mezcladas y del guardado "
            + "más reciente al más antiguo (a igual fecha, primero las películas y después el id mayor), las "
            + "películas que el usuario autenticado tiene a medio ver y una sola tarjeta por serie. La tarjeta de "
            + "una serie es el último episodio que tocó el usuario si lo tiene a medias (upNext false) o, si lo "
            + "terminó, el siguiente episodio con vídeo en orden de temporada y número, aunque sea de otra "
            + "temporada (upNext true). Ese siguiente trae su propio progreso si lo tiene a medias; si no, "
            + "positionSeconds y durationSeconds van a null. Si no queda ningún episodio siguiente con vídeo, la "
            + "serie no sale. La fecha (updatedAt) y el orden de una serie son los del último episodio tocado. "
            + "Nunca incluye lo terminado (95 % o más de la duración) como tarjeta ni los títulos que ya no "
            + "tienen vídeo: una película terminada sale de la fila. Cada elemento lleva type (MOVIE o EPISODE); "
            + "los campos de la serie van a null en las películas.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista obtenida correctamente (puede estar vacía)"),
            @ApiResponse(responseCode = "400", description = "El límite no es un número entero entre 1 y 24"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public List<ContinueWatchingItemResponse> getContinueWatching(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "" + WatchProgressService.DEFAULT_CONTINUE_WATCHING_LIMIT)
            @Parameter(description = "Número máximo de elementos: de 1 a 24 (por defecto, 10)")
            @Min(value = WatchProgressService.MIN_CONTINUE_WATCHING_LIMIT, message = "El límite mínimo es 1")
            @Max(value = WatchProgressService.MAX_CONTINUE_WATCHING_LIMIT, message = "El límite máximo es 24")
            int limit) {

        return watchProgressService.getContinueWatching(principal.id(), limit);
    }

    /**
     * Quita una película de «Seguir viendo».
     *
     * @param movieId   identificador de la película
     * @param principal usuario autenticado
     */
    @DeleteMapping("/movies/{movieId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Quita una película de «Seguir viendo»", description = "Borra el progreso del usuario "
            + "autenticado en la película: deja de salir en «Seguir viendo» y, al volver a abrirla, empieza desde "
            + "el principio. Como el historial y las cifras del perfil salen de ese mismo progreso, también deja "
            + "de salir en GET /api/users/me/watch-history y de contar en GET /api/users/me/watch-stats. "
            + "Es idempotente: si no había progreso, o la película no existe, también responde 204.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "La película ya no está en «Seguir viendo»"),
            @ApiResponse(responseCode = "400", description = INVALID_ID_DESCRIPTION),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public void removeMovieFromContinueWatching(
            @PathVariable Long movieId,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        watchProgressService.removeMovie(principal.id(), movieId);
    }

    /**
     * Quita una serie de «Seguir viendo».
     *
     * @param seriesId  identificador de la serie
     * @param principal usuario autenticado
     */
    @DeleteMapping("/series/{seriesId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Quita una serie de «Seguir viendo»", description = "Borra el progreso del usuario "
            + "autenticado en todos los episodios de la serie, para que la tarjeta no la ocupe otro episodio con "
            + "progreso ni el siguiente a uno terminado (también quita una tarjeta con upNext true). Como el "
            + "historial y las cifras del perfil salen de ese mismo progreso, la serie también deja de salir en "
            + "GET /api/users/me/watch-history y sus episodios de contar en GET /api/users/me/watch-stats. "
            + "Es idempotente: si no había progreso, o la serie no existe o no tiene episodios, también "
            + "responde 204.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "La serie ya no está en «Seguir viendo»"),
            @ApiResponse(responseCode = "400", description = INVALID_ID_DESCRIPTION),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public void removeSeriesFromContinueWatching(
            @PathVariable Long seriesId,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        watchProgressService.removeSeries(principal.id(), seriesId);
    }
}
