package com.emilio.streambox.controller;

import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.EpisodeProgressResponse;
import com.emilio.streambox.dto.WatchProgressRequest;
import com.emilio.streambox.dto.WatchProgressResponse;
import com.emilio.streambox.security.AuthenticatedUser;
import com.emilio.streambox.service.WatchProgressService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

/**
 * Controlador REST del progreso de visionado del usuario autenticado
 * ({@code /api/users/me/progress}): lo que guarda el reproductor integrado y
 * lo que lee para ofrecer «Reanudar desde X» y pintar las barras de progreso.
 *
 * <p>
 * Todas las operaciones usan el id del token ({@code @AuthenticationPrincipal});
 * no hay ninguna ruta con el id de otro usuario. {@code SecurityConfig} no
 * tiene una regla propia para estas rutas: caen en
 * {@code anyRequest().authenticated()}, como el resto de
 * {@code /api/users/me/**}, y el {@code PUT} con la cookie de sesión exige
 * {@code X-Requested-With} (defensa CSRF).
 * </p>
 *
 * <p>
 * <b>Por qué {@code PUT}.</b> El progreso es un estado («voy por aquí») que
 * cada envío sustituye entero: repetir la misma petición deja el mismo
 * resultado, que es la semántica de {@code PUT}. Responde 204 como los «me
 * gusta».
 * </p>
 *
 * <p>
 * <b>Por qué el {@code GET} de un título sin progreso es 204 y no 404.</b>
 * Que el usuario no lo haya empezado no es un error: el título existe y la
 * respuesta correcta es «no hay nada que reanudar». Con un 404 el cliente no
 * podría distinguirlo de un título inexistente sin mirar el texto, y el
 * navegador lo anotaría como fallo en cada ficha sin empezar. El 404 queda
 * solo para el título que no existe, con el mismo cuerpo que su detalle.
 * </p>
 */
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/users/me/progress")
public class WatchProgressController {

    /** Respuesta 400 de las rutas con un id en la ruta. */
    private static final String INVALID_ID_DESCRIPTION = "El id no es un número entero válido";

    /** Respuesta 400 de los guardados. */
    private static final String INVALID_PROGRESS_DESCRIPTION = "Id no válido, o posición o duración fuera de "
            + "rango (VALIDATION_ERROR, con el detalle en validationErrors), o un valor que no es un número "
            + "entero (MALFORMED_REQUEST)";

    private final WatchProgressService watchProgressService;

    /**
     * Crea el controlador del progreso de visionado.
     *
     * @param watchProgressService servicio con la lógica del progreso
     */
    public WatchProgressController(WatchProgressService watchProgressService) {
        this.watchProgressService = watchProgressService;
    }

    /**
     * Guarda por dónde va el usuario en una película.
     *
     * @param movieId   identificador de la película
     * @param request   posición y duración en segundos
     * @param principal usuario autenticado
     */
    @PutMapping("/movies/{movieId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Guarda mi progreso en una película", description = "Guarda el segundo por el que va "
            + "el usuario autenticado en la película y la duración del vídeo, sustituyendo lo anterior. El "
            + "reproductor lo envía periódicamente y al pausar o salir; repetirlo es idempotente. Si la posición "
            + "alcanza el 95 % de la duración, la película cuenta como vista (watched, que ya no se pierde "
            + "aunque se vuelva a ver) y deja de salir en «Seguir viendo».")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Progreso guardado"),
            @ApiResponse(responseCode = "400", description = INVALID_PROGRESS_DESCRIPTION),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "404", description = "Película no encontrada"),
            @ApiResponse(responseCode = "409", description = "La película no tiene vídeo (NO_VIDEO_AVAILABLE)")
    })
    public void saveMovieProgress(
            @PathVariable Long movieId,
            @Valid @RequestBody WatchProgressRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        watchProgressService.saveMovieProgress(principal.id(), movieId, request);
    }

    /**
     * Guarda por dónde va el usuario en un episodio.
     *
     * @param episodeId identificador del episodio
     * @param request   posición y duración en segundos
     * @param principal usuario autenticado
     */
    @PutMapping("/episodes/{episodeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Guarda mi progreso en un episodio", description = "Guarda el segundo por el que va "
            + "el usuario autenticado en el episodio y la duración del vídeo, sustituyendo lo anterior. "
            + "Idempotente. Si la posición alcanza el 95 % de la duración, el episodio cuenta como visto "
            + "(watched, que ya no se pierde aunque se vuelva a ver) y deja de salir en «Seguir viendo».")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Progreso guardado"),
            @ApiResponse(responseCode = "400", description = INVALID_PROGRESS_DESCRIPTION),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "404", description = "Episodio no encontrado"),
            @ApiResponse(responseCode = "409", description = "El episodio no tiene vídeo (NO_VIDEO_AVAILABLE)")
    })
    public void saveEpisodeProgress(
            @PathVariable Long episodeId,
            @Valid @RequestBody WatchProgressRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        watchProgressService.saveEpisodeProgress(principal.id(), episodeId, request);
    }

    /**
     * Obtiene por dónde va el usuario en una película.
     *
     * @param movieId   identificador de la película
     * @param principal usuario autenticado
     * @param response  respuesta HTTP (para el 204 cuando no hay progreso)
     * @return su progreso, o {@code null} (204 sin cuerpo) si no la ha empezado
     */
    @GetMapping("/movies/{movieId}")
    @Operation(summary = "Obtiene mi progreso en una película", description = "Devuelve el segundo por el que "
            + "va el usuario autenticado en la película, la duración del vídeo, si la posición actual llega al "
            + "final (finished, 95 % o más), si la ha terminado alguna vez (watched) y cuándo se guardó. Si nunca la empezó (o la quitó de «Seguir viendo»), 204 sin cuerpo.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Progreso obtenido"),
            @ApiResponse(responseCode = "204", description = "La película existe, pero no hay progreso guardado",
                    content = @Content),
            @ApiResponse(responseCode = "400", description = INVALID_ID_DESCRIPTION),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "404", description = "Película no encontrada")
    })
    public WatchProgressResponse getMovieProgress(
            @PathVariable Long movieId,
            @AuthenticationPrincipal AuthenticatedUser principal,
            HttpServletResponse response) {

        return bodyOrNoContent(watchProgressService.getMovieProgress(principal.id(), movieId), response);
    }

    /**
     * Obtiene por dónde va el usuario en un episodio.
     *
     * @param episodeId identificador del episodio
     * @param principal usuario autenticado
     * @param response  respuesta HTTP (para el 204 cuando no hay progreso)
     * @return su progreso, o {@code null} (204 sin cuerpo) si no lo ha empezado
     */
    @GetMapping("/episodes/{episodeId}")
    @Operation(summary = "Obtiene mi progreso en un episodio", description = "Devuelve el segundo por el que va "
            + "el usuario autenticado en el episodio, la duración del vídeo, si la posición actual llega al "
            + "final (finished, 95 % o más), si lo ha terminado alguna vez (watched) y cuándo se guardó. Si nunca lo empezó, 204 sin cuerpo.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Progreso obtenido"),
            @ApiResponse(responseCode = "204", description = "El episodio existe, pero no hay progreso guardado",
                    content = @Content),
            @ApiResponse(responseCode = "400", description = INVALID_ID_DESCRIPTION),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "404", description = "Episodio no encontrado")
    })
    public WatchProgressResponse getEpisodeProgress(
            @PathVariable Long episodeId,
            @AuthenticationPrincipal AuthenticatedUser principal,
            HttpServletResponse response) {

        return bodyOrNoContent(watchProgressService.getEpisodeProgress(principal.id(), episodeId), response);
    }

    /**
     * Obtiene el progreso del usuario en los episodios de una serie.
     *
     * @param seriesId  identificador de la serie
     * @param principal usuario autenticado
     * @return un elemento por episodio empezado, en orden de temporada y número
     */
    @GetMapping("/series/{seriesId}")
    @Operation(summary = "Obtiene mi progreso en los episodios de una serie", description = "Devuelve, en una "
            + "sola lista, el progreso del usuario autenticado en cada episodio de la serie que ha empezado "
            + "(terminados incluidos), en orden de temporada y número. Los episodios sin empezar no aparecen; "
            + "si no ha empezado ninguno, la lista va vacía.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista obtenida correctamente (puede estar vacía)"),
            @ApiResponse(responseCode = "400", description = INVALID_ID_DESCRIPTION),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "404", description = "Serie no encontrada: no existe o no tiene "
                    + "episodios, sin distinguir entre ambos casos")
    })
    public List<EpisodeProgressResponse> getSeriesProgress(
            @PathVariable Long seriesId,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        return watchProgressService.getSeriesProgress(principal.id(), seriesId);
    }

    /**
     * Devuelve el cuerpo o, si no hay, marca la respuesta como 204.
     *
     * <p>
     * Es el único sitio donde el estado depende del resultado: el resto de la
     * API fija el suyo con {@code @ResponseStatus}. Se cambia el estado en la
     * respuesta en lugar de devolver un {@code ResponseEntity} para seguir la
     * convención de los controladores (devolver el DTO); con {@code null},
     * Spring no escribe cuerpo.
     * </p>
     */
    private static WatchProgressResponse bodyOrNoContent(Optional<WatchProgressResponse> body,
            HttpServletResponse response) {

        if (body.isEmpty()) {
            response.setStatus(HttpStatus.NO_CONTENT.value());
            return null;
        }
        return body.get();
    }
}
