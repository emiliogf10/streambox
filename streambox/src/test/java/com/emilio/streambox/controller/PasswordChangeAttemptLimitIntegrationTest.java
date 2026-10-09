package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

/**
 * Límite de intentos con la contraseña actual incorrecta en
 * {@code PUT /api/users/me/password} ({@code PasswordChangeAttemptService}).
 *
 * <p>
 * Sin él, quien robe una sesión podría adivinar la contraseña por fuerza bruta
 * desde ella, sin pasar por el bloqueo del login. Usa los límites del bloqueo
 * del login, que aquí se bajan a 3 fallos (en {@code application-test} están
 * altísimos); el contador vive en el contexto, así que cada test usa su propio
 * usuario (la clave es su id).
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = "streambox.security.rate-limit.lockout.max-failures=3")
class PasswordChangeAttemptLimitIntegrationTest {

    private static final String DOMAIN = "@pwd-limit.test";
    private static final String PASSWORD = "Contraseña-Del-Limite-2026";
    private static final String NEW_PASSWORD = "Otra-Clave-Distinta-2026!";
    private static final String WRONG = "No-Es-Mi-Contraseña-1";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void deleteUsersOfThisSuite() {
        userRepository.deleteAll(userRepository.findAll().stream()
                .filter(user -> user.getEmail().endsWith(DOMAIN))
                .toList());
    }

    /**
     * Tras 3 fallos, el cuarto intento da 429 aunque la contraseña sea la
     * correcta (no se llega a comprobar) y no cambia nada. El contador es por
     * cuenta: no bloquea el login ni a otro usuario.
     */
    @Test
    void agotadosLosIntentosDa429AunqueLaContrasenaSeaCorrecta() throws Exception {
        User user = createUser("agota");
        String access = login(user);
        String hashBefore = storedHash(user);

        for (int i = 0; i < 3; i++) {
            change(access, WRONG).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INCORRECT"));
        }

        MvcResult blocked = change(access, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andReturn();
        long retryAfter = Long.parseLong(blocked.getResponse().getHeader(HttpHeaders.RETRY_AFTER));
        assertTrue(retryAfter > 0 && retryAfter <= 15 * 60, "Retry-After: " + retryAfter);
        assertEquals(hashBefore, storedHash(user));

        // Otro contador: el login de la cuenta sigue funcionando y otro usuario no se ve afectado.
        login(user);
        User other = createUser("otro");
        change(login(other), WRONG).andExpect(status().isBadRequest());
    }

    /** Un acierto borra los fallos acumulados (como el login). */
    @Test
    void unAciertoBorraLosFallos() throws Exception {
        User user = createUser("acierto");
        String access = login(user);

        change(access, WRONG).andExpect(status().isBadRequest());
        change(access, WRONG).andExpect(status().isBadRequest());
        MvcResult changed = change(access, PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent()).andReturn();
        String newAccess = changed.getResponse().getCookie("streambox_token").getValue();

        // Con los dos fallos anteriores, el segundo de estos sería ya un 429.
        for (int i = 0; i < 3; i++) {
            change(newAccess, WRONG).andExpect(status().isBadRequest());
        }
        change(newAccess, WRONG).andExpect(status().isTooManyRequests());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private User createUser(String name) {
        User user = new User();
        user.setUsername(name + "-limite");
        user.setEmail(name + DOMAIN);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user);
    }

    private String storedHash(User user) {
        return userRepository.findById(user.getId()).orElseThrow().getPassword();
    }

    /** Inicia sesión con la contraseña original y devuelve el JWT de acceso. */
    private String login(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", user.getEmail(), "password", PASSWORD))))
                .andExpect(status().isNoContent())
                .andReturn();
        return result.getResponse().getCookie("streambox_token").getValue();
    }

    private ResultActions change(String access, String currentPassword) throws Exception {
        return change(access, currentPassword, NEW_PASSWORD);
    }

    private ResultActions change(String access, String currentPassword, String newPassword) throws Exception {
        return mockMvc.perform(put("/api/users/me/password")
                .cookie(new Cookie("streambox_token", access))
                .header("X-Requested-With", "StreamBox")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("currentPassword", currentPassword, "newPassword", newPassword))));
    }
}
