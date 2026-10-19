package com.emilio.streambox.email;

/**
 * Envía un correo. Es la única puerta de la aplicación al correo electrónico.
 *
 * <p>
 * <b>Por qué una interfaz propia</b> (y no usar {@code JavaMailSender}
 * directamente en los servicios). Cada entorno necesita algo distinto y los
 * servicios no deben saberlo (lo decide {@link EmailSenderConfig}):
 * </p>
 * <ul>
 *   <li>{@link SmtpEmailSender}: un servidor SMTP de verdad (producción, o
 *       Mailpit con docker compose), si {@code spring.mail.host} está
 *       definido;</li>
 *   <li>{@link LoggingEmailSender}: en desarrollo sin SMTP, el correo se
 *       escribe en el log (el autor ve ahí el enlace de verificación);</li>
 *   <li>{@link MemoryEmailSender}: en los tests y el E2E, una bandeja en
 *       memoria que se puede leer.</li>
 * </ul>
 *
 * <p>
 * Los servicios no llaman a este método: usan {@link EmailDispatcher}, que lo
 * llama <b>después del commit</b> y fuera del hilo de la petición.
 * </p>
 */
public interface EmailSender {

    /**
     * Envía el correo.
     *
     * @param message correo que se envía
     * @throws RuntimeException si no se ha podido enviar (lo registra
     *         {@link EmailDispatcher} sin el contenido)
     */
    void send(EmailMessage message);
}
