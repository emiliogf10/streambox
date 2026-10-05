package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Contrato completo del bloqueo de cuentas con los valores de producción (5
 * fallos en 15 minutos) y un reloj controlado (revisión independiente de QA).
 *
 * <p>
 * El bean {@link Clock} de la aplicación se sustituye con {@link TestBean} por
 * un reloj manual: así {@code Retry-After} es exacto (900, 300...), se puede
 * comprobar que el bloqueo caduca sin esperar 15 minutos y las respuestas de
 * un email registrado y uno inexistente se pueden comparar byte a byte,
 * cabeceras incluidas. Sustituir el reloj crea un contexto propio, de modo que
 * sus contadores en memoria no comparten estado con otras suites.
 * </p>
 *
 * <p>
 * <b>Sin {@code @Transactional}</b>: el test de concurrencia lanza peticiones
 * desde varios hilos, que no ven los datos de una transacción abierta en el
 * hilo del test. Los usuarios se guardan con commit real y se borran antes y
 * después de cada test (solo los de esta clase, dominio {@value #DOMAIN}).
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // Los valores de producción (application.properties): 5 fallos en 15 minutos.
        "streambox.security.rate-limit.lockout.max-failures=5",
        "streambox.security.rate-limit.lockout.window=15m"
})
class LoginLockoutContractIntegrationTest {

    private static final String DOMAIN = "@lockout.qa";
    private static final String PASSWORD = "Contraseña-Correcta-2026";
    private static final String INVALID = "Email o contraseña incorrectos";
    private static final String JUST_LOCKED =
            "Has superado el número máximo de intentos. La cuenta queda bloqueada durante 15 minutos.";

    /** Reloj manual compartido por el contexto (el tiempo solo avanza cuando el test lo pide). */
    private static final ManualClock CLOCK = new ManualClock();

    @TestBean
    private Clock clock;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
    // Contrato exacto
    // ------------------------------------------------------------------

    /**
     * El contrato que consume el frontend, con los valores reales: 401 con
     * 4, 3, 2 y 1 intentos restantes; el quinto fallo, 429
     * {@code ACCOUNT_LOCKED} con {@code Retry-After: 900}; y durante el
     * bloqueo, 429 aunque la contraseña sea correcta.
     */
    @Test
    void contratoCompletoConCincoFallosEnQuinceMinutos() throws Exception {
        createUser("contrato");
        String email = "contrato" + DOMAIN;

        for (int remaining = 4; remaining >= 1; remaining--) {
            login(email, "mal")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                    .andExpect(jsonPath("$.message").value(INVALID))
                    .andExpect(jsonPath("$.remainingAttempts").value(remaining))
                    .andExpect(header().doesNotExist("Retry-After"));
        }

        login(email, "mal")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"))
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.message").value(JUST_LOCKED));

        login(email, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "900"))
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.message").value(stillLocked("15 minutos")))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    /**
     * El bloqueo es temporal: el tiempo restante baja con el reloj, un segundo
     * antes de caducar sigue bloqueada y, al cumplirse los 15 minutos, la
     * contraseña correcta vuelve a entrar y el contador empieza de cero.
     */
    @Test
    void elBloqueoCaducaALosQuinceMinutosYElExitoReiniciaElContador() throws Exception {
        createUser("caduca");
        String email = "caduca" + DOMAIN;
        failTimes(email, 5);

        CLOCK.advance(Duration.ofMinutes(10));
        login(email, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "300"))
                .andExpect(jsonPath("$.message").value(stillLocked("5 minutos")));

        CLOCK.advance(Duration.ofMinutes(5).minusSeconds(1));
        login(email, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.message").value(stillLocked("1 minuto")));

        CLOCK.advance(Duration.ofSeconds(1));
        login(email, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        login(email, "mal")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.remainingAttempts").value(4));
    }

    // ------------------------------------------------------------------
    // Sin enumeración de usuarios
    // ------------------------------------------------------------------

    /**
     * Con el reloj parado, la respuesta a un email registrado y a uno
     * inexistente debe ser idéntica en todo lo observable: estado, todas las
     * cabeceras (nombres y valores, incluido el {@code Retry-After} exacto) y
     * el cuerpo salvo la fecha. Cualquier diferencia permitiría saber qué
     * emails tienen cuenta.
     */
    @Test
    void unEmailRegistradoYUnoInexistenteSonIndistinguiblesCabecerasIncluidas() throws Exception {
        createUser("real");
        String registered = "real" + DOMAIN;
        String ghost = "fantasma" + DOMAIN;

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertIndistinguishable(attempt,
                    login(registered, "mal").andReturn().getResponse(),
                    login(ghost, "mal").andReturn().getResponse());
        }
        assertIndistinguishable(6,
                login(registered, PASSWORD).andReturn().getResponse(),
                login(ghost, PASSWORD).andReturn().getResponse());
    }

    /**
     * Comprobación grosera de tiempos: un email inexistente también paga el
     * coste de BCrypt (se compara contra un hash falso). Si alguien quitara
     * esa comparación, el email inexistente respondería decenas de veces más
     * rápido (sin BCrypt, ~1 ms frente a ~70 ms) y se podría enumerar midiendo.
     * El margen (factor 4 en ambos sentidos, sobre medianas) es amplio a
     * propósito para no dar falsos fallos en una máquina cargada.
     */
    @Test
    void unEmailInexistenteTardaLoMismoQueUnoRegistradoEnOrdenDeMagnitud() throws Exception {
        createUser("tiempo");
        login("calentamiento" + DOMAIN, "mal");

        List<Long> registered = new ArrayList<>();
        List<Long> ghost = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            registered.add(timeLogin("tiempo" + DOMAIN));
            ghost.add(timeLogin("tiempo-fantasma" + DOMAIN));
        }

        long registeredMedian = median(registered);
        long ghostMedian = median(ghost);
        assertTrue(ghostMedian * 4 >= registeredMedian && registeredMedian * 4 >= ghostMedian,
                () -> "Tiempos demasiado distintos: registrado " + registeredMedian / 1_000_000
                        + " ms, inexistente " + ghostMedian / 1_000_000 + " ms");
    }

    // ------------------------------------------------------------------
    // Concurrencia real (varias peticiones HTTP a la vez)
    // ------------------------------------------------------------------

    /**
     * Diez logins fallidos simultáneos contra la misma cuenta: la reserva
     * atómica deja comprobar la contraseña solo a 5. Debe haber exactamente
     * cuatro 401 (con 4, 3, 2 y 1 intentos, sin repetir y nunca 0 o menos) y
     * seis 429 {@code ACCOUNT_LOCKED}; ningún 500 ni ningún otro estado. Lo
     * mismo con un email inexistente.
     */
    @Test
    void diezLoginsSimultaneosNuncaDanUn401ConCeroIntentosNiMasDeCincoComprobaciones() throws Exception {
        createUser("concurrente");

        for (String email : List.of("concurrente" + DOMAIN, "concurrente-fantasma" + DOMAIN)) {
            List<MockHttpServletResponse> responses = concurrentLogins(email, 10);

            List<Integer> remaining = new ArrayList<>();
            int locked = 0;
            for (MockHttpServletResponse response : responses) {
                JsonNode body = json(response);
                if (response.getStatus() == 401) {
                    assertEquals("INVALID_CREDENTIALS", body.path("code").asText());
                    int value = body.path("remainingAttempts").asInt();
                    assertTrue(value >= 1, () -> "401 con remainingAttempts " + value + " para " + email);
                    remaining.add(value);
                } else {
                    assertEquals(429, response.getStatus(), () -> "Estado inesperado para " + email + ": " + body);
                    assertEquals("ACCOUNT_LOCKED", body.path("code").asText());
                    assertTrue(response.getHeader("Retry-After") != null);
                    locked++;
                }
            }
            Collections.sort(remaining);
            assertEquals(List.of(1, 2, 3, 4), remaining, email);
            assertEquals(6, locked, email);
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static String stillLocked(String duration) {
        return "La cuenta está bloqueada temporalmente por demasiados intentos fallidos. "
                + "Inténtalo de nuevo en " + duration + ".";
    }

    private void createUser(String name) {
        User user = new User();
        user.setUsername("qa-" + name);
        user.setEmail(name + DOMAIN);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        userRepository.save(user);
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private void failTimes(String email, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            login(email, "mal");
        }
    }

    private long timeLogin(String email) throws Exception {
        long start = System.nanoTime();
        login(email, "mal").andExpect(status().isUnauthorized());
        return System.nanoTime() - start;
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return (sorted.get((sorted.size() - 1) / 2) + sorted.get(sorted.size() / 2)) / 2;
    }

    private List<MockHttpServletResponse> concurrentLogins(String email, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<MockHttpServletResponse> task = () -> {
                    ready.countDown();
                    start.await();
                    return login(email, "mal").andReturn().getResponse();
                };
                futures.add(pool.submit(task));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            List<MockHttpServletResponse> responses = new ArrayList<>();
            for (Future<MockHttpServletResponse> future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertIndistinguishable(int attempt, MockHttpServletResponse registered,
            MockHttpServletResponse ghost) throws Exception {
        assertEquals(registered.getStatus(), ghost.getStatus(), "estado, intento " + attempt);
        assertEquals(headers(registered), headers(ghost), "cabeceras, intento " + attempt);
        assertEquals(withoutTimestamp(registered), withoutTimestamp(ghost), "cuerpo, intento " + attempt);
    }

    private static Map<String, List<String>> headers(MockHttpServletResponse response) {
        Map<String, List<String>> headers = new TreeMap<>();
        for (String name : response.getHeaderNames()) {
            headers.put(name, response.getHeaders(name));
        }
        return headers;
    }

    private ObjectNode withoutTimestamp(MockHttpServletResponse response) throws Exception {
        ObjectNode body = (ObjectNode) json(response);
        body.remove("timestamp");
        return body;
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    /** Reloj manual y seguro entre hilos: solo avanza con {@link #advance(Duration)}. */
    static final class ManualClock extends Clock {

        private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
