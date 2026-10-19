package com.emilio.streambox.email;

import java.util.Objects;

/**
 * Un correo listo para enviar: destinatario, asunto y cuerpo en <b>texto
 * plano</b>.
 *
 * <p>
 * <b>Por qué solo texto plano.</b> Un correo HTML exige escapar todo lo que se
 * interpola (si no, cualquier dato del usuario se convierte en marcado), se ve
 * distinto en cada cliente de correo y es lo que más se parece al
 * <em>phishing</em>. Los correos de StreamBox solo llevan un enlace y unas
 * frases: en texto plano el cliente de correo convierte la URL en enlace solo,
 * y lo que se lee es exactamente lo que se envía.
 * </p>
 *
 * <p>
 * <b>Cuidado con los logs:</b> el cuerpo puede llevar un token de un solo uso
 * (el enlace de verificación). Solo {@link LoggingEmailSender} lo escribe, a
 * propósito y solo en desarrollo. {@link #toString()} no lo incluye, para que
 * imprimir el mensaje por descuido no filtre el token.
 * </p>
 *
 * @param to      dirección del destinatario (ya normalizada)
 * @param subject asunto (texto fijo, sin datos del usuario)
 * @param body    cuerpo en texto plano
 */
public record EmailMessage(String to, String subject, String body) {

    /**
     * Comprueba que no falta ningún campo.
     *
     * @throws NullPointerException     si falta alguno
     * @throws IllegalArgumentException si el destinatario o el asunto contienen
     *                                  saltos de línea (inyección de cabeceras)
     */
    public EmailMessage {
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(body, "body");
        // Defensa en profundidad: un salto de línea en una cabecera permitiría
        // añadir otras (Bcc, por ejemplo). El destinatario ya viene validado
        // como email y el asunto es fijo, pero el coste de comprobarlo es nulo.
        if (to.indexOf('\r') >= 0 || to.indexOf('\n') >= 0
                || subject.indexOf('\r') >= 0 || subject.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("El destinatario y el asunto no pueden tener saltos de línea");
        }
    }

    /**
     * Descripción sin el cuerpo (puede llevar un token) ni el destinatario
     * (dato personal).
     *
     * @return el asunto del correo
     */
    @Override
    public String toString() {
        return "EmailMessage[subject=" + subject + "]";
    }
}
