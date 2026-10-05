package com.emilio.streambox.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Tests de integración del inicio de sesión ({@code POST /api/auth/login}). */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class AuthControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("loginuser");
        user.setEmail("loginuser@test.com");
        user.setPassword(passwordEncoder.encode("correct-password"));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        userRepository.save(user);
    }

    @Test
    void loginCorrectoDevuelveUnTokenValidoParaLaApi() throws Exception {
        MvcResult result = login("loginuser@test.com", "correct-password")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("token").asText();

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("loginuser@test.com"))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void passwordIncorrectaRetorna401() throws Exception {
        login("loginuser@test.com", "otra-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Email o contraseña incorrectos"))
                .andExpect(jsonPath("$.remainingAttempts").isNumber())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    /**
     * La política de contraseñas (mínimo 12 caracteres) es solo para cuentas
     * nuevas: una cuenta creada con la política anterior (mínimo 8) sigue
     * pudiendo entrar. Se inserta con su hash, como estaría en la base de datos.
     */
    @Test
    void unaCuentaAntiguaConContrasenaDe8CaracteresSigueEntrando() throws Exception {
        User legacy = new User();
        legacy.setUsername("legacyuser");
        legacy.setEmail("legacy@test.com");
        legacy.setPassword(passwordEncoder.encode("perro123"));
        legacy.setRole(Role.USER);
        legacy.setCreatedAt(Instant.now());
        userRepository.save(legacy);

        login("legacy@test.com", "perro123")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    /**
     * {@code BCryptPasswordEncoder.matches} no lanza excepciones con más de 72
     * bytes (solo {@code encode} lo hace): el login debe responder 401, no 500.
     */
    @Test
    void unaContrasenaDeMasDe72BytesEnElLoginDa401YNo500() throws Exception {
        login("loginuser@test.com", "ñ".repeat(100))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        login("nadie-bytes@test.com", "😀".repeat(100))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void usuarioInexistenteRetornaLaMismaRespuestaQuePasswordIncorrecta() throws Exception {
        // Para no revelar qué emails están registrados, el mensaje debe ser idéntico.
        String wrongPassword = login("loginuser@test.com", "otra-password")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownUser = login("nadie@test.com", "otra-password")
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String messageA = objectMapper.readTree(wrongPassword).get("message").asText();
        String messageB = objectMapper.readTree(unknownUser).get("message").asText();
        org.junit.jupiter.api.Assertions.assertEquals(messageA, messageB);
    }

    @Test
    void elEmailNoDistingueMayusculas() throws Exception {
        // El registro guarda el email en minúsculas; el login debe tolerar
        // cómo lo escriba el usuario.
        login("LoginUser@TEST.com", "correct-password").andExpect(status().isOk());
    }

    @Test
    void usuarioRegistradoConMayusculasPuedeIniciarSesion() throws Exception {
        var register = Map.of(
                "username", "mixedcase",
                "email", "Mixed.Case@Test.com",
                "password", "Secure-Pass-2026");
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated());

        login("Mixed.Case@Test.com", "Secure-Pass-2026")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void camposVaciosRetornan400() throws Exception {
        login("", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.email").exists())
                .andExpect(jsonPath("$.validationErrors.password").exists());
    }

    @Test
    void emailConFormatInvalidoRetorna400() throws Exception {
        login("no-es-un-email", "correct-password")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.email").exists());
    }

    @Test
    void cuerpoJsonMalFormadoRetorna400NoUn500() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{esto no es json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password)
            throws Exception {
        var body = Map.of("email", email, "password", password);
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
