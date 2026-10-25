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

import com.emilio.streambox.dto.AcceptedResponse;
import com.emilio.streambox.dto.CreateUserRequest;
import com.emilio.streambox.dto.UpdateProfileRequest;
import com.emilio.streambox.dto.UserResponse;
import com.emilio.streambox.security.AuthenticatedUser;
import com.emilio.streambox.service.RegistrationService;
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
    private final RegistrationService registrationService;

    /**
     * Crea el controlador de usuarios.
     *
     * @param userService         servicio con la lógica de las cuentas
     * @param registrationService registro de cuentas nuevas
     */
    public UserController(UserService userService, RegistrationService registrationService) {
        this.userService = userService;
        this.registrationService = registrationService;
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
            + "al token JWT utilizado en la petición, incluido su icono de perfil (avatar: una clave o null).")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Usuario obtenido correctamente"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado")
    })
    public UserResponse getCurrentUser(@AuthenticationPrincipal AuthenticatedUser principal) {

        return userService.getUserById(principal.id());
    }

    /**
     * Cambia el nombre de usuario y/o el icono de perfil (avatar) del usuario
     * autenticado («Editar perfil»).
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
     * @param request   cambios pedidos: nombre, avatar o los dos
     * @return los datos del usuario tras el cambio (los mismos que {@code GET /api/users/me})
     */
    @PatchMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Cambia mi nombre de usuario o mi icono de perfil", description = "Cambia el nombre "
            + "de usuario y/o el avatar de la cuenta autenticada. Cada campo es opcional (ausente = no cambia), "
            + "pero debe venir al menos uno. El nombre se normaliza (sin espacios ni caracteres invisibles en los "
            + "extremos y con los espacios interiores repetidos reducidos a uno) y debe tener entre 3 y 50 "
            + "caracteres. El avatar es una de las claves predefinidas, o null para quitarlo y volver a la "
            + "inicial. Si nada cambia se responde 200 sin escribir. La respuesta es el usuario completo, igual "
            + "que GET /api/users/me. El correo electrónico, el rol y la contraseña no se pueden cambiar aquí: "
            + "enviarlos da 400.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Perfil actualizado (o sin cambios si era el mismo)"),
            @ApiResponse(responseCode = "400", description = "Datos no válidos (código VALIDATION_ERROR, un "
                    + "mensaje por campo en validationErrors): cuerpo sin username ni avatar "
                    + "(validationErrors.username = \"" + UpdateProfileRequest.NO_CHANGES_MESSAGE + "\"), nombre de longitud no válida tras normalizarlo, avatar que no "
                    + "es una de las claves de la lista ni null (validationErrors.avatar = \"Elige uno de los "
                    + "avatares disponibles\"), o se ha enviado email, role o password"),
            @ApiResponse(responseCode = "401", description = "El usuario no está autenticado"),
            @ApiResponse(responseCode = "409", description = "El nombre de usuario ya está en uso, o lo "
                    + "ha reservado un registro con el correo de otra cuenta (código USER_ALREADY_EXISTS)")
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
     * Registra un usuario nuevo (público). Siempre se crea con rol {@code USER}
     * y <b>pendiente</b> de confirmar su correo.
     *
     * <p>
     * Responde 202 con el mismo cuerpo exista o no ya una cuenta con ese email,
     * para no revelar quién está registrado; lo que cambia es el correo que
     * recibe el buzón (ver {@link RegistrationService}). El nombre de usuario
     * ocupado (o reservado) sigue dando 409: no es un dato secreto.
     * </p>
     *
     * @param request datos de registro
     * @return la respuesta fija
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Registra un nuevo usuario", description = "Pide crear una cuenta de usuario (rol USER). "
            + "No requiere autenticación. Responde SIEMPRE 202 con el mismo cuerpo, exista o no ya una cuenta "
            + "con ese correo, para no revelar qué correos están registrados. Lo que cambia es el correo que "
            + "llega al buzón: con un correo nuevo, la cuenta se crea pendiente y llega un enlace para "
            + "confirmarla (<STREAMBOX_PUBLIC_URL>/verificar-email#token=..., válido 24 horas; hasta "
            + "confirmarla, el login responde el mismo 401 INVALID_CREDENTIALS que con una contraseña "
            + "incorrecta, para no revelar si la cuenta se creó); si el correo ya tiene una cuenta "
            + "confirmada, llega un aviso de que ya existe y no cambia nada; si tiene una pendiente, se "
            + "reenvía su enlace (como mucho 3 correos por hora y uno por minuto por cuenta; pasado el "
            + "límite no se envía nada, sin error). Una cuenta pendiente cuyos enlaces caducaron sin usarse "
            + "(de confirmación y de restablecer la contraseña) se sustituye por la nueva. La contraseña se "
            + "valida igual en todos los casos.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Petición aceptada; el cuerpo es siempre el mismo "
                    + "(message = \"" + AcceptedResponse.REGISTRATION_MESSAGE + "\")"),
            @ApiResponse(responseCode = "400", description = "Los datos proporcionados no son válidos "
                    + "(código VALIDATION_ERROR, un mensaje por campo en validationErrors). La contraseña "
                    + "debe tener entre 12 y 64 caracteres, ocupar como máximo 72 bytes en UTF-8, no ser "
                    + "común ni trivial y no contener el nombre de usuario ni la parte local del email. "
                    + "Si incumple varias reglas solo se informa de una, por este orden: longitud, bytes, "
                    + "común, datos personales. El email admite como máximo 100 caracteres (también una "
                    + "vez pasado a minúsculas). Un campo con un valor de otro tipo (p. ej. la contraseña "
                    + "como número) no se convierte: 400 MALFORMED_REQUEST"),
            @ApiResponse(responseCode = "409", description = "El nombre de usuario ya está en uso (código "
                    + "USER_ALREADY_EXISTS). Depende solo del nombre, nunca del correo: también lo conserva "
                    + "durante 24 horas desde su alta una cuenta pendiente creada por el registro (aunque "
                    + "después pida otro enlace), y lo reserva durante 24 horas un registro anterior con ese "
                    + "nombre cuyo correo ya tenía cuenta (los dos duran lo mismo para que el 409 no revele si "
                    + "el correo estaba registrado). El correo nunca da 409"),
            @ApiResponse(responseCode = "429", description = "Demasiados registros desde esta IP; "
                    + "ver cabecera Retry-After")
    })
    public AcceptedResponse createUser(@Valid @RequestBody CreateUserRequest request) {

        return registrationService.register(request);
    }
}
