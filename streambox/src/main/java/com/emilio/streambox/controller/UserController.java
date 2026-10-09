package com.emilio.streambox.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.CreateUserRequest;
import com.emilio.streambox.dto.UpdateProfileRequest;
import com.emilio.streambox.dto.UserResponse;
import com.emilio.streambox.security.AuthenticatedUser;
import com.emilio.streambox.service.UserService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

/**
 * Controlador REST de las cuentas de usuario ({@code /api/users}).
 *
 * <p>
 * El registro de nuevos usuarios es público; consultar o editar el propio
 * perfil requiere estar autenticado y listar todos los usuarios, ser
 * administrador.
 * La lista de favoritos está en {@link FavoriteController}.
 * </p>
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    /**
     * Crea el controlador de usuarios.
     *
     * @param userService servicio con la lógica de las cuentas
     */
    public UserController(UserService userService) {
        this.userService = userService;
    }

    /**
     * Obtiene los datos del usuario autenticado.
     *
     * <p>
     * El usuario se identifica por el token JWT: Spring Security entrega el
     * {@link AuthenticatedUser} que estableció el filtro de autenticación.
     * </p>
     *
     * @param principal usuario autenticado de la petición actual
     * @return datos del usuario
     */
    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Obtiene el usuario autenticado", description = "Devuelve la información del usuario asociado "
            + "al token JWT utilizado en la petición.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Usuario obtenido correctamente"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public UserResponse getCurrentUser(@AuthenticationPrincipal AuthenticatedUser principal) {

        return userService.getUserById(principal.id());
    }

    /**
     * Cambia el nombre de usuario del usuario autenticado («Editar perfil»).
     *
     * <p>
     * La cuenta sale del token ({@link AuthenticatedUser}), nunca de la URL:
     * así nadie puede editar el perfil de otro cambiando un id (IDOR). El
     * email no se puede cambiar (es la identidad del login y el
     * {@code subject} del JWT). El 403 {@code CSRF_REJECTED} de las peticiones
     * no seguras por cookie lo documenta {@code CookieAuthOperationCustomizer}.
     * </p>
     *
     * @param principal usuario autenticado de la petición actual
     * @param request   nuevo nombre de usuario
     * @return los datos del usuario tras el cambio (los mismos que {@code GET /api/users/me})
     */
    @PatchMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Cambia mi nombre de usuario", description = "Cambia el nombre de usuario de la cuenta "
            + "autenticada. El nombre se normaliza (sin espacios ni caracteres invisibles en los extremos y "
            + "con los espacios interiores repetidos reducidos a uno) y debe tener entre 3 y 50 caracteres. "
            + "Si coincide con el actual se responde 200 sin cambios. El correo electrónico, el rol y la "
            + "contraseña no se pueden cambiar aquí: enviarlos da 400.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Nombre cambiado (o sin cambios si era el mismo)"),
            @ApiResponse(responseCode = "400", description = "Datos no válidos (código VALIDATION_ERROR, un "
                    + "mensaje por campo en validationErrors): nombre ausente o de longitud no válida tras "
                    + "normalizarlo, o se ha enviado email, role o password"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "409", description = "El nombre de usuario ya está en uso "
                    + "(código USER_ALREADY_EXISTS)")
    })
    public UserResponse updateCurrentUser(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @Valid @RequestBody UpdateProfileRequest request) {

        return userService.updateProfile(principal.id(), request);
    }

    /**
     * Lista todos los usuarios registrados (solo administradores).
     *
     * @return usuarios registrados
     */
    @GetMapping
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Obtiene todos los usuarios", description = "Devuelve la lista de usuarios registrados. "
            + "Este endpoint requiere permisos de administrador.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Usuarios obtenidos correctamente"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "403", description = "El usuario no tiene permisos de administrador")
    })
    public List<UserResponse> getUsers() {

        return userService.getAllUsers();
    }

    /**
     * Registra un usuario nuevo (público). Siempre se crea con rol {@code USER}.
     *
     * @param request datos de registro
     * @return la cuenta creada
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Registra un nuevo usuario", description = "Crea una nueva cuenta de usuario en Streambox. "
            + "No requiere autenticación.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Usuario creado correctamente"),
            @ApiResponse(responseCode = "400", description = "Los datos proporcionados no son válidos "
                    + "(código VALIDATION_ERROR, un mensaje por campo en validationErrors). La contraseña "
                    + "debe tener entre 12 y 64 caracteres, ocupar como máximo 72 bytes en UTF-8, no ser "
                    + "común ni trivial y no contener el nombre de usuario ni la parte local del email. "
                    + "Si incumple varias reglas solo se informa de una, por este orden: longitud, bytes, "
                    + "común, datos personales"),
            @ApiResponse(responseCode = "409", description = "El nombre de usuario o el correo ya están en uso"),
            @ApiResponse(responseCode = "429", description = "Demasiados registros desde esta IP; "
                    + "ver cabecera Retry-After")
    })
    public UserResponse createUser(@Valid @RequestBody CreateUserRequest request) {

        return userService.registerUser(request);
    }
}
