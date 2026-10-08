package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/**
 * Autorización de {@code /api/users} por <b>ruta</b>, no solo por método
 * (hallazgo NV-1 de la auditoría).
 *
 * <p>
 * La regla de {@code SecurityConfig} solo restringía {@code GET}. {@code HEAD}
 * cae en {@code anyRequest().authenticated()} y Spring MVC lo despacha al
 * {@code @GetMapping}: un usuario corriente ejecutaba el listado completo de
 * cuentas y recibía un {@code Content-Length} proporcional a cuántas hay. Estos
 * tests fallan con la regla antigua (el {@code HEAD} y los demás métodos con
 * un token {@code USER} devolvían 200/405 en vez de 403) y fijan además lo que
 * no debe romperse: el registro sigue siendo público y {@code /api/users/me}
 * sigue siendo "autenticado".
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class UsersListAuthorizationIntegrationTest {

    private static final String AUTHORIZATION = "Authorization";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        adminToken = "Bearer " + jwtService.generateToken(save("nv1-admin", Role.ADMIN));
        userToken = "Bearer " + jwtService.generateToken(save("nv1-user", Role.USER));
    }

    private User save(String name, Role role) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@nv1.test");
        user.setPassword(passwordEncoder.encode("Contraseña-Segura-2026"));
        user.setRole(role);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user);
    }

    // ------------------------------------------------------------------
    // HEAD: el método que se saltaba la regla
    // ------------------------------------------------------------------

    @Test
    void headConUserEs403() throws Exception {
        mockMvc.perform(head("/api/users").header(AUTHORIZATION, userToken))
                .andExpect(status().isForbidden());
    }

    /**
     * Con 403 el cuerpo es el error genérico: no depende de cuántas cuentas
     * hay. Con el fallo, el HEAD ejecutaba el listado.
     *
     * <p>
     * MockMvc no es Tomcat: no descarta el cuerpo del {@code HEAD} y no fija
     * {@code Content-Length} cuando se escribe con un {@code Writer}
     * ({@code getContentLength()} vale -1 siempre, así que comparar ese valor
     * era una comparación vacía que pasaba con o sin el fallo). Se compara el
     * cuerpo real, sin la marca de tiempo: esta varía de longitud según el
     * instante (0, 3, 6 o 9 decimales), de modo que compararlo entero hacía el
     * test dependiente de la hora. La longitud en un servidor real la cubre
     * {@code UsersListHeadTomcatIntegrationTest}.
     * </p>
     */
    @Test
    void headConUserNoDevuelveContentLengthDelListado() throws Exception {
        var sinMasUsuarios = mockMvc.perform(head("/api/users").header(AUTHORIZATION, userToken))
                .andReturn().getResponse();
        save("nv1-extra-a", Role.USER);
        save("nv1-extra-b", Role.USER);
        var conMasUsuarios = mockMvc.perform(head("/api/users").header(AUTHORIZATION, userToken))
                .andReturn().getResponse();

        assertEquals(403, sinMasUsuarios.getStatus());
        assertEquals(403, conMasUsuarios.getStatus());
        String cuerpoSinCuentasExtra = sinMasUsuarios.getContentAsString(StandardCharsets.UTF_8);
        String cuerpoConCuentasExtra = conMasUsuarios.getContentAsString(StandardCharsets.UTF_8);
        assertTrue(cuerpoSinCuentasExtra.contains("\"code\":\"ACCESS_DENIED\""),
                "el cuerpo debe ser el error genérico: " + cuerpoSinCuentasExtra);
        assertEquals(sinTimestamp(cuerpoSinCuentasExtra), sinTimestamp(cuerpoConCuentasExtra));
    }

    /** Quita el valor de {@code timestamp}, el único campo del error que cambia entre peticiones. */
    private static String sinTimestamp(String cuerpo) {
        return cuerpo.replaceFirst("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"-\"");
    }

    @Test
    void headConAdminEs200() throws Exception {
        mockMvc.perform(head("/api/users").header(AUTHORIZATION, adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void headSinTokenEs401() throws Exception {
        mockMvc.perform(head("/api/users"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Cualquier otro método sobre la ruta del listado
    // ------------------------------------------------------------------

    @Test
    void getConUserSigueSiendo403() throws Exception {
        mockMvc.perform(get("/api/users").header(AUTHORIZATION, userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void getConAdminSigueSiendo200() throws Exception {
        mockMvc.perform(get("/api/users").header(AUTHORIZATION, adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void otrosMetodosConUserSon403() throws Exception {
        // Antes caían en anyRequest().authenticated() y llegaban a Spring MVC
        // (405 o, si algún día existe el endpoint, la operación).
        mockMvc.perform(put("/api/users").header(AUTHORIZATION, userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/users").header(AUTHORIZATION, userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(options("/api/users").header(AUTHORIZATION, userToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Lo que NO debe cambiar
    // ------------------------------------------------------------------

    @Test
    void elRegistroSigueSiendoPublico() throws Exception {
        String body = """
                {"username":"nv1-nuevo","email":"nv1-nuevo@nv1.test","password":"Contraseña-Segura-2026"}
                """;
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void meSigueSiendoAccesibleParaUnUser() throws Exception {
        mockMvc.perform(get("/api/users/me").header(AUTHORIZATION, userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("nv1-user@nv1.test"));
        mockMvc.perform(head("/api/users/me").header(AUTHORIZATION, userToken))
                .andExpect(status().isOk());
    }

    @Test
    void meSinTokenSigueSiendo401() throws Exception {
        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());
    }
}
