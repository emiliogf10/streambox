package com.emilio.streambox.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.LoginRequest;
import com.emilio.streambox.dto.LoginResponse;
import com.emilio.streambox.service.AuthenticationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Controlador REST de la autenticación ({@code /api/auth}).
 *
 * <p>
 * Es público: no requiere token, porque su función es precisamente
 * entregarlo. La lógica (comprobar credenciales, bloquear cuentas con
 * demasiados fallos y generar el token) está en {@link AuthenticationService}.
 * </p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationService authenticationService;

    /**
     * Crea el controlador de autenticación.
     *
     * @param authenticationService servicio que valida las credenciales y genera el token
     */
    public AuthController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    /**
     * Inicia sesión y devuelve un token JWT.
     *
     * @param request correo electrónico y contraseña
     * @return respuesta con el token JWT
     */
    @PostMapping("/login")
    @Operation(summary = "Inicia sesión", description = "Autentica un usuario mediante su correo electrónico "
            + "y contraseña y devuelve un token JWT.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Autenticación realizada correctamente"),
            @ApiResponse(responseCode = "400", description = "Los datos proporcionados no son válidos "
                    + "(email con formato incorrecto, contraseña vacía o de más de 1024 caracteres). No "
                    + "gasta intento de la cuenta. En el login no se aplica la política de longitud del "
                    + "registro: las cuentas antiguas siguen pudiendo entrar"),
            @ApiResponse(responseCode = "401", description = "Credenciales incorrectas (código "
                    + "INVALID_CREDENTIALS, mensaje «Email o contraseña incorrectos»). El cuerpo incluye "
                    + "remainingAttempts: intentos que le quedan a la cuenta ya descontado este fallo "
                    + "(siempre 1 o más); si el siguiente intento también falla y era el último, la "
                    + "cuenta se bloquea y se responde 429 ACCOUNT_LOCKED. Desde una misma IP de origen es idéntico "
                    + "exista o no el email"),
            @ApiResponse(responseCode = "429", description = "Demasiados intentos; la cabecera Retry-After "
                    + "indica los segundos que hay que esperar. Código ACCOUNT_LOCKED: la cuenta está "
                    + "bloqueada por logins fallidos (lo devuelve el fallo que agota los intentos y "
                    + "cualquier intento durante el bloqueo, aunque la contraseña sea correcta; el mensaje "
                    + "dice cuántos minutos dura; no se aplica a una IP desde la que el titular ya inició "
                    + "sesión con éxito, que tiene su propio contador con los mismos límites). "
                    + "Código RATE_LIMIT_EXCEEDED: demasiadas peticiones de "
                    + "login desde esta IP")
    })
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {

        return authenticationService.login(request.getEmail(), request.getPassword(), httpRequest.getRemoteAddr());
    }
}
