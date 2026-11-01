package com.emilio.streambox.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.FavoriteGenresRequest;
import com.emilio.streambox.dto.GenreResponse;
import com.emilio.streambox.security.AuthenticatedUser;
import com.emilio.streambox.service.FavoriteGenreService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

/**
 * Controlador REST de los géneros favoritos del usuario autenticado («Tus
 * gustos», {@code /api/users/me/genres}).
 *
 * <p>
 * Todas las operaciones usan el id del token ({@code @AuthenticationPrincipal});
 * no hay ninguna ruta con el id de otro usuario, así que nadie puede ver ni
 * cambiar los gustos ajenos. {@code SecurityConfig} no necesita una regla
 * propia: estas rutas caen en {@code anyRequest().authenticated()}, como el
 * resto de {@code /api/users/me/**} (y no en las de {@code /api/genres/**},
 * que reservan la escritura al ADMIN: aquí cada usuario escribe sus propios
 * gustos, también un ADMIN).
 * </p>
 *
 * <p>
 * <b>Por qué {@code PUT} con la lista entera.</b> La interfaz edita los gustos
 * como un conjunto (marca y desmarca y pulsa «Guardar»), y la regla de 3 a 5
 * géneros es sobre el conjunto: con operaciones de uno en uno ({@code POST}/
 * {@code DELETE} por género) habría estados intermedios inválidos. Sustituir la
 * lista es además idempotente. Responde 200 con la lista guardada (y no 204)
 * para que el cliente pinte exactamente lo que quedó, en el orden del servidor.
 * </p>
 */
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/users/me/genres")
public class FavoriteGenreController {

    private final FavoriteGenreService favoriteGenreService;

    /**
     * Crea el controlador de géneros favoritos.
     *
     * @param favoriteGenreService servicio con la lógica de los géneros favoritos
     */
    public FavoriteGenreController(FavoriteGenreService favoriteGenreService) {
        this.favoriteGenreService = favoriteGenreService;
    }

    /**
     * Obtiene los géneros favoritos del usuario.
     *
     * @param principal usuario autenticado
     * @return sus géneros favoritos que siguen teniendo títulos, por nombre
     */
    @GetMapping
    @Operation(summary = "Obtiene mis géneros favoritos", description = "Devuelve los géneros favoritos del "
            + "usuario autenticado («Tus gustos»), ordenados por nombre. Solo incluye los que siguen teniendo "
            + "algún título visible (una película o una serie con episodios): un favorito que se ha quedado sin "
            + "títulos no aparece (vuelve a aparecer si recupera alguno, y desaparece del todo al guardar otra "
            + "lista). Vacía si no ha elegido ninguno.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista obtenida correctamente (puede estar vacía)"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public List<GenreResponse> getFavoriteGenres(@AuthenticationPrincipal AuthenticatedUser principal) {

        return favoriteGenreService.getFavoriteGenres(principal.id());
    }

    /**
     * Obtiene los géneros que se pueden elegir como favoritos.
     *
     * @return géneros con algún título visible, por nombre
     */
    @GetMapping("/options")
    @Operation(summary = "Obtiene los géneros que puedo elegir como favoritos", description = "Devuelve los "
            + "géneros que tienen algún título visible (una película o una serie con episodios), ordenados por "
            + "nombre: son los únicos que admite PUT /api/users/me/genres. Un género sin títulos no se ofrece "
            + "porque elegirlo no daría ninguna recomendación.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista obtenida correctamente (puede estar vacía "
                    + "si el catálogo no tiene títulos)"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public List<GenreResponse> getFavoriteGenreOptions() {

        return favoriteGenreService.getGenreOptions();
    }

    /**
     * Sustituye la lista de géneros favoritos del usuario.
     *
     * @param principal usuario autenticado
     * @param request   lista completa de géneros elegidos
     * @return los géneros guardados, por nombre
     */
    @PutMapping
    @Operation(summary = "Guarda mis géneros favoritos", description = "Sustituye la lista entera de géneros "
            + "favoritos del usuario autenticado. Admite una lista vacía (no tener ninguno: «Ahora no» en la "
            + "bienvenida o quitar los gustos en el perfil) o entre 3 y 5 ids distintos de géneros con algún "
            + "título (los de GET /api/users/me/genres/options). Cada género favorito pesa en las "
            + "recomendaciones como 2 «me gusta». Cualquier llamada válida, también con la lista vacía, cierra "
            + "para siempre la pregunta de bienvenida (genresOnboardingPending pasa a false en GET "
            + "/api/users/me). Es idempotente: repetir la misma lista deja el mismo resultado.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista guardada; el cuerpo es la lista resultante, "
                    + "igual que GET /api/users/me/genres"),
            @ApiResponse(responseCode = "400", description = "Lista no válida (código VALIDATION_ERROR, motivo en "
                    + "validationErrors.genreIds): falta genreIds o es null (\"" + FavoriteGenresRequest.NULL_MESSAGE
                    + "\"), no está vacía ni tiene entre 3 y 5 elementos (\"Elige entre 3 y 5 géneros, o "
                    + "ninguno.\"), hay ids repetidos (\"Hay géneros repetidos.\") o alguno es null, no existe o "
                    + "no tiene títulos (\"Alguno de los géneros no existe o no tiene títulos.\"). Un id que no "
                    + "es un número entero da 400 MALFORMED_REQUEST. La lista anterior no cambia"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "404", description = "La cuenta ya no existe (se borró durante la "
                    + "petición)")
    })
    public List<GenreResponse> replaceFavoriteGenres(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody FavoriteGenresRequest request) {

        return favoriteGenreService.replaceFavoriteGenres(principal.id(), request);
    }
}
