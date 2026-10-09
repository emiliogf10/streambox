package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.AuthCookieService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * «IP conocida» del bloqueo de cuentas de extremo a extremo (hallazgo NV-2):
 * la IP sale de {@code request.getRemoteAddr()} y pasa del controlador al
 * servicio y a {@code LoginAttemptService}. Con el login por cookie (tarea 29)
 * un acierto es un 204 con la cookie de sesión, y un bloqueo no debe entregar
 * esa cookie aunque la contraseña sea correcta.
 *
 * <p>
 * Los contadores viven en memoria durante todo el contexto, así que cada test
 * usa sus propios emails y sus propias IPs (rangos de documentación
 * {@code 203.0.113.0/24} y {@code 198.51.100.0/24}); el límite de logins por IP
 * ya es muy alto en el perfil de test, y el bloqueo de cuentas se baja a los
 * 5 fallos de producción.
 * </p>
 *
 * <p>
 * <b>Reloj detenido.</b> El bean {@link Clock} de la aplicación se sustituye
 * con {@link TestBean} por uno fijo. {@code Retry-After} se calcula desde el
 * fallo más antiguo de la ventana ({@code SlidingWindowCounter}), así que con
 * el reloj real dependía de cuánto tardaban los cinco logins con BCrypt: con
 * la máquina cargada salió «897 frente a 900» y la comparación con ±2 s
 * fallaba sin que la respuesta fuera distinguible por diseño. Con el reloj
 * fijo todos los bloqueos valen exactamente la ventana (900 s) y la prueba de
 * indistinguibilidad puede exigir igualdad exacta, que es más estricta que la
 * tolerancia anterior. Sustituir el reloj crea un contexto propio (no comparte
 * contadores con otras suites); ninguna prueba de esta clase necesita que el
 * tiempo avance.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "streambox.security.rate-limit.lockout.max-failures=5",
        // Fijada aquí porque el Retry-After esperado se deriva de ella.
        "streambox.security.rate-limit.lockout.window=15m"
})
class KnownIpLockoutIntegrationTest {

    private static final String PASSWORD = "Contraseña-Correcta-2026";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Ventana del bloqueo en segundos: con el reloj detenido, el Retry-After de todo bloqueo. */
    private static final String LOCK_WINDOW_SECONDS = "900";

    /** Reloj de la aplicación sustituido por {@link TestBean} (ver la explicación de la clase). */
    @TestBean
    private Clock clock;

    /** Fábrica del bean {@code clock} que usa {@link TestBean} (mismo nombre que el campo). */
    static Clock clock() {
        return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    }

    /**
     * El escenario de la auditoría: el titular inicia sesión, un atacante
     * agota los intentos de la cuenta desde otra IP y el titular sigue
     * entrando desde la suya, mientras que desde una IP nueva sigue bloqueado.
     */
    @Test
    void elBloqueoDeLaCuentaNoDejaFueraAlTitularEnSuIpConocida() throws Exception {
        String email = createUser();
        String home = nextIp();
        String attacker = nextIp();
        String newNetwork = nextIp();
        login(email, PASSWORD, home).andExpect(status().isNoContent());

        for (int remaining = 4; remaining >= 1; remaining--) {
            login(email, "mal", attacker)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.remainingAttempts").value(remaining));
        }
        login(email, "mal", attacker)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        login(email, PASSWORD, home)
                .andExpect(status().isNoContent())
                .andExpect(cookie().exists(AuthCookieService.COOKIE_NAME));
        login(email, PASSWORD, attacker)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(cookie().doesNotExist(AuthCookieService.COOKIE_NAME));
        login(email, PASSWORD, newNetwork)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(cookie().doesNotExist(AuthCookieService.COOKIE_NAME));
    }

    /** Desde la IP conocida, adivinar contraseñas sigue limitado (5 fallos) en su propio contador. */
    @Test
    void desdeLaIpConocidaLosFallosSiguenLimitadosYNoBloqueanAOtrasIps() throws Exception {
        String email = createUser();
        String home = nextIp();
        String elsewhere = nextIp();
        login(email, PASSWORD, home).andExpect(status().isNoContent());

        for (int remaining = 4; remaining >= 1; remaining--) {
            login(email, "mal", home)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.remainingAttempts").value(remaining));
        }
        login(email, "mal", home)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        login(email, PASSWORD, home)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        // La cuenta no quedó bloqueada para el resto: una IP desconocida conserva sus 5 intentos.
        login(email, "mal", elsewhere)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.remainingAttempts").value(4));
    }

    /** Un fallo no «conoce» la IP: el atacante sigue sujeto al bloqueo de la cuenta. */
    @Test
    void unaIpQueSoloHaFalladoSigueSujetaAlBloqueoDeLaCuenta() throws Exception {
        String email = createUser();
        String attacker = nextIp();

        for (int i = 0; i < 4; i++) {
            login(email, "mal", attacker).andExpect(status().isUnauthorized());
        }
        login(email, "mal", attacker).andExpect(status().isTooManyRequests());

        login(email, PASSWORD, attacker)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    /**
     * El 429 de un email registrado (desde una IP desconocida) y el de uno
     * inexistente son idénticos en estado, código, mensaje y
     * {@code Retry-After}; y el de una IP conocida bloqueada en su propio
     * contador tampoco difiere. Es la indistinguibilidad petición a petición
     * desde un mismo origen: cruzar dos orígenes bajo una IP compartida con el
     * titular es una limitación conocida (ver {@code LoginAttemptService}).
     */
    @Test
    void elBloqueoEsIndistinguibleParaEmailRegistradoEInexistenteEIpConocida() throws Exception {
        String registered = createUser();
        String knownOwner = createUser();
        String ghost = "fantasma" + SEQUENCE.getAndIncrement() + "@test.com";
        String home = nextIp();
        String attacker = nextIp();
        login(knownOwner, PASSWORD, home).andExpect(status().isNoContent());

        MockHttpServletResponse onRegistered = lock(registered, attacker);
        MockHttpServletResponse onGhost = lock(ghost, attacker);
        MockHttpServletResponse onKnownIp = lock(knownOwner, home);

        assertSameLockResponse(onRegistered, onGhost);
        assertSameLockResponse(onRegistered, onKnownIp);
        // Y también las respuestas posteriores, durante el bloqueo.
        assertSameLockResponse(
                login(registered, PASSWORD, attacker).andReturn().getResponse(),
                login(ghost, PASSWORD, attacker).andReturn().getResponse());
        assertSameLockResponse(
                login(registered, PASSWORD, attacker).andReturn().getResponse(),
                login(knownOwner, PASSWORD, home).andReturn().getResponse());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Agota los intentos y devuelve el 429 del último fallo. */
    private MockHttpServletResponse lock(String email, String ip) throws Exception {
        for (int i = 0; i < 4; i++) {
            login(email, "mal", ip).andExpect(status().isUnauthorized());
        }
        return login(email, "mal", ip).andExpect(status().isTooManyRequests()).andReturn().getResponse();
    }

    /**
     * Comprueba que dos respuestas de bloqueo son indistinguibles: mismo
     * estado, tipo de contenido, {@code Retry-After} (exactamente la ventana,
     * gracias al reloj detenido) y cuerpo JSON idéntico salvo
     * {@code timestamp}, que el manejador de errores toma del reloj real.
     */
    private void assertSameLockResponse(MockHttpServletResponse expected, MockHttpServletResponse actual)
            throws Exception {
        assertEquals(429, expected.getStatus());
        assertEquals(expected.getStatus(), actual.getStatus());
        assertEquals(expected.getContentType(), actual.getContentType());
        assertEquals(LOCK_WINDOW_SECONDS, expected.getHeader("Retry-After"));
        assertEquals(expected.getHeader("Retry-After"), actual.getHeader("Retry-After"));
        assertEquals(bodyWithoutTimestamp(expected), bodyWithoutTimestamp(actual));
    }

    private JsonNode bodyWithoutTimestamp(MockHttpServletResponse response) throws Exception {
        JsonNode body = objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
        assertNotNull(body.get("code"), "el 429 debe llevar code");
        ((ObjectNode) body).remove("timestamp");
        return body;
    }

    private ResultActions login(String email, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(remoteAddr(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private String createUser() {
        String name = "knownip" + SEQUENCE.getAndIncrement();
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user).getEmail();
    }

    private static String nextIp() {
        int n = SEQUENCE.getAndIncrement();
        return "198.51.100." + (n % 250 + 1);
    }

    private static RequestPostProcessor remoteAddr(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
