package com.emilio.streambox.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;

/**
 * Comprueba las reglas de {@link SecurityConfig} que protegen la escritura
 * del catálogo ({@code /api/movies/**} y {@code /api/genres/**}).
 *
 * <p>
 * Estas pruebas no dependen de que el endpoint exista: la autorización la
 * decide la cadena de filtros de Spring Security <em>antes</em> de llegar al
 * controlador. Por eso sirven también para rutas o métodos que no tienen
 * endpoint (p. ej. {@code PATCH} u {@code OPTIONS} sobre el catálogo): sin la
 * regla correspondiente, la petición de un {@code USER} caería en
 * {@code anyRequest().authenticated()}, pasaría el filtro y no recibiría un
 * 403, así que el test fallaría. Que la regla de cierre no bloquee nada
 * legítimo lo comprueba {@link CatalogClosureRuleRegressionIntegrationTest}.
 * </p>
 *
 * <p>
 * El caso de un {@code ADMIN} no se comprueba aquí con un código concreto:
 * depende de que el endpoint exista y lo cubren los tests de cada
 * controlador.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class CatalogWriteAuthorizationIntegrationTest {

    private static final String GENRE = "/api/genres/1";
    private static final String MOVIE = "/api/movies/1";
    private static final String BODY = "{\"name\":\"Terror\"}";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String userToken;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("catalogwriteuser");
        user.setEmail("catalogwriteuser@test.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        userToken = "Bearer " + jwtService.generateToken(userRepository.save(user));
    }

    // --- Géneros: PUT y DELETE ---

    @Test
    void putGeneroSinTokenRetorna401() throws Exception {
        mockMvc.perform(put(GENRE).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void putGeneroConTokenUserRetorna403() throws Exception {
        mockMvc.perform(put(GENRE).header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void deleteGeneroSinTokenRetorna401() throws Exception {
        mockMvc.perform(delete(GENRE))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteGeneroConTokenUserRetorna403() throws Exception {
        mockMvc.perform(delete(GENRE).header("Authorization", userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    // --- PATCH: hoy no existe en el catálogo, pero no debe quedar abierto ---

    @Test
    void patchGeneroSinTokenRetorna401() throws Exception {
        mockMvc.perform(patch(GENRE).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void patchGeneroConTokenUserRetorna403() throws Exception {
        mockMvc.perform(patch(GENRE).header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void patchPeliculaSinTokenRetorna401() throws Exception {
        mockMvc.perform(patch(MOVIE).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void patchPeliculaConTokenUserRetorna403() throws Exception {
        mockMvc.perform(patch(MOVIE).header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
    }

    // --- Red de seguridad: métodos sin regla propia ---

    @Test
    void metodoSinReglaPropiaSobreElCatalogoConTokenUserRetorna403() throws Exception {
        // OPTIONS no tiene regla explícita: lo atrapa la regla por defecto del
        // catálogo (solo ADMIN) en lugar de anyRequest().authenticated().
        mockMvc.perform(request(HttpMethod.OPTIONS, GENRE).header("Authorization", userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(request(HttpMethod.OPTIONS, MOVIE).header("Authorization", userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void lecturaConHeadSigueDisponibleParaUnUser() throws Exception {
        // HEAD es una lectura: la red de seguridad no debe convertirla en
        // una operación de administrador.
        mockMvc.perform(head("/api/genres").header("Authorization", userToken))
                .andExpect(status().isOk());
        mockMvc.perform(head("/api/movies").header("Authorization", userToken))
                .andExpect(status().isOk());
    }
}
