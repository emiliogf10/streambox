package com.emilio.streambox.controller;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.dto.UpdateProfileRequest;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.Cookie;

/**
 * Tests de integración de {@code PATCH /api/users/me} (cambiar el propio
 * nombre de usuario).
 *
 * <p>
 * Comprueban el contrato completo: el cambio y su normalización, las
 * validaciones (también sobre el nombre ya normalizado), el 409 por nombre
 * ocupado, que el email, el rol y la contraseña no se pueden cambiar por
 * esta vía, la autenticación (401) y la defensa CSRF de las peticiones por
 * cookie (403 {@code CSRF_REJECTED}). Incluyen además el arreglo del registro,
 * que solo recortaba con {@code trim()} y aceptaba «gemelos» con espacio duro.
 * </p>
 *
 * <p>
 * Usa {@code @Transactional}: ninguna prueba necesita datos confirmados, y el
 * filtro JWT lee la cuenta en el mismo hilo y la misma transacción.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class UserProfileUpdateIntegrationTest {

    private static final String ME = "/api/users/me";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;

    private User user;
    private String userJwt;
    private String adminJwt;

    @BeforeEach
    void setUp() {
        User admin = newUser("perfiladmin", "perfiladmin@test.com", Role.ADMIN);
        adminJwt = jwtService.generateToken(admin);
        user = newUser("perfiluser", "perfiluser@test.com", Role.USER);
        userJwt = jwtService.generateToken(user);
    }

    // --- Cambio correcto ---

    @Test
    void cambiaElNombreDevuelveElUsuarioYGetMeLoRefleja() throws Exception {
        patchAsUser("{\"username\":\"cinefila_88\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId()))
                .andExpect(jsonPath("$.username").value("cinefila_88"))
                .andExpect(jsonPath("$.email").value("perfiluser@test.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist());

        mockMvc.perform(get(ME).header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("cinefila_88"));
    }

    @Test
    void normalizaEspaciosDurosInvisiblesYEspaciosRepetidos() throws Exception {
        //   = espacio duro, ​ = espacio de anchura cero (formato), \t = control.
        patchAsUser("{\"username\":\"\\u00A0 Ana \\u00A0\\t López\\u200B \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("Ana López"));

        assertEquals("Ana López", reload(user).getUsername());
    }

    @Test
    void elMismoNombreDa200SinCambios() throws Exception {
        patchAsUser("{\"username\":\"  perfiluser\\u00A0\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("perfiluser"));
    }

    @Test
    void cambiarSoloMayusculasEsUnCambio() throws Exception {
        patchAsUser("{\"username\":\"PerfilUser\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("PerfilUser"));
    }

    @Test
    void unAdminTambienPuedeCambiarSuNombre() throws Exception {
        // La regla solo-ADMIN de /api/users es de coincidencia exacta: no debe
        // atrapar /api/users/me (ni para bloquear a un USER ni para nada más).
        mockMvc.perform(patch(ME).header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"jefa_admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("jefa_admin"))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    // --- Validación ---

    @ParameterizedTest
    @ValueSource(strings = {
            "ab",
            "\\u00A0\\u00A0\\u00A0\\u00A0",       // pasa @Size pero normalizado queda vacío
            "   ab   ",                            // pasa @Size pero normalizado tiene 2
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // 51
    })
    void unNombreDeLongitudNoValidaDa400ConElMensajeDeLongitud(String username) throws Exception {
        patchAsUser("{\"username\":\"" + username + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.username")
                        .value(UpdateProfileRequest.USERNAME_SIZE_MESSAGE));

        assertEquals("perfiluser", reload(user).getUsername());
    }

    @ParameterizedTest
    @ValueSource(strings = { "{}", "{\"username\":null}" })
    void sinNombreDa400ConElMensajeDeObligatorio(String body) throws Exception {
        patchAsUser(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.username")
                        .value(UpdateProfileRequest.USERNAME_REQUIRED_MESSAGE));
    }

    // --- Nombre ocupado ---

    @Test
    void unNombreDeOtraCuentaDa409() throws Exception {
        patchAsUser("{\"username\":\"perfiladmin\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value("El nombre de usuario ya está en uso"));

        assertEquals("perfiluser", reload(user).getUsername());
    }

    @Test
    void unGemeloConEspacioDuroDeOtraCuentaDa409() throws Exception {
        patchAsUser("{\"username\":\"perfiladmin\\u00A0\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_EXISTS"));
    }

    // --- Nada más que el nombre puede cambiar ---

    @Test
    void enviarEmailDa400YNoCambiaNada() throws Exception {
        patchAsUser("{\"username\":\"otro_nombre\",\"email\":\"atacante@test.com\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.email")
                        .value("El correo electrónico no se puede cambiar"));

        User reloaded = reload(user);
        assertEquals("perfiluser@test.com", reloaded.getEmail());
        assertEquals("perfiluser", reloaded.getUsername());
    }

    @Test
    void enviarRolDa400YNoCambiaNada() throws Exception {
        patchAsUser("{\"username\":\"otro_nombre\",\"role\":\"ADMIN\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.role").value("El rol no se puede cambiar"));

        User reloaded = reload(user);
        assertEquals(Role.USER, reloaded.getRole());
        assertEquals("perfiluser", reloaded.getUsername());
    }

    @Test
    void enviarContrasenaDa400YNoCambiaNada() throws Exception {
        String hashBefore = user.getPassword();

        patchAsUser("{\"username\":\"otro_nombre\",\"password\":\"Nueva-Clave-Larga-2026\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.password")
                        .value("La contraseña no se puede cambiar desde aquí"));

        assertEquals(hashBefore, reload(user).getPassword());
    }

    @Test
    void unRolConValorNoTextualTambienEsErrorDeValidacionYNoJsonMalFormado() throws Exception {
        patchAsUser("{\"username\":\"otro_nombre\",\"role\":{\"name\":\"ADMIN\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.role").value("El rol no se puede cambiar"));
    }

    @Test
    void camposProhibidosANullYDesconocidosSeIgnoranSinCambiarNadaMas() throws Exception {
        // Se lee de la base (y no del objeto en memoria) porque la columna
        // puede guardar menos precisión que Instant.now().
        Instant createdAtBefore = reload(user).getCreatedAt();

        patchAsUser("{\"username\":\"otro_nombre\",\"email\":null,\"role\":null,\"id\":999999,"
                + "\"createdAt\":\"2000-01-01T00:00:00Z\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId()))
                .andExpect(jsonPath("$.username").value("otro_nombre"))
                .andExpect(jsonPath("$.email").value("perfiluser@test.com"))
                .andExpect(jsonPath("$.role").value("USER"));

        assertEquals(createdAtBefore, reload(user).getCreatedAt());
    }

    // --- Autenticación y CSRF ---

    @Test
    void sinTokenDa401() throws Exception {
        mockMvc.perform(patch(ME).contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"otro_nombre\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void porCookieSinCabeceraCsrfDa403YNoCambiaNada() throws Exception {
        mockMvc.perform(patch(ME).cookie(new Cookie("streambox_token", userJwt))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"otro_nombre\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));

        assertEquals("perfiluser", reload(user).getUsername());
    }

    @Test
    void porCookieConCabeceraCsrfFunciona() throws Exception {
        mockMvc.perform(patch(ME).cookie(new Cookie("streambox_token", userJwt))
                        .header("X-Requested-With", "StreamBox")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"otro_nombre\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("otro_nombre"));
    }

    // --- Registro: misma normalización ---

    @Test
    void elRegistroRechazaUnGemeloConEspacioDuro() throws Exception {
        // Antes el registro solo hacía trim(), que no quita U+00A0: se creaba
        // una segunda cuenta "perfiluser " que se ve igual que la primera.
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"perfiluser\\u00A0\",\"email\":\"gemelo@test.com\","
                                + "\"password\":\"Secure-Pass-2026\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_EXISTS"));
    }

    @Test
    void elRegistroValidaLaLongitudDelNombreNormalizado() throws Exception {
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"  ab  \",\"email\":\"corto@test.com\","
                                + "\"password\":\"Secure-Pass-2026\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.username")
                        .value(UpdateProfileRequest.USERNAME_SIZE_MESSAGE));
    }

    // --- OpenAPI ---

    @Test
    void openApiPublicaSoloElNombreYDocumentaElRechazoCsrf() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.UpdateProfileRequest.properties.username").exists())
                .andExpect(jsonPath("$.components.schemas.UpdateProfileRequest.properties.email").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.UpdateProfileRequest.properties.role").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.UpdateProfileRequest.properties.password").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/users/me'].patch.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/users/me'].patch.responses['403'].description")
                        .value(containsString("CSRF_REJECTED")));
    }

    // --- Utilidades ---

    private ResultActions patchAsUser(String body) throws Exception {
        return mockMvc.perform(patch(ME).header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private User newUser(String username, String email, Role role) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(email);
        u.setPassword(passwordEncoder.encode("irrelevante-en-este-test"));
        u.setRole(role);
        u.setCreatedAt(Instant.now());
        return userRepository.save(u);
    }

    /**
     * Relee la cuenta de la base de datos. Se vacía el contexto de persistencia
     * para que {@code findById} haga una consulta real y no devuelva la misma
     * instancia en memoria que se modificó (o no) durante la petición.
     */
    private User reload(User u) {
        entityManager.flush();
        entityManager.clear();
        return userRepository.findById(u.getId()).orElseThrow();
    }
}
