package com.emilio.streambox.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * «Envía» los correos escribiéndolos en el log (nivel INFO), con el cuerpo
 * entero.
 *
 * <p>
 * Es el emisor del perfil de desarrollo cuando no hay SMTP configurado: quien
 * arranca la aplicación en su equipo se registra en la web y copia el enlace
 * de verificación de la consola, sin montar ningún servidor de correo.
 * </p>
 *
 * <p>
 * <b>Nunca en producción:</b> el cuerpo lleva el token de verificación y los
 * logs de producción acaban en sistemas que lee más gente. Por eso
 * {@link EmailSenderConfig} hace fallar el arranque con el perfil {@code prod}
 * si no hay SMTP, en lugar de recurrir a este emisor.
 * </p>
 */
public class LoggingEmailSender implements EmailSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingEmailSender.class);

    /**
     * Escribe el correo en el log.
     *
     * @param message correo que se «envía»
     */
    @Override
    public void send(EmailMessage message) {
        LOGGER.info("""
                Correo (sin SMTP configurado: solo se escribe en el log)
                Para: {}
                Asunto: {}

                {}""", message.to(), message.subject(), message.body());
    }
}
