package com.emilio.streambox.email;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

/**
 * Propiedades del correo de la aplicación ({@code streambox.email.*}).
 *
 * <p>
 * La conexión con el servidor SMTP no está aquí: es la estándar de Spring
 * Boot ({@code spring.mail.host}, {@code spring.mail.port},
 * {@code spring.mail.username}...; variables {@code SPRING_MAIL_HOST}...).
 * Variables de entorno de estas: {@code STREAMBOX_EMAIL_FROM},
 * {@code STREAMBOX_EMAIL_VERIFICATION_TTL},
 * {@code STREAMBOX_EMAIL_PASSWORD_RESET_TTL}, etc.
 * </p>
 *
 * <p>
 * Se validan en el constructor compacto (como {@code RefreshTokenProperties}):
 * un valor absurdo impide arrancar con un mensaje que nombra la propiedad.
 * </p>
 *
 * @param from            remitente de los correos (por defecto
 *                        {@code StreamBox <no-reply@streambox.local>}). En
 *                        producción debe ser una dirección del dominio que el
 *                        proveedor SMTP permite usar (si no, los correos acaban
 *                        en spam o se rechazan)
 * @param verificationTtl vida del enlace de verificación (24 h por defecto;
 *                        entre 5 minutos y 7 días)
 * @param passwordResetTtl vida del enlace de «He olvidado mi contraseña» (1 h
 *                        por defecto; entre 5 minutos y 24 horas). Más corta
 *                        que la de la verificación porque quien tenga el
 *                        enlace puede fijar la contraseña y entrar en la
 *                        cuenta: cuanto menos viva un enlace olvidado en un
 *                        buzón, menos sirve si alguien lo lee después. El
 *                        correo usa este valor, pero la interfaz
 *                        ({@code RESET_LINK_TTL_HOURS} en
 *                        {@code frontend/src/lib/passwordReset.ts}) y la
 *                        descripción del OpenAPI de
 *                        {@code PasswordResetController} dicen «1 hora» con
 *                        un texto fijo: si se cambia el valor por defecto,
 *                        hay que cambiarlos a la vez
 * @param async           si el envío va en un hilo aparte (por defecto sí). Los
 *                        tests lo desactivan para que el correo esté en la
 *                        bandeja en cuanto responde la petición
 * @param resend          límite silencioso de correos por cuenta (la verificación
 *                        y «He olvidado mi contraseña» cuentan cada una aparte,
 *                        con estos mismos valores)
 */
@ConfigurationProperties(prefix = "streambox.email")
public record EmailProperties(
        @DefaultValue("StreamBox <no-reply@streambox.local>") String from,
        @DefaultValue("24h") Duration verificationTtl,
        @DefaultValue("1h") Duration passwordResetTtl,
        @DefaultValue("true") boolean async,
        @DefaultValue Resend resend) {

    /** Vida mínima del enlace: menos no da tiempo a que llegue el correo. */
    static final Duration MIN_VERIFICATION_TTL = Duration.ofMinutes(5);

    /** Vida máxima del enlace: un enlace robado no debe servir indefinidamente. */
    static final Duration MAX_VERIFICATION_TTL = Duration.ofDays(7);

    /**
     * Vida máxima del enlace de restablecimiento: da acceso a la cuenta, así
     * que no se admite el máximo de la verificación. Un día cubre cualquier
     * retraso razonable del correo.
     */
    static final Duration MAX_PASSWORD_RESET_TTL = Duration.ofHours(24);

    /**
     * Comprueba los valores.
     *
     * @throws IllegalArgumentException si alguno falta o no es válido
     */
    public EmailProperties {
        if (from == null || from.isBlank() || from.indexOf('\r') >= 0 || from.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("streambox.email.from es obligatorio (por ejemplo "
                    + "StreamBox <no-reply@tu-dominio.com>)");
        }
        try {
            // Validación estricta de Jakarta Mail: lo que se acepte aquí es lo
            // que el servidor SMTP recibirá en la cabecera From.
            new InternetAddress(from, true);
        } catch (AddressException e) {
            throw new IllegalArgumentException("streambox.email.from no es una dirección de correo válida "
                    + "(por ejemplo StreamBox <no-reply@tu-dominio.com>)", e);
        }
        if (verificationTtl == null || verificationTtl.compareTo(MIN_VERIFICATION_TTL) < 0
                || verificationTtl.compareTo(MAX_VERIFICATION_TTL) > 0) {
            throw new IllegalArgumentException(
                    "streambox.email.verification-ttl debe estar entre 5m y 7d (por ejemplo 24h)");
        }
        if (passwordResetTtl == null || passwordResetTtl.compareTo(MIN_VERIFICATION_TTL) < 0
                || passwordResetTtl.compareTo(MAX_PASSWORD_RESET_TTL) > 0) {
            throw new IllegalArgumentException(
                    "streambox.email.password-reset-ttl debe estar entre 5m y 24h (por ejemplo 1h)");
        }
        if (resend == null) {
            throw new IllegalArgumentException("streambox.email.resend es obligatorio");
        }
    }

    /**
     * Límite <b>silencioso</b> de correos a una misma cuenta
     * ({@code streambox.email.resend.*}): pasado el límite se sigue
     * respondiendo el 202 de siempre, pero no se envía nada.
     *
     * <p>
     * <b>Por qué silencioso.</b> Un 429 por email diría que ese email tiene
     * una cuenta (pendiente o no), justo lo que el 202 constante oculta. El
     * límite por IP (que sí responde 429) está en {@code RateLimitingFilter} y
     * no revela nada porque cuenta igual exista o no la cuenta. Sin este
     * límite, alguien con muchas IP podría llenar el buzón de una persona.
     * </p>
     *
     * @param maxPerWindow correos como mucho por cuenta en la ventana (3 por
     *                     defecto; el del registro cuenta)
     * @param window       ventana del límite (1 h por defecto)
     * @param minInterval  tiempo mínimo entre dos correos a la misma cuenta
     *                     (60 s por defecto; 0 lo desactiva, como hace el E2E).
     *                     Cada reenvío invalida el enlace anterior: pedir varios
     *                     seguidos solo dejaría sin valor el que ya va de camino
     */
    public record Resend(
            @DefaultValue("3") int maxPerWindow,
            @DefaultValue("1h") Duration window,
            @DefaultValue("60s") Duration minInterval) {

        /**
         * Comprueba los valores.
         *
         * @throws IllegalArgumentException si alguno no es válido
         */
        public Resend {
            if (maxPerWindow < 1) {
                throw new IllegalArgumentException("streambox.email.resend.max-per-window debe ser 1 o más");
            }
            if (window == null || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException(
                        "streambox.email.resend.window debe ser mayor que 0 (por ejemplo 1h)");
            }
            if (minInterval == null || minInterval.isNegative() || minInterval.compareTo(window) > 0) {
                throw new IllegalArgumentException("streambox.email.resend.min-interval debe estar entre 0s "
                        + "y streambox.email.resend.window (por ejemplo 60s)");
            }
        }
    }
}
