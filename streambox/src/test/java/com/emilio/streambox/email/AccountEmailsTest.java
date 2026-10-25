package com.emilio.streambox.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Textos de los correos ({@link AccountEmails}) y validación de su
 * configuración ({@link PublicUrlProperties}, {@link EmailProperties}).
 *
 * <p>
 * Los enlaces se construyen con la URL pública configurada (nunca con la
 * petición) y el token va en el fragmento ({@code #token=}), que el navegador
 * no envía al servidor. Una URL pública absurda o un remitente con saltos de
 * línea impiden arrancar con un mensaje que nombra la propiedad.
 * </p>
 */
class AccountEmailsTest {

    private static final PublicUrlProperties PUBLIC_URL = new PublicUrlProperties("https://streambox.example.com/");
    private static final String TOKEN = "q8Vb3c0mZ1yJx7pQ2rT5uW9aE4fH6kL1nB3dS8gV0iY";

    @Test
    void elCorreoDeVerificacionLlevaElEnlaceConElTokenEnElFragmentoYSuCaducidad() {
        EmailMessage email = AccountEmails.verification("ana@correo.test", "ana_92", PUBLIC_URL, TOKEN,
                Duration.ofHours(24));

        assertEquals("ana@correo.test", email.to());
        assertEquals("Confirma tu correo en StreamBox", email.subject());
        assertThat(email.body())
                .contains("\nhttps://streambox.example.com/verificar-email#token=" + TOKEN + "\n")
                .contains("El enlace caduca en 24 horas y solo sirve una vez.")
                .contains("pulsa «Confirmar mi correo»")
                .doesNotContain("terminar de crear");
    }

    /**
     * M2 (<i>pre-account hijacking</i>): el correo dice el nombre de la cuenta
     * que se confirmaría y pide no abrir el enlace si no se reconoce. Así quien
     * recibe el correo de una cuenta que otro creó con su email puede darse
     * cuenta antes de verificársela al atacante.
     */
    @Test
    void elCorreoDeVerificacionDiceElNombreDeLaCuentaYQueNoSeAbraSiNoSeReconoce() {
        String body = AccountEmails.verification("ana@correo.test", "ana_92", PUBLIC_URL, TOKEN,
                Duration.ofHours(24)).body();

        assertThat(body)
                .contains("poder iniciar sesión con la cuenta «ana_92»")
                .contains("Si no reconoces el nombre de usuario «ana_92», no abras el enlace: alguien ha usado tu "
                        + "correo para registrarse");
    }

    /**
     * M2: «no abras el enlace» solo no basta, porque la cuenta del atacante
     * ocupa el email de la víctima. El correo le da la salida que existe
     * («He olvidado mi contraseña», que deja la cuenta a su nombre), con el
     * enlace a la página que pide ese enlace, construido con la URL pública y
     * sin token. Va después del aviso, que es donde la víctima lo necesita.
     */
    @Test
    void elCorreoDeVerificacionDaLaSalidaDeRecuperarLaContrasenaSiNoSeReconoceElNombre() {
        String body = AccountEmails.verification("ana@correo.test", "pepe_92", PUBLIC_URL, TOKEN,
                Duration.ofHours(24)).body();

        assertThat(body).contains("Si quieres usar StreamBox con este correo, pide aquí un enlace para elegir tu "
                + "propia contraseña; al elegirla, la cuenta pasará a ser tuya y podrás cambiar el nombre en tu "
                + "perfil:\n\nhttps://streambox.example.com/recuperar-contrasena\n");
        assertThat(body.indexOf("recuperar-contrasena"))
                .as("La salida va después del aviso «Si no reconoces el nombre»")
                .isGreaterThan(body.indexOf("Si no reconoces el nombre"));
        assertThat(body.split("#token=", -1))
                .as("Solo el enlace de verificación lleva token")
                .hasSize(2);
    }

    /**
     * El reenvío tiene un límite silencioso: pedir otro correo no siempre
     * genera uno, y entonces el enlace anterior sigue valiendo. La frase solo
     * promete lo que siempre es verdad: que un correo nuevo invalida este.
     */
    @Test
    void losCorreosConEnlaceNoPrometenQuePedirOtroInvalidaEste() {
        String verification = AccountEmails.verification("ana@correo.test", "ana_92", PUBLIC_URL, TOKEN,
                Duration.ofHours(24)).body();
        String reset = AccountEmails.passwordReset("ana@correo.test", "ana_92", PUBLIC_URL, TOKEN,
                Duration.ofHours(1)).body();

        for (String body : new String[] { verification, reset }) {
            assertThat(body)
                    .contains("Si te llega otro correo como este, este enlace deja de funcionar: usa siempre el "
                            + "del último.")
                    .doesNotContain("Si pides otro correo");
        }
    }

    /**
     * El nombre lo escribe el usuario: en el correo va en una sola línea y sin
     * caracteres de formato invisibles (el registro ya lo normaliza; esto es
     * la segunda barrera, por si hubiera nombres antiguos sin normalizar). Un
     * salto de línea permitiría simular un párrafo del correo y {@code U+202E}
     * invertiría el texto que le sigue.
     */
    @Test
    void elNombreVaEnUnaSolaLineaYSinCaracteresDeFormato() {
        assertEquals("ana Llama ya", AccountEmails.displayName("ana\r\n\u2028Llama\u0085ya"));
        assertEquals("anaevil", AccountEmails.displayName("ana\u202Eevil\u200B"));

        String body = AccountEmails.verification("ana@correo.test", "ana\nEl equipo de StreamBox", PUBLIC_URL,
                TOKEN, Duration.ofHours(24)).body();
        assertThat(body).contains("con la cuenta «ana El equipo de StreamBox»");
    }

    @Test
    void elAvisoDeCuentaExistenteLlevaElEnlaceAlLoginYNingunToken() {
        EmailMessage email = AccountEmails.alreadyRegistered("ana@correo.test", PUBLIC_URL);

        assertEquals("Ya tienes una cuenta en StreamBox", email.subject());
        assertThat(email.body())
                .contains("\nhttps://streambox.example.com/login\n")
                .contains("No hemos cambiado nada.")
                .doesNotContain("#token=")
                .doesNotContain("verificar-email");
    }

    /**
     * Quien no recuerda la contraseña ya no queda en un callejón sin salida
     * («pide ayuda al administrador»): el aviso enlaza a la página donde se
     * pide el enlace para elegir una nueva.
     */
    @Test
    void elAvisoDeCuentaExistenteEnlazaARecuperarLaContrasenaYNoMandaAlAdministrador() {
        String body = AccountEmails.alreadyRegistered("ana@correo.test", PUBLIC_URL).body();

        assertThat(body)
                .contains("Si no recuerdas la contraseña, puedes elegir una nueva aquí:\n\n"
                        + "https://streambox.example.com/recuperar-contrasena\n")
                .doesNotContain("administrador");
    }

    /**
     * El enlace de «He olvidado mi contraseña»: a la página que lo entiende,
     * con el token en el fragmento, el nombre de la cuenta, su caducidad y qué
     * pasa si no se pidió (nada) o si se usa (correo confirmado y sesiones
     * cerradas).
     */
    @Test
    void elCorreoDeRestablecimientoLlevaElEnlaceElNombreYLoQuePasaAlUsarlo() {
        EmailMessage email = AccountEmails.passwordReset("ana@correo.test", "ana\u202E_92", PUBLIC_URL, TOKEN,
                Duration.ofHours(1));

        assertEquals("ana@correo.test", email.to());
        assertEquals("Restablece tu contraseña de StreamBox", email.subject());
        assertThat(email.body())
                .contains("\nhttps://streambox.example.com/restablecer-contrasena#token=" + TOKEN + "\n")
                .contains("para la cuenta «ana_92» de StreamBox")
                .contains("El enlace caduca en 1 hora y solo sirve una vez.")
                .contains("Si te llega otro correo como este, este enlace deja de funcionar")
                .contains("tu correo queda confirmado y se cierran todas las sesiones")
                .contains("Si no lo has pedido tú, ignora este correo: tu contraseña no cambia.");
    }

    /**
     * Riesgo M2: si otro registró el email de la víctima, el correo de
     * restablecimiento lleva un nombre que ella no eligió. El correo le explica
     * qué significa y que al usar el enlace la cuenta pasa a ser suya y puede
     * cambiar el nombre, en lugar de dejarle pensar que no va con ella. El
     * nombre va limpio (sin caracteres de formato), como en el resto del correo.
     */
    @Test
    void elCorreoDeRestablecimientoExplicaQueHacerSiNoSeReconoceElNombre() {
        String body = AccountEmails.passwordReset("ana@correo.test", "pepe‮_92", PUBLIC_URL, TOKEN,
                Duration.ofHours(1)).body();

        assertThat(body).contains("Si no reconoces el nombre de usuario «pepe_92», puede que alguien registrara tu "
                + "correo: al elegir la contraseña nueva, la cuenta pasa a ser tuya y podrás cambiar el nombre en tu "
                + "perfil.");
        assertThat(body.indexOf("Si no reconoces el nombre"))
                .as("Va antes de «Si no lo has pedido tú», que es lo último que se lee")
                .isLessThan(body.indexOf("Si no lo has pedido tú"));
    }

    /**
     * El aviso de contraseña cambiada dice cuándo (en UTC, porque el servidor
     * no sabe la zona de quien lo lee), que se cerraron las sesiones y qué
     * hacer si no fue él, sin ningún token.
     */
    @Test
    void elAvisoDeContrasenaCambiadaDiceCuandoYQueHacerSiNoFueEl() {
        EmailMessage email = AccountEmails.passwordChanged("ana@correo.test", PUBLIC_URL,
                Instant.parse("2026-10-19T14:05:59Z"));

        assertEquals("Tu contraseña de StreamBox ha cambiado", email.subject());
        assertThat(email.body())
                .contains("se ha cambiado el 19 de octubre de 2026 a las 14:05 (hora UTC)")
                .contains("se han cerrado todas las sesiones abiertas con tu cuenta")
                .contains("Si no has sido tú")
                .contains("\nhttps://streambox.example.com/recuperar-contrasena\n")
                .doesNotContain("#token=");
    }

    @Test
    void laDuracionSeDescribeEnHorasOMinutos() {
        assertEquals("24 horas", AccountEmails.describe(Duration.ofHours(24)));
        assertEquals("1 hora", AccountEmails.describe(Duration.ofHours(1)));
        assertEquals("90 minutos", AccountEmails.describe(Duration.ofMinutes(90)));
        assertEquals("168 horas", AccountEmails.describe(Duration.ofDays(7)));
    }

    // ------------------------------------------------------------------
    // streambox.public-url
    // ------------------------------------------------------------------

    @Test
    void laUrlPublicaSeGuardaSinLaBarraFinalYAdmiteUnaSubcarpeta() {
        assertEquals("https://streambox.example.com/verificar-email#token=x", PUBLIC_URL.link("/verificar-email#token=x"));
        assertEquals("http://localhost:8088/app/login", new PublicUrlProperties(" http://localhost:8088/app/ ")
                .link("/login"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   ", "streambox.example.com", "/verificar-email", "ftp://streambox.example.com",
            "javascript:alert(1)", "https://usuario:clave@streambox.example.com",
            "https://streambox.example.com/?a=b", "https://streambox.example.com/#x", "https:///sin-host" })
    void unaUrlPublicaQueNoEsUnaUrlWebAbsolutaImpideArrancar(String value) {
        assertThatThrownBy(() -> new PublicUrlProperties(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("STREAMBOX_PUBLIC_URL");
    }

    // ------------------------------------------------------------------
    // streambox.email.*
    // ------------------------------------------------------------------

    @Test
    void elRemitenteConSaltoDeLineaOMalFormadoImpideArrancar() {
        EmailProperties.Resend resend = new EmailProperties.Resend(3, Duration.ofHours(1), Duration.ofSeconds(60));
        assertThatThrownBy(() -> new EmailProperties("a@b.test\r\nBcc: x@y.test", Duration.ofHours(24), Duration.ofHours(1), true, resend))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("streambox.email.from");
        assertThatThrownBy(() -> new EmailProperties("no es un correo", Duration.ofHours(24), Duration.ofHours(1), true, resend))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("streambox.email.from");
        assertThat(new EmailProperties("StreamBox <no-reply@streambox.example.com>", Duration.ofHours(24), Duration.ofHours(1), true,
                resend).from()).isEqualTo("StreamBox <no-reply@streambox.example.com>");
    }

    @Test
    void laVidaDelEnlaceDebeEstarEntre5MinutosY7Dias() {
        EmailProperties.Resend resend = new EmailProperties.Resend(3, Duration.ofHours(1), Duration.ofSeconds(60));
        assertThatThrownBy(() -> new EmailProperties("a@b.test", Duration.ofMinutes(4), Duration.ofHours(1), true, resend))
                .hasMessageContaining("streambox.email.verification-ttl");
        assertThatThrownBy(() -> new EmailProperties("a@b.test", Duration.ofDays(8), Duration.ofHours(1), true, resend))
                .hasMessageContaining("streambox.email.verification-ttl");
        new EmailProperties("a@b.test", Duration.ofMinutes(5), Duration.ofHours(1), true, resend);
        new EmailProperties("a@b.test", Duration.ofDays(7), Duration.ofHours(1), true, resend);
    }

    /** El enlace de restablecimiento da acceso a la cuenta: entre 5 minutos y 24 horas, no los 7 días de la verificación. */
    @Test
    void laVidaDelEnlaceDeRestablecimientoDebeEstarEntre5MinutosY24Horas() {
        EmailProperties.Resend resend = new EmailProperties.Resend(3, Duration.ofHours(1), Duration.ofSeconds(60));
        assertThatThrownBy(() -> new EmailProperties("a@b.test", Duration.ofHours(24), Duration.ofMinutes(4), true,
                resend)).hasMessageContaining("streambox.email.password-reset-ttl");
        assertThatThrownBy(() -> new EmailProperties("a@b.test", Duration.ofHours(24), Duration.ofHours(25), true,
                resend)).hasMessageContaining("streambox.email.password-reset-ttl");
        assertThatThrownBy(() -> new EmailProperties("a@b.test", Duration.ofHours(24), null, true, resend))
                .hasMessageContaining("streambox.email.password-reset-ttl");
        new EmailProperties("a@b.test", Duration.ofHours(24), Duration.ofMinutes(5), true, resend);
        new EmailProperties("a@b.test", Duration.ofHours(24), Duration.ofHours(24), true, resend);
    }

    @Test
    void elLimiteDeReenviosDebeSerCoherente() {
        assertThatThrownBy(() -> new EmailProperties.Resend(0, Duration.ofHours(1), Duration.ZERO))
                .hasMessageContaining("max-per-window");
        assertThatThrownBy(() -> new EmailProperties.Resend(3, Duration.ZERO, Duration.ZERO))
                .hasMessageContaining("resend.window");
        assertThatThrownBy(() -> new EmailProperties.Resend(3, Duration.ofMinutes(1), Duration.ofMinutes(2)))
                .hasMessageContaining("min-interval");
        new EmailProperties.Resend(3, Duration.ofHours(1), Duration.ZERO);
    }
}
