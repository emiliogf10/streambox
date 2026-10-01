package com.emilio.streambox.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;

/**
 * Tests de las comprobaciones de salud (Actuator) y de que no quedan
 * endpoints de prueba ni de administración expuestos.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class ActuatorIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String adminToken;

    @BeforeEach
    void setUp() {
        User admin = new User();
        admin.setUsername("healthadmin");
        admin.setEmail("healthadmin@test.com");
        admin.setPassword(passwordEncoder.encode("password123"));
        admin.setRole(Role.ADMIN);
        adminToken = "Bearer " + jwtService.generateToken(userRepository.save(admin));
    }

    // --- Salud: pública y sin detalles ---

    @Test
    void healthEsPublicoYDevuelveSoloElEstado() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                // Sin detalles: no se revela qué base de datos ni qué componentes hay
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(strings = { "/actuator/health/liveness", "/actuator/health/readiness" })
    void lasSondasDeLivenessYReadinessSonPublicas(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    // --- Nada más de Actuator está expuesto ---

    @ParameterizedTest
    @ValueSource(strings = { "/actuator/env", "/actuator/beans", "/actuator/heapdump",
            "/actuator/loggers", "/actuator/metrics", "/actuator/mappings" })
    void losEndpointsSensiblesNoSonAccesiblesSinTokenNiSiquieraComoAdmin(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isUnauthorized());

        // Un administrador autenticado tampoco los ve: no están expuestos
        mockMvc.perform(get(path).header("Authorization", adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void infoRequiereAutenticacion() throws Exception {
        mockMvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
    }

    // --- Los controladores de prueba ya no existen ---

    @ParameterizedTest
    @ValueSource(strings = { "/", "/api/test/protected" })
    void losEndpointsDePruebaFueronEliminados(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path).header("Authorization", adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }
}
