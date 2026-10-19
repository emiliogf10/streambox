package com.emilio.streambox.controller;

import com.emilio.streambox.dto.AcceptedResponse;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tests de integración para {@link UserController}. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class UserControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private String adminToken;
    private String userToken;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        User admin = new User();
        admin.setEmailVerifiedAt(Instant.now()); // cuenta verificada (V9)
        admin.setUsername("adminuser");
        admin.setEmail("adminuser@test.com");
        admin.setPassword(passwordEncoder.encode("adminpass1"));
        admin.setRole(Role.ADMIN);
        admin.setCreatedAt(Instant.now());
        adminToken = "Bearer " + jwtService.generateToken(userRepository.save(admin));

        User user = new User();
        user.setEmailVerifiedAt(Instant.now()); // cuenta verificada (V9)
        user.setUsername("normaluser");
        user.setEmail("normaluser@test.com");
        user.setPassword(passwordEncoder.encode("userpass1"));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        userToken = "Bearer " + jwtService.generateToken(userRepository.save(user));
    }


    // --- POST /api/users (registro público) ---

    /**
     * Registro con verificación del correo: 202 con el mensaje fijo y sin
     * datos de la cuenta en el cuerpo (ni id, ni nombre, ni contraseña); la
     * cuenta se crea USER y pendiente, con la contraseña cifrada.
     */
    @Test
    void registroDatosValidosRetorna202YCreaLaCuentaPendiente() throws Exception {
        var b = Map.of("username", "newuser", "email", "newuser@test.com", "password", "Secure-Pass-2026");
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(b)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(AcceptedResponse.REGISTRATION_MESSAGE))
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.username").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());

        User created = userRepository.findByEmail("newuser@test.com").orElseThrow();
        assertEquals("newuser", created.getUsername());
        assertEquals(Role.USER, created.getRole());
        assertNull(created.getEmailVerifiedAt());
        assertTrue(passwordEncoder.matches("Secure-Pass-2026", created.getPassword()));
    }

    /**
     * Un email que ya tiene cuenta ya no da 409 «El correo electrónico ya está
     * en uso» (eso revelaba quién está registrado): el mismo 202 que un email
     * nuevo, y la cuenta existente no cambia ni se crea otra.
     */
    @Test
    void registroEmailDuplicadoRetornaElMismo202SinTocarLaCuenta() throws Exception {
        var b = Map.of("username", "otro", "email", "normaluser@test.com", "password", "Secure-Pass-2026");
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(b)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(AcceptedResponse.REGISTRATION_MESSAGE));

        User existing = userRepository.findByEmail("normaluser@test.com").orElseThrow();
        assertEquals("normaluser", existing.getUsername());
        assertTrue(passwordEncoder.matches("userpass1", existing.getPassword()));
        assertTrue(userRepository.findByUsername("otro").isEmpty());
    }

    /** El nombre de usuario de otra cuenta sigue dando 409 (no es un dato secreto). */
    @Test
    void registroUsernameDuplicadoRetorna409() throws Exception {
        var b = Map.of("username", "normaluser", "email", "otro@test.com", "password", "Secure-Pass-2026");
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(b)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value("El nombre de usuario ya está en uso"));
        assertTrue(userRepository.findByEmail("otro@test.com").isEmpty());
    }

    @Test
    void registroContrasenaCortaRetorna400() throws Exception {
        var b = Map.of("username", "u2", "email", "u2@test.com", "password", "short");
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(b)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password")
                        .value("La contraseña debe tener entre 12 y 64 caracteres"));
    }

    @Test
    void registroEmailInvalidoRetorna400() throws Exception {
        var b = Map.of("username", "u3", "email", "noemail", "password", "Secure-Pass-2026");
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(b)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.email").exists());
    }

    @Test
    void registroUsernameCortoRetorna400() throws Exception {
        var b = Map.of("username", "ab", "email", "ab@test.com", "password", "Secure-Pass-2026");
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(b)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.username").exists());
    }

    // --- GET /api/users/me ---

    @Test
    void getMeSinTokenRetorna401() throws Exception {
        mockMvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void getMeConTokenRetornaDatos() throws Exception {
        mockMvc.perform(get("/api/users/me").header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("normaluser"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    // --- GET /api/users (solo ADMIN) ---

    @Test
    void getUsersSinTokenRetorna401() throws Exception {
        mockMvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
    }

    @Test
    void getUsersConTokenUserRetorna403() throws Exception {
        mockMvc.perform(get("/api/users").header("Authorization", userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void getUsersConTokenAdminRetorna200() throws Exception {
        mockMvc.perform(get("/api/users").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(2))));
    }
}

