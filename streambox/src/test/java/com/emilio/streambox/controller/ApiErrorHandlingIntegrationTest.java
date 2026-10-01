package com.emilio.streambox.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;

/**
 * Comprueba que los errores del cliente (peticiones mal formadas, rutas
 * inexistentes, parámetros no permitidos...) devuelven el 4xx correcto con el
 * formato de error de la API, y no un 500.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class ApiErrorHandlingIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        adminToken = "Bearer " + jwtService.generateToken(saveUser("erradmin", Role.ADMIN));
        userToken = "Bearer " + jwtService.generateToken(saveUser("erruser", Role.USER));
    }

    // --- Errores de Spring MVC que antes acababan en 500 ---

    @Test
    void jsonMalFormadoRetorna400() throws Exception {
        mockMvc.perform(post("/api/movies")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{titulo: sin comillas"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.path").value("/api/movies"));
    }

    @Test
    void jsonMalFormadoNoFiltraDetallesInternos() throws Exception {
        mockMvc.perform(post("/api/movies")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[1,2,3]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("La petición no se puede interpretar. Revisa el formato de los datos"));
    }

    @Test
    void idConFormatoIncorrectoRetorna400() throws Exception {
        mockMvc.perform(get("/api/movies/abc").header("Authorization", userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.id").exists());
    }

    @Test
    void parametroObligatorioAusenteRetorna400() throws Exception {
        mockMvc.perform(post("/api/users/me/favorites/by-title").header("Authorization", userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.title").exists());
    }

    @Test
    void metodoNoSoportadoRetorna405() throws Exception {
        mockMvc.perform(delete("/api/genres").header("Authorization", adminToken))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void rutaInexistenteRetorna404() throws Exception {
        mockMvc.perform(get("/api/no-existe").header("Authorization", userToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/no-existe"));
    }

    @Test
    void tipoDeContenidoNoSoportadoRetorna415() throws Exception {
        mockMvc.perform(post("/api/movies")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("hola"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void errorDeValidacionDelCuerpoIncluyeLosCampos() throws Exception {
        mockMvc.perform(post("/api/genres")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.name").exists());
    }

    // --- Paginación y ordenación ---

    @ParameterizedTest
    @ValueSource(strings = { "/api/movies", "/api/movies/search" })
    void ordenarPorCampoNoPermitidoRetorna400(String endpoint) throws Exception {
        for (String field : new String[] { "genres", "description", "videoUrl", "campoInventado" }) {
            mockMvc.perform(get(endpoint).param("sort", field).header("Authorization", userToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.validationErrors.sort").exists());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "id", "title", "releaseYear", "duration", "createdAt" })
    void ordenarPorCamposPermitidosFunciona(String field) throws Exception {
        mockMvc.perform(get("/api/movies").param("sort", field).header("Authorization", userToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/movies/search").param("sort", field).header("Authorization", userToken))
                .andExpect(status().isOk());
    }

    @Test
    void tamanoDePaginaFueraDeRangoRetorna400() throws Exception {
        mockMvc.perform(get("/api/movies").param("size", "101").header("Authorization", userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.size").exists());
        mockMvc.perform(get("/api/movies").param("page", "-1").header("Authorization", userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.page").exists());
    }

    private User saveUser(String name, Role role) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(role);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user);
    }
}
