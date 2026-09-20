package com.emilio.streambox.controller;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.CreateUserRequest;
import com.emilio.streambox.dto.MovieResponse;
import com.emilio.streambox.dto.UserResponse;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.mapper.MovieMapper;
import com.emilio.streambox.mapper.UserMapper;
import com.emilio.streambox.service.UserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

/**
 * Controlador REST encargado de gestionar las operaciones relacionadas
 * con los usuarios de Streambox.
 *
 * <p>
 * Expone los endpoints disponibles bajo la ruta
 * {@code /api/users} y delega la lógica de negocio en
 * {@link UserService}.
 * </p>
 *
 * <p>
 * El registro de nuevos usuarios no requiere autenticación. El resto
 * de operaciones están protegidas mediante autenticación JWT y,
 * dependiendo del endpoint, pueden requerir permisos de administrador.
 * </p>
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    /**
     * Crea una instancia del controlador de usuarios.
     *
     * @param userService servicio encargado de gestionar la lógica
     *                    relacionada con los usuarios
     */
    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Obtiene los datos del usuario actualmente autenticado.
     *
     * <p>
     * Spring Security proporciona la información del usuario autenticado
     * mediante el objeto {@link Authentication}. El {@code principal}
     * contiene la entidad {@link User} establecida por
     * {@link com.emilio.streambox.security.JwtAuthenticationFilter}.
     * </p>
     *
     * <p>
     * Este endpoint requiere que el usuario esté autenticado mediante
     * un token JWT válido.
     * </p>
     *
     * @param authentication información de autenticación de la petición actual
     * @return datos del usuario autenticado
     */
    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Obtiene el usuario autenticado", description = "Devuelve la información del usuario asociado "
            + "al token JWT utilizado en la petición.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Usuario obtenido correctamente"),
            @ApiResponse(responseCode = "403", description = "El usuario no está autenticado")
    })
    public UserResponse getCurrentUser(Authentication authentication) {

        User user = (User) authentication.getPrincipal();

        return UserMapper.toResponse(user);
    }

    /**
     * Obtiene las películas incluidas en la lista del usuario autenticado.
     *
     * @param authentication información de autenticación de la petición actual
     * @return lista de películas favoritas del usuario autenticado
     */
    @GetMapping("/me/favorites")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Obtiene mi lista", description = "Devuelve las películas favoritas del usuario autenticado.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Lista obtenida correctamente"),
            @ApiResponse(responseCode = "403", description = "El usuario no está autenticado")
    })
    public List<MovieResponse> getFavoriteMovies(Authentication authentication) {

        User user = (User) authentication.getPrincipal();

        return MovieMapper.toResponseList(userService.getFavoriteMovies(user.getId()));
    }

    /**
     * Añade una película a la lista del usuario autenticado.
     *
     * @param movieId identificador de la película que se desea añadir
     * @param authentication información de autenticación de la petición actual
     */
    @PostMapping("/me/favorites/{movieId}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Añade una película a mi lista", description = "Añade una película a los favoritos del usuario autenticado.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Película añadida correctamente"),
            @ApiResponse(responseCode = "403", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "404", description = "Película no encontrada")
    })
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void addMovieToFavorites(
            @PathVariable Long movieId,
            Authentication authentication) {

        User user = (User) authentication.getPrincipal();
        userService.addMovieToFavorites(user.getId(), movieId);
    }

    /**
     * Añade a la lista una película mediante su título exacto.
     *
     * <p>
     * La coincidencia del título no distingue entre mayúsculas y minúsculas.
     * El título se recibe como parámetro de consulta para preservar los espacios
     * y caracteres especiales sin incorporarlos a la ruta.
     * </p>
     *
     * @param title          título exacto de la película que se desea añadir
     * @param authentication información de autenticación de la petición actual
     */
    @PostMapping("/me/favorites/by-title")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Añade una película a mi lista por título", description = "Añade a favoritos una película cuyo título coincide exactamente con el parámetro title, sin distinguir mayúsculas.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Película añadida correctamente"),
            @ApiResponse(responseCode = "403", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "404", description = "No existe una película con el título indicado"),
            @ApiResponse(responseCode = "409", description = "La película ya está incluida en mi lista")
    })
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void addMovieToFavoritesByTitle(
            @RequestParam String title,
            Authentication authentication) {

        User user = (User) authentication.getPrincipal();
        userService.addMovieToFavoritesByTitle(user.getId(), title);
    }

    /**
     * Elimina una película de la lista del usuario autenticado.
     *
     * @param movieId identificador de la película que se desea eliminar
     * @param authentication información de autenticación de la petición actual
     */
    @DeleteMapping("/me/favorites/{movieId}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Elimina una película de mi lista", description = "Elimina una película de los favoritos del usuario autenticado.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Película eliminada correctamente"),
            @ApiResponse(responseCode = "403", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "404", description = "Película no encontrada")
    })
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void removeMovieFromFavorites(
            @PathVariable Long movieId,
            Authentication authentication) {

        User user = (User) authentication.getPrincipal();
        userService.removeMovieFromFavorites(user.getId(), movieId);
    }

    /**
     * Elimina de la lista una película mediante su título exacto.
     *
     * <p>
     * La coincidencia del título no distingue entre mayúsculas y minúsculas.
     * </p>
     *
     * @param title          título exacto de la película que se desea eliminar
     * @param authentication información de autenticación de la petición actual
     */
    @DeleteMapping("/me/favorites/by-title")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Elimina una película de mi lista por título", description = "Elimina de favoritos una película cuyo título coincide exactamente con el parámetro title, sin distinguir mayúsculas.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Película eliminada correctamente"),
            @ApiResponse(responseCode = "403", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "404", description = "La película no existe o no está incluida en mi lista")
    })
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void removeMovieFromFavoritesByTitle(
            @RequestParam String title,
            Authentication authentication) {

        User user = (User) authentication.getPrincipal();
        userService.removeMovieFromFavoritesByTitle(user.getId(), title);
    }

    /**
     * Elimina todas las películas de la lista del usuario autenticado.
     *
     * <p>
     * La operación se completa correctamente aunque la lista ya esté vacía.
     * </p>
     *
     * @param authentication información de autenticación de la petición actual
     */
    @DeleteMapping("/me/favorites")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Vacía mi lista", description = "Elimina de una vez todas las películas favoritas del usuario autenticado.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Lista vaciada correctamente"),
            @ApiResponse(responseCode = "403", description = "El usuario no está autenticado")
    })
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void clearFavoriteMovies(Authentication authentication) {

        User user = (User) authentication.getPrincipal();
        userService.clearFavoriteMovies(user.getId());
    }

    /**
     * Obtiene todos los usuarios registrados en Streambox.
     *
     * <p>
     * Las entidades {@link User} obtenidas desde el servicio se
     * convierten en {@link UserResponse} antes de devolverlas al cliente,
     * evitando exponer directamente las entidades JPA.
     * </p>
     *
     * <p>
     * Este endpoint está restringido a usuarios con rol
     * {@code ADMIN}.
     * </p>
     *
     * @return lista de usuarios representados mediante {@link UserResponse}
     */
    @GetMapping
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Obtiene todos los usuarios", description = "Devuelve la lista de usuarios registrados. "
            + "Este endpoint requiere permisos de administrador.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Usuarios obtenidos correctamente"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene permisos de administrador")
    })
    public List<UserResponse> getUsers() {

        return UserMapper.toResponseList(
                userService.getAllUsers());
    }

    /**
     * Crea un nuevo usuario en Streambox.
     *
     * <p>
     * El cuerpo de la petición se valida mediante {@link Valid}.
     * Posteriormente, el {@link UserMapper} convierte el DTO recibido
     * en una entidad {@link User}, que es procesada y almacenada por
     * {@link UserService}.
     * </p>
     *
     * <p>
     * Este endpoint no requiere autenticación, ya que permite a nuevos
     * usuarios registrarse en la plataforma.
     * </p>
     *
     * @param request datos necesarios para crear el usuario
     * @return información del usuario creado
     */
    @PostMapping
    @Operation(summary = "Registra un nuevo usuario", description = "Crea una nueva cuenta de usuario en Streambox. "
            + "No requiere autenticación.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Usuario creado correctamente"),
            @ApiResponse(responseCode = "400", description = "Los datos proporcionados no son válidos")
    })
    public UserResponse createUser(
            @Valid @RequestBody CreateUserRequest request) {

        User user = UserMapper.toEntity(request);

        User savedUser = userService.saveUser(user);

        return UserMapper.toResponse(savedUser);
    }
}