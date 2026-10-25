package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.emilio.streambox.dto.AcceptedResponse;
import com.emilio.streambox.email.AccountEmails;
import com.emilio.streambox.email.MemoryEmailSender;
import com.emilio.streambox.email.MemoryEmailSender.SentEmail;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.InvalidResetTokenException;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.password.PasswordPolicy;
import com.emilio.streambox.security.ratelimit.LoginAttemptService;
import com.emilio.streambox.support.AdjustableClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.servlet.http.Cookie;

/**
 * «He olvidado mi contraseña» de punta a punta: pedir el enlace
 * ({@code POST /api/auth/password-reset}) y fijar la contraseña nueva
 * ({@code POST /api/auth/password-reset/confirm}), contra la base de datos y
 * leyendo los correos de {@link MemoryEmailSender}.
 *
 * <p>
 * Lo que se comprueba, y por qué falla sin lo que prueba:
 * </p>
 * <ul>
 *   <li>Pedir el enlace responde <b>idéntico</b> (código, cuerpo y cabeceras,
 *       sin cookies) exista o no la cuenta, y solo las cuentas que existen
 *       (verificada, pendiente, heredada o ADMIN) reciben el correo, con el
 *       enlace construido con la URL pública aunque la petición traiga otra
 *       cabecera {@code Host}.</li>
 *   <li>El límite silencioso por cuenta es propio: no comparte cupo con la
 *       verificación del correo. Pedir otro enlace invalida el anterior.</li>
 *   <li>Confirmar cambia la contraseña (la nueva entra y la vieja no), deja la
 *       cuenta verificada, invalida todos sus enlaces, revoca todas sus
 *       sesiones, borra sus fallos de login y deja como única «IP conocida» la
 *       de la confirmación; avisa por correo.</li>
 *   <li>Una contraseña que no cumple la política da {@code VALIDATION_ERROR}
 *       en {@code newPassword} <b>sin gastar el token</b>; un token que no
 *       sirve, siempre el mismo 400 {@code INVALID_RESET_TOKEN}.</li>
 *   <li>Una cuenta pendiente con un enlace de restablecimiento vigente no se
 *       sustituye con otro registro <b>con su email</b>; pasadas 24 h desde el
 *       alta, sí pierde su <b>nombre</b> si alguien lo pide (MEDIA-1).</li>
 * </ul>
 *
 * <p>
 * Contexto propio: reloj {@link AdjustableClock}, los límites de correos de
 * producción (3 por hora, 60 s entre dos; {@code application-test.properties}
 * los desactiva para el resto) y el bloqueo de cuentas de producción (5 fallos
 * en 15 minutos). {@link LoginAttemptService} es un {@code @MockitoSpyBean}
 * (con su comportamiento real) para ver qué se le pide. Sin
 * {@code @Transactional}: los correos salen tras el commit. Cada test usa
 * cuentas propias (dominio {@value #DOMAIN}) e IPs propias (10.250.x.y).
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "streambox.email.resend.max-per-window=3",
        "streambox.email.resend.window=1h",
        "streambox.email.resend.min-interval=60s",
        "streambox.security.rate-limit.lockout.max-failures=5",
        "streambox.security.rate-limit.lockout.window=15m"
})
class PasswordResetIntegrationTest {

    private static final String DOMAIN = "@restablecimiento.test";
    private static final String OLD_PASSWORD = "Paseo-Por-El-Retiro-2026";
    private static final String NEW_PASSWORD = "Rio-Verde-Bajo-Un-Puente-77";
    private static final String RESET = "/api/auth/password-reset";
    private static final String CONFIRM = "/api/auth/password-reset/confirm";
    private static final Pattern RESET_LINK = Pattern.compile(
            "https://streambox\\.test/restablecer-contrasena#token=([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");
    private static final Pattern VERIFICATION_LINK = Pattern.compile(
            "https://streambox\\.test/verificar-email#token=([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");

    /** Un token con el formato correcto que no se ha emitido nunca. */
    private static final String UNKNOWN_TOKEN = "q8Vb3c0mZ1yJx7pQ2rT5uW9aE4fH6kL1nB3dS8gV0iY";

    private static final AdjustableClock CLOCK = new AdjustableClock();

    @TestBean
    private Clock clock;

    @MockitoSpyBean
    private LoginAttemptService loginAttemptService;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MemoryEmailSender inbox;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Fábrica del bean {@code clock} que usa {@link TestBean} (mismo nombre que el campo). */
    static Clock clock() {
        return CLOCK;
    }

    @BeforeEach
    void setUp() {
        CLOCK.reset();
        cleanUp();
        inbox.clear();
        clearInvocations(loginAttemptService);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
    }

    // ------------------------------------------------------------------
    // Pedir el enlace
    // ------------------------------------------------------------------

    /**
     * La respuesta es idéntica (código, cuerpo y cabeceras, sin
     * {@code Set-Cookie}) con una cuenta verificada, una pendiente, una
     * heredada, una ADMIN y un email sin cuenta: nada revela quién está
     * registrado. Solo las cuatro que existen reciben el correo, con su nombre
     * de usuario y el enlace hecho con {@code streambox.public-url}, aunque la
     * petición diga venir de otro {@code Host} (un atacante no puede hacer que
     * el enlace apunte a su web).
     */
    @Test
    void pedirElEnlaceRespondeIgualConCualquierCuentaYSinCuentaYSoloEnviaALasQueExisten() throws Exception {
        createUser("vera", Role.USER, true);
        register("pili_cuenta", "pili" + DOMAIN);
        createUser("hugo", Role.USER, false);
        createUser("jefa", Role.ADMIN, true);
        inbox.clear();

        List<MockHttpServletResponse> responses = new ArrayList<>();
        for (String name : List.of("nadie", "vera", "pili", "hugo", "jefa")) {
            responses.add(mockMvc.perform(post(RESET).with(fromHost("atacante.example"))
                            .header(HttpHeaders.HOST, "atacante.example")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("email", name + DOMAIN))))
                    .andReturn().getResponse());
        }

        MockHttpServletResponse reference = responses.get(0);
        assertEquals(202, reference.getStatus());
        assertEquals(Map.of("message", AcceptedResponse.PASSWORD_RESET_MESSAGE), bodyOf(reference));
        assertNull(reference.getHeader(HttpHeaders.SET_COOKIE));
        for (MockHttpServletResponse response : responses) {
            assertEquals(reference.getStatus(), response.getStatus());
            assertEquals(reference.getContentAsString(StandardCharsets.UTF_8),
                    response.getContentAsString(StandardCharsets.UTF_8));
            assertEquals(headersOf(reference), headersOf(response));
            assertTrue(response.getCookies().length == 0, "Pedir el enlace no toca ninguna cookie");
        }

        assertTrue(inbox.sentTo("nadie" + DOMAIN).isEmpty());
        for (String[] account : new String[][] { { "vera", "vera_cuenta" }, { "pili", "pili_cuenta" },
                { "hugo", "hugo_cuenta" }, { "jefa", "jefa_cuenta" } }) {
            List<SentEmail> emails = inbox.sentTo(account[0] + DOMAIN);
            assertEquals(1, emails.size(), emails::toString);
            SentEmail email = emails.get(0);
            assertEquals(AccountEmails.PASSWORD_RESET_SUBJECT, email.subject());
            assertTrue(email.body().contains("la cuenta «" + account[1] + "»"), email.body());
            assertTrue(email.body().contains("caduca en 1 hora y solo sirve una vez"), email.body());
            assertFalse(email.body().contains("atacante.example"), email.body());
            resetTokenIn(email);
        }
        assertEquals(4, inbox.all().size());
    }

    /**
     * Límite silencioso propio, con los valores de producción: como mucho uno
     * cada 60 s y 3 por hora. Pasado el límite, el mismo 202 y ningún correo;
     * cuando pasa la ventana, vuelve a enviarse. Y no gasta el cupo de la
     * verificación: tras agotar los enlaces de restablecimiento, el reenvío de
     * la confirmación del correo sigue saliendo (sin contar los tokens por
     * propósito, saldría bloqueado).
     */
    @Test
    void elLimiteSilenciosoEsPropioYNoGastaElDeLaVerificacion() throws Exception {
        register("lidia_cuenta", "lidia" + DOMAIN);
        String email = "lidia" + DOMAIN;
        CLOCK.advance(Duration.ofSeconds(61));

        requestReset(email).andExpect(status().isAccepted());
        requestReset(email).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(AcceptedResponse.PASSWORD_RESET_MESSAGE));
        assertEquals(1, resetEmailsTo(email).size(), "Antes de 60 s no se envía otro");

        CLOCK.advance(Duration.ofSeconds(61));
        requestReset(email).andExpect(status().isAccepted());
        CLOCK.advance(Duration.ofSeconds(61));
        requestReset(email).andExpect(status().isAccepted());
        assertEquals(3, resetEmailsTo(email).size());

        CLOCK.advance(Duration.ofSeconds(61));
        requestReset(email).andExpect(status().isAccepted());
        assertEquals(3, resetEmailsTo(email).size(), "El cuarto de la hora no se envía");

        postJson("/api/auth/email-verification/resend", json(Map.of("email", email)))
                .andExpect(status().isAccepted());
        assertEquals(2, emailsTo(email, AccountEmails.VERIFICATION_SUBJECT).size(),
                "Los enlaces de restablecimiento no gastan el cupo de la verificación");

        CLOCK.advance(Duration.ofHours(1));
        requestReset(email).andExpect(status().isAccepted());
        assertEquals(4, resetEmailsTo(email).size());
    }

    /**
     * Al revés: con el cupo de la verificación agotado (el registro y dos
     * reenvíos), el enlace de restablecimiento sale igual. Es lo que necesita
     * la víctima de M2: quien registró su email puede agotar los reenvíos, pero
     * no impedirle restablecer.
     */
    @Test
    void conElCupoDeLaVerificacionAgotadoElEnlaceDeRestablecimientoSale() throws Exception {
        register("nora_cuenta", "nora" + DOMAIN);
        String email = "nora" + DOMAIN;
        for (int i = 0; i < 3; i++) {
            CLOCK.advance(Duration.ofSeconds(61));
            postJson("/api/auth/email-verification/resend", json(Map.of("email", email)));
        }
        assertEquals(3, emailsTo(email, AccountEmails.VERIFICATION_SUBJECT).size(), "Cupo de verificación agotado");

        requestReset(email).andExpect(status().isAccepted());

        assertEquals(1, resetEmailsTo(email).size());
    }

    /** Pedir otro enlace invalida el anterior: solo vale el del último correo. */
    @Test
    void pedirOtroEnlaceInvalidaElAnterior() throws Exception {
        createUser("eloy", Role.USER, true);
        String first = requestAndGetToken("eloy" + DOMAIN);
        CLOCK.advance(Duration.ofSeconds(61));

        String second = requestAndGetToken("eloy" + DOMAIN);

        assertNotEquals(first, second);
        assertInvalidToken(confirm(first, NEW_PASSWORD));
        confirm(second, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    /** Un email mal formado es un 400 de validación (depende solo del texto) y no envía nada. */
    @Test
    void unEmailMalFormadoDa400DeValidacion() throws Exception {
        requestReset("esto-no-es-un-email")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.email").exists());
        assertTrue(inbox.all().isEmpty());
    }

    // ------------------------------------------------------------------
    // Confirmar
    // ------------------------------------------------------------------

    /**
     * Con el enlace: 204 sin cuerpo ni cookies (no inicia sesión), la
     * contraseña nueva entra y la vieja ya no, el enlace no sirve dos veces y
     * llega el aviso «Tu contraseña de StreamBox ha cambiado», sin token y con
     * el enlace para pedir otro si no fue el dueño.
     */
    @Test
    void conElEnlaceLaContrasenaNuevaEntraYLaViejaNo() throws Exception {
        createUser("irene", Role.USER, true);
        String email = "irene" + DOMAIN;
        String token = requestAndGetToken(email);

        MockHttpServletResponse response = confirm(token, NEW_PASSWORD).andExpect(status().isNoContent())
                .andReturn().getResponse();

        assertEquals("", response.getContentAsString());
        assertEquals(0, response.getCookies().length, "Restablecer no inicia sesión");
        login(email, NEW_PASSWORD, "10.250.0.1").andExpect(status().isNoContent());
        login(email, OLD_PASSWORD, "10.250.0.1").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        assertInvalidToken(confirm(token, "Otra-Clave-Distinta-Del-Todo-9"));

        List<SentEmail> notices = emailsTo(email, AccountEmails.PASSWORD_CHANGED_SUBJECT);
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).body().contains("\nhttps://streambox.test/recuperar-contrasena\n"),
                notices.get(0).body());
        assertFalse(notices.get(0).body().contains("#token="), notices.get(0).body());
    }

    /**
     * Una contraseña nueva que no cumple la política (comprobada contra el
     * nombre de usuario y el email de <b>la cuenta del enlace</b>) da 400
     * {@code VALIDATION_ERROR} con el mensaje en {@code newPassword}, no
     * cambia nada y <b>no gasta el token</b>: con una válida, el mismo enlace
     * funciona.
     */
    @ParameterizedTest
    @MethodSource("invalidNewPasswords")
    void unaContrasenaQueNoCumpleLaPoliticaNoGastaElToken(String newPassword, String expectedMessage)
            throws Exception {
        User user = createUser("marta", "pirata_rojo", Role.USER, true);
        String token = requestAndGetToken(user.getEmail());

        Map<String, Object> body = new HashMap<>();
        body.put("token", token);
        body.put("newPassword", newPassword);
        postJson(CONFIRM, json(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.newPassword").value(expectedMessage));

        assertEquals(user.getPassword(), storedHash(user));
        assertTrue(emailsTo(user.getEmail(), AccountEmails.PASSWORD_CHANGED_SUBJECT).isEmpty());
        confirm(token, NEW_PASSWORD).andExpect(status().isNoContent());
        login(user.getEmail(), NEW_PASSWORD, "10.250.0.2").andExpect(status().isNoContent());
    }

    static Stream<Arguments> invalidNewPasswords() {
        return Stream.of(
                Arguments.of(null, PasswordPolicy.REQUIRED_MESSAGE),
                Arguments.of("corta-123", PasswordPolicy.LENGTH_MESSAGE),
                Arguments.of("x".repeat(PasswordPolicy.MAX_LENGTH + 1), PasswordPolicy.LENGTH_MESSAGE),
                Arguments.of("netflixpassword", PasswordPolicy.COMMON_MESSAGE),
                // 30 caracteres de 3 bytes: longitud válida, 90 bytes.
                Arguments.of("€".repeat(15) + "ñandú-€€€€€€€€€", PasswordPolicy.BYTES_MESSAGE),
                // El nombre de usuario de la cuenta del enlace.
                Arguments.of("mi-clave-PIRATA_ROJO-2026", PasswordPolicy.PERSONAL_DATA_MESSAGE),
                // La parte local de su email.
                Arguments.of("la-clave-de-marta-2026", PasswordPolicy.PERSONAL_DATA_MESSAGE));
    }

    /**
     * Inexistente, mal formado, ausente o de otro tipo: siempre el mismo
     * cuerpo (salvo la fecha) que un token inexistente con el formato
     * correcto. Ninguno toca las IPs conocidas ni los fallos de nadie.
     */
    @ParameterizedTest
    @MethodSource("invalidTokenBodies")
    void cualquierTokenQueNoSirveDaElMismo400(String requestBody) throws Exception {
        ObjectNode reference = errorBody(confirm(UNKNOWN_TOKEN, NEW_PASSWORD));

        ResultActions result = postJson(CONFIRM, requestBody);

        assertInvalidToken(result);
        assertEquals(reference, errorBody(result));
        verify(loginAttemptService, never()).forgetKnownIpsExcept(anyString(), anyString());
        verify(loginAttemptService, never()).clearFailures(anyString());
    }

    static Stream<String> invalidTokenBodies() {
        String password = ",\"newPassword\":\"" + NEW_PASSWORD + "\"}";
        return Stream.of(
                "{\"token\":\"" + UNKNOWN_TOKEN + "\"" + password,
                "{\"newPassword\":\"" + NEW_PASSWORD + "\"}",
                "{\"token\":null" + password,
                "{\"token\":\"\"" + password,
                "{\"token\":\"   \"" + password,
                "{\"token\":\"abc\"" + password,
                "{\"token\":\"" + UNKNOWN_TOKEN.substring(1) + "\"" + password,
                "{\"token\":\"" + UNKNOWN_TOKEN + "A\"" + password,
                "{\"token\":\"" + UNKNOWN_TOKEN.substring(1) + "=\"" + password,
                "{\"token\":\"" + UNKNOWN_TOKEN.substring(1) + "/\"" + password,
                "{\"token\":\"" + UNKNOWN_TOKEN.substring(1) + "ñ\"" + password,
                "{\"token\":\"" + "A".repeat(10_000) + "\"" + password);
    }

    /** Un token que no es texto no llega a interpretarse: {@code MALFORMED_REQUEST}, como en la verificación. */
    @Test
    void unTokenQueNoEsTextoDaMalformedRequest() throws Exception {
        postJson(CONFIRM, "{\"token\":12345,\"newPassword\":\"" + NEW_PASSWORD + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    /**
     * El token de un enlace de <b>verificación</b> no sirve para restablecer
     * la contraseña (cada propósito tiene los suyos): mismo 400 y la
     * contraseña no cambia.
     */
    @Test
    void unTokenDeVerificacionNoSirveParaRestablecer() throws Exception {
        register("olga_cuenta", "olga" + DOMAIN);
        String verificationToken = tokenIn(emailsTo("olga" + DOMAIN, AccountEmails.VERIFICATION_SUBJECT).get(0),
                VERIFICATION_LINK);

        assertInvalidToken(confirm(verificationToken, NEW_PASSWORD));
        User user = userRepository.findByEmail("olga" + DOMAIN).orElseThrow();
        assertTrue(passwordEncoder.matches(OLD_PASSWORD, user.getPassword()));
        assertFalse(user.isEmailVerified());
    }

    /** Un token caducado (justo a la hora: la vida es {@code expires_at > now}) da el mismo 400 y no cambia nada. */
    @Test
    void unTokenCaducadoJustoALaHoraDaElMismo400() throws Exception {
        User user = createUser("paco", Role.USER, true);
        String token = requestAndGetToken(user.getEmail());

        CLOCK.advance(Duration.ofHours(1));

        assertInvalidToken(confirm(token, NEW_PASSWORD));
        assertEquals(user.getPassword(), storedHash(user));
    }

    /** Un segundo antes de caducar, el token aún vale. */
    @Test
    void unSegundoAntesDeCaducarElTokenVale() throws Exception {
        User user = createUser("rita", Role.USER, true);
        String token = requestAndGetToken(user.getEmail());

        CLOCK.advance(Duration.ofHours(1).minusSeconds(1));

        confirm(token, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    /**
     * Una cuenta <b>pendiente</b> (registrada y sin confirmar) queda verificada
     * al restablecer (quien tiene el token controla el buzón: cierra M2) y ya
     * puede entrar. Su enlace de verificación, que seguía vigente, deja de
     * servir: se invalidan todos los tokens de la cuenta, no solo los de
     * restablecimiento.
     */
    @Test
    void unaCuentaPendienteQuedaVerificadaEntraYSuEnlaceDeVerificacionDejaDeServir() throws Exception {
        register("sara_cuenta", "sara" + DOMAIN);
        String email = "sara" + DOMAIN;
        String verificationToken = tokenIn(emailsTo(email, AccountEmails.VERIFICATION_SUBJECT).get(0),
                VERIFICATION_LINK);
        login(email, OLD_PASSWORD, "10.250.0.3").andExpect(status().isUnauthorized());

        confirm(requestAndGetToken(email), NEW_PASSWORD).andExpect(status().isNoContent());

        assertEquals(CLOCK.instant(), userRepository.findByEmail(email).orElseThrow().getEmailVerifiedAt());
        login(email, NEW_PASSWORD, "10.250.0.3").andExpect(status().isNoContent());
        postJson("/api/auth/email-verification", json(Map.of("token", verificationToken)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));
        assertEquals(0, activeTokensOf(email));
    }

    /** Una cuenta <b>heredada</b> (pendiente y sin ningún token, anterior a la V9) también queda verificada y entra. */
    @Test
    void unaCuentaHeredadaQuedaVerificadaYEntra() throws Exception {
        User legacy = createUser("tomas", Role.USER, false);

        confirm(requestAndGetToken(legacy.getEmail()), NEW_PASSWORD).andExpect(status().isNoContent());

        assertTrue(userRepository.findById(legacy.getId()).orElseThrow().isEmailVerified());
        login(legacy.getEmail(), NEW_PASSWORD, "10.250.0.4").andExpect(status().isNoContent());
    }

    /**
     * Restablecer cierra <b>todas</b> las sesiones anteriores: el refresh
     * token de cada una da 401 {@code SESSION_EXPIRED}.
     */
    @Test
    void restablecerRevocaTodasLasSesionesAnteriores() throws Exception {
        User user = createUser("ursula", Role.USER, true);
        String firstRefresh = refreshCookie(login(user.getEmail(), OLD_PASSWORD, "10.250.0.5")
                .andExpect(status().isNoContent()));
        login(user.getEmail(), OLD_PASSWORD, "10.250.0.6").andExpect(status().isNoContent());
        assertEquals(2, activeSessionsOf(user));

        confirm(requestAndGetToken(user.getEmail()), NEW_PASSWORD).andExpect(status().isNoContent());

        assertEquals(0, activeSessionsOf(user));
        mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "StreamBox")
                        .cookie(new Cookie("streambox_refresh", firstRefresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    /**
     * Una cuenta bloqueada por 5 fallos (el titular que agotó sus intentos, o
     * alguien que se los agotó a propósito) entra con la contraseña nueva en
     * cuanto la fija, desde cualquier IP: se borran sus fallos
     * ({@code clearFailures}). Sin eso, el login daría 429
     * {@code ACCOUNT_LOCKED} durante 15 minutos.
     */
    @Test
    void unaCuentaBloqueadaEntraConLaContrasenaNuevaNadaMasFijarla() throws Exception {
        User user = createUser("vicente", Role.USER, true);
        String email = user.getEmail();
        for (int i = 0; i < 4; i++) {
            login(email, "No-Es-La-Contrasena-" + i, "10.250.2.1").andExpect(status().isUnauthorized());
        }
        login(email, "No-Es-La-Contrasena-5", "10.250.2.1").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        login(email, OLD_PASSWORD, "10.250.2.2").andExpect(status().isTooManyRequests());

        confirm(requestAndGetToken(email), NEW_PASSWORD, "10.250.2.3").andExpect(status().isNoContent());

        verify(loginAttemptService).clearFailures(email);
        login(email, NEW_PASSWORD, "10.250.2.4").andExpect(status().isNoContent());
    }

    /**
     * Tras restablecer, la IP que confirmó queda como la <b>única</b> «IP
     * conocida» de la cuenta ({@code forgetKnownIpsExcept}): si después
     * alguien bloquea la cuenta con 5 fallos, el titular sigue entrando desde
     * ella, y una IP que era conocida antes (quizá la de un intruso que conocía
     * la contraseña vieja) ya no se libra del bloqueo.
     */
    @Test
    void laIpQueConfirmaQuedaComoLaUnicaConocida() throws Exception {
        User user = createUser("wanda", Role.USER, true);
        String email = user.getEmail();
        String before = "10.250.3.1";
        String confirmer = "10.250.3.2";
        login(email, OLD_PASSWORD, before).andExpect(status().isNoContent());

        confirm(requestAndGetToken(email), NEW_PASSWORD, confirmer).andExpect(status().isNoContent());
        verify(loginAttemptService).forgetKnownIpsExcept(email, confirmer);

        for (int i = 0; i < 5; i++) {
            login(email, "No-Es-La-Contrasena-" + i, "10.250.3.3");
        }
        login(email, NEW_PASSWORD, before).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        login(email, NEW_PASSWORD, confirmer).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // Una pendiente con un enlace vigente no se sustituye
    // ------------------------------------------------------------------

    /**
     * Alguien registra el email de la víctima; su enlace de verificación
     * caduca (24 h) y la cuenta pasaría a ser sustituible. La víctima pide
     * restablecer. Mientras ese enlace esté vigente, un registro nuevo con el
     * mismo email <b>no</b> borra la cuenta (si la borrara, el enlace que la
     * víctima tiene en el buzón dejaría de valer): la cuenta sigue, con su
     * id, y el enlace funciona.
     */
    @Test
    void unaPendienteConUnEnlaceDeRestablecimientoVigenteNoSeSustituyeConOtroRegistro() throws Exception {
        register("yago_cuenta", "yago" + DOMAIN);
        String email = "yago" + DOMAIN;
        Long id = userRepository.findByEmail(email).orElseThrow().getId();
        CLOCK.advance(Duration.ofHours(24).plusSeconds(1));
        String token = requestAndGetToken(email);

        register("otro_yago", email).andExpect(status().isAccepted());

        User after = userRepository.findByEmail(email).orElseThrow();
        assertEquals(id, after.getId(), "La cuenta no se ha sustituido");
        assertEquals("yago_cuenta", after.getUsername());
        confirm(token, NEW_PASSWORD).andExpect(status().isNoContent());
        login(email, NEW_PASSWORD, "10.250.0.7").andExpect(status().isNoContent());
    }

    /**
     * Lo mismo repitiendo el registro con el mismo email <b>y el mismo
     * nombre</b>: el nombre no da 409 (pasadas 24 h ya no lo conserva, como
     * con cualquier otro email: incidencia MEDIA-1), pero la cuenta tampoco
     * se borra, porque con su email decide la regla por email. El enlace del
     * titular sigue valiendo.
     */
    @Test
    void repetirElRegistroConElMismoEmailYNombreNoBorraUnaPendienteConUnEnlaceVigente() throws Exception {
        register("zoe_cuenta", "zoe" + DOMAIN);
        String email = "zoe" + DOMAIN;
        Long id = userRepository.findByEmail(email).orElseThrow().getId();
        pinCreatedAtToClock(email);
        CLOCK.advance(Duration.ofHours(24).plusSeconds(1));
        String token = requestAndGetToken(email);

        register("zoe_cuenta", email).andExpect(status().isAccepted());

        assertEquals(id, userRepository.findByEmail(email).orElseThrow().getId(), "La cuenta no se ha sustituido");
        confirm(token, NEW_PASSWORD).andExpect(status().isNoContent());
        login(email, NEW_PASSWORD, "10.250.0.8").andExpect(status().isNoContent());
    }

    /**
     * <b>Coste asumido de MEDIA-1.</b> Por el <b>nombre</b>, una pendiente
     * creada por el registro deja de conservarlo a las 24 h de su alta aunque
     * tenga un enlace de restablecimiento vigente: si alguien registra ese
     * nombre con otro email, la pendiente se borra y el enlace deja de valer.
     * El titular no pierde datos (una pendiente no ha podido entrar) y puede
     * volver a registrarse con su email, que ha quedado libre.
     */
    @Test
    void pasadas24HorasOtroEmailConElNombreBorraLaPendienteAunqueTengaUnEnlaceVigente() throws Exception {
        register("ugo_cuenta", "ugo" + DOMAIN);
        String email = "ugo" + DOMAIN;
        pinCreatedAtToClock(email);
        CLOCK.advance(Duration.ofHours(24).plusSeconds(1));
        String token = requestAndGetToken(email);

        register("ugo_cuenta", "otro.ugo" + DOMAIN).andExpect(status().isAccepted());

        assertTrue(userRepository.findByEmail(email).isEmpty());
        assertEquals("otro.ugo" + DOMAIN, userRepository.findByUsername("ugo_cuenta").orElseThrow().getEmail());
        confirm(token, NEW_PASSWORD).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"));
        register("ugo_otra_vez", email).andExpect(status().isAccepted());
        assertEquals("ugo_otra_vez", userRepository.findByEmail(email).orElseThrow().getUsername());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Crea una cuenta con nombre {@code <name>_cuenta} y email {@code <name>@restablecimiento.test}. */
    private User createUser(String name, Role role, boolean verified) {
        return createUser(name, name + "_cuenta", role, verified);
    }

    private User createUser(String name, String username, Role role, boolean verified) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(name + DOMAIN);
        user.setPassword(passwordEncoder.encode(OLD_PASSWORD));
        user.setRole(role);
        user.setEmailVerifiedAt(verified ? Instant.now() : null);
        return userRepository.saveAndFlush(user);
    }

    /**
     * Fija la fecha de alta de la cuenta al instante del reloj de los tests.
     *
     * <p>
     * Hibernate la pone con la hora <b>real</b> ({@code @CreationTimestamp}),
     * con milisegundos y después del BCrypt del registro, mientras que
     * {@link AdjustableClock} se queda parado en el segundo truncado de
     * {@code reset()}: la fecha de alta va hasta algo más de un segundo por
     * delante del reloj. Con el margen de 1 s de los tests que miran la regla
     * por nombre (24 h desde el alta), en una máquina cargada la pendiente aún
     * conservaba su nombre y el registro daba un 409 que en producción (un
     * solo reloj) no se da: el test fallaba de forma intermitente (QA). Así
     * las dos horas coinciden, como en producción, y el segundo de margen
     * sigue siendo exacto.
     * </p>
     */
    private void pinCreatedAtToClock(String email) {
        jdbc.update("UPDATE users SET created_at = ? WHERE email = ?", java.sql.Timestamp.from(CLOCK.instant()), email);
    }

    private ResultActions register(String username, String email) throws Exception {
        return postJson("/api/users", json(Map.of("username", username, "email", email, "password", OLD_PASSWORD)));
    }

    /** Pide el enlace y devuelve el token del correo que llega (el más reciente). */
    private String requestAndGetToken(String email) throws Exception {
        int before = resetEmailsTo(email).size();
        requestReset(email).andExpect(status().isAccepted());
        List<SentEmail> emails = resetEmailsTo(email);
        assertEquals(before + 1, emails.size(), emails::toString);
        return resetTokenIn(emails.get(0));
    }

    private ResultActions requestReset(String email) throws Exception {
        return postJson(RESET, json(Map.of("email", email)));
    }

    private ResultActions confirm(String token, String newPassword) throws Exception {
        return confirm(token, newPassword, "10.250.9.9");
    }

    private ResultActions confirm(String token, String newPassword, String ip) throws Exception {
        return mockMvc.perform(post(CONFIRM).with(remoteAddr(ip)).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("token", token, "newPassword", newPassword))));
    }

    private ResultActions login(String email, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/login").with(remoteAddr(ip)).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "password", password))));
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String json(Map<String, ?> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private List<SentEmail> resetEmailsTo(String email) {
        return emailsTo(email, AccountEmails.PASSWORD_RESET_SUBJECT);
    }

    private List<SentEmail> emailsTo(String email, String subject) {
        return inbox.sentTo(email).stream().filter(sent -> sent.subject().equals(subject)).toList();
    }

    private String storedHash(User user) {
        return jdbc.queryForObject("SELECT password FROM users WHERE id = ?", String.class, user.getId());
    }

    private long activeSessionsOf(User user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Long.class, user.getId());
    }

    private long activeTokensOf(String email) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM account_tokens t JOIN users u ON u.id = t.user_id"
                + " WHERE u.email = ? AND t.used_at IS NULL AND t.expires_at > ?", Long.class, email,
                java.sql.Timestamp.from(CLOCK.instant()));
    }

    private Map<?, ?> bodyOf(MockHttpServletResponse response) throws Exception {
        return objectMapper.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map.class);
    }

    /** Todas las cabeceras de una respuesta (nombre → valores), para compararlas. */
    private static Map<String, List<String>> headersOf(MockHttpServletResponse response) {
        Map<String, List<String>> headers = new HashMap<>();
        for (String name : response.getHeaderNames()) {
            headers.put(name, response.getHeaders(name));
        }
        return headers;
    }

    private static String refreshCookie(ResultActions login) {
        Cookie cookie = login.andReturn().getResponse().getCookie("streambox_refresh");
        assertTrue(cookie != null && !cookie.getValue().isEmpty(), "El login debía entregar el refresh token");
        return cookie.getValue();
    }

    private static String resetTokenIn(SentEmail email) {
        return tokenIn(email, RESET_LINK);
    }

    private static String tokenIn(SentEmail email, Pattern link) {
        Matcher matcher = link.matcher(email.body());
        assertTrue(matcher.find(), email.body());
        return matcher.group(1);
    }

    private static void assertInvalidToken(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"))
                .andExpect(jsonPath("$.message").value(InvalidResetTokenException.MESSAGE));
    }

    /** Cuerpo del error sin la fecha, que es lo único que cambia entre dos respuestas. */
    private ObjectNode errorBody(ResultActions result) throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(
                result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        body.remove("timestamp");
        return body;
    }

    private static RequestPostProcessor remoteAddr(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static RequestPostProcessor fromHost(String host) {
        return request -> {
            request.setServerName(host);
            return request;
        };
    }
}
