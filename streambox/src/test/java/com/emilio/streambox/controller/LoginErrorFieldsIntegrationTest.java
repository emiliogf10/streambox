package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Campos opcionales de {@code ErrorResponse} tras añadir {@code remainingAttempts}
 * (revisión independiente de QA).
 *
 * <p>
 * <b>Por qué no basta con {@code jsonPath(...).doesNotExist()}.</b> En Spring,
 * {@code doesNotExist()} también pasa si la clave existe con valor
 * {@code null}. El contrato es más estricto: fuera del 401 del login,
 * {@code remainingAttempts} no debe aparecer <i>ni siquiera como null</i>
 * (el frontend comprueba si la clave está). Por eso aquí se lee el JSON y se
 * pregunta si la clave existe. Se revisan las dos vías por las que salen los
 * errores: {@code GlobalExceptionHandler} (Spring MVC, Jackson 3) y
 * {@code SecurityErrorResponseWriter} (filtros de seguridad, su propio
 * {@code ObjectMapper} de Jackson 2), porque cada una serializa por su cuenta.
 * </p>
 *
 * <p>
 * Usa la misma configuración que {@link LoginAttemptsIntegrationTest} y
 * {@link RateLimitingIntegrationTest} (3 logins por IP, 2 registros por IP, 3
 * fallos por cuenta) para reutilizar su contexto; por eso cada petición sale
 * de una IP propia (10.210.x.y) y cada test usa sus propios emails.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "streambox.security.rate-limit.login.max-requests=3",
        "streambox.security.rate-limit.register.max-requests=2",
        "streambox.security.rate-limit.lockout.max-failures=3"
})
class LoginErrorFieldsIntegrationTest {

    private static final String PASSWORD = "Contraseña-Correcta-2026";

    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Recorre un error de cada tipo (400, 401, 403, 404, 405, 409, 415 y los
     * dos 429) y comprueba que ninguno lleva la clave {@code remainingAttempts}
     * y que solo los de validación llevan {@code validationErrors}.
     */
    @Test
    void remainingAttemptsSoloApareceEnEl401DelLoginYNuncaComoNull() throws Exception {
        User user = createUser("qafields1");
        String token = "Bearer " + jwtService.generateToken(user);

        Map<String, MockHttpServletResponse> errors = new LinkedHashMap<>();

        errors.put("400 validación del login", perform(post("/api/auth/login").with(ip(nextIp()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"no-es-email\",\"password\":\"\"}")));
        errors.put("400 JSON mal formado en el login", perform(post("/api/auth/login").with(ip(nextIp()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":")));
        errors.put("415 login sin JSON", perform(post("/api/auth/login").with(ip(nextIp()))
                .contentType(MediaType.TEXT_PLAIN).content("hola")));
        errors.put("401 sin token", perform(get("/api/users/me")));
        errors.put("401 token manipulado", perform(get("/api/users/me")
                .header("Authorization", token.substring(0, token.length() - 3) + "abc")));
        errors.put("403 USER en un endpoint de ADMIN", perform(post("/api/genres").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"QA\"}")));
        errors.put("404 ruta inexistente", perform(get("/api/no-existe").header("Authorization", token)));
        errors.put("405 GET en el login", perform(get("/api/auth/login").header("Authorization", token)));
        errors.put("409 email duplicado", perform(post("/api/users").with(ip(nextIp()))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(
                        Map.of("username", "qafields1b", "email", "qafields1@test.com", "password", PASSWORD)))));
        errors.put("400 política de contraseñas", perform(post("/api/users").with(ip(nextIp()))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(
                        Map.of("username", "qafields1c", "email", "qafields1c@test.com", "password", "corta")))));

        String sameIp = nextIp();
        for (int i = 0; i < 3; i++) {
            login("qafields-ip" + i + "@test.com", "mal", sameIp);
        }
        errors.put("429 límite por IP", login("qafields-ip-extra@test.com", "mal", sameIp).andReturn().getResponse());

        for (int i = 0; i < 2; i++) {
            login("qafields1@test.com", "mal", nextIp());
        }
        errors.put("429 cuenta bloqueada", login("qafields1@test.com", "mal", nextIp()).andReturn().getResponse());

        assertEquals(400, errors.get("400 validación del login").getStatus());
        assertEquals(400, errors.get("400 JSON mal formado en el login").getStatus());
        assertEquals(415, errors.get("415 login sin JSON").getStatus());
        assertEquals(401, errors.get("401 sin token").getStatus());
        assertEquals(401, errors.get("401 token manipulado").getStatus());
        assertEquals(403, errors.get("403 USER en un endpoint de ADMIN").getStatus());
        assertEquals(404, errors.get("404 ruta inexistente").getStatus());
        assertEquals(405, errors.get("405 GET en el login").getStatus());
        assertEquals(409, errors.get("409 email duplicado").getStatus());
        assertEquals(400, errors.get("400 política de contraseñas").getStatus());
        assertEquals(429, errors.get("429 límite por IP").getStatus());
        assertEquals(429, errors.get("429 cuenta bloqueada").getStatus());

        for (Map.Entry<String, MockHttpServletResponse> entry : errors.entrySet()) {
            String label = entry.getKey();
            JsonNode body = json(entry.getValue());

            assertFalse(body.has("remainingAttempts"), () -> label + " no debe llevar remainingAttempts: " + body);
            assertTrue(body.hasNonNull("code") && body.hasNonNull("message") && body.hasNonNull("path")
                    && body.hasNonNull("status"), () -> label + " no tiene el formato ErrorResponse: " + body);

            boolean isValidation = "VALIDATION_ERROR".equals(body.path("code").asText());
            assertEquals(isValidation, body.has("validationErrors"),
                    () -> label + ": validationErrors solo en errores de validación: " + body);
        }

        assertEquals("RATE_LIMIT_EXCEEDED", json(errors.get("429 límite por IP")).path("code").asText());
        assertEquals("ACCOUNT_LOCKED", json(errors.get("429 cuenta bloqueada")).path("code").asText());
    }

    /** En el 401 del login la clave sí está, es un entero y vale al menos 1. */
    @Test
    void enEl401DelLoginRemainingAttemptsEsUnEnteroPositivo() throws Exception {
        createUser("qafields2");

        JsonNode body = json(login("qafields2@test.com", "mal", nextIp()).andReturn().getResponse());

        assertEquals("INVALID_CREDENTIALS", body.path("code").asText());
        assertTrue(body.path("remainingAttempts").isInt(), body::toString);
        assertEquals(2, body.path("remainingAttempts").asInt());
        assertFalse(body.has("validationErrors"));
    }

    /**
     * Un login rechazado por validación (400) no llega a comprobar la
     * contraseña y, por tanto, no debe gastar intentos de la cuenta: si los
     * gastara, cualquiera podría bloquear una cuenta con peticiones que ni
     * siquiera son un intento real.
     */
    @Test
    void unLoginConEmailMalFormadoNoGastaIntentosDeLaCuenta() throws Exception {
        createUser("qafields3");

        login("qafields3@test.com ", "mal", nextIp()).andExpect(status().isBadRequest());
        login(" QAFIELDS3@test.com", "mal", nextIp()).andExpect(status().isBadRequest());
        login("qafields3@@test.com", "mal", nextIp()).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/auth/login").with(ip(nextIp())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"qafields3@test.com\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());

        login("qafields3@test.com", "mal", nextIp())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.remainingAttempts").value(2));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private User createUser(String name) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user);
    }

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn().getResponse();
    }

    private ResultActions login(String email, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(ip(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private static String nextIp() {
        int n = IP_SEQUENCE.getAndIncrement();
        return "10.210." + (n / 250) + "." + (n % 250 + 1);
    }

    private static RequestPostProcessor ip(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
