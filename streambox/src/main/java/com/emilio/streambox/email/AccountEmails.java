package com.emilio.streambox.email;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Textos de los correos de las cuentas: verificación del email, aviso de «ya
 * tienes una cuenta», enlace de «He olvidado mi contraseña» y aviso de
 * contraseña cambiada.
 *
 * <p>
 * <b>Un solo dato escrito por el usuario: el nombre de la cuenta en el correo
 * de verificación (y en el de restablecer la contraseña, con las mismas
 * cautelas).</b> Es una decisión de compromiso. Sin él, quien registra
 * el email de otra persona con su propia contraseña (<i>pre-account
 * hijacking</i>) consigue que la víctima reciba un correo legítimo de
 * StreamBox «confirma tu correo» y, si lo pulsa, la cuenta del atacante queda
 * verificada con el email de la víctima. Con el nombre de la cuenta en el
 * correo (p. ej. {@code pepe_92}) la víctima puede ver que esa cuenta no es
 * suya, y el correo le pide que entonces no abra el enlace y le ofrece la
 * salida: pedir en «He olvidado mi contraseña» su propia contraseña, con lo
 * que la cuenta pasa a ser suya (el riesgo completo en
 * {@code RegistrationService}). El precio es que el nombre lo elige quien se
 * registra: alguien podría llamar a su cuenta «Llama al 600 000 000»
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

    /** Asunto del correo con el enlace para elegir una contraseña nueva. */
    public static final String PASSWORD_RESET_SUBJECT = "Restablece tu contraseña de StreamBox";

    /** Asunto del aviso tras cambiar la contraseña con ese enlace (lo espera el E2E). */
    public static final String PASSWORD_CHANGED_SUBJECT = "Tu contraseña de StreamBox ha cambiado";

    /** Ruta de la página que fija la contraseña nueva; el token va en el fragmento. */
    public static final String PASSWORD_RESET_PATH = "/restablecer-contrasena#token=";

    /** Ruta de la página donde se pide el enlace de «He olvidado mi contraseña». */
    public static final String FORGOT_PASSWORD_PATH = "/recuperar-contrasena";

    /**
     * Fecha y hora del aviso de contraseña cambiada, en UTC (el servidor no sabe
     * en qué zona horaria está quien lo lee, así que lo dice): «19 de octubre de
     * 2026 a las 14:05».
     */
    private static final DateTimeFormatter CHANGED_AT = DateTimeFormatter
            .ofPattern("d 'de' MMMM 'de' yyyy 'a las' HH:mm", Locale.forLanguageTag("es-ES"))
            .withZone(ZoneOffset.UTC);

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
     * <p>
     * <b>«No abras el enlace» no basta</b>: a la víctima del <i>pre-account
     * hijacking</i> la dejaría sin poder usar StreamBox con su propio correo
     * (la cuenta del atacante lo ocupa). Por eso el correo le da la salida que
     * existe: pedir en {@link #FORGOT_PASSWORD_PATH} un enlace para elegir su
     * contraseña, que deja la cuenta confirmada, con su contraseña y con las
     * sesiones del atacante cerradas. Dice «pide un enlace» y no «elige aquí tu
     * contraseña» porque esa página solo pide el email y envía el enlace.
     * </p>
     *
     * <p>
     * <b>«Si te llega otro correo como este»</b>, y no «si pides otro»: el
     * reenvío tiene un límite silencioso por cuenta, así que pedir otro no
     * siempre genera uno (y entonces este enlace sigue valiendo). Lo que sí es
     * siempre verdad es que cada correo nuevo invalida los enlaces anteriores.
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

                El enlace caduca en %s y solo sirve una vez. Si te llega otro correo como este, este enlace deja \
                de funcionar: usa siempre el del último.

                Si no reconoces el nombre de usuario «%s», no abras el enlace: alguien ha usado tu correo para \
                registrarse y, si lo confirmas, podría iniciar sesión con tu correo y la contraseña que eligió. \
                Sin confirmarla, nadie puede iniciar sesión con esa cuenta.

                Si quieres usar StreamBox con este correo, pide aquí un enlace para elegir tu propia contraseña; \
                al elegirla, la cuenta pasará a ser tuya y podrás cambiar el nombre en tu perfil:

                %s

                El equipo de StreamBox
                """.formatted(name, publicUrl.link(VERIFICATION_PATH + token), describe(ttl), name,
                publicUrl.link(FORGOT_PASSWORD_PATH));
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
     * cuenta, dónde entrar y, si no recuerda la contraseña, dónde pedir una
     * nueva («He olvidado mi contraseña»). Antes decía «pide ayuda al
     * administrador», un callejón sin salida para quien no conoce a nadie.
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

                Si no recuerdas la contraseña, puedes elegir una nueva aquí:

                %s

                Si no has sido tú, no tienes que hacer nada: tu cuenta y tu contraseña siguen igual.

                El equipo de StreamBox
                """.formatted(publicUrl.link(LOGIN_PATH), publicUrl.link(FORGOT_PASSWORD_PATH));
        return new EmailMessage(to, ALREADY_REGISTERED_SUBJECT, body);
    }

    /**
     * Correo con el enlace para elegir una contraseña nueva («He olvidado mi
     * contraseña»).
     *
     * <p>
     * Como en la verificación, el token va en el <b>fragmento</b>
     * ({@code #token=...}: no llega a los logs ni al {@code Referer}) y la
     * página pide escribir la contraseña nueva antes de gastarlo, así que un
     * antivirus que abra el enlace no lo usa.
     * </p>
     *
     * <p>
     * <b>Lo que dice es verdad para quien lo recibe, lo haya pedido o no.</b>
     * Lo puede pedir cualquiera que sepa el email, así que empieza por
     * «alguien, quizá tú» y termina diciendo que, si no fue él, puede
     * ignorarlo: mientras nadie use el enlace, la contraseña no cambia. Avisa
     * de lo que pasará al usarlo (el correo queda confirmado y se cierran todas
     * las sesiones) para que nada de eso sorprenda. Lleva el nombre de la
     * cuenta para que el dueño del buzón sepa a qué cuenta pondrá la
     * contraseña.
     * </p>
     *
     * <p>
     * <b>Si el nombre no es suyo</b> (riesgo M2: otro registró su email con un
     * nombre y una contraseña elegidos por él; la cuenta puede seguir
     * pendiente o, si la víctima pulsó el enlace de verificación, estar ya
     * confirmada), el nombre desconocido podría hacerle pensar que el correo
     * no va con él y descartar justo el enlace que le devuelve su email. Por
     * eso le explica qué significa y que, al usar el enlace, la cuenta pasa a
     * ser suya (contraseña suya, correo confirmado, las demás sesiones
     * cerradas) y puede cambiar el nombre en su perfil. El servidor no sabe
     * quién eligió el nombre, así que la frase va siempre y en condicional.
     * </p>
     *
     * @param to        destinatario (el email de la cuenta)
     * @param username  nombre de usuario de la cuenta
     * @param publicUrl URL pública de la web
     * @param token     token en claro (Base64URL, sin caracteres que haya que escapar)
     * @param ttl       vida del enlace
     * @return el correo
     */
    public static EmailMessage passwordReset(String to, String username, PublicUrlProperties publicUrl,
            String token, Duration ttl) {
        String name = displayName(username);
        String body = """
                Hola:

                Alguien, quizá tú, ha pedido elegir una contraseña nueva para la cuenta «%s» de StreamBox. \
                Para elegirla, abre este enlace:

                %s

                El enlace caduca en %s y solo sirve una vez. Si te llega otro correo como este, este enlace deja \
                de funcionar: usa siempre el del último.

                Al cambiar la contraseña, tu correo queda confirmado y se cierran todas las sesiones abiertas con \
                la cuenta: después tendrás que iniciar sesión con la contraseña nueva.

                Si no reconoces el nombre de usuario «%s», puede que alguien registrara tu correo: al elegir la \
                contraseña nueva, la cuenta pasa a ser tuya y podrás cambiar el nombre en tu perfil.

                Si no lo has pedido tú, ignora este correo: tu contraseña no cambia.

                El equipo de StreamBox
                """.formatted(name, publicUrl.link(PASSWORD_RESET_PATH + token), describe(ttl), name);
        return new EmailMessage(to, PASSWORD_RESET_SUBJECT, body);
    }

    /**
     * Aviso de que la contraseña se acaba de cambiar con un enlace de «He
     * olvidado mi contraseña».
     *
     * <p>
     * Es la alarma por si no fue el dueño: quien leyera su correo podría pedir
     * el enlace y cambiarla. Le dice cuándo pasó y qué hacer: proteger primero
     * el correo (si alguien lo lee, podría repetirlo) y luego pedir otro enlace
     * para recuperar la cuenta. No lleva ningún enlace con token, ni el nombre
     * de la cuenta (el dueño ya la conoce).
     * </p>
     *
     * <p>
     * «Puede tardar unos minutos» en otros dispositivos es verdad: su refresh
     * token se revoca al momento, pero el JWT de acceso que ya tienen vale
     * hasta que caduca (15 minutos por defecto) y no se puede revocar.
     * </p>
     *
     * @param to        destinatario (el email de la cuenta)
     * @param publicUrl URL pública de la web
     * @param changedAt cuándo se cambió (del {@code Clock} del servicio)
     * @return el correo
     */
    public static EmailMessage passwordChanged(String to, PublicUrlProperties publicUrl, Instant changedAt) {
        String body = """
                Hola:

                La contraseña de tu cuenta de StreamBox se ha cambiado el %s (hora UTC) con un enlace para \
                restablecerla enviado a este correo.

                Por seguridad, se han cerrado todas las sesiones abiertas con tu cuenta. En otros dispositivos \
                puede tardar unos minutos.

                Si has sido tú, no tienes que hacer nada más: ya puedes iniciar sesión con la contraseña nueva.

                Si no has sido tú, alguien ha podido entrar en tu correo: cambia primero la contraseña de tu \
                correo electrónico y después pide otro enlace para elegir una contraseña nueva en StreamBox:

                %s

                El equipo de StreamBox
                """.formatted(CHANGED_AT.format(changedAt), publicUrl.link(FORGOT_PASSWORD_PATH));
        return new EmailMessage(to, PASSWORD_CHANGED_SUBJECT, body);
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
