package com.emilio.streambox.controller;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

/**
 * Tests de los límites contra fuerza bruta: límite por IP en login y registro
 * y bloqueo temporal de cuentas.
 *
 * <p>
 * Los límites se reducen a valores pequeños para poder alcanzarlos. Los
 * contadores viven en memoria durante todo el contexto, así que cada test usa
 * IPs y emails propios para no interferir con los demás.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "streambox.security.rate-limit.login.max-requests=3",
        "streambox.security.rate-limit.register.max-requests=2",
        "streambox.security.rate-limit.refresh.max-requests=2",
        "streambox.security.rate-limit.lockout.max-failures=3"
})
class RateLimitingIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Crea un usuario propio para cada test: los contadores de fallos viven en
     * memoria durante todo el contexto, así que compartir emails entre tests
     * los haría depender unos de otros.
     */
    private void createUser(String name) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword(passwordEncoder.encode("correct-password"));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        userRepository.save(user);
    }

    // --- Límite por IP en login ---

    @Test
    void superarElLimiteDeLoginPorIpRetorna429ConRetryAfter() throws Exception {
        createUser("user1");
        String ip = "10.1.0.1";
        for (int i = 0; i < 3; i++) {
            login("nadie" + i + "@test.com", "x", ip).andExpect(status().isUnauthorized());
        }

        login("user1@test.com", "correct-password", ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.remainingAttempts").doesNotExist());
    }

    @Test
    void elLimiteDeLoginEsPorIpYNoAfectaAOtrasIps() throws Exception {
        createUser("user2");
        for (int i = 0; i < 3; i++) {
            login("otro" + i + "@test.com", "x", "10.2.0.1");
        }
        login("user2@test.com", "correct-password", "10.2.0.1")
                .andExpect(status().isTooManyRequests());

        login("user2@test.com", "correct-password", "10.2.0.2")
                .andExpect(status().isNoContent());
    }

    // --- Límite por IP en registro ---

    @Test
    void superarElLimiteDeRegistroPorIpRetorna429() throws Exception {
        createUser("user3");
        String ip = "10.3.0.1";
        register("reg1", ip).andExpect(status().isCreated());
        register("reg2", ip).andExpect(status().isCreated());

        register("reg3", ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }

    @Test
    void elLimiteDeRegistroYElDeLoginSonIndependientes() throws Exception {
        createUser("user4");
        String ip = "10.4.0.1";
        register("indep1", ip).andExpect(status().isCreated());
        register("indep2", ip).andExpect(status().isCreated());

        login("user4@test.com", "correct-password", ip).andExpect(status().isNoContent());
    }

    @Test
    void otrosEndpointsNoTienenLimite() throws Exception {
        createUser("user5");
        String token = "Bearer " + jwtService.generateToken(userRepository.findByEmail("user5@test.com").orElseThrow());
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(get("/api/users/me").header("Authorization", token).with(ip("10.5.0.1")))
                    .andExpect(status().isOk());
        }
    }

    // --- Bloqueo de cuenta ---

    @Test
    void elTercerFalloBloqueaLaCuentaAunqueDespuesLaPasswordSeaCorrecta() throws Exception {
        createUser("user6");
        // IPs distintas: se prueba el bloqueo por cuenta, no el límite por IP
        login("user6@test.com", "mal", "10.6.0.1").andExpect(status().isUnauthorized());
        login("user6@test.com", "mal", "10.6.0.2").andExpect(status().isUnauthorized());
        // El fallo que alcanza el máximo ya responde 429 (antes era un 401 más)
        login("user6@test.com", "mal", "10.6.0.3")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        login("user6@test.com", "correct-password", "10.6.0.4")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void elBloqueoNoDependeDeLasMayusculasDelEmail() throws Exception {
        createUser("user7");
        login("user7@test.com", "mal", "10.7.0.1");
        login("USER7@TEST.com", "mal", "10.7.0.2");
        login("User7@Test.com", "mal", "10.7.0.3");

        login("user7@test.com", "correct-password", "10.7.0.4")
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void unEmailInexistenteSeBloqueaIgualQueUnoExistente() throws Exception {
        // Si solo se bloquearan las cuentas reales, el 429 delataría qué
        // emails están registrados.
        login("fantasma@test.com", "mal", "10.8.0.1").andExpect(status().isUnauthorized());
        login("fantasma@test.com", "mal", "10.8.0.2").andExpect(status().isUnauthorized());
        login("fantasma@test.com", "mal", "10.8.0.3")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        login("fantasma@test.com", "mal", "10.8.0.4")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    void unLoginCorrectoReiniciaElContadorDeFallos() throws Exception {
        createUser("user8");
        login("user8@test.com", "mal", "10.9.0.1").andExpect(status().isUnauthorized());
        login("user8@test.com", "mal", "10.9.0.2").andExpect(status().isUnauthorized());
        login("user8@test.com", "correct-password", "10.9.0.3").andExpect(status().isNoContent());

        // Vuelve a tener 3 intentos fallidos disponibles
        login("user8@test.com", "mal", "10.9.0.4").andExpect(status().isUnauthorized());
        login("user8@test.com", "mal", "10.9.0.5").andExpect(status().isUnauthorized());
        login("user8@test.com", "correct-password", "10.9.0.6").andExpect(status().isNoContent());
    }

    @Test
    void elBloqueoDeUnaCuentaNoAfectaAOtra() throws Exception {
        createUser("user9");
        User other = new User();
        other.setUsername("other");
        other.setEmail("other@test.com");
        other.setPassword(passwordEncoder.encode("other-password"));
        other.setRole(Role.USER);
        other.setCreatedAt(Instant.now());
        userRepository.save(other);

        login("user9@test.com", "mal", "10.10.0.1");
        login("user9@test.com", "mal", "10.10.0.2");
        login("user9@test.com", "mal", "10.10.0.3");

        login("other@test.com", "other-password", "10.10.0.4").andExpect(status().isNoContent());
    }

    // --- Variantes de la ruta (regresión) ---
    //
    // El filtro comparaba getRequestURI() (la ruta tal como llega, sin
    // decodificar) con "/api/auth/login" y "/api/users". Spring MVC y las
    // reglas de autorización sí decodifican cada segmento, así que
    // "/api/auth/%6cogin" llegaba al login sin pasar por el límite. Se usa
    // URI.create porque post(String) volvería a codificar el '%'.

    /**
     * Agotado el límite de login de una IP, la misma petición con la ruta
     * codificada (que Spring MVC atiende igual) debe recibir también el 429.
     * Sin el arreglo respondía 401 y dejaba probar contraseñas sin límite.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/auth/%6cogin", "/api/auth/%6C%6F%67%69%6E", "/%61pi/auth/login", "/api/%61uth/login"
    })
    void elLimiteDeLoginNoSeSaltaCodificandoLaRuta(String encodedPath) throws Exception {
        String ip = nextVariantIp();
        // Emails propios de cada invocación: si se repitieran, el bloqueo por
        // cuenta (3 fallos) daría un 429 que no es el que se quiere probar.
        String prefix = "variante" + VARIANT_SEQUENCE.get() + "-";
        for (int i = 0; i < 3; i++) {
            login(prefix + i + "@test.com", "x", ip).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post(URI.create(encodedPath))
                        .with(ip(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", prefix + "x@test.com", "password", "x"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }

    /**
     * Agotado el límite de registro de una IP, la ruta codificada no debe
     * crear la cuenta. Sin el arreglo respondía 201 y permitía registrar
     * cuentas sin límite.
     */
    @ParameterizedTest
    @ValueSource(strings = { "/api/%75sers", "/api/user%73", "/%61pi/users" })
    void elLimiteDeRegistroNoSeSaltaCodificandoLaRuta(String encodedPath) throws Exception {
        String ip = nextVariantIp();
        String prefix = "codif" + VARIANT_SEQUENCE.get();
        register(prefix + "a", ip).andExpect(status().isCreated());
        register(prefix + "b", ip).andExpect(status().isCreated());

        mockMvc.perform(post(URI.create(encodedPath))
                        .with(ip(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(prefix + "c")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));

        assertFalse(userRepository.existsByEmail(prefix + "c@test.com"));
    }

    /**
     * Variantes que el filtro no cuenta porque tampoco llegan al registro ni
     * al login: Spring MVC no enruta la barra final ni otras mayúsculas (y las
     * reglas de autorización tampoco las hacen públicas, así que sin token
     * dan 401), y el cortafuegos de Spring Security rechaza el punto y coma
     * de {@code ;jsessionid} con un 400. En ningún caso se crea la cuenta.
     */
    @Test
    void lasVariantesQueSpringNoEnrutaNoCreanCuentasNiDanToken() throws Exception {
        String ip = nextVariantIp();
        register("noenruta1", ip).andExpect(status().isCreated());
        register("noenruta2", ip).andExpect(status().isCreated());

        for (String path : new String[] { "/api/users/", "/api/Users", "/api/USERS" }) {
            mockMvc.perform(post(URI.create(path)).with(ip(ip))
                            .contentType(MediaType.APPLICATION_JSON).content(registerBody("noenruta3")))
                    .andExpect(status().isUnauthorized());
        }
        for (String path : new String[] { "/api/auth/login/", "/api/auth/Login" }) {
            mockMvc.perform(post(URI.create(path)).with(ip(ip))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("email", "noenruta1@test.com", "password", "Secure-Pass-2026"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.token").doesNotExist());
        }
        mockMvc.perform(post(URI.create("/api/users;jsessionid=abc")).with(ip(ip))
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody("noenruta3")))
                .andExpect(status().isBadRequest());

        assertFalse(userRepository.existsByEmail("noenruta3@test.com"));
    }

    /**
     * Con un token válido (el de una cuenta ya registrada) las variantes no
     * enrutadas pasan la autorización, pero Spring MVC no las asocia al
     * registro: no se crea la cuenta, así que no son un atajo para saltarse
     * el límite.
     */
    @Test
    void conTokenLasVariantesNoEnrutadasTampocoRegistran() throws Exception {
        String ip = nextVariantIp();
        register("contoken1", ip).andExpect(status().isCreated());
        register("contoken2", ip).andExpect(status().isCreated());
        String token = "Bearer " + jwtService.generateToken(
                userRepository.findByEmail("contoken1@test.com").orElseThrow());

        for (String path : new String[] { "/api/users/", "/api/Users" }) {
            mockMvc.perform(post(URI.create(path)).with(ip(ip)).header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content(registerBody("contoken3")))
                    .andExpect(status().isNotFound());
        }

        assertFalse(userRepository.existsByEmail("contoken3@test.com"));
    }

    // --- Límite por IP en refresh (tarea 29) ---

    /**
     * El refresh tiene su propio límite (aquí 2): el tercero desde la misma IP
     * da 429 aunque no traiga cuerpo ni {@code Content-Type} (con la regla del
     * login, sin {@code Content-Type} no contaría y quedaría sin límite). Con
     * otra IP sigue funcionando.
     */
    @Test
    void superarElLimiteDeRefreshPorIpRetorna429ConRetryAfter() throws Exception {
        String ip = "10.20.0.1";
        refresh(ip, true).andExpect(status().isUnauthorized());
        refresh(ip, true).andExpect(status().isUnauthorized());

        refresh(ip, true)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
        refresh("10.20.0.2", true).andExpect(status().isUnauthorized());
    }

    /**
     * Las peticiones sin la cabecera CSRF (lo único que puede mandar otra web)
     * reciben 403 y no gastan el límite: no sirven para dejar a la víctima sin
     * poder renovar su sesión.
     */
    @Test
    void losRefreshSinCabeceraCsrfNoGastanElLimite() throws Exception {
        String ip = "10.20.0.3";
        for (int i = 0; i < 5; i++) {
            refresh(ip, false)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        }

        refresh(ip, true).andExpect(status().isUnauthorized());
        refresh(ip, true).andExpect(status().isUnauthorized());
        refresh(ip, true).andExpect(status().isTooManyRequests());
    }

    /** El límite del refresh no gasta el del login (cada uno tiene su contador). */
    @Test
    void elLimiteDeRefreshYElDeLoginSonIndependientes() throws Exception {
        createUser("user-refresh");
        String ip = "10.20.0.4";
        refresh(ip, true);
        refresh(ip, true);
        refresh(ip, true).andExpect(status().isTooManyRequests());

        login("user-refresh@test.com", "correct-password", ip).andExpect(status().isNoContent());
    }

    /**
     * Regresión: cada visita sin sesión hace un refresh sin cookie al arrancar
     * (el frontend no sabe si la cookie HttpOnly existe). Contaban en el
     * límite, así que unas pocas visitas desde la misma IP (una oficina tras
     * un NAT) dejaban el refresh en 429 para todos, también para quien sí
     * tenía sesión. Ahora responden siempre el 401 {@code SESSION_EXPIRED}
     * (borrando las cookies, como cualquier otro fallo) sin gastar nada; los
     * refresh con cookie siguen limitados (aquí 2).
     */
    @Test
    void losRefreshSinCookieNoGastanElLimiteYLosConCookieSiguenLimitados() throws Exception {
        createUser("user-refresh-anon");
        String ip = "10.20.0.5";
        for (int i = 0; i < 5; i++) {
            refreshWithCookie(ip, null)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"))
                    .andExpect(header().stringValues("Set-Cookie", hasItems(
                            startsWith("streambox_token=;"), startsWith("streambox_refresh=;"))));
        }
        refreshWithCookie(ip, "").andExpect(status().isUnauthorized());

        MvcResult login = login("user-refresh-anon@test.com", "correct-password", ip)
                .andExpect(status().isNoContent())
                .andReturn();
        String r1 = refreshCookieOf(login);

        MvcResult first = refreshWithCookie(ip, r1).andExpect(status().isNoContent()).andReturn();
        MvcResult second = refreshWithCookie(ip, refreshCookieOf(first))
                .andExpect(status().isNoContent())
                .andReturn();
        refreshWithCookie(ip, refreshCookieOf(second))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }

    /**
     * Decisión: un valor que no puede ser un token propio <b>sí</b> cuenta,
     * aunque se rechace sin base de datos. El navegador solo guarda cookies de
     * buen formato, así que contarlo no perjudica a ningún usuario legítimo.
     */
    @Test
    void losRefreshConUnValorMalFormadoSiGastanElLimite() throws Exception {
        String ip = "10.20.0.6";
        refreshWithCookie(ip, "basura").andExpect(status().isUnauthorized());
        refreshWithCookie(ip, "basura").andExpect(status().isUnauthorized());

        refreshWithCookie(ip, "basura").andExpect(status().isTooManyRequests());
    }

    // --- Utilidades ---

    /**
     * Valor con la forma de un refresh token (43 caracteres Base64URL) que no
     * existe: llega al servicio, consulta la base de datos y da 401.
     */
    private static final String UNKNOWN_REFRESH_TOKEN = "A".repeat(43);

    /**
     * Refresh como el de un navegador con una cookie de refresh (aquí, de
     * buen formato pero desconocida: da 401 si no pasa del límite), con o sin
     * la cabecera CSRF.
     */
    private ResultActions refresh(String ip, boolean csrfHeader) throws Exception {
        var request = post("/api/auth/refresh")
                .with(ip(ip))
                .cookie(new Cookie("streambox_refresh", UNKNOWN_REFRESH_TOKEN));
        if (csrfHeader) {
            request.header("X-Requested-With", "StreamBox");
        }
        return mockMvc.perform(request);
    }

    /**
     * Refresh con la cabecera CSRF y la cookie indicada.
     *
     * @param value valor de la cookie {@code streambox_refresh}; {@code null} = sin cookie
     */
    private ResultActions refreshWithCookie(String ip, String value) throws Exception {
        var request = post("/api/auth/refresh")
                .with(ip(ip))
                .header("X-Requested-With", "StreamBox");
        if (value != null) {
            request.cookie(new Cookie("streambox_refresh", value));
        }
        return mockMvc.perform(request);
    }

    /** Valor de la cookie {@code streambox_refresh} que fija la respuesta. */
    private static String refreshCookieOf(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie("streambox_refresh");
        assertNotNull(cookie, "La respuesta debía fijar la cookie del refresh");
        return cookie.getValue();
    }

    /** Secuencia para las IPs de los tests de variantes (10.11.x.y, sin uso en otros tests). */
    private static final AtomicInteger VARIANT_SEQUENCE = new AtomicInteger();

    private static String nextVariantIp() {
        int n = VARIANT_SEQUENCE.incrementAndGet();
        return "10.11." + (n / 250) + "." + (n % 250 + 1);
    }

    private ResultActions login(String email, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(ip(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private ResultActions register(String name, String ip) throws Exception {
        return mockMvc.perform(post("/api/users")
                .with(ip(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(name)));
    }

    private String registerBody(String name) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "username", name,
                "email", name + "@test.com",
                "password", "Secure-Pass-2026"));
    }

    private static RequestPostProcessor ip(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
