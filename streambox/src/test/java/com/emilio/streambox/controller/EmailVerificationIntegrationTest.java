package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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

import com.emilio.streambox.dto.AcceptedResponse;
import com.emilio.streambox.email.AccountEmails;
import com.emilio.streambox.email.MemoryEmailSender;
import com.emilio.streambox.email.MemoryEmailSender.SentEmail;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.InvalidVerificationTokenException;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.ratelimit.LoginAttemptService;
import com.emilio.streambox.support.AdjustableClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.servlet.http.Cookie;

/**
 * Confirmación del correo ({@code POST /api/auth/email-verification}) y
 * reenvío del enlace ({@code POST /api/auth/email-verification/resend}),
 * contra la base de datos y leyendo los correos de {@link MemoryEmailSender}.
 *
 * <p>
 * Lo que se comprueba, y por qué falla sin el cambio:
 * </p>
 * <ul>
 *   <li>Un token válido verifica la cuenta (204) y desde ese momento puede
 *       iniciar sesión; uno usado, caducado, inexistente o mal formado da
 *       <b>el mismo</b> 400 {@code INVALID_VERIFICATION_TOKEN}.</li>
 *   <li>Confirmar revoca todas las sesiones anteriores de la cuenta y olvida
 *       sus «IPs conocidas»: una cuenta heredada pudo iniciar sesión antes de la
 *       V9, y tras demostrar quién es el dueño del buzón no debe quedar ninguna
 *       sesión de antes.</li>
 *   <li>El reenvío responde siempre el mismo 202, invalida el enlace anterior y
 *       tiene un límite <b>silencioso</b> por cuenta (aquí el de producción: 3
 *       por hora y 60 s entre dos), igual que el aviso «Ya tienes una cuenta» del
 *       registro.</li>
 * </ul>
 *
 * <p>
 * Contexto propio: reloj {@link AdjustableClock} y los límites de correos de
 * producción ({@code application-test.properties} los desactiva para el
 * resto de suites). {@link LoginAttemptService} es un {@code @MockitoSpyBean}
 * (con su comportamiento real) para ver que se olvidan las IPs conocidas. Sin
 * {@code @Transactional}: los correos salen tras el commit.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "streambox.email.resend.max-per-window=3",
        "streambox.email.resend.window=1h",
        "streambox.email.resend.min-interval=60s"
})
class EmailVerificationIntegrationTest {

    private static final String DOMAIN = "@verificacion.test";
    private static final String PASSWORD = "Paseo-Por-El-Retiro-2026";
    private static final String VERIFY = "/api/auth/email-verification";
    private static final String RESEND = "/api/auth/email-verification/resend";
    private static final Pattern LINK = Pattern.compile(
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
    // Confirmar
    // ------------------------------------------------------------------

    /**
     * Token válido: 204 sin cuerpo, la cuenta queda verificada (con la hora
     * del reloj de la aplicación), el token gastado, y el login, que antes
     * daba el 401 {@code INVALID_CREDENTIALS} de un fallo (cuenta pendiente,
     * aunque la contraseña sea correcta), ya entra. Un segundo uso del mismo
     * token da el 400 de siempre.
     */
    @Test
    void conUnTokenValidoLaCuentaQuedaVerificadaYPuedeIniciarSesion() throws Exception {
        String token = registerAndGetToken("ana");
        login("ana" + DOMAIN).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        MockHttpServletResponse response = confirm(token).andExpect(status().isNoContent())
                .andReturn().getResponse();

        assertEquals("", response.getContentAsString());
        User user = userRepository.findByEmail("ana" + DOMAIN).orElseThrow();
        assertEquals(CLOCK.instant(), user.getEmailVerifiedAt());
        assertNotNull(jdbc.queryForObject("SELECT used_at FROM account_tokens WHERE user_id = ?", Object.class,
                user.getId()));
        login("ana" + DOMAIN).andExpect(status().isNoContent());
        assertInvalidToken(confirm(token));
    }

    /**
     * Un token caducado (justo a las 24 h: la vida es {@code expires_at > now})
     * da el 400 de siempre y no verifica la cuenta.
     */
    @Test
    void unTokenCaducadoDaElMismo400() throws Exception {
        String token = registerAndGetToken("bea");

        CLOCK.advance(Duration.ofHours(24));

        assertInvalidToken(confirm(token));
        assertNull(userRepository.findByEmail("bea" + DOMAIN).orElseThrow().getEmailVerifiedAt());
    }

    /** Un segundo antes de caducar, el token aún vale. */
    @Test
    void unSegundoAntesDeCaducarElTokenVale() throws Exception {
        String token = registerAndGetToken("bruno");

        CLOCK.advance(Duration.ofHours(24).minusSeconds(1));

        confirm(token).andExpect(status().isNoContent());
    }

    /**
     * Inexistente, mal formado o ausente: siempre el mismo cuerpo (salvo la
     * fecha) que un token inexistente con el formato correcto. Distinguirlos no
     * ayudaría al usuario (en todos tiene que pedir otro enlace) y sí a quien
     * prueba tokens. Ninguno olvida IPs conocidas.
     */
    @ParameterizedTest
    @MethodSource("invalidTokenBodies")
    void cualquierTokenQueNoSirveDaElMismo400(String requestBody) throws Exception {
        ObjectNode reference = errorBody(postJson(VERIFY, "{\"token\":\"" + UNKNOWN_TOKEN + "\"}"));

        ResultActions result = postJson(VERIFY, requestBody);

        assertInvalidToken(result);
        assertEquals(reference, errorBody(result));
        verify(loginAttemptService, never()).forgetKnownIps(anyString());
    }

    static Stream<String> invalidTokenBodies() {
        return Stream.of(
                "{\"token\":\"" + UNKNOWN_TOKEN + "\"}",
                "{}",
                "{\"token\":null}",
                "{\"token\":\"\"}",
                "{\"token\":\"   \"}",
                "{\"token\":\"abc\"}",
                "{\"token\":\"" + UNKNOWN_TOKEN.substring(1) + "\"}",
                "{\"token\":\"" + UNKNOWN_TOKEN + "A\"}",
                "{\"token\":\"" + UNKNOWN_TOKEN.substring(1) + "=\"}",
                "{\"token\":\"" + UNKNOWN_TOKEN.substring(1) + "+\"}",
                "{\"token\":\"" + UNKNOWN_TOKEN.substring(1) + "/\"}",
                "{\"token\":\"" + UNKNOWN_TOKEN.substring(1) + "ñ\"}",
                "{\"token\":\"" + "A".repeat(10_000) + "\"}");
    }

    /**
     * Confirmar revoca <b>todas</b> las sesiones anteriores de la cuenta y
     * olvida sus IPs conocidas. Se simula una cuenta heredada: inició sesión
     * (dos dispositivos) antes de la V9, que la dejó pendiente.
     */
    @Test
    void confirmarRevocaLasSesionesAnterioresYOlvidaLasIpsConocidas() throws Exception {
        User user = createVerifiedUser("carla");
        String firstRefresh = refreshCookie(login(user.getEmail()).andExpect(status().isNoContent()));
        login(user.getEmail()).andExpect(status().isNoContent());
        assertEquals(2, activeSessionsOf(user));
        jdbc.update("UPDATE users SET email_verified_at = NULL WHERE id = ?", user.getId());

        resend(user.getEmail()).andExpect(status().isAccepted());
        confirm(tokenIn(inbox.sentTo(user.getEmail()).get(0))).andExpect(status().isNoContent());

        assertEquals(0, activeSessionsOf(user));
        verify(loginAttemptService).forgetKnownIps(user.getEmail());
        mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "StreamBox")
                        .cookie(new Cookie("streambox_refresh", firstRefresh)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
    }

    // ------------------------------------------------------------------
    // Reenviar
    // ------------------------------------------------------------------

    /**
     * El reenvío responde exactamente igual (código y cuerpo) con un email sin
     * cuenta, con el de una cuenta confirmada y con el de una pendiente; solo la
     * pendiente recibe un correo.
     */
    @Test
    void elReenvioRespondeIgualExistaONoLaCuentaYSoloEnviaALasPendientes() throws Exception {
        createVerifiedUser("dani");
        registerAndGetToken("diana");
        CLOCK.advance(Duration.ofSeconds(61));
        inbox.clear();

        MockHttpServletResponse unknown = resend("nadie" + DOMAIN).andReturn().getResponse();
        MockHttpServletResponse verified = resend("dani" + DOMAIN).andReturn().getResponse();
        MockHttpServletResponse pending = resend("DIANA" + DOMAIN).andReturn().getResponse();

        for (MockHttpServletResponse response : List.of(unknown, verified, pending)) {
            assertEquals(202, response.getStatus());
            assertEquals(Map.of("message", AcceptedResponse.RESEND_MESSAGE),
                    objectMapper.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map.class));
        }
        assertTrue(inbox.sentTo("nadie" + DOMAIN).isEmpty());
        assertTrue(inbox.sentTo("dani" + DOMAIN).isEmpty());
        assertEquals(AccountEmails.VERIFICATION_SUBJECT, inbox.sentTo("diana" + DOMAIN).get(0).subject());
        assertEquals(1, inbox.all().size());
    }

    /** El reenvío invalida el enlace anterior: solo vale el del último correo. */
    @Test
    void elReenvioInvalidaElEnlaceAnterior() throws Exception {
        String first = registerAndGetToken("eva");
        CLOCK.advance(Duration.ofSeconds(61));

        resend("eva" + DOMAIN).andExpect(status().isAccepted());
        String second = tokenIn(inbox.sentTo("eva" + DOMAIN).get(0));

        assertNotEquals(first, second);
        assertInvalidToken(confirm(first));
        confirm(second).andExpect(status().isNoContent());
    }

    /**
     * Límite silencioso por cuenta: como mucho uno cada 60 s y 3 por hora (el
     * del registro cuenta). Pasado el límite, el mismo 202 y ningún correo (ni
     * se invalida el enlace que ya va de camino); cuando pasa la ventana, vuelve
     * a enviarse.
     */
    @Test
    void elReenvioTieneUnLimiteSilenciosoPorCuenta() throws Exception {
        String registrationToken = registerAndGetToken("fede");
        String email = "fede" + DOMAIN;

        resend(email).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(AcceptedResponse.RESEND_MESSAGE));
        assertEquals(1, inbox.sentTo(email).size(), "Antes de 60 s no se envía otro");

        CLOCK.advance(Duration.ofSeconds(61));
        resend(email).andExpect(status().isAccepted());
        CLOCK.advance(Duration.ofSeconds(61));
        resend(email).andExpect(status().isAccepted());
        assertEquals(3, inbox.sentTo(email).size());
        String third = tokenIn(inbox.sentTo(email).get(0));

        CLOCK.advance(Duration.ofSeconds(61));
        resend(email).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(AcceptedResponse.RESEND_MESSAGE));
        assertEquals(3, inbox.sentTo(email).size(), "El cuarto de la hora no se envía");

        CLOCK.advance(Duration.ofHours(1));
        resend(email).andExpect(status().isAccepted());
        assertEquals(4, inbox.sentTo(email).size());
        assertInvalidToken(confirm(registrationToken));
        assertInvalidToken(confirm(third));
        confirm(tokenIn(inbox.sentTo(email).get(0))).andExpect(status().isNoContent());
    }

    /**
     * El aviso «Ya tienes una cuenta» del registro también tiene su límite
     * silencioso: registrarse dos veces seguidas con el email de una cuenta
     * confirmada responde igual, pero solo manda un aviso.
     */
    @Test
    void elAvisoDeCuentaExistenteTambienTieneLimiteSilencioso() throws Exception {
        createVerifiedUser("gema");

        register("gema_1", "gema" + DOMAIN).andExpect(status().isAccepted());
        register("gema_2", "gema" + DOMAIN).andExpect(status().isAccepted());
        assertEquals(1, inbox.sentTo("gema" + DOMAIN).size());

        CLOCK.advance(Duration.ofSeconds(61));
        register("gema_3", "gema" + DOMAIN).andExpect(status().isAccepted());
        List<SentEmail> emails = inbox.sentTo("gema" + DOMAIN);
        assertEquals(2, emails.size());
        emails.forEach(sent -> assertEquals(AccountEmails.ALREADY_REGISTERED_SUBJECT, sent.subject()));
    }

    /**
     * Un email mal formado en el reenvío es un 400 {@code VALIDATION_ERROR}:
     * depende solo del texto enviado, no de si existe la cuenta.
     */
    @Test
    void unEmailMalFormadoEnElReenvioDa400DeValidacion() throws Exception {
        resend("esto-no-es-un-email")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.email").exists());
        assertTrue(inbox.all().isEmpty());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Registra una cuenta por HTTP y devuelve el token del correo que recibe. */
    private String registerAndGetToken(String name) throws Exception {
        register(name, name + DOMAIN).andExpect(status().isAccepted());
        List<SentEmail> emails = inbox.sentTo(name + DOMAIN);
        assertEquals(1, emails.size(), emails::toString);
        return tokenIn(emails.get(0));
    }

    private User createVerifiedUser(String name) {
        User user = new User();
        user.setUsername(name + "_verificada");
        user.setEmail(name + DOMAIN);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setEmailVerifiedAt(Instant.now());
        return userRepository.saveAndFlush(user);
    }

    private ResultActions register(String username, String email) throws Exception {
        return postJson("/api/users", objectMapper.writeValueAsString(
                Map.of("username", username, "email", email, "password", PASSWORD)));
    }

    private ResultActions confirm(String token) throws Exception {
        return postJson(VERIFY, objectMapper.writeValueAsString(Map.of("token", token)));
    }

    private ResultActions resend(String email) throws Exception {
        return postJson(RESEND, objectMapper.writeValueAsString(Map.of("email", email)));
    }

    private ResultActions login(String email) throws Exception {
        return postJson("/api/auth/login", objectMapper.writeValueAsString(
                Map.of("email", email, "password", PASSWORD)));
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long activeSessionsOf(User user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                Long.class, user.getId());
    }

    private static String refreshCookie(ResultActions login) {
        Cookie cookie = login.andReturn().getResponse().getCookie("streambox_refresh");
        assertTrue(cookie != null && !cookie.getValue().isEmpty(), "El login debía entregar el refresh token");
        return cookie.getValue();
    }

    private static String tokenIn(SentEmail email) {
        Matcher matcher = LINK.matcher(email.body());
        assertTrue(matcher.find(), email.body());
        return matcher.group(1);
    }

    private static void assertInvalidToken(ResultActions result) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"))
                .andExpect(jsonPath("$.message").value(InvalidVerificationTokenException.MESSAGE));
    }

    /** Cuerpo del error sin la fecha, que es lo único que cambia entre dos respuestas. */
    private ObjectNode errorBody(ResultActions result) throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(
                result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        body.remove("timestamp");
        return body;
    }
}
