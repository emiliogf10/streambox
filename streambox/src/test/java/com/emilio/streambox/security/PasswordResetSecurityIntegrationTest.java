package com.emilio.streambox.security;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.emilio.streambox.dto.AcceptedResponse;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.InvalidResetTokenException;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.ratelimit.RateLimitingFilter;

import jakarta.servlet.http.Cookie;

/**
 * Reglas de seguridad de «He olvidado mi contraseña»
 * ({@value RateLimitingFilter#PASSWORD_RESET_PATH} y
 * {@value RateLimitingFilter#PASSWORD_RESET_CONFIRM_PATH}), escritas <b>antes
 * que los endpoints</b> («la regla antes que el endpoint»), como se hizo con la
 * verificación del correo.
 *
 * <p>
 * Cada test falla si se quita la protección que prueba:
 * </p>
 * <ul>
 * <li>{@code permitAll} solo del {@code POST}: sin la regla, el {@code POST}
 * sería un 401; con una regla por ruta en vez de por método, el resto de
 * métodos dejaría de dar 401.</li>
 * <li>La cookie de sesión se ignora en las dos rutas: sin la exención, una
 * sesión abierta sin {@code X-Requested-With} recibiría el 403
 * {@code CSRF_REJECTED}.</li>
 * <li>Cada ruta tiene su límite por IP (429 {@code RATE_LIMIT_EXCEEDED} con
 * {@code Retry-After}), independiente de los demás, y lo que otra web puede
 * enviar sin preflight no lo gasta.</li>
 * </ul>
 *
 * <p>
 * <b>«Llega al endpoint» se comprueba con la respuesta real.</b> Se escribieron
 * antes que los endpoints y, mientras no existían, solo podían afirmar que la
 * cadena de seguridad no rechazaba la petición (ni 401 ni 403 ni 429). Con los
 * endpoints ya escritos ({@code PasswordResetController}) afirman lo que
 * responde cada uno: 202 con el texto fijo al pedir el enlace, 400
 * {@code INVALID_RESET_TOKEN} al confirmar con un token que no existe y 400
 * {@code VALIDATION_ERROR} con un cuerpo no válido. Así un 404 (ruta mal
 * escrita) o un 500 también harían fallar estos tests.
 * </p>
 *
 * <p>
 * Los límites se bajan a 2 con {@code @TestPropertySource} (contexto propio) y
 * cada test usa IPs únicas (10.241.x.y), porque los contadores viven en el
 * contexto. Los usuarios de la clase (dominio {@value #DOMAIN}) se borran antes
 * y después de cada test.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "streambox.security.rate-limit.password-reset.max-requests=2",
        "streambox.security.rate-limit.password-reset-confirm.max-requests=2"
})
class PasswordResetSecurityIntegrationTest {

    private static final String DOMAIN = "@restablecer.test";
    private static final String RESET = "/api/auth/password-reset";
    private static final String CONFIRM = "/api/auth/password-reset/confirm";

    /** Cuerpos con la forma del contrato (el email y el token no existen). */
    private static final String RESET_BODY = "{\"email\":\"nadie" + DOMAIN + "\"}";
    private static final String CONFIRM_BODY = "{\"token\":\"" + "A".repeat(43)
            + "\",\"newPassword\":\"Una-Contraseña-Larga-2026\"}";

    /** IPs que no usa ningún otro test (10.241.x.y). */
    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    @BeforeEach
    @AfterEach
    void deleteUsersOfThisSuite() {
        userRepository.deleteAll(userRepository.findAll().stream()
                .filter(user -> user.getEmail().endsWith(DOMAIN))
                .toList());
    }

    // ------------------------------------------------------------------
    // Públicas solo con POST
    // ------------------------------------------------------------------

    /**
     * Sin sesión, el {@code POST} de cada ruta llega al endpoint y recibe su
     * respuesta real. Si la regla {@code permitAll} desapareciera, sería un 401.
     */
    @ParameterizedTest
    @ValueSource(strings = { RESET, CONFIRM })
    void elPostEsPublico(String path) throws Exception {
        postJson(path, nextIp()).andExpect(reachesTheEndpoint(path));
    }

    /**
     * Un cuerpo que no supera la validación también llega al endpoint sin
     * sesión: 400 {@code VALIDATION_ERROR} con el campo culpable (un email mal
     * formado al pedir el enlace; una contraseña demasiado corta al confirmar).
     * Es la validación del controlador, no un rechazo de la cadena.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "/api/auth/password-reset|{\"email\":\"no-es-un-correo\"}|email",
            "/api/auth/password-reset/confirm|{\"token\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\","
                    + "\"newPassword\":\"corta\"}|newPassword"
    })
    void unCuerpoNoValidoLlegaAlEndpointYDa400DeValidacion(String path, String body, String field)
            throws Exception {
        mockMvc.perform(post(path).with(ip(nextIp())).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors." + field).exists());
    }

    /**
     * Cualquier otro método sobre esas rutas, y las rutas vecinas, siguen
     * pidiendo token: la regla {@code permitAll} es solo del {@code POST}
     * exacto (con {@code HEAD} u {@code OPTIONS} no se ejecuta nada sin
     * autenticar, y {@code /confirm} no queda abierta por ser «hija» de la
     * otra ruta: tiene su propia regla).
     */
    @ParameterizedTest
    @CsvSource({
            "GET, /api/auth/password-reset", "HEAD, /api/auth/password-reset",
            "OPTIONS, /api/auth/password-reset", "PUT, /api/auth/password-reset",
            "PATCH, /api/auth/password-reset", "DELETE, /api/auth/password-reset",
            "GET, /api/auth/password-reset/confirm", "HEAD, /api/auth/password-reset/confirm",
            "OPTIONS, /api/auth/password-reset/confirm", "PUT, /api/auth/password-reset/confirm",
            "PATCH, /api/auth/password-reset/confirm", "DELETE, /api/auth/password-reset/confirm",
            "POST, /api/auth/password-reset/otra", "POST, /api/auth/password-reset/confirm/otra",
            "POST, /api/auth/password-reset/", "POST, /api/auth/Password-Reset"
    })
    void losDemasMetodosYRutasVecinasPidenToken(String method, String path) throws Exception {
        mockMvc.perform(request(HttpMethod.valueOf(method), path).with(ip(nextIp()))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // La cookie de sesión se ignora
    // ------------------------------------------------------------------

    /**
     * Con una sesión abierta en el navegador (cookie {@code streambox_token}
     * válida) y sin {@code X-Requested-With}, el {@code POST} no recibe el 403
     * {@code CSRF_REJECTED}: la cookie se ignora en estas rutas, como en login,
     * registro y verificación del correo (no hay sesión que proteger; ver
     * {@code SecurityConfig}). Sin la exención, el filtro JWT autenticaría la
     * petición con la cookie y la rechazaría por la cabecera. Llega al endpoint
     * y recibe su respuesta real.
     */
    @ParameterizedTest
    @ValueSource(strings = { RESET, CONFIRM })
    void conUnaSesionAbiertaYSinLaCabeceraCsrfNoDa403(String path) throws Exception {
        User account = createUser(path.endsWith("confirm") ? "c1" : "r1");
        String jwt = jwtService.generateToken(account);

        mockMvc.perform(post(path).with(ip(nextIp())).cookie(new Cookie("streambox_token", jwt))
                        .contentType(MediaType.APPLICATION_JSON).content(bodyFor(path)))
                .andExpect(reachesTheEndpoint(path));
    }

    // ------------------------------------------------------------------
    // Límite por IP
    // ------------------------------------------------------------------

    /**
     * Límite por IP (aquí 2) en cada ruta: las dos primeras peticiones JSON
     * llegan al endpoint, la tercera de la misma IP da 429
     * {@code RATE_LIMIT_EXCEEDED} con {@code Retry-After} (el límite va delante
     * del endpoint), y otra IP vuelve a llegar.
     */
    @ParameterizedTest
    @ValueSource(strings = { RESET, CONFIRM })
    void cadaRutaTieneSuLimitePorIp(String path) throws Exception {
        String sameIp = nextIp();
        postJson(path, sameIp).andExpect(reachesTheEndpoint(path));
        postJson(path, sameIp).andExpect(reachesTheEndpoint(path));

        postJson(path, sameIp)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(header().exists("Retry-After"));
        postJson(path, nextIp()).andExpect(reachesTheEndpoint(path));
    }

    /**
     * Las rutas no comparten contador: agotar las peticiones del enlace no
     * impide confirmar con el que ya llegó (ni al revés), ni gasta el reenvío
     * de la verificación del correo.
     */
    @Test
    void pedirElEnlaceYConfirmarNoCompartenContador() throws Exception {
        String sameIp = nextIp();
        postJson(RESET, sameIp);
        postJson(RESET, sameIp);
        postJson(RESET, sameIp).andExpect(status().isTooManyRequests());

        postJson(CONFIRM, sameIp).andExpect(reachesTheEndpoint(CONFIRM));
        postJson(CONFIRM, sameIp).andExpect(reachesTheEndpoint(CONFIRM));
        postJson(CONFIRM, sameIp).andExpect(status().isTooManyRequests());
        mockMvc.perform(post("/api/auth/email-verification/resend").with(ip(sameIp))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    /**
     * Lo que otra web puede enviar sin preflight (text/plain, formulario, sin
     * Content-Type) no gasta el presupuesto de la víctima: tras muchas, la
     * petición JSON de la misma IP sigue pasando.
     */
    @ParameterizedTest
    @ValueSource(strings = { RESET, CONFIRM })
    void lasPeticionesSimplesDeOtraWebNoGastanElLimite(String path) throws Exception {
        String sameIp = nextIp();
        for (int i = 0; i < 4; i++) {
            mockMvc.perform(post(path).with(ip(sameIp)).contentType(MediaType.TEXT_PLAIN).content(bodyFor(path)))
                    .andExpect(status().is(not(429)));
            mockMvc.perform(post(path).with(ip(sameIp)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .content("a=b"))
                    .andExpect(status().is(not(429)));
            mockMvc.perform(post(path).with(ip(sameIp)))
                    .andExpect(status().is(not(429)));
        }

        postJson(path, sameIp).andExpect(reachesTheEndpoint(path));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * La respuesta real del endpoint al cuerpo de {@link #bodyFor}: pedir el
     * enlace para un email sin cuenta da el 202 de siempre; confirmar con un
     * token que no existe, el 400 {@code INVALID_RESET_TOKEN}. Implica que la
     * cadena no la rechazó (ni 401, ni 403, ni 429) y que la ruta existe.
     */
    private static ResultMatcher reachesTheEndpoint(String path) {
        if (path.equals(CONFIRM)) {
            return allOf(status().isBadRequest(),
                    jsonPath("$.code").value("INVALID_RESET_TOKEN"),
                    jsonPath("$.message").value(InvalidResetTokenException.MESSAGE));
        }
        return allOf(status().isAccepted(),
                jsonPath("$.message").value(AcceptedResponse.PASSWORD_RESET_MESSAGE));
    }

    /** Une varios comprobadores en uno (se aplican en orden; el primero que falla corta). */
    private static ResultMatcher allOf(ResultMatcher... matchers) {
        return result -> {
            for (ResultMatcher matcher : matchers) {
                matcher.match(result);
            }
        };
    }

    private User createUser(String name) {
        User user = new User();
        user.setUsername(name + "-restablecer");
        user.setEmail(name + DOMAIN);
        user.setPassword(passwordEncoder.encode("Contraseña-Restablecer-2026"));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        user.setEmailVerifiedAt(Instant.now());
        return userRepository.save(user);
    }

    private static String bodyFor(String path) {
        return path.equals(CONFIRM) ? CONFIRM_BODY : RESET_BODY;
    }

    private ResultActions postJson(String path, String ip) throws Exception {
        return mockMvc.perform(post(path).with(ip(ip)).contentType(MediaType.APPLICATION_JSON).content(bodyFor(path)));
    }

    private static String nextIp() {
        int n = IP_SEQUENCE.getAndIncrement();
        return "10.241." + (n / 250) + "." + (n % 250 + 1);
    }

    private static RequestPostProcessor ip(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
