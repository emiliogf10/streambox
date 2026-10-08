package com.emilio.streambox.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.LoginRequest;
import com.emilio.streambox.security.AuthCookieService;
import com.emilio.streambox.service.AuthenticationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

    private final AuthCookieService authCookieService;

    /**
     * Crea el controlador de autenticación.
     *
     * @param authenticationService servicio que valida las credenciales y genera el token
     * @param authCookieService     construye la cookie de sesión
     */
    public AuthController(AuthenticationService authenticationService, AuthCookieService authCookieService) {
        this.authenticationService = authenticationService;
        this.authCookieService = authCookieService;
    }

    /**
     * Inicia sesión: entrega el JWT en una cookie HttpOnly y no en el cuerpo.
     *
     * @param request     correo electrónico y contraseña
     * @param httpRequest petición HTTP, de la que se toma la IP de origen
     *                    ({@code getRemoteAddr()}) para el bloqueo por IP conocida
     * @param response    respuesta en la que se añade {@code Set-Cookie}
     */
    @PostMapping("/login")
    @Operation(summary = "Inicia sesión", description = "Autentica un usuario mediante su correo electrónico "
            + "y contraseña. El token JWT no va en el cuerpo: se entrega en la cookie streambox_token "
            + "(HttpOnly; SameSite=Strict; Path=/api; Max-Age = vida del token; Secure según la "
            + "configuración). Las peticiones POST/PUT/PATCH/DELETE autenticadas por cookie deben llevar "
            + "la cabecera X-Requested-With: StreamBox (403 CSRF_REJECTED si falta). También se acepta "
            + "Authorization: Bearer con el mismo token (clientes de API), sin esa cabecera extra.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Autenticación correcta; sin cuerpo, "
                    + "con la cookie de sesión en Set-Cookie"),
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
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
            HttpServletResponse response) {

        // getRemoteAddr() y no X-Forwarded-For: en prod la cabecera solo la
        // aplica Spring (forward-headers-strategy=native) y no se puede falsificar.
        String token = authenticationService.login(
                request.getEmail(), request.getPassword(), httpRequest.getRemoteAddr());
        response.addHeader(HttpHeaders.SET_COOKIE, authCookieService.sessionCookie(token));
    }

    /**
     * Cierra la sesión borrando la cookie.
     */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Cierra sesión", description = "Borra la cookie de sesión "
            + "(Set-Cookie con Max-Age=0). Es público e idempotente: responde 204 haya o no "
            + "sesión. El JWT es stateless y no se revoca en el servidor: un token copiado "
            + "seguiría siendo válido hasta que caduque.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Cookie de sesión borrada")
    })
    public void logout(HttpServletResponse response) {

        response.addHeader(HttpHeaders.SET_COOKIE, authCookieService.clearingCookie());
    }
}
