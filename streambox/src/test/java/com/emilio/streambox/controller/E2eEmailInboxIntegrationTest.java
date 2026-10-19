package com.emilio.streambox.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.emilio.streambox.email.AccountEmails;
import com.emilio.streambox.email.MemoryEmailSender;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

/**
 * Bandeja de correo del E2E ({@code GET /api/admin/e2e/emails?to=}), que solo
 * existe con el perfil {@code e2e}: devuelve los correos de una dirección (del
 * más reciente al más antiguo) para que Playwright confirme las cuentas con el
 * enlace, como haría una persona.
 *
 * <p>
 * Aunque solo exista en el E2E, cuelga de {@code /api/admin/**}: sin sesión
 * 401 y con un USER 403. Que no existe sin el perfil lo comprueban
 * {@code RegistrationIntegrationTest} (404 con el perfil {@code test}) y
 * {@code EmailSenderConfigTest} (ni se crea el bean; y {@code e2e} con
 * {@code prod} no arranca).
 * </p>
 */
@SpringBootTest
@ActiveProfiles({ "test", "e2e" })
@AutoConfigureMockMvc
class E2eEmailInboxIntegrationTest {

    private static final String DOMAIN = "@bandeja-e2e.test";
    private static final String INBOX = "/api/admin/e2e/emails";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MemoryEmailSender inbox;

    private Cookie adminSession;
    private Cookie userSession;

    @BeforeEach
    void setUp() {
        cleanUp();
        inbox.clear();
        adminSession = session(createUser("admin", Role.ADMIN));
        userSession = session(createUser("usuaria", Role.USER));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
    }

    @Test
    void unAdministradorLeeLosCorreosDeUnaDireccionConElEnlace() throws Exception {
        String email = "nueva" + DOMAIN;
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(new ObjectMapper().writeValueAsString(Map.of(
                                "username", "nueva_e2e", "email", email, "password", "Paseo-Por-El-Retiro-2026"))))
                .andExpect(status().isAccepted());

        mockMvc.perform(get(INBOX).param("to", "NUEVA" + DOMAIN).cookie(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].to").value(email))
                .andExpect(jsonPath("$[0].subject").value(AccountEmails.VERIFICATION_SUBJECT))
                .andExpect(jsonPath("$[0].body").value(containsString(
                        "https://streambox.test/verificar-email#token=")))
                .andExpect(jsonPath("$[0].sentAt").exists());
    }

    @Test
    void unaDireccionSinCorreosDevuelveUnaListaVacia() throws Exception {
        mockMvc.perform(get(INBOX).param("to", "nadie" + DOMAIN).cookie(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void sinSesionDa401YConUnUsuarioNormal403() throws Exception {
        mockMvc.perform(get(INBOX).param("to", "nadie" + DOMAIN)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(INBOX).param("to", "nadie" + DOMAIN).cookie(userSession))
                .andExpect(status().isForbidden());
    }

    private User createUser(String name, Role role) {
        User user = new User();
        user.setUsername(name + "_bandeja");
        user.setEmail(name + DOMAIN);
        user.setPassword(passwordEncoder.encode("Paseo-Por-El-Retiro-2026"));
        user.setRole(role);
        user.setEmailVerifiedAt(Instant.now());
        return userRepository.saveAndFlush(user);
    }

    private Cookie session(User user) {
        return new Cookie("streambox_token", jwtService.generateToken(user));
    }
}
