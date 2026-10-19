package com.emilio.streambox.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

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
        assertThatThrownBy(() -> new EmailProperties("a@b.test\r\nBcc: x@y.test", Duration.ofHours(24), true, resend))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("streambox.email.from");
        assertThatThrownBy(() -> new EmailProperties("no es un correo", Duration.ofHours(24), true, resend))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("streambox.email.from");
        assertThat(new EmailProperties("StreamBox <no-reply@streambox.example.com>", Duration.ofHours(24), true,
                resend).from()).isEqualTo("StreamBox <no-reply@streambox.example.com>");
    }

    @Test
    void laVidaDelEnlaceDebeEstarEntre5MinutosY7Dias() {
        EmailProperties.Resend resend = new EmailProperties.Resend(3, Duration.ofHours(1), Duration.ofSeconds(60));
        assertThatThrownBy(() -> new EmailProperties("a@b.test", Duration.ofMinutes(4), true, resend))
                .hasMessageContaining("streambox.email.verification-ttl");
        assertThatThrownBy(() -> new EmailProperties("a@b.test", Duration.ofDays(8), true, resend))
                .hasMessageContaining("streambox.email.verification-ttl");
        new EmailProperties("a@b.test", Duration.ofMinutes(5), true, resend);
        new EmailProperties("a@b.test", Duration.ofDays(7), true, resend);
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
