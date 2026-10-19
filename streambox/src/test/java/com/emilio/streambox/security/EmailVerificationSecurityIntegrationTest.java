package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.RefreshToken;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.SessionExpiredException;
import com.emilio.streambox.repository.RefreshTokenRepository;
import com.emilio.streambox.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.servlet.http.Cookie;

/**
 * Seguridad de la verificación del correo (V9): login de una cuenta pendiente,
 * sesiones abiertas antes de la V9, reglas de las rutas públicas nuevas y sus
 * límites por IP.
 *
 * <p>
 * Cada test falla si se quita la protección que prueba:
 * </p>
 * <ul>
 * <li>Login (incidencia QA-1): con la contraseña correcta y la cuenta
 * pendiente, exactamente el mismo 401 {@code INVALID_CREDENTIALS} que una
 * contraseña incorrecta o un email inexistente, sin cookies ni refresh token.
 * Es un fallo a todos los efectos: gasta intento, bloquea la cuenta al
 * agotarlos, no borra los fallos anteriores, no hace «conocida» la IP y, desde
 * una IP ya conocida (cuenta heredada), gasta el contador de esa IP como
 * cualquier fallo desde ella.</li>
 * <li>Una cuenta pendiente no usa la API con un JWT emitido antes (filtro) ni
 * renueva la sesión (refresh: 401 y se revocan todas sus sesiones, también en
 * la gracia de 10 s).</li>
 * <li>{@code POST /api/auth/email-verification} y {@code .../resend}: públicos
 * solo con {@code POST}, ignoran la cookie (sin 403 CSRF) y tienen su límite
 * por IP. Las reglas se escribieron antes que los endpoints (cuando daban
 * 404); ahora que existen, los tests comprueban la respuesta real del
 * endpoint: 400 {@code INVALID_VERIFICATION_TOKEN} al confirmar con un token
 * que no sirve y 400 {@code VALIDATION_ERROR} (o 202 con un email válido) al
 * reenviar.</li>
 * </ul>
 *
 * <p>
 * Sin {@code @Transactional}: el refresh confirma de verdad la revocación
 * aunque responda 401, y eso es lo que se prueba. Los usuarios de la clase
 * (dominio {@value #DOMAIN}) se borran antes y después de cada test. Los
 * límites se bajan con {@code @TestPropertySource} (contexto propio) y cada
 * petición usa una IP única (10.240.x.y), porque los contadores viven en el
 * contexto.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "streambox.security.rate-limit.lockout.max-failures=3",
        "streambox.security.rate-limit.email-verification.max-requests=2",
        "streambox.security.rate-limit.email-verification-resend.max-requests=2"
})
class EmailVerificationSecurityIntegrationTest {

    private static final String DOMAIN = "@pendiente.test";
    private static final String PASSWORD = "Contraseña-Pendiente-2026";
    private static final String VERIFY = "/api/auth/email-verification";
    private static final String RESEND = "/api/auth/email-verification/resend";

    /** IPs que no usa ningún otro test (10.240.x.y). */
    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void deleteUsersOfThisSuite() {
        userRepository.deleteAll(userRepository.findAll().stream()
                .filter(user -> user.getEmail().endsWith(DOMAIN))
                .toList());
    }

    // ------------------------------------------------------------------
    // Login de una cuenta pendiente
    // ------------------------------------------------------------------

    /**
     * <b>QA-1.</b> Contraseña correcta + cuenta pendiente: exactamente el mismo
     * 401 (estado, código, mensaje, ruta e intentos restantes; todo el cuerpo
     * salvo la hora) que una contraseña incorrecta en una cuenta verificada y
     * que un email inexistente, intento a intento. Sin ninguna cookie ni sesión
     * en la base de datos. Con el 403 {@code EMAIL_NOT_VERIFIED} de antes,
     * quien se registraba con un email ajeno y su contraseña sabía si el email
     * ya tenía cuenta.
     */
    @Test
    void conLaContrasenaCorrectaUnaCuentaPendienteRecibeElMismo401QueUnFallo() throws Exception {
        User pending = createUser("p1", false);
        User verified = createUser("p1v", true);

        for (int attempt = 1; attempt <= 2; attempt++) {
            MockHttpServletResponse pendingResponse = login(pending.getEmail(), PASSWORD, nextIp())
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                    .andExpect(jsonPath("$.remainingAttempts").value(3 - attempt))
                    .andReturn().getResponse();
            ObjectNode wrongPassword = body(login(verified.getEmail(), "mal", nextIp())
                    .andExpect(status().isUnauthorized()).andReturn().getResponse());
            ObjectNode ghost = body(login("p1-fantasma" + DOMAIN, "mal", nextIp())
                    .andExpect(status().isUnauthorized()).andReturn().getResponse());

            ObjectNode pendingBody = body(pendingResponse);
            assertEquals(wrongPassword, pendingBody);
            assertEquals(ghost, pendingBody);
            assertEquals(List.of(), pendingResponse.getHeaders(HttpHeaders.SET_COOKIE),
                    "No se entrega ninguna cookie");
        }
        assertEquals(0, activeTokensOf(pending), "No se abre ninguna familia de refresh tokens");
    }

    /**
     * Contraseña incorrecta: la cuenta pendiente recibe exactamente el mismo
     * 401 que una verificada y que un email inexistente (mismo código,
     * mensaje e intentos restantes). El estado de verificación solo lo ve
     * quien conoce la contraseña.
     */
    @Test
    void conLaContrasenaIncorrectaUnaCuentaPendienteNoSeDistingueDeNinguna() throws Exception {
        User pending = createUser("p2", false);
        User verified = createUser("p2v", true);

        for (int attempt = 1; attempt <= 2; attempt++) {
            ObjectNode pendingBody = body(login(pending.getEmail(), "mal", nextIp())
                    .andExpect(status().isUnauthorized()).andReturn().getResponse());
            ObjectNode verifiedBody = body(login(verified.getEmail(), "mal", nextIp())
                    .andExpect(status().isUnauthorized()).andReturn().getResponse());
            ObjectNode ghostBody = body(login("p2-fantasma" + DOMAIN, "mal", nextIp())
                    .andExpect(status().isUnauthorized()).andReturn().getResponse());

            assertEquals(verifiedBody, pendingBody);
            assertEquals(ghostBody, pendingBody);
            assertEquals("INVALID_CREDENTIALS", pendingBody.get("code").asText());
        }
    }

    /**
     * Cuenta como un fallo más y no borra los anteriores: con un máximo de 3,
     * un fallo y un intento con la contraseña correcta de la cuenta pendiente
     * dejan 1 intento, y el siguiente (otra vez con la correcta) bloquea la
     * cuenta con el mismo 429 que el fallo que agota los intentos de una cuenta
     * verificada. Si no contara, quedarían 2; si reiniciara el contador, 2 tras
     * el primer intento con la correcta.
     */
    @Test
    void cuentaComoFalloYBloqueaLaCuentaAlAgotarLosIntentos() throws Exception {
        User pending = createUser("p3", false);
        User verified = createUser("p3v", true);

        login(pending.getEmail(), "mal", nextIp()).andExpect(jsonPath("$.remainingAttempts").value(2));
        login(pending.getEmail(), PASSWORD, nextIp())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.remainingAttempts").value(1));
        MockHttpServletResponse pendingLock = login(pending.getEmail(), PASSWORD, nextIp())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(header().exists("Retry-After"))
                .andReturn().getResponse();

        for (int i = 0; i < 2; i++) {
            login(verified.getEmail(), "mal", nextIp()).andExpect(status().isUnauthorized());
        }
        MockHttpServletResponse verifiedLock = login(verified.getEmail(), "mal", nextIp())
                .andExpect(status().isTooManyRequests()).andReturn().getResponse();

        assertEquals(body(verifiedLock), body(pendingLock));
        assertEquals(List.of(), pendingLock.getHeaders(HttpHeaders.SET_COOKIE));
    }

    /** Durante el bloqueo, la contraseña correcta de la cuenta pendiente sigue dando 429. */
    @Test
    void conLaCuentaBloqueadaLaContrasenaCorrectaDa429() throws Exception {
        User pending = createUser("p4", false);
        for (int i = 0; i < 3; i++) {
            login(pending.getEmail(), "mal", nextIp());
        }

        login(pending.getEmail(), PASSWORD, nextIp())
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(header().exists("Retry-After"));
    }

    /**
     * No hace «conocida» la IP: si lo hiciera, desde esa IP se entraría aunque
     * la cuenta estuviera bloqueada (una IP conocida tiene su propio
     * contador). Aquí, tras verificar la cuenta y bloquearla desde otra IP, la
     * del intento con la cuenta pendiente sigue bloqueada.
     */
    @Test
    void elIntentoConLaCuentaPendienteNoHaceConocidaLaIp() throws Exception {
        User account = createUser("p5", false);
        String ipOfPendingLogin = nextIp();
        login(account.getEmail(), PASSWORD, ipOfPendingLogin).andExpect(status().isUnauthorized());

        markVerified(account);
        String attackerIp = nextIp();
        for (int i = 0; i < 3; i++) {
            login(account.getEmail(), "mal", attackerIp);
        }

        login(account.getEmail(), PASSWORD, ipOfPendingLogin)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    /**
     * Una cuenta USER anterior a la V9 que ya había iniciado sesión desde una
     * IP (IP «conocida») y quedó pendiente: desde esa IP, la contraseña
     * correcta se comporta exactamente como un fallo desde esa IP. Se compara
     * con una cuenta gemela verificada que falla la contraseña desde su propia
     * IP conocida: los mismos cuerpos (401 con 2 y 1 intentos y, al tercero, el
     * 429). Y gasta el contador de la IP conocida, no el de la cuenta: desde
     * una IP desconocida la cuenta conserva sus 3 intentos (el primer fallo deja 2).
     */
    @Test
    void unaHeredadaDesdeSuIpConocidaSeComportaComoUnFalloDesdeEsaIp() throws Exception {
        User legacy = createUser("p11", true);
        User twin = createUser("p11v", true);
        String legacyKnownIp = nextIp();
        String twinKnownIp = nextIp();
        login(legacy.getEmail(), PASSWORD, legacyKnownIp).andExpect(status().isNoContent());
        login(twin.getEmail(), PASSWORD, twinKnownIp).andExpect(status().isNoContent());
        markPending(legacy);
        long sessionsBefore = activeTokensOf(legacy);

        for (int attempt = 1; attempt <= 2; attempt++) {
            MockHttpServletResponse legacyResponse = login(legacy.getEmail(), PASSWORD, legacyKnownIp)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.remainingAttempts").value(3 - attempt))
                    .andReturn().getResponse();
            MockHttpServletResponse twinResponse = login(twin.getEmail(), "mal", twinKnownIp)
                    .andReturn().getResponse();
            assertEquals(body(twinResponse), body(legacyResponse));
            assertEquals(List.of(), legacyResponse.getHeaders(HttpHeaders.SET_COOKIE));
        }
        MockHttpServletResponse legacyLock = login(legacy.getEmail(), PASSWORD, legacyKnownIp)
                .andExpect(status().isTooManyRequests()).andReturn().getResponse();
        MockHttpServletResponse twinLock = login(twin.getEmail(), "mal", twinKnownIp).andReturn().getResponse();
        assertEquals(body(twinLock), body(legacyLock));

        login(legacy.getEmail(), "mal", nextIp())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.remainingAttempts").value(2));
        assertEquals(sessionsBefore, activeTokensOf(legacy), "Ningún intento con la cuenta pendiente abre sesión");
    }

    /** Control: la misma cuenta, ya verificada, inicia sesión con normalidad. */
    @Test
    void unaVezVerificadaLaCuentaIniciaSesion() throws Exception {
        User account = createUser("p6", false);
        login(account.getEmail(), PASSWORD, nextIp()).andExpect(status().isUnauthorized());

        markVerified(account);

        login(account.getEmail(), PASSWORD, nextIp()).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // Sesiones abiertas antes de la V9
    // ------------------------------------------------------------------

    /**
     * Un JWT de acceso válido de una cuenta pendiente (emitido antes de la V9)
     * no abre la API, ni por cookie ni por Bearer: 401, como una cuenta
     * borrada. El mismo token vale en cuanto la cuenta se verifica (el filtro
     * lee la cuenta de la base de datos en cada petición).
     */
    @Test
    void unaCuentaPendienteNoUsaLaApiConUnJwtVivo() throws Exception {
        User account = createUser("p7", false);
        String jwt = jwtService.generateToken(account);

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/me").cookie(new Cookie("streambox_token", jwt)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/users/me/favorites/1").cookie(new Cookie("streambox_token", jwt))
                        .header("X-Requested-With", "StreamBox"))
                .andExpect(status().isUnauthorized());

        markVerified(account);

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(account.getEmail()));
    }

    /**
     * Refresh de una cuenta que quedó pendiente con sesiones abiertas: 401
     * {@code SESSION_EXPIRED} que borra las cookies, y se revocan
     * <b>todas</b> sus sesiones, no solo la presentada: aunque después se
     * verifique el correo, la otra sesión (la pudo abrir quien se registró
     * con un email ajeno) ya no se renueva.
     */
    @Test
    void elRefreshDeUnaCuentaPendienteDa401YRevocaTodasSusSesiones() throws Exception {
        User account = createUser("p8", true);
        String firstRefresh = refreshCookie(login(account.getEmail(), PASSWORD, nextIp())
                .andExpect(status().isNoContent()).andReturn());
        String secondRefresh = refreshCookie(login(account.getEmail(), PASSWORD, nextIp())
                .andExpect(status().isNoContent()).andReturn());
        assertEquals(2, activeTokensOf(account));

        markPending(account);

        assertSessionExpired(refresh(firstRefresh));
        assertEquals(0, activeTokensOf(account), "Se revocan todas las sesiones de la cuenta pendiente");

        markVerified(account);
        assertSessionExpired(refresh(secondRefresh));
    }

    /**
     * También en la gracia de 10 s de dos pestañas: el token recién rotado no
     * entrega un JWT nuevo a una cuenta pendiente. Sin la comprobación en esa
     * rama, el segundo refresh daría 204 con un JWT.
     */
    @Test
    void enLaGraciaDeLasPestanasSimultaneasTampocoSeRenuevaUnaCuentaPendiente() throws Exception {
        User account = createUser("p9", true);
        String original = refreshCookie(login(account.getEmail(), PASSWORD, nextIp())
                .andExpect(status().isNoContent()).andReturn());
        MvcResult rotated = refresh(original);
        assertEquals(204, rotated.getResponse().getStatus(), rotated.getResponse().getContentAsString());

        markPending(account);

        assertSessionExpired(refresh(original));
        assertEquals(0, activeTokensOf(account));
    }

    // ------------------------------------------------------------------
    // Rutas públicas nuevas: solo POST, sin CSRF, con límite por IP
    // ------------------------------------------------------------------

    /**
     * Sin sesión, el {@code POST} de la confirmación no se rechaza en la cadena
     * de seguridad: llega al endpoint, que responde su propio error. Con un
     * cuerpo sin token y con un token de formato imposible, el mismo 400
     * {@code INVALID_VERIFICATION_TOKEN} (ni 401 ni 403 ni 404). Si la regla
     * {@code permitAll} desapareciera, sería un 401.
     */
    @ParameterizedTest
    @ValueSource(strings = { "{}", "{\"token\":\"no-es-un-token\"}" })
    void elPostDeLaConfirmacionEsPublicoYRespondeElEndpoint(String body) throws Exception {
        mockMvc.perform(post(VERIFY).with(ip(nextIp())).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"))
                .andExpect(jsonPath("$.path").value(VERIFY));
    }

    /**
     * Sin sesión, el {@code POST} del reenvío llega al endpoint: 400
     * {@code VALIDATION_ERROR} con un email ausente o mal formado y 202 con el
     * cuerpo fijo con uno válido (aunque no tenga cuenta). Si la regla
     * {@code permitAll} desapareciera, los tres serían 401.
     */
    @Test
    void elPostDelReenvioEsPublicoYRespondeElEndpoint() throws Exception {
        mockMvc.perform(post(RESEND).with(ip(nextIp())).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.email").exists());
        mockMvc.perform(post(RESEND).with(ip(nextIp())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"no-es-un-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.email").exists());
        mockMvc.perform(post(RESEND).with(ip(nextIp())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nadie" + DOMAIN + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(com.emilio.streambox.dto.AcceptedResponse.RESEND_MESSAGE));
    }

    /**
     * Cualquier otro método sobre esas rutas sigue pidiendo token: la regla
     * {@code permitAll} es solo del {@code POST} (con {@code HEAD} u
     * {@code OPTIONS} no se ejecuta nada sin autenticar).
     */
    @ParameterizedTest
    @CsvSource({
            "GET, /api/auth/email-verification", "HEAD, /api/auth/email-verification",
            "OPTIONS, /api/auth/email-verification", "PUT, /api/auth/email-verification",
            "PATCH, /api/auth/email-verification", "DELETE, /api/auth/email-verification",
            "GET, /api/auth/email-verification/resend", "HEAD, /api/auth/email-verification/resend",
            "OPTIONS, /api/auth/email-verification/resend", "PUT, /api/auth/email-verification/resend",
            "DELETE, /api/auth/email-verification/resend",
            "POST, /api/auth/email-verification/otra", "POST, /api/auth/email-verification/resend/otra"
    })
    void losDemasMetodosYRutasVecinasPidenToken(String method, String path) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(method), path).with(ip(nextIp()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Ignoran la cookie de sesión, como login y registro: con una sesión
     * abierta y sin {@code X-Requested-With}, el {@code POST} no recibe el 403
     * {@code CSRF_REJECTED} (no hay sesión que proteger; ver SecurityConfig),
     * sino la respuesta del endpoint: el mismo 400 que sin sesión
     * ({@code INVALID_VERIFICATION_TOKEN} al confirmar sin token,
     * {@code VALIDATION_ERROR} al reenviar sin email).
     */
    @ParameterizedTest
    @CsvSource({ VERIFY + ", INVALID_VERIFICATION_TOKEN", RESEND + ", VALIDATION_ERROR" })
    void conUnaSesionAbiertaYSinLaCabeceraCsrfNoDa403(String path, String expectedCode) throws Exception {
        User account = createUser("p10" + (path.endsWith("resend") ? "r" : "v"), true);
        String jwt = jwtService.generateToken(account);

        mockMvc.perform(post(path).with(ip(nextIp())).cookie(new Cookie("streambox_token", jwt))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(expectedCode));
    }

    /**
     * Límite por IP (aquí 2) en cada ruta: las dos primeras peticiones JSON
     * llegan al endpoint (su 400 de siempre, también las que fallan cuentan),
     * la tercera de la misma IP da 429 {@code RATE_LIMIT_EXCEEDED} con
     * {@code Retry-After}, y otra IP vuelve a llegar al endpoint.
     */
    @ParameterizedTest
    @CsvSource({ VERIFY + ", INVALID_VERIFICATION_TOKEN", RESEND + ", VALIDATION_ERROR" })
    void cadaRutaTieneSuLimitePorIp(String path, String endpointCode) throws Exception {
        String sameIp = nextIp();
        postJson(path, sameIp).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(endpointCode));
        postJson(path, sameIp).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(endpointCode));

        postJson(path, sameIp)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(header().exists("Retry-After"));
        postJson(path, nextIp()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(endpointCode));
    }

    /** Las dos rutas no comparten contador: agotar los reenvíos no impide confirmar. */
    @Test
    void confirmarYReenviarNoCompartenContador() throws Exception {
        String sameIp = nextIp();
        postJson(RESEND, sameIp);
        postJson(RESEND, sameIp);
        postJson(RESEND, sameIp).andExpect(status().isTooManyRequests());

        postJson(VERIFY, sameIp)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));
    }

    /**
     * Lo que otra web puede enviar sin preflight (text/plain, formulario, sin
     * Content-Type) no gasta el presupuesto de la víctima: tras muchas, la
     * petición JSON de la misma IP sigue pasando.
     */
    @ParameterizedTest
    @ValueSource(strings = { VERIFY, RESEND })
    void lasPeticionesSimplesDeOtraWebNoGastanElLimite(String path) throws Exception {
        String sameIp = nextIp();
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post(path).with(ip(sameIp)).contentType(MediaType.TEXT_PLAIN).content("{}"))
                    .andExpect(status().is(org.hamcrest.Matchers.not(429)));
            mockMvc.perform(post(path).with(ip(sameIp)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .content("a=b"))
                    .andExpect(status().is(org.hamcrest.Matchers.not(429)));
            mockMvc.perform(post(path).with(ip(sameIp)))
                    .andExpect(status().is(org.hamcrest.Matchers.not(429)));
        }

        postJson(path, sameIp).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(path.equals(VERIFY) ? "INVALID_VERIFICATION_TOKEN"
                        : "VALIDATION_ERROR"));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private User createUser(String name, boolean verified) {
        User user = new User();
        user.setUsername(name + "-pendiente");
        user.setEmail(name + DOMAIN);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        user.setEmailVerifiedAt(verified ? Instant.now() : null);
        return userRepository.save(user);
    }

    private void markVerified(User user) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> userRepository.markEmailVerified(user.getId(), Instant.now()));
    }

    /** Deja la cuenta pendiente, como hizo la V9 con las cuentas USER que ya existían. */
    private void markPending(User user) {
        User current = userRepository.findById(user.getId()).orElseThrow();
        current.setEmailVerifiedAt(null);
        userRepository.save(current);
        assertNull(userRepository.findById(user.getId()).orElseThrow().getEmailVerifiedAt());
    }

    /** Refresh tokens del usuario sin revocar (leídos en una transacción). */
    private long activeTokensOf(User user) {
        return new TransactionTemplate(transactionManager).execute(status -> refreshTokenRepository.findAll()
                .stream()
                .filter(token -> token.getUser().getId().equals(user.getId()))
                .filter(token -> token.getRevokedAt() == null)
                .map(RefreshToken::getId)
                .count());
    }

    private ResultActions login(String email, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(ip(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").with(ip(nextIp()))
                        .header("X-Requested-With", "StreamBox")
                        .cookie(new Cookie("streambox_refresh", refreshToken)))
                .andReturn();
    }

    private ResultActions postJson(String path, String ip) throws Exception {
        return mockMvc.perform(post(path).with(ip(ip)).contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    private static String refreshCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie("streambox_refresh");
        assertTrue(cookie != null && !cookie.getValue().isEmpty(), "El login debía entregar el refresh token");
        return cookie.getValue();
    }

    /** 401 {@code SESSION_EXPIRED} con el mensaje único y las dos cookies borradas. */
    private static void assertSessionExpired(MvcResult result) throws Exception {
        MockHttpServletResponse response = result.getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(401, response.getStatus(), body);
        assertTrue(body.contains("\"code\":\"SESSION_EXPIRED\""), body);
        assertTrue(body.contains(SessionExpiredException.MESSAGE), body);
        List<String> cookies = response.getHeaders(HttpHeaders.SET_COOKIE);
        assertTrue(cookies.stream().anyMatch(c -> c.startsWith("streambox_token=;") && c.contains("Max-Age=0")),
                cookies.toString());
        assertTrue(cookies.stream().anyMatch(c -> c.startsWith("streambox_refresh=;") && c.contains("Max-Age=0")),
                cookies.toString());
    }

    private ObjectNode body(MockHttpServletResponse response) throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        body.remove("timestamp");
        return body;
    }

    private static String nextIp() {
        int n = IP_SEQUENCE.getAndIncrement();
        return "10.240." + (n / 250) + "." + (n % 250 + 1);
    }

    private static RequestPostProcessor ip(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
