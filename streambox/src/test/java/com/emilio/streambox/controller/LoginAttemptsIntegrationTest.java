package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import com.emilio.streambox.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Tests de integración de los intentos de login: {@code remainingAttempts} en
 * el 401, bloqueo de la cuenta con {@code ACCOUNT_LOCKED} y ausencia de
 * enumeración de usuarios.
 *
 * <p>
 * Usa exactamente la misma configuración que {@link RateLimitingIntegrationTest}
 * (máximo 3 fallos por cuenta y 3 logins por IP por minuto) para que Spring
 * reutilice el mismo contexto en lugar de arrancar otro. Como los contadores
 * viven en memoria durante todo el contexto, cada test usa sus propios emails
 * y cada petición una IP nueva (así se prueba el bloqueo por cuenta sin chocar
 * con el límite por IP, salvo en el test que lo comprueba a propósito).
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
class LoginAttemptsIntegrationTest {

    private static final String PASSWORD = "Contraseña-Correcta-2026";
    private static final String INVALID = "Email o contraseña incorrectos";
    private static final String JUST_LOCKED =
            "Has superado el número máximo de intentos. La cuenta queda bloqueada durante 15 minutos.";
    private static final String STILL_LOCKED = "La cuenta está bloqueada temporalmente por demasiados "
            + "intentos fallidos. Inténtalo de nuevo en 15 minutos.";

    /** Genera IPs que no usa ningún otro test del mismo contexto (10.200.x.y). */
    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ------------------------------------------------------------------
    // Intentos restantes y bloqueo
    // ------------------------------------------------------------------

    @Test
    void cadaFalloIndicaLosIntentosRestantesYElQueAlcanzaElMaximoBloquea() throws Exception {
        createUser("att1");

        login("att1@test.com", "mal")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value(INVALID))
                .andExpect(jsonPath("$.remainingAttempts").value(2));
        login("att1@test.com", "mal")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.remainingAttempts").value(1));

        MockHttpServletResponse locked = login("att1@test.com", "mal")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.message").value(JUST_LOCKED))
                .andExpect(jsonPath("$.remainingAttempts").doesNotExist())
                .andExpect(header().exists("Retry-After"))
                .andReturn().getResponse();

        long retryAfter = Long.parseLong(locked.getHeader("Retry-After"));
        assertTrue(retryAfter >= 1 && retryAfter <= 900, "Retry-After fuera de la ventana: " + retryAfter);
    }

    @Test
    void duranteElBloqueoInclusoLaContrasenaCorrectaDa429ConElTiempoRestante() throws Exception {
        createUser("att2");
        failTimes("att2@test.com", 3);

        login("att2@test.com", PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.message").value(STILL_LOCKED))
                .andExpect(jsonPath("$.remainingAttempts").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void conLaContrasenaCorrectaEnElUltimoIntentoSeEntra() throws Exception {
        // Bloquea el fallo número 3, no el intento número 3.
        createUser("att3");
        failTimes("att3@test.com", 2);

        login("att3@test.com", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    /**
     * Los fallos salen de una IP desconocida A y el acierto de otra IP
     * desconocida B, de modo que ambos usan el contador de la CUENTA y el
     * acierto tiene que borrarlo. Los fallos posteriores vuelven a salir de A
     * (todavía desconocida). Si el acierto y los fallos posteriores salieran
     * de la misma IP, esa IP pasaría a ser «conocida», sus fallos irían a otro
     * contador vacío y el test no detectaría que el de la cuenta no se
     * reinicia.
     */
    @Test
    void unLoginCorrectoReiniciaLosIntentosRestantes() throws Exception {
        createUser("att4");
        String failingIp = nextIp();
        String successIp = nextIp();
        login("att4@test.com", "mal", failingIp).andExpect(jsonPath("$.remainingAttempts").value(2));
        login("att4@test.com", "mal", failingIp).andExpect(jsonPath("$.remainingAttempts").value(1));
        login("att4@test.com", PASSWORD, successIp).andExpect(status().isOk());

        login("att4@test.com", "mal", failingIp)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.remainingAttempts").value(2));
    }

    /**
     * Una contraseña de más de 1024 caracteres se rechaza con un 400 de
     * validación antes de llegar al servicio: no se pasa a BCrypt y no gasta
     * intento de la cuenta (los dos fallos siguientes siguen dando 2 y 1).
     * Con exactamente 1024 se comprueba como cualquier otra (401).
     */
    @Test
    void unaContrasenaDeMasDe1024CaracteresDa400YNoGastaIntento() throws Exception {
        createUser("att9");

        login("att9@test.com", "x".repeat(1025))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.password")
                        .value("La contraseña no puede tener más de 1024 caracteres"))
                .andExpect(jsonPath("$.remainingAttempts").doesNotExist());
        login("att9@test.com", "x".repeat(1025)).andExpect(status().isBadRequest());

        login("att9@test.com", "x".repeat(1024))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.remainingAttempts").value(2));
        login("att9@test.com", "mal")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.remainingAttempts").value(1));
        login("att9@test.com", PASSWORD).andExpect(status().isOk());
    }

    @Test
    void losIntentosSeCuentanSinDistinguirMayusculas() throws Exception {
        // (Un email con espacios exteriores no llega aquí: @Email lo rechaza con un 400.)
        createUser("att5");
        login("ATT5@Test.com", "mal").andExpect(jsonPath("$.remainingAttempts").value(2));
        login("att5@test.com", "mal").andExpect(jsonPath("$.remainingAttempts").value(1));
    }

    // ------------------------------------------------------------------
    // Sin enumeración de usuarios
    // ------------------------------------------------------------------

    /**
     * Si un email registrado y uno inexistente recibieran respuestas distintas
     * (otro número de intentos, otro mensaje, bloqueo solo para uno), un
     * atacante podría averiguar qué emails tienen cuenta.
     */
    @Test
    void unEmailInexistenteRecibeExactamenteLasMismasRespuestas() throws Exception {
        createUser("att6");

        for (int attempt = 1; attempt <= 3; attempt++) {
            assertSameResponse(
                    login("att6@test.com", "mal").andReturn().getResponse(),
                    login("att6-fantasma@test.com", "mal").andReturn().getResponse());
        }

        // Ya bloqueados: también idéntico, aunque al registrado se le envíe la
        // contraseña correcta.
        assertSameResponse(
                login("att6@test.com", PASSWORD).andReturn().getResponse(),
                login("att6-fantasma@test.com", PASSWORD).andReturn().getResponse());
    }

    // ------------------------------------------------------------------
    // remainingAttempts solo en el 401 del login
    // ------------------------------------------------------------------

    @Test
    void remainingAttemptsNoApareceEnOtrosErrores() throws Exception {
        User user = createUser("att7");
        String token = "Bearer " + jwtService.generateToken(user);

        // 400 de validación en el login
        mockMvc.perform(post("/api/auth/login").with(ip(nextIp()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors").exists())
                .andExpect(jsonPath("$.remainingAttempts").doesNotExist());

        // 401 sin token (lo escribe SecurityErrorResponseWriter, fuera de Spring MVC)
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.remainingAttempts").doesNotExist());

        // 404 de un recurso inexistente
        mockMvc.perform(get("/api/movies/987654321").header("Authorization", token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.remainingAttempts").doesNotExist());

        // 400 del registro (política de contraseñas) y 409 de email duplicado
        register("att7b", "att7b@test.com", "corta")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.remainingAttempts").doesNotExist());
        register("att7c", "att7@test.com", PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.remainingAttempts").doesNotExist());
    }

    @Test
    void elLimitePorIpSigueDandoRateLimitExceeded() throws Exception {
        String sameIp = nextIp();
        for (int i = 0; i < 3; i++) {
            login("att8-" + i + "@test.com", "mal", sameIp).andExpect(status().isUnauthorized());
        }

        login("att8-otro@test.com", "mal", sameIp)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.remainingAttempts").doesNotExist())
                .andExpect(header().exists("Retry-After"));
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

    private void failTimes(String email, int times) throws Exception {
        for (int i = 0; i < times; i++) {
            login(email, "mal");
        }
    }

    /** Compara estado, código, mensaje, intentos y Retry-After (ignorando la fecha). */
    private void assertSameResponse(MockHttpServletResponse registered, MockHttpServletResponse ghost)
            throws Exception {
        assertEquals(registered.getStatus(), ghost.getStatus());
        assertEquals(registered.getHeader("Retry-After") != null, ghost.getHeader("Retry-After") != null);
        assertEquals(withoutTimestamp(registered), withoutTimestamp(ghost));
    }

    private ObjectNode withoutTimestamp(MockHttpServletResponse response) throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(response.getContentAsString());
        body.remove("timestamp");
        return body;
    }

    private ResultActions login(String email, String password) throws Exception {
        return login(email, password, nextIp());
    }

    private ResultActions login(String email, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(ip(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private ResultActions register(String username, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/users")
                .with(ip(nextIp()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("username", username, "email", email, "password", password))));
    }

    private static String nextIp() {
        int n = IP_SEQUENCE.getAndIncrement();
        return "10.200." + (n / 250) + "." + (n % 250 + 1);
    }

    private static RequestPostProcessor ip(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
