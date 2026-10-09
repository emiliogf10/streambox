package com.emilio.streambox.security.refresh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.RefreshToken;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.RefreshTokenRepository;
import com.emilio.streambox.repository.UserRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.Cookie;

/**
 * Token de acceso de vida corta + refresh token rotatorio y revocable (tarea
 * 29), de punta a punta: login, {@code POST /api/auth/refresh} y logout, con
 * las cookies y las filas de {@code refresh_tokens}.
 *
 * <p>
 * El {@link Clock} de la aplicación se sustituye con {@link TestBean} por un
 * reloj manual: los JWT, los refresh tokens y la gracia usan el mismo reloj, así
 * que se puede comprobar la caducidad (15 min, 7 días, 30 días) y la gracia de
 * 10 s sin esperar. Sustituir el reloj da a la clase un contexto propio.
 * </p>
 *
 * <p>
 * Sin {@code @Transactional}: el refresh confirma de verdad (la revocación de
 * la familia por reutilización debe confirmarse aunque la respuesta sea un
 * 401, y eso es justo lo que se prueba). Los usuarios de la clase (dominio
 * {@value #DOMAIN}) se borran antes y después de cada test y sus tokens caen en
 * cascada.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = "streambox.auth.cookie.secure=true")
class RefreshTokenIntegrationTest {

    private static final String DOMAIN = "@refresh.test";
    private static final String PASSWORD = "Contraseña-Del-Refresh-2026";
    private static final String ACCESS = "streambox_token";
    private static final String REFRESH = "streambox_refresh";

    private static final ManualClock CLOCK = new ManualClock();

    @TestBean
    private Clock clock;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RefreshTokenService refreshTokenService;

    /** Fábrica del bean {@code clock} que usa {@link TestBean} (mismo nombre que el campo). */
    static Clock clock() {
        return CLOCK;
    }

    @BeforeEach
    @AfterEach
    void deleteUsersOfThisSuite() {
        userRepository.deleteAll(userRepository.findAll().stream()
                .filter(user -> user.getEmail().endsWith(DOMAIN))
                .toList());
    }

    // ------------------------------------------------------------------
    // Login
    // ------------------------------------------------------------------

    @Test
    void elLoginEntregaLasDosCookiesConSusAtributos() throws Exception {
        User user = createUser("login");
        Instant now = CLOCK.instant();

        MvcResult result = login(user);

        String access = setCookie(result, ACCESS);
        assertTrue(access.contains("Path=/api;"), access);
        assertTrue(access.contains("Max-Age=900;"), access);
        assertSessionAttributes(access);

        String refresh = setCookie(result, REFRESH);
        assertTrue(refresh.contains("Path=/api/auth;"), refresh);
        assertTrue(refresh.contains("Max-Age=" + Duration.ofDays(7).toSeconds() + ";"), refresh);
        assertSessionAttributes(refresh);

        // Token opaco de 32 bytes en Base64URL sin relleno; en la base de datos
        // solo está su SHA-256 (64 hex), nunca el valor en claro.
        String value = cookie(result, REFRESH);
        assertTrue(value.matches("[A-Za-z0-9_-]{43}"), value);
        TokenState state = state(value);
        assertNotNull(state);
        assertEquals(now.plus(Duration.ofDays(7)), state.expiresAt());
        assertEquals(now.plus(Duration.ofDays(30)), state.familyExpiresAt());
        assertNull(state.revokedAt());
        assertTrue(refreshTokenRepository.findAll().stream().noneMatch(t -> t.getTokenHash().equals(value)));

        assertEquals("", result.getResponse().getContentAsString());
    }

    /** Cada login abre su propia familia (un dispositivo no cierra la sesión de otro). */
    @Test
    void cadaLoginAbreUnaFamiliaDistinta() throws Exception {
        User user = createUser("familias");

        TokenState first = state(cookie(login(user), REFRESH));
        TokenState second = state(cookie(login(user), REFRESH));

        assertNotEquals(first.familyId(), second.familyId());
    }

    // ------------------------------------------------------------------
    // Refresh correcto
    // ------------------------------------------------------------------

    @Test
    void unRefreshValidoRotaYEntregaCookiesNuevas() throws Exception {
        User user = createUser("rota");
        String r1 = cookie(login(user), REFRESH);
        CLOCK.advance(Duration.ofMinutes(1));
        Instant now = CLOCK.instant();

        MvcResult result = refresh(r1).andExpectStatus(204);

        String r2 = cookie(result, REFRESH);
        assertNotEquals(r1, r2);
        assertTrue(setCookie(result, ACCESS).contains("Max-Age=900;"));
        assertTrue(setCookie(result, REFRESH).contains("Path=/api/auth;"));
        assertEquals("", result.getResponse().getContentAsString());

        // El viejo queda revocado y enlazado con su sucesor, de la misma familia.
        TokenState old = state(r1);
        TokenState successor = state(r2);
        assertEquals(now, old.revokedAt());
        assertEquals(successor.id(), old.replacedById());
        assertEquals(old.familyId(), successor.familyId());
        assertNull(successor.revokedAt());
        assertEquals(now.plus(Duration.ofDays(7)), successor.expiresAt());
        assertEquals(old.familyExpiresAt(), successor.familyExpiresAt());

        // El JWT nuevo sirve.
        mockMvc.perform(get("/api/users/me").cookie(new Cookie(ACCESS, cookie(result, ACCESS))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.getEmail()));
    }

    /**
     * El caso que justifica todo: con el JWT de acceso caducado, la API da 401;
     * con el refresh se obtiene uno nuevo sin contraseña y la ruta vuelve a
     * funcionar.
     */
    @Test
    void conElAccesoCaducadoLaApiDa401YElRefreshLaRecupera() throws Exception {
        User user = createUser("caduca");
        MvcResult login = login(user);
        Cookie access = new Cookie(ACCESS, cookie(login, ACCESS));
        mockMvc.perform(get("/api/users/me").cookie(access)).andExpect(status().isOk());

        CLOCK.advance(Duration.ofMinutes(16));

        mockMvc.perform(get("/api/users/me").cookie(access)).andExpect(status().isUnauthorized());
        MvcResult refreshed = refresh(cookie(login, REFRESH)).andExpectStatus(204);
        mockMvc.perform(get("/api/users/me").cookie(new Cookie(ACCESS, cookie(refreshed, ACCESS))))
                .andExpect(status().isOk());
    }

    /**
     * El refresh solo mira la cookie {@code streambox_refresh}: un JWT de
     * acceso válido (cookie o Bearer) no lo sustituye, y un Bearer basura no
     * lo estropea.
     */
    @Test
    void elRefreshSoloLeeLaCookieDelRefresh() throws Exception {
        User user = createUser("solocookie");
        MvcResult login = login(user);
        String access = cookie(login, ACCESS);

        mockMvc.perform(withCsrf(post("/api/auth/refresh"))
                        .cookie(new Cookie(ACCESS, access))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));

        mockMvc.perform(withCsrf(post("/api/auth/refresh"))
                        .cookie(new Cookie(REFRESH, cookie(login, REFRESH)))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer basura"))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // Refresh rechazado: 401 SESSION_EXPIRED y cookies borradas
    // ------------------------------------------------------------------

    @Test
    void sinCookieDeRefreshDa401YBorraLasDosCookies() throws Exception {
        MvcResult result = refresh(null).andExpectStatus(401);

        assertSessionExpired(result);
    }

    /** Un valor desconocido o que no tiene forma de token: mismo 401 (sin consultar la BD si no tiene forma). */
    @ParameterizedTest
    @MethodSource("unknownTokens")
    void unRefreshDesconocidoOMalFormadoDa401(String value) throws Exception {
        MvcResult result = refresh(value).andExpectStatus(401);

        assertSessionExpired(result);
    }

    @Test
    void unRefreshCaducadoDa401() throws Exception {
        String r1 = cookie(login(createUser("caducado")), REFRESH);

        CLOCK.advance(Duration.ofDays(7));

        assertSessionExpired(refresh(r1).andExpectStatus(401));
    }

    /**
     * La familia tiene un tope absoluto de 30 días: por mucho que se rote, al
     * llegar se acabó, y las últimas cookies duran solo lo que le queda.
     */
    @Test
    void rotarNoAlargaLaSesionMasAllaDe30Dias() throws Exception {
        String token = cookie(login(createUser("tope")), REFRESH);

        long[] expectedMaxAge = {
                Duration.ofDays(7).toSeconds(), Duration.ofDays(7).toSeconds(),
                Duration.ofDays(7).toSeconds(), Duration.ofDays(6).toSeconds() };
        for (long maxAge : expectedMaxAge) {
            CLOCK.advance(Duration.ofDays(6));
            MvcResult result = refresh(token).andExpectStatus(204);
            assertTrue(setCookie(result, REFRESH).contains("Max-Age=" + maxAge + ";"), setCookie(result, REFRESH));
            token = cookie(result, REFRESH);
        }

        // Día 30 desde el login: la familia ha caducado aunque el token no.
        CLOCK.advance(Duration.ofDays(6));
        assertSessionExpired(refresh(token).andExpectStatus(401));
    }

    @Test
    void siElUsuarioSeBorraElRefreshDa401() throws Exception {
        User user = createUser("borrado");
        String r1 = cookie(login(user), REFRESH);

        userRepository.delete(user);

        assertSessionExpired(refresh(r1).andExpectStatus(401));
    }

    /**
     * Sin {@code X-Requested-With: StreamBox}, 403 {@code CSRF_REJECTED} y el
     * token sigue intacto (ni se mira): el navegador adjunta la cookie solo, y
     * el refresh tiene efecto.
     */
    @Test
    void sinLaCabeceraCsrfDa403YNoRota() throws Exception {
        String r1 = cookie(login(createUser("csrf")), REFRESH);

        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie(REFRESH, r1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        mockMvc.perform(post("/api/auth/refresh").cookie(new Cookie(REFRESH, r1))
                        .header("X-Requested-With", "XMLHttpRequest"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));

        assertNull(state(r1).revokedAt());
        refresh(r1).andExpectStatus(204);
    }

    // ------------------------------------------------------------------
    // Reutilización y gracia
    // ------------------------------------------------------------------

    /**
     * Presentar un token ya rotado fuera de la gracia es la señal de un robo:
     * 401 y se revoca la familia entera, también el sucesor que tenga el
     * legítimo (o el ladrón). Se avisa a WARN con usuario y familia, nunca con
     * el token ni su hash. A los 10 s exactos ya no hay gracia.
     */
    @Test
    void reutilizarUnTokenRotadoFueraDeLaGraciaRevocaLaFamilia() throws Exception {
        User user = createUser("robo");
        String r1 = cookie(login(user), REFRESH);
        String r2 = cookie(refresh(r1).andExpectStatus(204), REFRESH);
        CLOCK.advance(Duration.ofSeconds(10));

        ListAppender<ILoggingEvent> logs = captureLogs();
        MvcResult reuse;
        try {
            reuse = refresh(r1).andExpectStatus(401);
        } finally {
            stopCapturing(logs);
        }

        assertSessionExpired(reuse);
        assertNotNull(state(r2).revokedAt(), "el sucesor también queda revocado");
        assertSessionExpired(refresh(r2).andExpectStatus(401));

        UUID family = state(r1).familyId();
        List<ILoggingEvent> warnings = logs.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertEquals(1, warnings.size(), logs.list.toString());
        String message = warnings.get(0).getFormattedMessage();
        assertTrue(message.contains(family.toString()), message);
        assertTrue(message.contains(String.valueOf(user.getId())), message);
        for (String secret : List.of(r1, r2, RefreshTokenService.sha256Hex(r1), RefreshTokenService.sha256Hex(r2))) {
            assertFalse(message.contains(secret), "el log no puede contener tokens ni hashes: " + message);
        }
    }

    /**
     * Dos pestañas que renuevan a la vez: la segunda llega con el token que la
     * primera acaba de rotar. Dentro de la gracia recibe solo un JWT nuevo, sin
     * rotar ni {@code Set-Cookie} del refresh (el navegador ya tiene el
     * sucesor), y la sesión sigue viva.
     */
    @Test
    void dentroDeLaGraciaSoloSeEntregaUnAccesoNuevo() throws Exception {
        User user = createUser("pestanas");
        String r1 = cookie(login(user), REFRESH);
        String r2 = cookie(refresh(r1).andExpectStatus(204), REFRESH);
        CLOCK.advance(Duration.ofSeconds(9));

        MvcResult second = refresh(r1).andExpectStatus(204);

        List<String> setCookies = second.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertEquals(1, setCookies.size(), setCookies.toString());
        assertTrue(setCookies.get(0).startsWith(ACCESS + "="), setCookies.toString());
        mockMvc.perform(get("/api/users/me").cookie(new Cookie(ACCESS, cookie(second, ACCESS))))
                .andExpect(status().isOk());

        // No se ha rotado ni revocado nada: la familia tiene los mismos dos
        // tokens y el sucesor sigue sirviendo.
        assertEquals(2, tokensOfFamily(state(r1).familyId()));
        assertNull(state(r2).revokedAt());
        refresh(r2).andExpectStatus(204);
    }

    /**
     * La gracia exige que el sucesor siga vivo: si la sesión ya se cerró
     * (logout con el sucesor), presentar el viejo dentro de los 10 s da 401.
     */
    @Test
    void enLaGraciaPeroConLaSesionCerradaDa401() throws Exception {
        String r1 = cookie(login(createUser("cerrada")), REFRESH);
        String r2 = cookie(refresh(r1).andExpectStatus(204), REFRESH);
        logout(r2);

        assertSessionExpired(refresh(r1).andExpectStatus(401));
    }

    // ------------------------------------------------------------------
    // Logout
    // ------------------------------------------------------------------

    /** El logout revoca la familia: un refresh posterior con esa cookie da 401. */
    @Test
    void elLogoutRevocaLaFamilia() throws Exception {
        User user = createUser("logout");
        MvcResult login = login(user);
        String r1 = cookie(login, REFRESH);
        String r2 = cookie(refresh(r1).andExpectStatus(204), REFRESH);

        MvcResult result = logout(r2);

        assertClearsBothCookies(result);
        assertNotNull(state(r2).revokedAt());
        assertSessionExpired(refresh(r2).andExpectStatus(401));
        // Otra sesión del mismo usuario (otro dispositivo) no se ve afectada.
        String other = cookie(login(user), REFRESH);
        refresh(other).andExpectStatus(204);
    }

    /** Público e idempotente: sin cookie, con basura o con un token desconocido, 204. */
    @ParameterizedTest
    @MethodSource("unknownTokens")
    void elLogoutSinSesionValidaResponde204(String value) throws Exception {
        assertClearsBothCookies(logout(value));
        assertClearsBothCookies(logout(value));
    }

    @Test
    void elLogoutSinCookieResponde204() throws Exception {
        assertClearsBothCookies(logout(null));
        assertClearsBothCookies(logout(null));
    }

    /**
     * Sin {@code X-Requested-With: StreamBox} (o con otro valor), el logout da
     * 403 {@code CSRF_REJECTED}, no revoca nada y su respuesta no trae ningún
     * {@code Set-Cookie}: antes, un formulario {@code POST} de otra web (que
     * con {@code SameSite=Strict} llega sin cookies) recibía 204 con los
     * {@code Set-Cookie} de borrado y la víctima perdía la sesión. La sesión
     * sigue viva: el refresh posterior con la misma cookie da 204.
     */
    @Test
    void elLogoutSinLaCabeceraCsrfDa403YNoCierraLaSesion() throws Exception {
        User user = createUser("logoutcsrf");
        MvcResult login = login(user);
        String r1 = cookie(login, REFRESH);
        Cookie access = new Cookie(ACCESS, cookie(login, ACCESS));

        // Como llegaría el formulario de otra web (sin cookies), como lo enviaría
        // un navegador del mismo sitio sin la cabecera y con un valor distinto.
        List<MockHttpServletRequestBuilder> rejected = List.of(
                post("/api/auth/logout"),
                post("/api/auth/logout").cookie(access, new Cookie(REFRESH, r1)),
                post("/api/auth/logout").cookie(access, new Cookie(REFRESH, r1))
                        .header("X-Requested-With", "XMLHttpRequest"));
        for (MockHttpServletRequestBuilder request : rejected) {
            MvcResult result = mockMvc.perform(request)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CSRF_REJECTED"))
                    .andReturn();
            assertTrue(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).isEmpty(),
                    result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).toString());
        }

        assertNull(state(r1).revokedAt(), "sin la cabecera no se revoca la sesión");
        refresh(r1).andExpectStatus(204);
    }

    /**
     * La cabecera se exige también con {@code Authorization: Bearer} (como en
     * el refresh): el logout no autentica con el Bearer, así que su presencia
     * no cambia la regla. Con un JWT válido y sin la cabecera, 403 y la sesión
     * sigue viva; con la cabecera, el logout funciona igual que siempre.
     */
    @Test
    void elLogoutConBearerTambienExigeLaCabeceraCsrf() throws Exception {
        User user = createUser("logoutbearer");
        MvcResult login = login(user);
        String r1 = cookie(login, REFRESH);
        String bearer = "Bearer " + cookie(login, ACCESS);

        MvcResult rejected = mockMvc.perform(post("/api/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .cookie(new Cookie(REFRESH, r1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"))
                .andReturn();
        assertTrue(rejected.getResponse().getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
        assertNull(state(r1).revokedAt());

        MvcResult accepted = mockMvc.perform(withCsrf(post("/api/auth/logout"))
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .cookie(new Cookie(REFRESH, r1)))
                .andExpect(status().isNoContent())
                .andReturn();
        assertClearsBothCookies(accepted);
        assertNotNull(state(r1).revokedAt());
    }

    /**
     * Valores de cookie que no son un token vivo: uno con el formato correcto
     * (43 caracteres Base64URL) que no existe, y otros sin ese formato
     * (corto, largo, con relleno, con caracteres que no son Base64URL).
     */
    static java.util.stream.Stream<String> unknownTokens() {
        return java.util.stream.Stream.of("A".repeat(43), "A".repeat(44), "A".repeat(42) + "=",
                "A".repeat(42) + "+", "basura", "a.b.c");
    }

    // ------------------------------------------------------------------
    // Limpieza
    // ------------------------------------------------------------------

    /**
     * {@code deleteExpired()} borra las familias caducadas y los tokens
     * caducados hace más de la retención (3 días), y conserva los caducados
     * recientes de una familia viva (delatan una reutilización) y los activos.
     */
    @Test
    void laLimpiezaRespetaLaRetencion() throws Exception {
        User user = createUser("limpieza");
        Instant now = CLOCK.instant();
        UUID alive = UUID.randomUUID();
        Instant aliveUntil = now.plus(Duration.ofDays(10));
        long familyExpired = insert(user, UUID.randomUUID(), now.minus(Duration.ofDays(31)),
                now.minus(Duration.ofDays(24)), now.minus(Duration.ofSeconds(1)));
        long expiredLongAgo = insert(user, alive, now.minus(Duration.ofDays(11)),
                now.minus(Duration.ofDays(4)), aliveUntil);
        long expiredRecently = insert(user, alive, now.minus(Duration.ofDays(8)),
                now.minus(Duration.ofDays(1)), aliveUntil);
        long active = insert(user, alive, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(6)), aliveUntil);

        int deleted = refreshTokenService.deleteExpired();

        // Al menos estos dos: la base H2 es compartida por todos los contextos
        // de la suite y puede borrar también restos caducados de otras clases.
        assertTrue(deleted >= 2, "borrados: " + deleted);
        assertFalse(refreshTokenRepository.existsById(familyExpired));
        assertFalse(refreshTokenRepository.existsById(expiredLongAgo));
        assertTrue(refreshTokenRepository.existsById(expiredRecently));
        assertTrue(refreshTokenRepository.existsById(active));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private User createUser(String name) {
        User user = new User();
        user.setUsername(name + "-refresh");
        user.setEmail(name + DOMAIN);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user);
    }

    private MvcResult login(User user) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Requested-With", "StreamBox")
                        .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent())
                .andReturn();
    }

    private Expect refresh(String refreshToken) throws Exception {
        MockHttpServletRequestBuilder request = withCsrf(post("/api/auth/refresh"));
        if (refreshToken != null) {
            request.cookie(new Cookie(REFRESH, refreshToken));
        }
        return new Expect(mockMvc.perform(request).andReturn());
    }

    private MvcResult logout(String refreshToken) throws Exception {
        MockHttpServletRequestBuilder request = withCsrf(post("/api/auth/logout"));
        if (refreshToken != null) {
            request.cookie(new Cookie(REFRESH, refreshToken));
        }
        return mockMvc.perform(request).andExpect(status().isNoContent()).andReturn();
    }

    private static MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) {
        return request.header("X-Requested-With", "StreamBox");
    }

    /** Resultado con una comprobación de estado que muestra el cuerpo si falla. */
    private record Expect(MvcResult result) {
        MvcResult andExpectStatus(int expected) throws Exception {
            assertEquals(expected, result.getResponse().getStatus(), result.getResponse().getContentAsString());
            return result;
        }
    }

    /** Valor de la cookie con ese nombre que fija la respuesta. */
    private static String cookie(MvcResult result, String name) {
        Cookie cookie = result.getResponse().getCookie(name);
        assertNotNull(cookie, "la respuesta no fija la cookie " + name);
        return cookie.getValue();
    }

    /** Cabecera {@code Set-Cookie} de esa cookie (exactamente una). */
    private static String setCookie(MvcResult result, String name) {
        List<String> matching = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(name + "="))
                .toList();
        assertEquals(1, matching.size(), "Set-Cookie de " + name + ": " + matching);
        return matching.get(0);
    }

    private static void assertSessionAttributes(String setCookie) {
        assertTrue(setCookie.contains("HttpOnly"), setCookie);
        assertTrue(setCookie.contains("Secure"), setCookie);
        assertTrue(setCookie.contains("SameSite=Strict"), setCookie);
    }

    /** 401 {@code SESSION_EXPIRED} con el mensaje único y las dos cookies borradas. */
    private static void assertSessionExpired(MvcResult result) throws Exception {
        assertEquals(401, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("\"code\":\"SESSION_EXPIRED\""), body);
        assertTrue(body.contains(com.emilio.streambox.exception.SessionExpiredException.MESSAGE), body);
        assertClearsBothCookies(result);
    }

    private static void assertClearsBothCookies(MvcResult result) {
        String access = setCookie(result, ACCESS);
        String refresh = setCookie(result, REFRESH);
        assertTrue(access.startsWith(ACCESS + "=;") && access.contains("Max-Age=0") && access.contains("Path=/api;"),
                access);
        assertTrue(refresh.startsWith(REFRESH + "=;") && refresh.contains("Max-Age=0")
                && refresh.contains("Path=/api/auth;"), refresh);
        assertSessionAttributes(access);
        assertSessionAttributes(refresh);
    }

    /** Estado de un token en la base de datos (leído en una transacción, como exige el repositorio). */
    private record TokenState(Long id, UUID familyId, Instant expiresAt, Instant familyExpiresAt,
            Instant revokedAt, Long replacedById) {
    }

    private TokenState state(String value) {
        return new TransactionTemplate(transactionManager).execute(status -> refreshTokenRepository
                .findByTokenHashForUpdate(RefreshTokenService.sha256Hex(value))
                .map(t -> new TokenState(t.getId(), t.getFamilyId(), t.getExpiresAt(), t.getFamilyExpiresAt(),
                        t.getRevokedAt(), t.getReplacedBy() == null ? null : t.getReplacedBy().getId()))
                .orElse(null));
    }

    private long tokensOfFamily(UUID familyId) {
        return refreshTokenRepository.findAll().stream().filter(t -> familyId.equals(t.getFamilyId())).count();
    }

    private long insert(User user, UUID family, Instant createdAt, Instant expiresAt, Instant familyExpiresAt) {
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(RefreshTokenService.sha256Hex(UUID.randomUUID().toString()));
        token.setFamilyId(family);
        token.setCreatedAt(createdAt);
        token.setExpiresAt(expiresAt);
        token.setFamilyExpiresAt(familyExpiresAt);
        return refreshTokenRepository.save(token).getId();
    }

    private static ListAppender<ILoggingEvent> captureLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(RefreshTokenService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void stopCapturing(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(RefreshTokenService.class)).detachAppender(appender);
    }
}
