package com.emilio.streambox.controller;

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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tests de integración para {@link UserController}. */
@SpringBootTest
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
        admin.setUsername("adminuser");
        admin.setEmail("adminuser@test.com");
        admin.setPassword(passwordEncoder.encode("adminpass1"));
        admin.setRole(Role.ADMIN);
        admin.setCreatedAt(LocalDateTime.now());
        adminToken = "Bearer " + jwtService.generateToken(userRepository.save(admin));

        User user = new User();
        user.setUsername("normaluser");
        user.setEmail("normaluser@test.com");
        user.setPassword(passwordEncoder.encode("userpass1"));
        user.setRole(Role.USER);
        user.setCreatedAt(LocalDateTime.now());
        userToken = "Bearer " + jwtService.generateToken(userRepository.save(user));
    }


    // --- POST /api/users (registro público) ---

    @Test
    void registroDatosValidosRetorna201() throws Exception {
        var b = Map.of("username", "newuser", "email", "newuser@test.com", "password", "securepass1");
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(b)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.username").value("newuser"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void registroEmailDuplicadoRetorna409() throws Exception {
        var b = Map.of("username", "otro", "email", "normaluser@test.com", "password", "securepass1");
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(b)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_EXISTS"));
    }

    @Test
    void registroContrasenaCortaRetorna400() throws Exception {
        var b = Map.of("username", "u2", "email", "u2@test.com", "password", "short");
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(b)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password").exists());
    }

    @Test
    void registroEmailInvalidoRetorna400() throws Exception {
        var b = Map.of("username", "u3", "email", "noemail", "password", "securepass1");
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(b)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.email").exists());
    }

    @Test
    void registroUsernameCortoRetorna400() throws Exception {
        var b = Map.of("username", "ab", "email", "ab@test.com", "password", "securepass1");
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

