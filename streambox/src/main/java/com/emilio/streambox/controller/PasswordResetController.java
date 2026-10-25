package com.emilio.streambox.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.AcceptedResponse;
import com.emilio.streambox.dto.PasswordResetConfirmRequest;
import com.emilio.streambox.dto.PasswordResetRequest;
import com.emilio.streambox.exception.InvalidResetTokenException;
import com.emilio.streambox.security.ratelimit.RateLimitingFilter;
import com.emilio.streambox.service.PasswordResetService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Controlador REST de «He olvidado mi contraseña»: pedir el enlace y fijar la
 * contraseña nueva con su token.
 *
 * <p>
 * Las dos rutas son <b>públicas</b> (quien ha olvidado la contraseña no puede
 * iniciar sesión) y tienen su propio límite por IP: las constantes de ruta son
 * las de {@link RateLimitingFilter}, que también usa {@code SecurityConfig}
 * para hacerlas públicas, para que lo público y lo limitado no se separen. No
 * exigen la cabecera {@code X-Requested-With} y la cookie de sesión se ignora:
 * no usan ninguna sesión que proteger. Sin {@code @SecurityRequirement}, por
 * lo mismo. La lógica está en {@link PasswordResetService}.
 * </p>
 *
 * <p>
 * <b>Los métodos no se llaman {@code request} y {@code confirm}</b>, a
 * propósito: springdoc toma el nombre del método como {@code operationId} del
 * OpenAPI y, si dos coinciden, añade {@code _1} a uno de ellos.
 * {@code EmailVerificationController} ya tiene un {@code confirm}: con el mismo
 * nombre aquí, su {@code operationId} pasaba a ser {@code confirm_1} y
 * cualquier cliente generado a partir de {@code docs/api/openapi.yaml} se
 * rompía sin haber cambiado ese endpoint.
 * </p>
 */
@RestController
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    /**
     * Crea el controlador.
     *
     * @param passwordResetService servicio de «He olvidado mi contraseña»
     */
    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    /**
     * Pide el correo con el enlace para elegir una contraseña nueva.
     *
     * @param request email de la cuenta
     * @return la respuesta fija
     */
    @PostMapping(RateLimitingFilter.PASSWORD_RESET_PATH)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Pide el enlace para restablecer la contraseña", description = "Si el correo es de una "
            + "cuenta (confirmada o pendiente de confirmar, de usuario o de administrador), le envía un enlace "
            + "<STREAMBOX_PUBLIC_URL>/restablecer-contrasena#token=... para elegir una contraseña nueva; los "
            + "enlaces de restablecimiento anteriores dejan de servir. El enlace caduca en 1 hora y sirve una sola "
            + "vez. No requiere autenticación ni la cabecera X-Requested-With. Responde SIEMPRE 202 con el mismo "
            + "cuerpo, exista o no la cuenta, para no revelar qué correos están registrados. Límite silencioso "
            + "por cuenta: como mucho 3 correos por hora y uno por minuto (aparte de los de confirmación del "
            + "correo); pasado el límite se responde igual, pero no se envía nada.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Petición aceptada; el cuerpo es siempre el mismo "
                    + "(message = \"" + AcceptedResponse.PASSWORD_RESET_MESSAGE + "\")"),
            @ApiResponse(responseCode = "400", description = "Código VALIDATION_ERROR con el detalle en "
                    + "validationErrors.email: correo vacío, con formato incorrecto o de más de 100 caracteres "
                    + "(depende solo del texto enviado, no de si existe la cuenta)"),
            @ApiResponse(responseCode = "429", description = "Código RATE_LIMIT_EXCEEDED: demasiadas peticiones "
                    + "desde esta IP (5 por hora por defecto), exista o no la cuenta; la cabecera Retry-After "
                    + "indica los segundos que hay que esperar")
    })
    public AcceptedResponse requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {

        return passwordResetService.request(request.email());
    }

    /**
     * Fija la contraseña nueva con el token del enlace.
     *
     * @param request     token del enlace y contraseña nueva
     * @param httpRequest petición HTTP: su IP ({@code getRemoteAddr()}, nunca
     *                    {@code X-Forwarded-For} a mano) queda como la única «IP
     *                    conocida» de la cuenta para el bloqueo del login
     * @throws InvalidResetTokenException si el token no sirve (400)
     */
    @PostMapping(RateLimitingFilter.PASSWORD_RESET_CONFIRM_PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Restablece la contraseña", description = "Fija una contraseña nueva con el token del "
            + "enlace del correo (lo que va tras #token= en <STREAMBOX_PUBLIC_URL>/restablecer-contrasena). No "
            + "requiere autenticación ni la cabecera X-Requested-With. Al cambiarla, el correo de la cuenta queda "
            + "confirmado, dejan de servir todos los enlaces pendientes de la cuenta (de restablecimiento y de "
            + "confirmación), se cierran TODAS sus sesiones (los refresh tokens quedan revocados; los JWT de "
            + "acceso ya emitidos valen hasta caducar, como mucho 15 minutos), se borran los intentos fallidos "
            + "de login de la cuenta y se envía un aviso al correo. No inicia sesión: después hay que entrar con "
            + "la contraseña nueva. Si la contraseña nueva no cumple la política, el token NO se gasta.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Contraseña cambiada; sin cuerpo y sin cookies"),
            @ApiResponse(responseCode = "400", description = "Código INVALID_RESET_TOKEN (mensaje «"
                    + InvalidResetTokenException.MESSAGE + "»): el token no existe, ha caducado, ya se usó, se "
                    + "invalidó al pedir otro enlace, falta o no tiene el formato de un token; mismo cuerpo en "
                    + "todos los casos y la contraseña no cambia. Código VALIDATION_ERROR con el detalle en "
                    + "validationErrors.newPassword: la contraseña nueva falta o no cumple la política (12 a 64 "
                    + "caracteres, como mucho 72 bytes, no común, sin el nombre de usuario ni la parte del email "
                    + "anterior a la @ de la cuenta del enlace); el token sigue valiendo. Un token que no es texto "
                    + "(p. ej. un número) da MALFORMED_REQUEST"),
            @ApiResponse(responseCode = "429", description = "Código RATE_LIMIT_EXCEEDED: demasiadas peticiones "
                    + "desde esta IP (20 cada 15 minutos por defecto); la cabecera Retry-After indica los segundos "
                    + "que hay que esperar")
    })
    public void confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request,
            HttpServletRequest httpRequest) {

        // getRemoteAddr() y no X-Forwarded-For: en prod la cabecera solo la
        // traduce el servidor si viene de un proxy de confianza (como el login).
        passwordResetService.confirm(request.token(), request.newPassword(), httpRequest.getRemoteAddr());
    }
}
