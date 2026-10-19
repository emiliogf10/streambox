package com.emilio.streambox.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.emilio.streambox.dto.AcceptedResponse;
import com.emilio.streambox.dto.EmailVerificationRequest;
import com.emilio.streambox.dto.ResendVerificationRequest;
import com.emilio.streambox.exception.InvalidVerificationTokenException;
import com.emilio.streambox.security.ratelimit.RateLimitingFilter;
import com.emilio.streambox.service.EmailVerificationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;

/**
 * Controlador REST de la verificación del correo: confirmar con el token del
 * enlace y pedir otro enlace.
 *
 * <p>
 * Las dos rutas son <b>públicas</b> (quien confirma aún no puede iniciar
 * sesión) y tienen su propio límite por IP: las constantes de ruta son las de
 * {@link RateLimitingFilter}, que también usa {@code SecurityConfig} para
 * hacerlas públicas, para que lo público y lo limitado no se separen. No
 * exigen la cabecera {@code X-Requested-With}: no usan ninguna sesión que
 * proteger. La lógica está en {@link EmailVerificationService}.
 * </p>
 */
@RestController
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;

    /**
     * Crea el controlador.
     *
     * @param emailVerificationService servicio de verificación del correo
     */
    public EmailVerificationController(EmailVerificationService emailVerificationService) {
        this.emailVerificationService = emailVerificationService;
    }

    /**
     * Confirma el correo de una cuenta con el token del enlace.
     *
     * @param request token del enlace
     * @throws InvalidVerificationTokenException si el token no sirve (400)
     */
    @PostMapping(RateLimitingFilter.EMAIL_VERIFICATION_PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Confirma el correo de una cuenta", description = "Confirma el correo con el token del "
            + "enlace que llega al registrarse (lo que va tras #token= en <STREAMBOX_PUBLIC_URL>/verificar-email). "
            + "No requiere autenticación ni la cabecera X-Requested-With. Cada token sirve una sola vez y "
            + "caduca a las 24 horas; pedir otro correo invalida los anteriores. Al confirmar se cierran todas "
            + "las sesiones que tuviera la cuenta. No inicia sesión: después hay que entrar con la contraseña.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Correo confirmado; la cuenta ya puede iniciar sesión"),
            @ApiResponse(responseCode = "400", description = "Código INVALID_VERIFICATION_TOKEN (mensaje «"
                    + InvalidVerificationTokenException.MESSAGE + "»): el token no existe, ha caducado, ya se "
                    + "usó, se invalidó al pedir otro correo, falta o no tiene el formato de un token. Mismo "
                    + "cuerpo en todos los casos. Un token que no es texto (p. ej. un número) da "
                    + "MALFORMED_REQUEST"),
            @ApiResponse(responseCode = "429", description = "Código RATE_LIMIT_EXCEEDED: demasiadas "
                    + "confirmaciones desde esta IP (20 cada 15 minutos por defecto); la cabecera Retry-After "
                    + "indica los segundos que hay que esperar")
    })
    public void confirm(@RequestBody EmailVerificationRequest request) {

        emailVerificationService.confirm(request.token());
    }

    /**
     * Pide otro correo de confirmación para una cuenta pendiente.
     *
     * @param request email de la cuenta
     * @return la respuesta fija
     */
    @PostMapping(RateLimitingFilter.EMAIL_VERIFICATION_RESEND_PATH)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Reenvía el correo de confirmación", description = "Si el correo es de una cuenta "
            + "pendiente de confirmar, le envía un enlace nuevo (y los anteriores dejan de servir). No requiere "
            + "autenticación ni la cabecera X-Requested-With. Responde SIEMPRE 202 con el mismo cuerpo, exista "
            + "o no la cuenta y esté o no confirmada, para no revelar qué correos están registrados. Límite "
            + "silencioso por cuenta: como mucho 3 correos por hora (el del registro cuenta) y uno por minuto; "
            + "pasado el límite se responde igual, pero no se envía nada.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Petición aceptada; el cuerpo es siempre el mismo "
                    + "(message = \"" + AcceptedResponse.RESEND_MESSAGE + "\")"),
            @ApiResponse(responseCode = "400", description = "Código VALIDATION_ERROR con el detalle en "
                    + "validationErrors.email: correo vacío, con formato incorrecto o de más de 100 caracteres "
                    + "(depende solo del texto enviado, no de si existe la cuenta)"),
            @ApiResponse(responseCode = "429", description = "Código RATE_LIMIT_EXCEEDED: demasiados reenvíos "
                    + "desde esta IP (5 por hora por defecto), exista o no la cuenta; la cabecera Retry-After "
                    + "indica los segundos que hay que esperar")
    })
    public AcceptedResponse resend(@Valid @RequestBody ResendVerificationRequest request) {

        return emailVerificationService.resend(request.email());
    }
}
