package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * «IP conocida» del bloqueo de cuentas de extremo a extremo (hallazgo NV-2):
 * la IP sale de {@code request.getRemoteAddr()} y pasa del controlador al
 * servicio y a {@code LoginAttemptService}.
 *
 * <p>
 * Los contadores viven en memoria durante todo el contexto, así que cada test
 * usa sus propios emails y sus propias IPs (rangos de documentación
 * {@code 203.0.113.0/24} y {@code 198.51.100.0/24}); el límite de logins por IP
 * ya es muy alto en el perfil de test, y el bloqueo de cuentas se baja a los
 * 5 fallos de producción.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "streambox.security.rate-limit.lockout.max-failures=5")
class KnownIpLockoutIntegrationTest {

    private static final String PASSWORD = "Contraseña-Correcta-2026";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
        login(email, PASSWORD, home).andExpect(status().isOk());

        for (int remaining = 4; remaining >= 1; remaining--) {
            login(email, "mal", attacker)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.remainingAttempts").value(remaining));
        }
        login(email, "mal", attacker)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        login(email, PASSWORD, home)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
        login(email, PASSWORD, attacker)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.token").doesNotExist());
        login(email, PASSWORD, newNetwork)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    /** Desde la IP conocida, adivinar contraseñas sigue limitado (5 fallos) en su propio contador. */
    @Test
    void desdeLaIpConocidaLosFallosSiguenLimitadosYNoBloqueanAOtrasIps() throws Exception {
        String email = createUser();
        String home = nextIp();
        String elsewhere = nextIp();
        login(email, PASSWORD, home).andExpect(status().isOk());

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
        login(knownOwner, PASSWORD, home).andExpect(status().isOk());

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

    private void assertSameLockResponse(MockHttpServletResponse expected, MockHttpServletResponse actual)
            throws Exception {
        assertEquals(expected.getStatus(), actual.getStatus());
        assertEquals(expected.getContentType(), actual.getContentType());
        assertNotNull(expected.getHeader("Retry-After"));
        // Cada bloqueo se provoca en un instante distinto (cada login gasta ~100 ms de
        // BCrypt), así que el Retry-After puede diferir en un par de segundos.
        long expectedRetry = Long.parseLong(expected.getHeader("Retry-After"));
        long actualRetry = Long.parseLong(actual.getHeader("Retry-After"));
        assertTrue(Math.abs(expectedRetry - actualRetry) <= 2,
                "Retry-After distinto: " + expectedRetry + " frente a " + actualRetry);
        JsonNode expectedBody = objectMapper.readTree(expected.getContentAsString());
        JsonNode actualBody = objectMapper.readTree(actual.getContentAsString());
        assertEquals(expectedBody.path("code"), actualBody.path("code"));
        assertEquals(expectedBody.path("message"), actualBody.path("message"));
        assertEquals(expectedBody.path("status"), actualBody.path("status"));
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
