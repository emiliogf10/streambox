package com.emilio.streambox.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.WatchHistoryItemResponse;
import com.emilio.streambox.dto.WatchStatsResponse;
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
 * Controlador REST del <b>historial de visionado</b> y de las <b>cifras</b> del
 * perfil del usuario autenticado ({@code /api/users/me/watch-history} y
 * {@code /api/users/me/watch-stats}).
 *
 * <p>
 * Va aparte de {@link WatchProgressController} y de
 * {@link ContinueWatchingController} porque es otro recurso para el cliente
 * (una sección del perfil, de solo lectura), aunque los tres usen
 * {@link WatchProgressService} y las mismas tablas. Mismo modelo de seguridad:
 * id del token, nunca de la URL (sin IDOR posible), y las rutas caen en
 * {@code anyRequest().authenticated()} de {@code SecurityConfig} (USER y
 * ADMIN; cada uno ve solo lo suyo).
 * </p>
 *
 * <p>
 * Los nombres de los métodos ({@code getWatchHistory}, {@code getWatchStats})
 * son únicos en toda la API a propósito: springdoc los usa como
 * {@code operationId} y renombraría uno repetido ({@code ..._1}).
 * </p>
 *
 * <p>
 * Lleva {@code @Validated} para que {@code @Min}/{@code @Max} del límite den un
 * 400 {@code VALIDATION_ERROR} con el motivo en {@code validationErrors.limit},
 * como en «Seguir viendo».
 * </p>
 */
@SecurityRequirement(name = "bearerAuth")
@RestController
@Validated
@RequestMapping("/api/users/me")
public class WatchHistoryController {

    private final WatchProgressService watchProgressService;

    /**
     * Crea el controlador del historial y las cifras del perfil.
     *
     * @param watchProgressService servicio con la lógica del progreso
     */
    public WatchHistoryController(WatchProgressService watchProgressService) {
        this.watchProgressService = watchProgressService;
    }

    /**
     * Obtiene el historial de visionado del perfil.
     *
     * @param principal usuario autenticado
     * @param limit     máximo de entradas (1 a 24, por defecto 12)
     * @return una entrada por película y una por serie, de la más reciente a la más antigua
     */
    @GetMapping("/watch-history")
    @Operation(summary = "Obtiene mi historial de visionado", description = "Devuelve, del guardado más "
            + "reciente al más antiguo (a igual fecha, primero las películas y después el id mayor), lo que el "
            + "usuario autenticado ha empezado a ver: una entrada por película y una sola por serie, la de su "
            + "último episodio tocado, con el progreso de ese episodio (nunca propone el siguiente). A diferencia "
            + "de «Seguir viendo», incluye lo terminado (finished true: la posición actual llega al 95 % de la "
            + "duración), lo visto alguna vez aunque se esté volviendo a ver (watched true; es lo que se muestra "
            + "como «Vista») y los títulos que ya no tienen vídeo. Cada entrada lleva type (MOVIE o EPISODE): en EPISODE, id y title son los "
            + "del episodio e imageUrl, seriesId y seriesTitle los de la serie; en MOVIE, seasonNumber, "
            + "episodeNumber, seriesId y seriesTitle van a null. No hay una tabla de historial aparte: sale del "
            + "progreso guardado, así que quitar un título de «Seguir viendo» (DELETE "
            + "/api/users/me/continue-watching/...) o borrarlo del catálogo también lo quita de aquí.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Historial obtenido correctamente (puede estar vacío)"),
            @ApiResponse(responseCode = "400", description = "El límite no es un número entero entre 1 y 24"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public List<WatchHistoryItemResponse> getWatchHistory(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "" + WatchProgressService.DEFAULT_WATCH_HISTORY_LIMIT)
            @Parameter(description = "Número máximo de entradas: de 1 a 24 (por defecto, 12)")
            @Min(value = WatchProgressService.MIN_WATCH_HISTORY_LIMIT, message = "El límite mínimo es 1")
            @Max(value = WatchProgressService.MAX_WATCH_HISTORY_LIMIT, message = "El límite máximo es 24")
            int limit) {

        return watchProgressService.getWatchHistory(principal.id(), limit);
    }

    /**
     * Obtiene las cifras de visionado del perfil.
     *
     * @param principal usuario autenticado
     * @return películas y episodios vistos alguna vez y tiempo visto estimado
     */
    @GetMapping("/watch-stats")
    @Operation(summary = "Obtiene mis cifras de visionado", description = "Devuelve cuántas películas "
            + "(finishedMovies) y cuántos episodios (finishedEpisodes) ha terminado alguna vez el usuario "
            + "autenticado (llegó al 95 % o más de la duración al menos una vez, watched; volver a verlos no los "
            + "resta; cada episodio cuenta por separado) y el tiempo visto en segundos (watchedSeconds). Ese "
            + "tiempo es una estimación: solo se guarda el último punto de cada título, así que suma la duración "
            + "de lo visto alguna vez y la posición de lo que nunca se ha terminado, sin contar lo vuelto a ver. Cuenta todo el progreso, tenga hoy vídeo el título o no; lo quitado de «Seguir viendo» o "
            + "borrado del catálogo deja de contar. Sin progreso, las tres cifras son 0.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cifras obtenidas correctamente"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public WatchStatsResponse getWatchStats(@AuthenticationPrincipal AuthenticatedUser principal) {

        return watchProgressService.getWatchStats(principal.id());
    }
}
