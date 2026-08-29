package com.emilio.streambox.controller;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.CreateUserRequest;
import com.emilio.streambox.dto.UserResponse;
import com.emilio.streambox.entity.User;
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