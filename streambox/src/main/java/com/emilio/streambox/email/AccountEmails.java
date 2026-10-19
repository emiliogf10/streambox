package com.emilio.streambox.email;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Textos de los correos de las cuentas (verificación del email y aviso de
 * «ya tienes una cuenta»).
 *
 * <p>
 * <b>Un solo dato escrito por el usuario: el nombre de la cuenta en el correo
 * de verificación.</b> Es una decisión de compromiso. Sin él, quien registra
 * el email de otra persona con su propia contraseña (<i>pre-account
 * hijacking</i>) consigue que la víctima reciba un correo legítimo de
 * StreamBox «confirma tu correo» y, si lo pulsa, la cuenta del atacante queda
 * verificada con el email de la víctima. Con el nombre de la cuenta en el
 * correo (p. ej. {@code pepe_92}) la víctima puede ver que esa cuenta no es
 * suya, y el correo le pide que entonces no abra el enlace (el riesgo
 * completo y su solución pendiente, en {@code RegistrationService}). El precio es que el nombre lo elige
 * quien se registra: alguien podría llamar a su cuenta «Llama al 600 000 000»
 * para que el correo legítimo lleve ese texto. Se acota así:
 * </p>
 * <ul>
 *   <li>Solo 50 caracteres, siempre <b>entre comillas</b> y presentado como lo
 *       que es, un nombre de usuario; nunca como una instrucción.</li>
 *   <li>En <b>una sola línea</b>: el registro convierte en espacios los
 *       caracteres de control y los separadores (también los saltos de línea
 *       Unicode, {@code U+2028}/{@code U+2029}), y {@link #displayName} lo
 *       repite aquí por si hubiera nombres antiguos guardados sin normalizar.
 *       Con un salto de línea se podría simular un párrafo del correo.</li>
 *   <li>Sin caracteres de formato invisibles ({@code \p{Cf}}, como
 *       {@code U+202E}, que invierte el texto que le sigue): se quitan al
 *       mostrarlo.</li>
 *   <li>El correo es de <b>texto plano</b>, así que no hay HTML que inyectar.</li>
 * </ul>
 * <p>
 * El aviso «Ya tienes una cuenta» no lleva el nombre: lo recibe el dueño de
 * una cuenta confirmada, que ya la conoce, y el nombre enviado en ese registro
 * es de quien lo intentó.
 * </p>
 *
 * <p>
 * <b>Lo que dicen es verdad para quien lo recibe</b>, que quizá no pidió nada:
 * por eso explican qué pasa si no se hace caso y piden no abrir el enlace si
 * no fue él.
 * </p>
 */
public final class AccountEmails {

    /** Asunto del correo con el enlace de verificación. */
    public static final String VERIFICATION_SUBJECT = "Confirma tu correo en StreamBox";

    /** Asunto del aviso a una cuenta que ya existe y está confirmada. */
    public static final String ALREADY_REGISTERED_SUBJECT = "Ya tienes una cuenta en StreamBox";

    /** Ruta de la página que confirma el correo; el token va en el fragmento. */
    public static final String VERIFICATION_PATH = "/verificar-email#token=";

    /** Ruta de la página de inicio de sesión. */
    public static final String LOGIN_PATH = "/login";

    /** Caracteres de formato invisibles (p. ej. {@code U+202E}, que invierte el texto). */
    private static final Pattern FORMAT_CHARACTERS = Pattern.compile("\\p{Cf}");

    /** Controles (saltos de línea incluidos) y separadores Unicode: se convierten en un espacio. */
    private static final Pattern LINE_BREAKS_AND_SPACES = Pattern.compile("[\\p{Z}\\p{Cc}]+");

    private AccountEmails() {
    }

    /**
     * Correo con el enlace para confirmar el email.
     *
     * <p>
     * El token va en el <b>fragmento</b> ({@code #token=...}): el navegador no
     * lo envía al servidor, así que no queda en los logs de nginx ni en la
     * cabecera {@code Referer}. La página pide pulsar un botón para confirmar,
     * porque los antivirus del correo abren los enlaces y gastarían el token.
     * </p>
     *
     * <p>
     * La primera frase no dice «terminar de crear tu cuenta» a propósito: este
     * correo también lo reciben las cuentas heredadas (anteriores a la V9, que
     * existen desde hace tiempo y quedaron pendientes), para las que esa frase
     * sería falsa. «Confirmar tu correo y poder iniciar sesión» es verdad en
     * los dos casos.
     * </p>
     *
     * <p>
     * {@code username} es el nombre de la cuenta <b>que quedará verificada</b>
     * (no el del registro que provocó el correo, que puede ser otro si la
     * cuenta ya existía): es lo que el dueño del buzón tiene que reconocer
     * (ver la clase).
     * </p>
     *
     * @param to        destinatario
     * @param username  nombre de usuario de la cuenta que se confirma
     * @param publicUrl URL pública de la web
     * @param token     token en claro (Base64URL, sin caracteres que haya que escapar)
     * @param ttl       vida del enlace
     * @return el correo
     */
    public static EmailMessage verification(String to, String username, PublicUrlProperties publicUrl,
            String token, Duration ttl) {
        String name = displayName(username);
        String body = """
                Hola:

                Para confirmar tu correo en StreamBox y poder iniciar sesión con la cuenta «%s», abre este \
                enlace y pulsa «Confirmar mi correo»:

                %s

                El enlace caduca en %s y solo sirve una vez. Si pides otro correo, este enlace deja de funcionar: \
                usa siempre el del último.

                Si no reconoces el nombre de usuario «%s», no abras el enlace: alguien ha usado tu correo para \
                registrarse y, si lo confirmas, podría iniciar sesión con tu correo y la contraseña que eligió. \
                Sin confirmarla, nadie puede iniciar sesión con esa cuenta.

                El equipo de StreamBox
                """.formatted(name, publicUrl.link(VERIFICATION_PATH + token), describe(ttl), name);
        return new EmailMessage(to, VERIFICATION_SUBJECT, body);
    }

    /**
     * Nombre de usuario tal como se muestra en un correo: sin caracteres de
     * formato invisibles y en una sola línea (ver la clase). El nombre ya llega
     * así del registro; esto es una segunda barrera, no la primera.
     *
     * @param username nombre guardado
     * @return el nombre listo para el texto del correo
     */
    static String displayName(String username) {
        String withoutFormat = FORMAT_CHARACTERS.matcher(username).replaceAll("");
        return LINE_BREAKS_AND_SPACES.matcher(withoutFormat).replaceAll(" ").strip();
    }

    /**
     * Aviso a quien ya tiene una cuenta confirmada con ese email y alguien
     * (quizá él mismo) ha intentado registrarse otra vez.
     *
     * <p>
     * Es lo que permite que el registro responda igual exista o no el email
     * sin dejar perdido al dueño: si fue él, el correo le dice que ya tiene
     * cuenta y dónde entrar. Todavía no existe «He olvidado mi contraseña», así
     * que el texto no lo promete.
     * </p>
     *
     * @param to        destinatario
     * @param publicUrl URL pública de la web
     * @return el correo
     */
    public static EmailMessage alreadyRegistered(String to, PublicUrlProperties publicUrl) {
        String body = """
                Hola:

                Alguien, quizá tú, ha intentado crear una cuenta en StreamBox con este correo, pero ya tienes una. \
                No hemos cambiado nada.

                Puedes iniciar sesión aquí:

                %s

                Si no recuerdas la contraseña, de momento no se puede recuperar desde la web: pide ayuda al \
                administrador de StreamBox.

                Si no has sido tú, no tienes que hacer nada: tu cuenta y tu contraseña siguen igual.

                El equipo de StreamBox
                """.formatted(publicUrl.link(LOGIN_PATH));
        return new EmailMessage(to, ALREADY_REGISTERED_SUBJECT, body);
    }

    /**
     * Describe una duración en horas (o minutos si no son horas exactas):
     * «24 horas», «1 hora», «90 minutos».
     *
     * @param duration duración positiva
     * @return la duración en palabras
     */
    static String describe(Duration duration) {
        if (duration.toMinutes() % 60 == 0) {
            long hours = duration.toHours();
            return hours == 1 ? "1 hora" : hours + " horas";
        }
        long minutes = duration.toMinutes();
        return minutes == 1 ? "1 minuto" : minutes + " minutos";
    }
}
