package com.emilio.streambox.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.MovieResponse;
import com.emilio.streambox.dto.SeriesResponse;
import com.emilio.streambox.security.AuthenticatedUser;
import com.emilio.streambox.service.LikeService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

/**
 * Controlador REST de los «me gusta» del usuario autenticado
 * ({@code /api/users/me/likes}).
 *
 * <p>
 * Todas las operaciones usan el id del token ({@code @AuthenticationPrincipal});
 * no hay ninguna ruta con el id de otro usuario, así que nadie puede ver ni
 * tocar los «me gusta» ajenos. {@code SecurityConfig} no tiene una regla propia
 * para estas rutas: caen en {@code anyRequest().authenticated()}, como el resto
 * de {@code /api/users/me/**}.
 * </p>
 *
 * <p>
 * <b>Por qué {@code PUT} y {@code DELETE} y no {@code POST}.</b> El «me gusta»
 * es un estado (te gusta o no), y las dos operaciones son idempotentes: repetir
 * cualquiera deja el mismo resultado y responde lo mismo (204). Es justo la
 * semántica de {@code PUT} («que exista este recurso») y {@code DELETE}; con
 * {@code POST}, como en «Mi lista», repetir sería un 409. Las dos son
 * escrituras: con la cookie de sesión exigen {@code X-Requested-With}.
 * </p>
 */
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/users/me/likes")
public class LikeController {

    /** Respuesta 400 de las rutas con un id en la ruta. */
    private static final String INVALID_ID_DESCRIPTION = "El id no es un número entero válido";

    private final LikeService likeService;

    /**
     * Crea el controlador de «me gusta».
     *
     * @param likeService servicio con la lógica de los «me gusta»
     */
    public LikeController(LikeService likeService) {
        this.likeService = likeService;
    }

    /**
     * Obtiene las películas que le gustan al usuario.
     *
     * @param principal usuario autenticado
     * @return sus películas con «me gusta», la más reciente primero
     */
    @GetMapping("/movies")
    @Operation(summary = "Obtiene las películas que me gustan", description = "Devuelve las películas a las "
            + "que el usuario autenticado ha dado «me gusta», de la que le gustó más recientemente a la más "
            + "antigua (a igual fecha, id descendente).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista obtenida correctamente (puede estar vacía)"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public List<MovieResponse> getLikedMovies(@AuthenticationPrincipal AuthenticatedUser principal) {

        return likeService.getLikedMovies(principal.id());
    }

    /**
     * Obtiene las series que le gustan al usuario.
     *
     * @param principal usuario autenticado
     * @return sus series visibles con «me gusta», la más reciente primero
     */
    @GetMapping("/series")
    @Operation(summary = "Obtiene las series que me gustan", description = "Devuelve las series a las que el "
            + "usuario autenticado ha dado «me gusta», de la que le gustó más recientemente a la más antigua "
            + "(a igual fecha, id descendente). Las series que se han quedado sin episodios no aparecen "
            + "(vuelven a aparecer si se les añade alguno).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista obtenida correctamente (puede estar vacía)"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public List<SeriesResponse> getLikedSeries(@AuthenticationPrincipal AuthenticatedUser principal) {

        return likeService.getLikedSeries(principal.id());
    }

    /**
     * Da «me gusta» a una película.
     *
     * @param movieId   identificador de la película
     * @param principal usuario autenticado
     */
    @PutMapping("/movies/{movieId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Da «me gusta» a una película", description = "Marca la película como «me gusta» "
            + "del usuario autenticado. Es idempotente: si ya le gustaba, responde igual y conserva la fecha "
            + "original.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "La película te gusta (ahora o ya antes)"),
            @ApiResponse(responseCode = "400", description = INVALID_ID_DESCRIPTION),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "404", description = "Película no encontrada")
    })
    public void likeMovie(
            @PathVariable Long movieId,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        likeService.likeMovie(principal.id(), movieId);
    }

    /**
     * Quita el «me gusta» de una película.
     *
     * @param movieId   identificador de la película
     * @param principal usuario autenticado
     */
    @DeleteMapping("/movies/{movieId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Quita el «me gusta» de una película", description = "Quita la película de los «me "
            + "gusta» del usuario autenticado. Es idempotente: si no le gustaba, o la película no existe, "
            + "también responde 204.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "La película ya no te gusta"),
            @ApiResponse(responseCode = "400", description = INVALID_ID_DESCRIPTION),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public void unlikeMovie(
            @PathVariable Long movieId,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        likeService.unlikeMovie(principal.id(), movieId);
    }

    /**
     * Da «me gusta» a una serie con episodios.
     *
     * @param seriesId  identificador de la serie
     * @param principal usuario autenticado
     */
    @PutMapping("/series/{seriesId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Da «me gusta» a una serie", description = "Marca la serie como «me gusta» del "
            + "usuario autenticado. Es idempotente: si ya le gustaba, responde igual y conserva la fecha "
            + "original. Solo se admiten series con episodios.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "La serie te gusta (ahora o ya antes)"),
            @ApiResponse(responseCode = "400", description = INVALID_ID_DESCRIPTION),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "404", description = "Serie no encontrada: no existe o no tiene "
                    + "episodios, sin distinguir entre ambos casos")
    })
    public void likeSeries(
            @PathVariable Long seriesId,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        likeService.likeSeries(principal.id(), seriesId);
    }

    /**
     * Quita el «me gusta» de una serie.
     *
     * @param seriesId  identificador de la serie
     * @param principal usuario autenticado
     */
    @DeleteMapping("/series/{seriesId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Quita el «me gusta» de una serie", description = "Quita la serie de los «me gusta» "
            + "del usuario autenticado. Es idempotente: si no le gustaba, o la serie no existe o no tiene "
            + "episodios, también responde 204.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "La serie ya no te gusta"),
            @ApiResponse(responseCode = "400", description = INVALID_ID_DESCRIPTION),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public void unlikeSeries(
            @PathVariable Long seriesId,
            @AuthenticationPrincipal AuthenticatedUser principal) {

        likeService.unlikeSeries(principal.id(), seriesId);
    }
}
