package com.emilio.streambox.security.ratelimit;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Límites contra el abuso de los endpoints públicos
 * ({@code streambox.security.rate-limit.*}). Los valores por defecto están en
 * {@code application.properties}.
 *
 * @param login    límite de intentos de login por IP
 * @param register límite de registros de usuario por IP
 * @param refresh  límite de renovaciones de sesión ({@code POST /api/auth/refresh})
 *                 por IP
 * @param emailVerification límite de confirmaciones del correo
 *                 ({@code POST /api/auth/email-verification}) por IP. El token
 *                 tiene 256 bits, así que no hace falta contra la fuerza bruta:
 *                 limita el abuso (cada intento consulta la base de datos)
 * @param emailVerificationResend límite de reenvíos del correo de
 *                 confirmación ({@code POST /api/auth/email-verification/resend})
 *                 por IP. Cada reenvío puede mandar un correo: sin límite,
 *                 cualquiera usaría el servidor para inundar buzones ajenos o
 *                 gastar el cupo del proveedor de correo. El límite <b>por
 *                 email</b> va aparte, en el servicio, y es silencioso (ver
 *                 {@code RateLimitingFilter})
 * @param passwordReset límite de peticiones del enlace para restablecer la
 *                 contraseña ({@code POST /api/auth/password-reset}) por IP. Por
 *                 el mismo motivo que el reenvío: cada una puede mandar un
 *                 correo. El límite por email también va en el servicio y es
 *                 silencioso
 * @param passwordResetConfirm límite de confirmaciones del restablecimiento
 *                 ({@code POST /api/auth/password-reset/confirm}) por IP. Como
 *                 en la confirmación del correo, el token de 256 bits hace
 *                 imposible la fuerza bruta: limita el abuso (cada intento
 *                 consulta la base de datos y, con un token válido, calcula un
 *                 BCrypt)
 * @param lockout  bloqueo temporal de una cuenta tras varios logins fallidos
 * @param maxKeys  tope de claves vivas <b>por contador</b> (cada contador en
 *                 memoria, el de login, el de registro, el de refresh, los dos
 *                 de la verificación del correo, los dos del restablecimiento
 *                 de contraseña, el de bloqueo por
 *                 cuenta y el de IP conocida, lo aplica por separado). Al
 *                 llenarse se purgan las caducadas y, si sigue lleno, se
 *                 expulsa la clave con la actividad más antigua; ver
 *                 {@link SlidingWindowCounter}
 */
@Validated
@ConfigurationProperties(prefix = "streambox.security.rate-limit")
public record RateLimitProperties(
        @Valid @NotNull Rule login,
        @Valid @NotNull Rule register,
        @Valid @NotNull Rule refresh,
        @Valid @NotNull Rule emailVerification,
        @Valid @NotNull Rule emailVerificationResend,
        @Valid @NotNull Rule passwordReset,
        @Valid @NotNull Rule passwordResetConfirm,
        @Valid @NotNull Lockout lockout,
        @Positive int maxKeys) {

    /**
     * @param maxRequests peticiones permitidas por IP dentro de la ventana
     * @param window      duración de la ventana
     */
    public record Rule(@Positive int maxRequests, @NotNull Duration window) {
    }

    /**
     * @param maxFailures logins fallidos permitidos antes de bloquear la cuenta
     * @param window      ventana en la que se cuentan los fallos (y duración
     *                    máxima del bloqueo)
     * @param maxKnownIps máximo de «IPs conocidas» recordadas por cuenta (las
     *                    desde las que el titular ha iniciado sesión con
     *                    éxito); al superarlo se olvida la más antigua
     * @param knownIpTtl  tiempo que se recuerda una IP conocida desde su último
     *                    login correcto
     */
    public record Lockout(
            @Positive int maxFailures,
            @NotNull Duration window,
            @Positive int maxKnownIps,
            @NotNull Duration knownIpTtl) {
    }
}
