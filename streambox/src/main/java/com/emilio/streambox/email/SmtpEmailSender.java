package com.emilio.streambox.email;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Envía los correos por SMTP con el {@link JavaMailSender} que Spring Boot
 * configura a partir de {@code spring.mail.*} (host, puerto, usuario,
 * contraseña, TLS...).
 *
 * <p>
 * Se usa siempre que {@code spring.mail.host} esté definido (salvo en los
 * perfiles {@code test} y {@code e2e}): en producción con el SMTP del
 * proveedor y con docker compose contra Mailpit. Envía texto plano
 * ({@link SimpleMailMessage}) en UTF-8 (la codificación por defecto de
 * {@code spring.mail.default-encoding}).
 * </p>
 */
public class SmtpEmailSender implements EmailSender {

    private final JavaMailSender mailSender;
    private final String from;

    /**
     * Crea el emisor SMTP.
     *
     * @param mailSender cliente SMTP configurado por Spring Boot
     * @param from       remitente ({@code streambox.email.from})
     */
    public SmtpEmailSender(JavaMailSender mailSender, String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    /**
     * Envía el correo por SMTP.
     *
     * @param message correo que se envía
     * @throws org.springframework.mail.MailException si el servidor no lo acepta
     */
    @Override
    public void send(EmailMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.body());
        mailSender.send(mail);
    }
}
