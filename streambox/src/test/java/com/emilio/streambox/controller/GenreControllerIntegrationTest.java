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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tests de integración para {@link GenreController}. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GenreControllerIntegrationTest {

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
        admin.setUsername("admingenre");
        admin.setEmail("admingenre@test.com");
        admin.setPassword(passwordEncoder.encode("adminpass1"));
        admin.setRole(Role.ADMIN);
        admin.setCreatedAt(LocalDateTime.now());
        adminToken = "Bearer " + jwtService.generateToken(userRepository.save(admin));

        User user = new User();
        user.setUsername("usergenre");
        user.setEmail("usergenre@test.com");
        user.setPassword(passwordEncoder.encode("userpass1"));
        user.setRole(Role.USER);
        user.setCreatedAt(LocalDateTime.now());
        userToken = "Bearer " + jwtService.generateToken(userRepository.save(user));
    }


    @Test
    void getGenresSinTokenRetorna401() throws Exception {
        mockMvc.perform(get("/api/genres")).andExpect(status().isUnauthorized());
    }

    @Test
    void getGenresConTokenRetorna200() throws Exception {
        mockMvc.perform(get("/api/genres").header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void createGenreSinTokenRetorna401() throws Exception {
        mockMvc.perform(post("/api/genres").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Terror"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createGenreConTokenUserRetorna403() throws Exception {
        mockMvc.perform(post("/api/genres").header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Terror"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void createGenreConTokenAdminRetorna201() throws Exception {
        mockMvc.perform(post("/api/genres").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Terror"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Terror"));
    }

    @Test
    void createGenreNombreNormalizadoMayuscula() throws Exception {
        mockMvc.perform(post("/api/genres").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "  cOMeDiA  "))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Comedia"));
    }

    @Test
    void createGenreNombreVacioRetorna400() throws Exception {
        mockMvc.perform(post("/api/genres").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.name").exists());
    }

    @Test
    void createGenreNombreCortoRetorna400() throws Exception {
        mockMvc.perform(post("/api/genres").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "A"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.name").exists());
    }

    @Test
    void createGenreNombreLargoRetorna400() throws Exception {
        mockMvc.perform(post("/api/genres").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "A".repeat(51)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.name").exists());
    }
}

