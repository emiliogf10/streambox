package com.emilio.streambox.security.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.emilio.streambox.email.MemoryEmailSender;
import com.emilio.streambox.email.MemoryEmailSender.SentEmail;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.AuthCookieService;
import com.emilio.streambox.security.JwtProperties;
import com.emilio.streambox.support.AdjustableClock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.servlet.http.Cookie;

/**
 * Cookie de dispositivo {@code streambox_device} de extremo a extremo (plan
 * 2.5, «bloqueo de cuenta como arma»): un anónimo que agota los intentos de
 * una cuenta ya no deja fuera al navegador en el que el titular inició sesión,
 * aunque cambie de red, y la fuerza bruta sigue limitada.
 *
 * <p>
 * Sin la cookie, estos tests fallan: el titular recibe 429
 * {@code ACCOUNT_LOCKED} desde cualquier IP en la que no hubiera iniciado
 * sesión antes. Para que lo que se prueba sea la cookie y no las «IPs
 * conocidas», el login con cookie se hace siempre desde una IP sin ningún login
 * correcto previo (a menudo, la misma del atacante).
 * </p>
 *
 * <p>
 * Bloqueo de producción (5 fallos / 15 min). Reloj {@link AdjustableClock}
 * parado (hora real truncada al segundo): todos los {@code Retry-After} valen
 * exactamente la ventana y las respuestas se pueden comparar campo a campo.
 * Sin {@code @Transactional}: el cambio y el restablecimiento de la contraseña
 * confirman de verdad. Cada test usa cuentas propias (dominio {@value #DOMAIN})
 * e IPs propias.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "streambox.security.rate-limit.lockout.max-failures=5",
        "streambox.security.rate-limit.lockout.window=15m",
        "streambox.security.device-cookie.ttl=30d"
})
class DeviceCookieLoginIntegrationTest {

    private static final String DOMAIN = "@cookie-dispositivo.test";
    private static final String PASSWORD = "Contraseña-Del-Titular-2026";
    private static final String NEW_PASSWORD = "Otra-Clave-Muy-Distinta-2026!";
    private static final String DEVICE = AuthCookieService.DEVICE_COOKIE_NAME;
    private static final Pattern RESET_LINK = Pattern.compile("#token=([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    private static final AdjustableClock CLOCK = new AdjustableClock();

    @TestBean
    private Clock clock;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MemoryEmailSender inbox;
    @Autowired private DeviceCookieService deviceCookieService;
    @Autowired private JwtProperties jwtProperties;

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
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
    }

    // ------------------------------------------------------------------
    // (a) El titular entra pese al bloqueo
    // ------------------------------------------------------------------

    /**
     * La cuenta queda bloqueada por 5 fallos desde otra IP: sin cookie, la
     * contraseña correcta recibe 429 desde una IP nueva; con la cookie, el
     * mismo navegador entra desde esa IP (y desde la del atacante) y recibe la
     * cookie renovada.
     */
    @Test
    void conLaCuentaBloqueadaElNavegadorConCookieEntraDesdeCualquierRed() throws Exception {
        String email = createUser(true).getEmail();
        String device = deviceCookie(login(email, PASSWORD, nextIp(), null)
                .andExpect(status().isNoContent()).andReturn());
        String attacker = nextIp();
        String newNetwork = nextIp();

        lockFrom(email, attacker);

        assertLocked(login(email, PASSWORD, newNetwork, null));
        MvcResult entered = login(email, PASSWORD, newNetwork, device)
                .andExpect(status().isNoContent()).andReturn();
        assertNotNull(entered.getResponse().getCookie(AuthCookieService.COOKIE_NAME));
        String renewed = deviceCookie(entered);
        login(email, PASSWORD, attacker, renewed).andExpect(status().isNoContent());
        // Renovar conserva el dispositivo: la cookie anterior y la renovada
        // son del mismo navegador.
        User user = userRepository.findByEmail(email).orElseThrow();
        assertEquals(deviceCookieService.deviceIdFor(device, user), deviceCookieService.deviceIdFor(renewed, user));
    }

    // ------------------------------------------------------------------
    // (b) y (c) La fuerza bruta sigue limitada y no afecta a los demás
    // ------------------------------------------------------------------

    /**
     * Con cookie válida siguen bastando 5 fallos para bloquear ese
     * dispositivo (ni siquiera la contraseña correcta entra después), y esos
     * fallos no bloquean la cuenta para los demás: sin cookie, desde otra IP,
     * se entra con la contraseña correcta.
     */
    @Test
    void conCookieCincoFallosBloqueanEseDispositivoPeroNoLaCuenta() throws Exception {
        String email = createUser(true).getEmail();
        String device = deviceCookie(login(email, PASSWORD, nextIp(), null).andReturn());
        String thief = nextIp();

        for (int remaining = 4; remaining >= 1; remaining--) {
            login(email, "mal", thief, device)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.remainingAttempts").value(remaining));
        }
        assertLocked(login(email, "mal", thief, device));
        assertLocked(login(email, PASSWORD, thief, device));

        // La cuenta no ha gastado ni un intento: sin cookie, 4 restantes tras un fallo...
        String other = nextIp();
        login(email, "mal", other, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.remainingAttempts").value(4));
        // ...y la contraseña correcta entra.
        login(email, PASSWORD, other, null).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // (d) Revocación persistente con token_version
    // ------------------------------------------------------------------

    /** «Cerrar sesión en todos los dispositivos» anula las cookies anteriores. */
    @Test
    void cerrarSesionEnTodosLosDispositivosAnulaLasCookies() throws Exception {
        String email = createUser(true).getEmail();
        MvcResult session = login(email, PASSWORD, nextIp(), null).andReturn();
        String device = deviceCookie(session);

        MvcResult logoutAll = mockMvc.perform(post("/api/auth/logout-all")
                        .with(remoteAddr(nextIp()))
                        .cookie(new Cookie(AuthCookieService.COOKIE_NAME, accessCookie(session)))
                        .header("X-Requested-With", "StreamBox"))
                .andExpect(status().isNoContent()).andReturn();
        // No la borra (se anula en el servidor) ni entrega otra.
        assertTrue(setCookies(logoutAll.getResponse(), DEVICE).isEmpty());

        String attacker = nextIp();
        lockFrom(email, attacker);
        assertLocked(login(email, PASSWORD, attacker, device));
        assertLocked(login(email, PASSWORD, nextIp(), device));
    }

    /**
     * Cambiar la contraseña anula las cookies anteriores (también la de un
     * intruso) y entrega una nueva, con la versión ya subida, que sí vale.
     */
    @Test
    void cambiarLaContrasenaAnulaLasCookiesAnterioresYEntregaUnaValida() throws Exception {
        String email = createUser(true).getEmail();
        MvcResult owner = login(email, PASSWORD, nextIp(), null).andReturn();
        String ownerOldDevice = deviceCookie(owner);
        String intruderDevice = deviceCookie(login(email, PASSWORD, nextIp(), null).andReturn());

        MvcResult changed = mockMvc.perform(put("/api/users/me/password")
                        .with(remoteAddr(nextIp()))
                        .cookie(new Cookie(AuthCookieService.COOKIE_NAME, accessCookie(owner)))
                        .header("X-Requested-With", "StreamBox")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD))))
                .andExpect(status().isNoContent()).andReturn();
        String newDevice = deviceCookie(changed);
        assertDeviceCookieAttributes(changed.getResponse());

        String attacker = nextIp();
        lockFrom(email, attacker);
        assertLocked(login(email, NEW_PASSWORD, attacker, intruderDevice));
        assertLocked(login(email, NEW_PASSWORD, attacker, ownerOldDevice));
        login(email, NEW_PASSWORD, attacker, newDevice).andExpect(status().isNoContent());
    }

    /**
     * Restablecer la contraseña con el enlace del correo anula las cookies
     * anteriores y entrega una nueva válida (sin abrir sesión).
     */
    @Test
    void restablecerLaContrasenaAnulaLasCookiesAnterioresYEntregaUnaValida() throws Exception {
        String email = createUser(true).getEmail();
        String oldDevice = deviceCookie(login(email, PASSWORD, nextIp(), null).andReturn());

        mockMvc.perform(post("/api/auth/password-reset")
                        .with(remoteAddr(nextIp()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isAccepted());
        MvcResult confirmed = mockMvc.perform(post("/api/auth/password-reset/confirm")
                        .with(remoteAddr(nextIp()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("token", resetToken(email), "newPassword", NEW_PASSWORD))))
                .andExpect(status().isNoContent()).andReturn();
        // Solo la cookie de dispositivo: el restablecimiento no inicia sesión.
        assertTrue(setCookies(confirmed.getResponse(), AuthCookieService.COOKIE_NAME).isEmpty());
        assertTrue(setCookies(confirmed.getResponse(), AuthCookieService.REFRESH_COOKIE_NAME).isEmpty());
        String newDevice = deviceCookie(confirmed);
        assertDeviceCookieAttributes(confirmed.getResponse());

        String attacker = nextIp();
        lockFrom(email, attacker);
        assertLocked(login(email, NEW_PASSWORD, attacker, oldDevice));
        login(email, NEW_PASSWORD, attacker, newDevice).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // (e) Atributos
    // ------------------------------------------------------------------

    /**
     * HttpOnly, SameSite=Strict, Path=/api/auth, Max-Age = 30 días y Secure
     * (el perfil de test no lo desactiva). El logout no la borra.
     */
    @Test
    void laCookieLlevaLosAtributosDeSeguridadYElLogoutNoLaBorra() throws Exception {
        String email = createUser(true).getEmail();
        MvcResult result = login(email, PASSWORD, nextIp(), null).andExpect(status().isNoContent()).andReturn();

        assertDeviceCookieAttributes(result.getResponse());

        MvcResult logout = mockMvc.perform(post("/api/auth/logout")
                        .header("X-Requested-With", "StreamBox")
                        .cookie(new Cookie(DEVICE, deviceCookie(result))))
                .andExpect(status().isNoContent()).andReturn();
        assertTrue(setCookies(logout.getResponse(), DEVICE).isEmpty(),
                logout.getResponse().getHeaders(HttpHeaders.SET_COOKIE).toString());
    }

    // ------------------------------------------------------------------
    // (f) Cookie falsa o ajena = sin cookie
    // ------------------------------------------------------------------

    /**
     * Cookie falsificada, basura, caducada o de otra cuenta: gastan intentos
     * del contador de la cuenta igual que sin cookie (los intentos restantes
     * bajan de uno en uno), con respuestas idénticas a las de sin cookie, y
     * con la cuenta bloqueada no entran.
     */
    @Test
    void unaCookieFalsaOAjenaSeTrataComoAusente() throws Exception {
        User victim = createUser(true);
        String email = victim.getEmail();
        String otherAccountEmail = createUser(true).getEmail();
        String otherAccountDevice = deviceCookie(login(otherAccountEmail, PASSWORD, nextIp(), null).andReturn());
        // Auténtica, de esta cuenta y con su versión, pero emitida hace 31 días.
        String expired = new DeviceCookieService(jwtProperties, new DeviceCookieProperties(Duration.ofDays(30)),
                Clock.fixed(CLOCK.instant().minus(Duration.ofDays(31)), ZoneOffset.UTC))
                .issueNew(victim.getId(), 0).value();
        String forged = "v1." + "A".repeat(40) + "." + "B".repeat(43);
        String ip = nextIp();

        ObjectNode withoutCookie = errorBody(login(email, "mal", ip, null)
                .andExpect(status().isUnauthorized()));
        assertEquals(4, withoutCookie.get("remainingAttempts").asInt());

        List<String> fakes = List.of(forged, otherAccountDevice, "basura");
        for (int i = 0; i < fakes.size(); i++) {
            ObjectNode body = errorBody(login(email, "mal", ip, fakes.get(i))
                    .andExpect(status().isUnauthorized()));
            withoutCookie.put("remainingAttempts", 3 - i);
            assertEquals(withoutCookie, body, fakes.get(i));
        }

        // Quinto fallo: bloqueo. Ninguna cookie falsa, ajena o caducada entra.
        assertLocked(login(email, "mal", ip, null));
        MvcResult lockedWithout = login(email, PASSWORD, ip, null).andReturn();
        for (String fake : List.of(forged, otherAccountDevice, "basura", expired)) {
            MvcResult lockedWith = login(email, PASSWORD, ip, fake).andReturn();
            assertSameResponse(lockedWithout, lockedWith);
            assertEquals(429, lockedWith.getResponse().getStatus());
        }
    }

    /**
     * BAJA-2 de la revisión de la PR #33 (defensa en profundidad): una cookie
     * auténtica, de esta cuenta y de su versión actual no sirve si la cuenta
     * está pendiente de confirmar el correo. Se obtiene con un login correcto
     * y después la cuenta se deja pendiente en la base de datos sin tocar su
     * {@code token_version} (el único modo de que una pendiente tenga una
     * cookie que, por lo demás, valdría). Con la cuenta bloqueada y la
     * contraseña correcta, la respuesta con la cookie es idéntica a la de sin
     * ella (429 {@code ACCOUNT_LOCKED}, mismo {@code Retry-After}, sin
     * cookies). Sin la comprobación, la cookie llevaría el intento al contador
     * propio del dispositivo y respondería 401 con 4 intentos restantes.
     */
    @Test
    void unaCookieValidaDeUnaCuentaPendienteSeTrataComoAusente() throws Exception {
        String email = createUser(true).getEmail();
        String device = deviceCookie(login(email, PASSWORD, nextIp(), null)
                .andExpect(status().isNoContent()).andReturn());
        int versionBefore = userRepository.findByEmail(email).orElseThrow().getTokenVersion();
        jdbc.update("UPDATE users SET email_verified_at = NULL WHERE email = ?", email);
        User pending = userRepository.findByEmail(email).orElseThrow();
        assertFalse(pending.isEmailVerified());
        assertEquals(versionBefore, pending.getTokenVersion(), "misma versión: solo cambia la confirmación");
        lockFrom(email, nextIp());

        // Desde una IP sin ningún login correcto: lo que se prueba es la cookie.
        String ip = nextIp();
        MvcResult without = login(email, PASSWORD, ip, null).andReturn();
        MvcResult with = login(email, PASSWORD, ip, device).andReturn();

        assertEquals(429, with.getResponse().getStatus(), with.getResponse().getContentAsString());
        assertSameResponse(without, with);
    }

    /**
     * Un email inexistente responde igual con o sin cookie (basura o válida
     * de otra cuenta), e igual que un email registrado sin cookie: nunca hay
     * una cookie válida para una cuenta que no existe.
     */
    @Test
    void unEmailInexistenteRespondeIgualConOSinCookie() throws Exception {
        String registered = createUser(true).getEmail();
        String validDevice = deviceCookie(login(registered, PASSWORD, nextIp(), null).andReturn());
        String ip = nextIp();

        ObjectNode reference = errorBody(login("nadie1" + DOMAIN, "mal", ip, null));
        assertEquals(reference, errorBody(login("nadie2" + DOMAIN, "mal", ip, "basura")));
        assertEquals(reference, errorBody(login("nadie3" + DOMAIN, "mal", ip, validDevice)));
        assertEquals(reference, errorBody(login(createUser(true).getEmail(), "mal", ip, null)));
        assertEquals(4, reference.get("remainingAttempts").asInt());
    }

    // ------------------------------------------------------------------
    // (g) Cuándo NO se emite
    // ------------------------------------------------------------------

    /** Ni en un 401, ni en un 429, ni para una cuenta pendiente con la contraseña correcta. */
    @Test
    void noSeEmiteEnUnLoginFallidoNiParaUnaCuentaPendiente() throws Exception {
        String email = createUser(true).getEmail();
        String pending = createUser(false).getEmail();
        String ip = nextIp();

        MvcResult failed = login(email, "mal", ip, null).andExpect(status().isUnauthorized()).andReturn();
        assertTrue(setCookies(failed.getResponse(), DEVICE).isEmpty());

        MvcResult pendingResult = login(pending, PASSWORD, ip, null)
                .andExpect(status().isUnauthorized()).andReturn();
        assertTrue(pendingResult.getResponse().getHeaders(HttpHeaders.SET_COOKIE).isEmpty());

        // Otra cuenta, para empezar el bloqueo desde cero intentos.
        String lockedEmail = createUser(true).getEmail();
        lockFrom(lockedEmail, nextIp());
        MvcResult locked = login(lockedEmail, PASSWORD, nextIp(), null).andReturn();
        assertEquals(429, locked.getResponse().getStatus());
        assertTrue(locked.getResponse().getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Cinco fallos sin cookie desde la IP: 401 con 4..1 intentos restantes y el quinto, 429. */
    private void lockFrom(String email, String ip) throws Exception {
        for (int remaining = 4; remaining >= 1; remaining--) {
            login(email, "mal", ip, null)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.remainingAttempts").value(remaining));
        }
        assertLocked(login(email, "mal", ip, null));
    }

    private static void assertLocked(ResultActions result) throws Exception {
        result.andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        assertTrue(setCookies(result.andReturn().getResponse(), AuthCookieService.COOKIE_NAME).isEmpty());
    }

    private void assertSameResponse(MvcResult expected, MvcResult actual) throws Exception {
        MockHttpServletResponse a = expected.getResponse();
        MockHttpServletResponse b = actual.getResponse();
        assertEquals(a.getStatus(), b.getStatus());
        assertEquals(a.getHeader(HttpHeaders.RETRY_AFTER), b.getHeader(HttpHeaders.RETRY_AFTER));
        assertEquals(a.getHeaders(HttpHeaders.SET_COOKIE), b.getHeaders(HttpHeaders.SET_COOKIE));
        assertEquals(body(a), body(b));
    }

    private static void assertDeviceCookieAttributes(MockHttpServletResponse response) {
        List<String> headers = setCookies(response, DEVICE);
        assertEquals(1, headers.size(), headers.toString());
        String header = headers.get(0);
        assertTrue(header.contains("Path=/api/auth;"), header);
        assertTrue(header.contains("Max-Age=" + Duration.ofDays(30).toSeconds() + ";"), header);
        assertTrue(header.contains("HttpOnly"), header);
        assertTrue(header.contains("SameSite=Strict"), header);
        assertTrue(header.contains("Secure"), header);
        assertFalse(header.contains(DOMAIN), "sin el email en la cookie: " + header);
    }

    private ResultActions login(String email, String password, String ip, String deviceCookie) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/auth/login")
                .with(remoteAddr(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password)));
        if (deviceCookie != null) {
            request.cookie(new Cookie(DEVICE, deviceCookie));
        }
        return mockMvc.perform(request);
    }

    private User createUser(boolean verified) {
        String name = "dispositivo" + SEQUENCE.getAndIncrement();
        User user = new User();
        user.setEmailVerifiedAt(verified ? Instant.now() : null);
        user.setUsername(name);
        user.setEmail(name + DOMAIN);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user);
    }

    private String resetToken(String email) {
        List<SentEmail> emails = inbox.sentTo(email);
        assertEquals(1, emails.size(), emails::toString);
        Matcher matcher = RESET_LINK.matcher(emails.get(0).body());
        assertTrue(matcher.find(), emails.get(0).body());
        return matcher.group(1);
    }

    private static String deviceCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(DEVICE);
        assertNotNull(cookie, "la respuesta no entrega la cookie de dispositivo: "
                + result.getResponse().getHeaders(HttpHeaders.SET_COOKIE));
        assertNotEquals("", cookie.getValue());
        return cookie.getValue();
    }

    private static String accessCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(AuthCookieService.COOKIE_NAME);
        assertNotNull(cookie, "el login no fija la cookie de sesión");
        return cookie.getValue();
    }

    private static List<String> setCookies(MockHttpServletResponse response, String name) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(name + "="))
                .toList();
    }

    private ObjectNode errorBody(ResultActions result) throws Exception {
        return body(result.andReturn().getResponse());
    }

    /** Cuerpo del error sin la fecha, que es lo único que cambia entre dos respuestas. */
    private ObjectNode body(MockHttpServletResponse response) throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        body.remove("timestamp");
        return body;
    }

    private static String nextIp() {
        int n = IP_SEQUENCE.getAndIncrement();
        return "10.251." + (n / 250) + "." + (n % 250 + 1);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor remoteAddr(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
