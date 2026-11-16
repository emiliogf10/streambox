package com.emilio.streambox.security.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.emilio.streambox.email.MemoryEmailSender;
import com.emilio.streambox.email.MemoryEmailSender.SentEmail;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.AuthCookieService;
import com.emilio.streambox.support.AdjustableClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.servlet.http.Cookie;

/**
 * Revisión independiente (QA) de la cookie de dispositivo
 * {@code streambox_device}: intentos de saltarse el límite de 5 fallos, de
 * revivir cookies revocadas y de provocar errores con valores raros. Completa
 * {@code DeviceCookieLoginIntegrationTest} (que cubre el camino feliz) sin
 * repetirlo.
 *
 * <ul>
 *   <li><b>Concurrencia:</b> muchas peticiones de login simultáneas con la
 *       misma cookie válida no comprueban más de 5 contraseñas; mezclarlas
 *       con peticiones sin cookie no da más que 5 + 5.</li>
 *   <li><b>Renovación:</b> la cookie renovada y la anterior son el mismo
 *       dispositivo y comparten contador (renovar no multiplica el
 *       presupuesto).</li>
 *   <li><b>Revocación:</b> confirmar el correo anula una cookie anterior; un
 *       login correcto con una cookie revocada entrega un dispositivo
 *       <b>nuevo</b>, no resucita el anterior.</li>
 *   <li><b>Robustez HTTP:</b> cookies duplicadas, valores con muchos puntos o
 *       caracteres raros y la cookie en otras rutas de {@code /api/auth}: nunca
 *       un 500 y, si no vale, respuesta idéntica a la de sin cookie.</li>
 * </ul>
 *
 * <p>
 * Mismos límites que producción (5 fallos / 15 min) y reloj parado para que
 * los {@code Retry-After} se puedan comparar. Sin {@code @Transactional}: la
 * confirmación del correo y el logout-all confirman de verdad; cada test usa
 * cuentas (dominio {@value #DOMAIN}) e IPs propias.
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
class DeviceCookieAbuseQaIntegrationTest {

    private static final String DOMAIN = "@cookie-dispositivo-qa.test";
    private static final String PASSWORD = "Contraseña-Del-Titular-QA-2026";
    private static final String DEVICE = AuthCookieService.DEVICE_COOKIE_NAME;
    private static final Pattern LINK = Pattern.compile("#token=([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");
    private static final String EXHAUSTED_PREFIX = "Has superado";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    /**
     * Reloj ajustable de {@code DeviceCookieLoginIntegrationTest}: se usa
     * <b>su</b> fábrica (no una propia) para que las dos clases compartan el
     * mismo contexto de Spring. La suite está justo en el tope de la caché de
     * contextos (32): un contexto más expulsa el más antiguo y, al cerrarse,
     * deja la base H2 compartida inservible para los tests siguientes
     * («The database has been closed»). Ver el informe de QA.
     */
    @TestBean(methodName = "com.emilio.streambox.security.device.DeviceCookieLoginIntegrationTest#clock")
    private Clock clock;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MemoryEmailSender inbox;
    @Autowired private DeviceCookieService deviceCookieService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        adjustableClock().reset();
        cleanUp();
        inbox.clear();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
    }

    // ------------------------------------------------------------------
    // 1. Concurrencia: la reserva sigue siendo atómica con cookie
    // ------------------------------------------------------------------

    /**
     * 24 logins fallidos simultáneos con la misma cookie válida, cada uno
     * desde una IP distinta: solo 5 llegan a comprobar la contraseña (4 «quedan
     * N» con 4, 3, 2 y 1, y uno que agota los intentos); el resto recibe el
     * 429 de «ya bloqueada» sin comprobarla. La cuenta no ha gastado nada.
     */
    @Test
    void fallosSimultaneosConLaMismaCookieSoloCompruebanCincoContrasenas() throws Exception {
        String email = createUser(true).getEmail();
        String device = deviceCookie(login(email, PASSWORD, nextIp(), null).andReturn());

        List<MockHttpServletResponse> responses = concurrentLogins(24, i -> device, email);

        assertBudget(responses, 1);
        // La cuenta, sin cookie, sigue con sus 5 intentos.
        login(email, "mal", nextIp(), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.remainingAttempts").value(4));
    }

    /**
     * Ataque «alternando»: 32 logins fallidos simultáneos, la mitad con la
     * cookie válida y la mitad sin ella. Como mucho 10 contraseñas comprobadas
     * (5 del dispositivo + 5 de la cuenta): 8 «quedan N» y 2 que agotan.
     * Después, con y sin cookie, todo es 429 aunque la contraseña sea la
     * correcta.
     */
    @Test
    void alternarConYSinCookieNoDaMasQueLaSumaDeLosDosPresupuestos() throws Exception {
        String email = createUser(true).getEmail();
        String device = deviceCookie(login(email, PASSWORD, nextIp(), null).andReturn());

        List<MockHttpServletResponse> responses = concurrentLogins(32, i -> i % 2 == 0 ? device : null, email);

        assertBudget(responses, 2);
        assertLocked(login(email, PASSWORD, nextIp(), device));
        assertLocked(login(email, PASSWORD, nextIp(), null));
    }

    // ------------------------------------------------------------------
    // 2. Renovación: no multiplica el presupuesto
    // ------------------------------------------------------------------

    /**
     * La cookie renovada por un login correcto y la anterior (que sigue
     * siendo válida: misma versión) son el mismo dispositivo: 3 fallos con una
     * y 2 con la otra bloquean las dos. Si la renovación creara otro id,
     * guardar cookies viejas daría 5 fallos más por cada login correcto.
     */
    @Test
    void laCookieRenovadaYLaAnteriorCompartenContador() throws Exception {
        String email = createUser(true).getEmail();
        String original = deviceCookie(login(email, PASSWORD, nextIp(), null).andReturn());
        // Un segundo después: con el reloj parado la renovación sería idéntica
        // a la original y el test no probaría nada.
        adjustableClock().advance(Duration.ofSeconds(1));
        String renewed = deviceCookie(login(email, PASSWORD, nextIp(), original)
                .andExpect(status().isNoContent()).andReturn());
        assertNotEquals(original, renewed, "la renovación emite un valor nuevo (otra fecha de emisión)");

        for (int remaining = 4; remaining >= 2; remaining--) {
            login(email, "mal", nextIp(), renewed)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.remainingAttempts").value(remaining));
        }
        login(email, "mal", nextIp(), original)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.remainingAttempts").value(1));
        assertLocked(login(email, "mal", nextIp(), original));
        assertLocked(login(email, PASSWORD, nextIp(), renewed));
        assertLocked(login(email, PASSWORD, nextIp(), original));
    }

    // ------------------------------------------------------------------
    // 3. Revocación
    // ------------------------------------------------------------------

    /**
     * Confirmar el correo sube {@code token_version} y anula una cookie de
     * dispositivo anterior de la cuenta. Hoy una cuenta pendiente no puede
     * conseguir una por el login (no entra), así que se emite directamente
     * con el servicio para probar la revocación en sí: antes de confirmar
     * vale, después no, y con la cuenta bloqueada se comporta como ausente.
     */
    @Test
    void confirmarElCorreoAnulaUnaCookieAnterior() throws Exception {
        String email = "pendiente" + SEQUENCE.getAndIncrement() + DOMAIN;
        mockMvc.perform(post("/api/users")
                        .with(remoteAddr(nextIp()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "pend" + SEQUENCE.getAndIncrement(),
                                "email", email,
                                "password", PASSWORD))))
                .andExpect(status().is2xxSuccessful());
        User pending = userRepository.findByEmail(email).orElseThrow();
        String device = deviceCookieService.issueNew(pending.getId(), pending.getTokenVersion()).value();
        assertTrue(deviceCookieService.deviceIdFor(device, pending).isPresent(), "control: vale antes de confirmar");

        mockMvc.perform(post("/api/auth/email-verification")
                        .with(remoteAddr(nextIp()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("token", linkToken(email)))))
                .andExpect(status().isNoContent());

        User confirmed = userRepository.findByEmail(email).orElseThrow();
        assertTrue(confirmed.isEmailVerified());
        assertEquals(Optional.empty(), deviceCookieService.deviceIdFor(device, confirmed));
        String attacker = nextIp();
        lockFrom(email, attacker);
        assertLocked(login(email, PASSWORD, nextIp(), device));
    }

    /**
     * Tras «cerrar sesión en todos los dispositivos», un login correcto que
     * aún envía la cookie revocada entrega una cookie de un dispositivo
     * <b>nuevo</b>: el id de la revocada no se recicla (si se reciclara, una
     * copia robada de la cookie antigua volvería a compartir contador con el
     * navegador del titular en cuanto este entrara).
     */
    @Test
    void unLoginCorrectoConUnaCookieRevocadaEntregaUnDispositivoNuevo() throws Exception {
        String email = createUser(true).getEmail();
        MvcResult session = login(email, PASSWORD, nextIp(), null).andReturn();
        String revoked = deviceCookie(session);
        String revokedId = deviceCookieService.deviceIdFor(revoked, userRepository.findByEmail(email).orElseThrow())
                .orElseThrow();

        mockMvc.perform(post("/api/auth/logout-all")
                        .with(remoteAddr(nextIp()))
                        .cookie(new Cookie(AuthCookieService.COOKIE_NAME, cookieValue(session, AuthCookieService.COOKIE_NAME)))
                        .header("X-Requested-With", "StreamBox"))
                .andExpect(status().isNoContent());

        String fresh = deviceCookie(login(email, PASSWORD, nextIp(), revoked)
                .andExpect(status().isNoContent()).andReturn());
        User current = userRepository.findByEmail(email).orElseThrow();
        assertEquals(Optional.empty(), deviceCookieService.deviceIdFor(revoked, current));
        String freshId = deviceCookieService.deviceIdFor(fresh, current).orElseThrow();
        assertNotEquals(revokedId, freshId);
    }

    // ------------------------------------------------------------------
    // 4. Robustez HTTP
    // ------------------------------------------------------------------

    /**
     * Valores raros de la cookie con la cuenta bloqueada y la contraseña
     * correcta: la respuesta es idéntica (estado, {@code Retry-After},
     * {@code Set-Cookie} y cuerpo) a la de sin cookie. Incluye una cookie
     * válida con un punto o un campo de más y una con la firma en mayúsculas.
     */
    @ParameterizedTest
    @ValueSource(strings = { "v1.a.b.c.d.e", "....", "v1..", "%s.", "%s.extra", "UPPER", "v1.%s",
            "ñáü", "=", "v1.AAAA.AAAA=" })
    void unValorRaroSeTrataComoAusenteYNuncaDa500(String template) throws Exception {
        String email = createUser(true).getEmail();
        String valid = deviceCookie(login(email, PASSWORD, nextIp(), null).andReturn());
        String value = switch (template) {
            case "UPPER" -> valid.substring(0, valid.lastIndexOf('.') + 1)
                    + valid.substring(valid.lastIndexOf('.') + 1).toUpperCase(java.util.Locale.ROOT);
            default -> template.contains("%s") ? template.formatted(valid) : template;
        };
        String ip = nextIp();
        lockFrom(email, ip);

        MvcResult without = login(email, PASSWORD, ip, null).andReturn();
        MvcResult with = login(email, PASSWORD, ip, value).andReturn();

        assertEquals(429, with.getResponse().getStatus(), value);
        assertSameResponse(without, with);
    }

    /**
     * Un {@code %} suelto o una secuencia {@code %XX} inválida en la cookie de
     * dispositivo (caracteres que RFC 6265 permite y que Tomcat deja pasar)
     * debe tratarse como cookie ausente: 401 con los intentos restantes, igual
     * que sin cookie.
     *
     * <p>
     * <b>Falla hoy (incidencia QA-DC-1):</b> {@code @CookieValue} decodifica
     * el valor como si fuera parte de una URL
     * ({@code ServletCookieValueMethodArgumentResolver} →
     * {@code UrlPathHelper.decodeRequestString}), y con una secuencia
     * inválida lanza {@code IllegalArgumentException("Invalid encoded
     * sequence")} antes de llegar al servicio: 500 {@code INTERNAL_ERROR} y una
     * traza ERROR en el log por cada petición, sin autenticar. Lo mismo pasa
     * con {@code streambox_refresh} en refresh y logout (anterior a este
     * cambio; ver {@code DeviceCookieTomcatQaIntegrationTest}).
     * </p>
     */
    @Disabled("QA-DC-1: @CookieValue URL-decodifica la cookie y un '%' inválido da 500 INTERNAL_ERROR; "
            + "pendiente del agente security (leer la cookie sin decodificar)")
    @ParameterizedTest
    @ValueSource(strings = { "%", "%zz", "v1.%%%.%%%", "%E0%A4%A" })
    void unaSecuenciaPorcentualInvalidaEnLaCookieNoDa500(String value) throws Exception {
        String email = createUser(true).getEmail();
        String ip = nextIp();

        MvcResult without = login(email, "mal", ip, null).andReturn();
        MvcResult with = login(email, "mal", ip, value).andReturn();

        assertEquals(401, with.getResponse().getStatus(), with.getResponse().getContentAsString());
        assertEquals(3, objectMapper.readTree(with.getResponse().getContentAsString())
                .get("remainingAttempts").asInt());
        assertEquals(without.getResponse().getHeaders(HttpHeaders.SET_COOKIE),
                with.getResponse().getHeaders(HttpHeaders.SET_COOKIE));
    }

    /**
     * Dos cookies {@code streambox_device} en la misma petición, la válida
     * primero: se usa la válida (entra pese al bloqueo de la cuenta). Con dos
     * valores basura: igual que sin cookie. Nunca un 500.
     */
    @Test
    void conCookiesDuplicadasLaValidaDelanteSeUsaYDosBasurasSonAusencia() throws Exception {
        String email = createUser(true).getEmail();
        String valid = deviceCookie(login(email, PASSWORD, nextIp(), null).andReturn());
        String ip = nextIp();
        lockFrom(email, ip);

        mockMvc.perform(loginRequest(email, PASSWORD, ip)
                        .cookie(new Cookie(DEVICE, valid), new Cookie(DEVICE, "basura")))
                .andExpect(status().isNoContent());

        // Desde otra IP: el login correcto de arriba ha hecho «conocida» la suya.
        String otherIp = nextIp();
        MvcResult without = login(email, PASSWORD, otherIp, null).andReturn();
        MvcResult twoGarbage = mockMvc.perform(loginRequest(email, PASSWORD, otherIp)
                        .cookie(new Cookie(DEVICE, "basura"), new Cookie(DEVICE, "v1.x.y")))
                .andReturn();
        assertEquals(429, twoGarbage.getResponse().getStatus());
        assertSameResponse(without, twoGarbage);
    }

    /**
     * Con la basura delante de la válida: hoy se lee solo la primera
     * ({@code @CookieValue} toma la primera con ese nombre), así que el
     * titular queda bloqueado aunque su navegador tenga la cookie válida.
     *
     * <p>
     * Importa porque el navegador ordena las cookies con el mismo nombre por
     * ruta más específica primero: una cookie {@code streambox_device}
     * plantada desde otro subdominio ({@code Domain=} del dominio padre,
     * «cookie tossing») con {@code Path=/api/auth/login} iría delante de la
     * legítima y devolvería al titular al «bloqueo como arma». Lo esperado es
     * que baste con que <b>alguna</b> de las cookies con ese nombre sea válida
     * para la cuenta (no da nada a quien no tenga ya una válida).
     * </p>
     *
     * <p>
     * <b>Falla hoy (incidencia QA-DC-2, BAJA):</b> 429 {@code ACCOUNT_LOCKED}.
     * </p>
     */
    @Disabled("QA-DC-2: con dos cookies streambox_device solo se mira la primera; una plantada delante "
            + "anula la legítima. Pendiente de decisión del agente security")
    @Test
    void conCookiesDuplicadasLaBasuraDelanteNoAnulaLaValida() throws Exception {
        String email = createUser(true).getEmail();
        String valid = deviceCookie(login(email, PASSWORD, nextIp(), null).andReturn());
        String ip = nextIp();
        lockFrom(email, ip);

        mockMvc.perform(loginRequest(email, PASSWORD, ip)
                        .cookie(new Cookie(DEVICE, "basura-plantada"), new Cookie(DEVICE, valid)))
                .andExpect(status().isNoContent());
    }

    /**
     * La cookie viaja (Path=/api/auth) a refresh, logout, logout-all, el
     * restablecimiento y la verificación del correo: con un valor basura o
     * válido, ninguna de esas rutas da un 500 ni la toca (no la borra ni
     * emite otra).
     */
    @Test
    void enLasDemasRutasDeAuthLaCookieSeIgnoraSinErrores() throws Exception {
        String email = createUser(true).getEmail();
        String valid = deviceCookie(login(email, PASSWORD, nextIp(), null).andReturn());

        for (String value : List.of(valid, "basura", "v1.a.b.c")) {
            List<MockHttpServletRequestBuilder> requests = List.of(
                    post("/api/auth/refresh").header("X-Requested-With", "StreamBox"),
                    post("/api/auth/logout").header("X-Requested-With", "StreamBox"),
                    post("/api/auth/logout-all").header("X-Requested-With", "StreamBox"),
                    post("/api/auth/password-reset").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"nadie" + DOMAIN + "\"}"),
                    post("/api/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"x\",\"newPassword\":\"Otra-Clave-Muy-Distinta-2026!\"}"),
                    post("/api/auth/email-verification").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"x\"}"));
            for (MockHttpServletRequestBuilder request : requests) {
                MockHttpServletResponse response = mockMvc.perform(request
                                .with(remoteAddr(nextIp()))
                                .cookie(new Cookie(DEVICE, value)))
                        .andReturn().getResponse();
                assertTrue(response.getStatus() < 500, response.getStatus() + " " + response.getContentAsString());
                assertTrue(setCookies(response).isEmpty(), response.getHeaders(HttpHeaders.SET_COOKIE).toString());
            }
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * Comprueba el reparto de respuestas de un ataque simultáneo con
     * {@code budgets} presupuestos de 5: {@code 4 × budgets} 401 (con los
     * intentos restantes 4..1 repetidos {@code budgets} veces), {@code budgets}
     * 429 «Has superado...» y el resto 429 «ya bloqueada». Ninguna respuesta
     * es un 204 ni un 5xx y ninguna fija cookies.
     */
    private void assertBudget(List<MockHttpServletResponse> responses, int budgets) throws Exception {
        List<Integer> remaining = new ArrayList<>();
        int exhausted = 0;
        int alreadyLocked = 0;
        for (MockHttpServletResponse response : responses) {
            JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
            assertTrue(response.getHeaders(HttpHeaders.SET_COOKIE).isEmpty(), body.toString());
            if (response.getStatus() == 401) {
                remaining.add(body.get("remainingAttempts").asInt());
            } else {
                assertEquals(429, response.getStatus(), body.toString());
                assertEquals("ACCOUNT_LOCKED", body.get("code").asText());
                if (body.get("message").asText().startsWith(EXHAUSTED_PREFIX)) {
                    exhausted++;
                } else {
                    alreadyLocked++;
                }
            }
        }
        remaining.sort(null);
        List<Integer> expected = new ArrayList<>();
        for (int n = 1; n <= 4; n++) {
            for (int b = 0; b < budgets; b++) {
                expected.add(n);
            }
        }
        assertEquals(expected, remaining, "contraseñas comprobadas con «quedan N»");
        assertEquals(budgets, exhausted, "fallos que agotan un contador");
        assertEquals(responses.size() - 5 * budgets, alreadyLocked, "rechazados sin comprobar la contraseña");
    }

    /** El bean {@code clock} del contexto compartido (un {@link AdjustableClock}). */
    private AdjustableClock adjustableClock() {
        return (AdjustableClock) clock;
    }

    /** Lanza {@code count} logins fallidos a la vez, cada uno desde una IP nueva. */
    private List<MockHttpServletResponse> concurrentLogins(int count,
            java.util.function.IntFunction<String> cookieFor, String email) throws Exception {
        List<String> ips = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ips.add(nextIp());
        }
        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch ready = new CountDownLatch(count);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String ip = ips.get(i);
                String cookie = cookieFor.apply(i);
                Callable<MockHttpServletResponse> call = () -> {
                    ready.countDown();
                    start.await();
                    return login(email, "mal", ip, cookie).andReturn().getResponse();
                };
                futures.add(executor.submit(call));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<MockHttpServletResponse> responses = new ArrayList<>();
            for (Future<MockHttpServletResponse> future : futures) {
                responses.add(future.get(60, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            executor.shutdownNow();
        }
    }

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
        assertTrue(result.andReturn().getResponse().getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
    }

    private void assertSameResponse(MvcResult expected, MvcResult actual) throws Exception {
        MockHttpServletResponse a = expected.getResponse();
        MockHttpServletResponse b = actual.getResponse();
        assertEquals(a.getStatus(), b.getStatus());
        assertEquals(a.getHeader(HttpHeaders.RETRY_AFTER), b.getHeader(HttpHeaders.RETRY_AFTER));
        assertEquals(a.getHeaders(HttpHeaders.SET_COOKIE), b.getHeaders(HttpHeaders.SET_COOKIE));
        assertEquals(body(a), body(b));
    }

    private ResultActions login(String email, String password, String ip, String deviceCookie) throws Exception {
        MockHttpServletRequestBuilder request = loginRequest(email, password, ip);
        if (deviceCookie != null) {
            request.cookie(new Cookie(DEVICE, deviceCookie));
        }
        return mockMvc.perform(request);
    }

    private MockHttpServletRequestBuilder loginRequest(String email, String password, String ip) throws Exception {
        return post("/api/auth/login")
                .with(remoteAddr(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password)));
    }

    private User createUser(boolean verified) {
        String name = "dispqa" + SEQUENCE.getAndIncrement();
        User user = new User();
        user.setEmailVerifiedAt(verified ? Instant.now() : null);
        user.setUsername(name);
        user.setEmail(name + DOMAIN);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user);
    }

    private String linkToken(String email) {
        List<SentEmail> emails = inbox.sentTo(email);
        assertEquals(1, emails.size(), emails::toString);
        Matcher matcher = LINK.matcher(emails.get(0).body());
        assertTrue(matcher.find(), emails.get(0).body());
        return matcher.group(1);
    }

    private static String deviceCookie(MvcResult result) {
        return cookieValue(result, DEVICE);
    }

    private static String cookieValue(MvcResult result, String name) {
        Cookie cookie = result.getResponse().getCookie(name);
        assertNotNull(cookie, "la respuesta no entrega la cookie " + name + ": "
                + result.getResponse().getHeaders(HttpHeaders.SET_COOKIE));
        return cookie.getValue();
    }

    private static List<String> setCookies(MockHttpServletResponse response) {
        return response.getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(DEVICE + "="))
                .toList();
    }

    /** Cuerpo del error sin la fecha, que es lo único que cambia entre dos respuestas. */
    private ObjectNode body(MockHttpServletResponse response) throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        body.remove("timestamp");
        return body;
    }

    private static String nextIp() {
        int n = IP_SEQUENCE.getAndIncrement();
        return "10.252." + (n / 250) + "." + (n % 250 + 1);
    }

    private static RequestPostProcessor remoteAddr(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
