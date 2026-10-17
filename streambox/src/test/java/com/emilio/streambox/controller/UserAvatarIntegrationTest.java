package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import com.emilio.streambox.validation.Avatar;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

/**
 * Tests de integración del icono de perfil (avatar): {@code UserResponse.avatar}
 * y el campo {@code avatar} de {@code PATCH /api/users/me}.
 *
 * <p>
 * Contrato que fijan: ausente = no cambia; {@code null} = vuelve a la inicial;
 * una clave de {@link Avatar} = se guarda; cualquier otro valor JSON = 400
 * {@code VALIDATION_ERROR} con {@code validationErrors.avatar} y sin tocar
 * nada (tampoco el nombre que viniera en la misma petición). El caso clave es
 * una clave <b>con formato válido pero fuera de la lista</b>: el
 * {@code CHECK} {@code ck_users_avatar} de la base de datos la aceptaría, así
 * que solo la lista blanca del backend la para.
 * </p>
 *
 * <p>
 * {@code avatar} debe salir siempre en el JSON (también a {@code null}): se
 * comprueba leyendo el cuerpo como árbol, porque {@code jsonPath(...).exists()}
 * de Spring trata un {@code null} como «no existe».
 * </p>
 *
 * <p>
 * Usa {@code @Transactional} como {@code UserProfileUpdateIntegrationTest}:
 * ninguna prueba necesita datos confirmados.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class UserAvatarIntegrationTest {

    private static final String ME = "/api/users/me";

    private final ObjectMapper objectMapper = new ObjectMapper();

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
        User admin = newUser("avataradmin", "avataradmin@test.com", Role.ADMIN);
        adminJwt = jwtService.generateToken(admin);
        user = newUser("avataruser", "avataruser@test.com", Role.USER);
        userJwt = jwtService.generateToken(user);
    }

    // --- UserResponse.avatar siempre presente ---

    @Test
    void getMeDevuelveAvatarNullSiNoHayNinguno() throws Exception {
        JsonNode body = json(mockMvc.perform(get(ME).header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isOk()));

        assertNullField(body, "avatar");
    }

    @Test
    void elRegistroYElListadoDeAdminTambienLlevanAvatar() throws Exception {
        JsonNode created = json(mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"recien_llegada\",\"email\":\"recien@test.com\","
                                + "\"password\":\"Secure-Pass-2026\"}"))
                .andExpect(status().isCreated()));
        assertNullField(created, "avatar");

        user.setAvatar("cat");
        userRepository.saveAndFlush(user);
        JsonNode list = json(mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isOk()));
        assertTrue(list.isArray());
        for (JsonNode item : list) {
            assertTrue(item.has("avatar"), "cada usuario del listado lleva avatar: " + item);
            String expected = item.get("id").asLong() == user.getId() ? "cat" : null;
            assertEquals(expected, item.get("avatar").isNull() ? null : item.get("avatar").asText());
        }
    }

    // --- Cambios correctos ---

    @Test
    void guardaUnAvatarDevuelveElUsuarioCompletoYGetMeLoRefleja() throws Exception {
        patchAsUser("{\"avatar\":\"rocket\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.getId()))
                .andExpect(jsonPath("$.username").value("avataruser"))
                .andExpect(jsonPath("$.email").value("avataruser@test.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.avatar").value("rocket"))
                .andExpect(jsonPath("$.password").doesNotExist());

        mockMvc.perform(get(ME).header("Authorization", "Bearer " + userJwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatar").value("rocket"));
        assertEquals("rocket", reload(user).getAvatar());
        assertEquals("avataruser", reload(user).getUsername());
    }

    /** Cada clave de la lista blanca se acepta y se guarda tal cual (también pasa el CHECK de H2). */
    @ParameterizedTest
    @EnumSource(Avatar.class)
    void cadaAvatarDeLaListaSeGuarda(Avatar avatar) throws Exception {
        patchAsUser("{\"avatar\":\"" + avatar.key() + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatar").value(avatar.key()));

        assertEquals(avatar.key(), reload(user).getAvatar());
    }

    @Test
    void avatarNullVuelveALaInicial() throws Exception {
        setStoredAvatar("ghost");

        JsonNode body = json(patchAsUser("{\"avatar\":null}").andExpect(status().isOk()));

        assertNullField(body, "avatar");
        assertNull(reload(user).getAvatar());
    }

    @Test
    void sinCampoAvatarElAvatarNoCambia() throws Exception {
        setStoredAvatar("moon");

        patchAsUser("{\"username\":\"nuevo_nombre\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("nuevo_nombre"))
                .andExpect(jsonPath("$.avatar").value("moon"));

        assertEquals("moon", reload(user).getAvatar());
    }

    @Test
    void nombreYAvatarALaVez() throws Exception {
        patchAsUser("{\"username\":\"cinefila_88\",\"avatar\":\"crown\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("cinefila_88"))
                .andExpect(jsonPath("$.avatar").value("crown"));

        User reloaded = reload(user);
        assertEquals("cinefila_88", reloaded.getUsername());
        assertEquals("crown", reloaded.getAvatar());
    }

    /** {@code "username": null} equivale a omitirlo: con avatar, solo cambia el avatar. */
    @Test
    void usernameNullConAvatarSoloCambiaElAvatar() throws Exception {
        patchAsUser("{\"username\":null,\"avatar\":\"sun\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("avataruser"))
                .andExpect(jsonPath("$.avatar").value("sun"));
    }

    @Test
    void elMismoAvatarDa200SinCambios() throws Exception {
        setStoredAvatar("zap");

        patchAsUser("{\"avatar\":\"zap\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatar").value("zap"));
    }

    @Test
    void unAdminTambienPuedeElegirAvatar() throws Exception {
        mockMvc.perform(patch(ME).header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"avatar\":\"star\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.avatar").value("star"));

        assertNull(reload(user).getAvatar(), "el avatar es de la cuenta del token, no de otra");
    }

    // --- Valores no válidos ---

    /**
     * Cualquier valor JSON que no sea {@code null} ni una clave de la lista:
     * 400 {@code VALIDATION_ERROR} con el mensaje del contrato, también para
     * números, booleanos, objetos y arrays (no un {@code MALFORMED_REQUEST}).
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "\"unicornio\"",          // formato válido para ck_users_avatar, pero fuera de la lista
            "\"rocket-2\"",           // ídem, con guion y número
            "\"Cat\"",                // mayúsculas
            "\"CAT\"",
            "\" cat\"",               // espacios: no se recortan
            "\"cat \"",
            "\"\"",                   // vacío
            "\"   \"",
            "\"https://example.com/a.png\"",
            "\"\\u212Aat\"",          // signo Kelvin: en minúsculas sería una k ASCII
            "\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"", // 33 caracteres
            "5",
            "0",
            "true",
            "false",
            "{\"key\":\"cat\"}",
            "[\"cat\"]",
            "[]"
    })
    void unAvatarFueraDeLaListaDa400YNoCambiaNada(String avatarJson) throws Exception {
        setStoredAvatar("leaf");

        patchAsUser("{\"username\":\"no_debe_cambiar\",\"avatar\":" + avatarJson + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.avatar").value("Elige uno de los avatares disponibles"))
                .andExpect(jsonPath("$.validationErrors.username").doesNotExist());

        User reloaded = reload(user);
        assertEquals("leaf", reloaded.getAvatar());
        assertEquals("avataruser", reloaded.getUsername());
    }

    @Test
    void elMensajeEsElDelContrato() {
        assertEquals("Elige uno de los avatares disponibles", UpdateProfileRequest.AVATAR_INVALID_MESSAGE);
    }

    @Test
    void avatarConUnCampoProhibidoDa400ConLosDosErroresYNoCambiaNada() throws Exception {
        patchAsUser("{\"avatar\":\"Gato\",\"email\":\"otro@test.com\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.avatar").value("Elige uno de los avatares disponibles"))
                .andExpect(jsonPath("$.validationErrors.email").value("El correo electrónico no se puede cambiar"));

        assertNull(reload(user).getAvatar());
    }

    /**
     * Un campo mal escrito ({@code "avatr"}) se ignora como cualquier propiedad
     * desconocida, y entonces el cuerpo no pide nada: 400, no un 200 que haría
     * creer al cliente que se guardó. El mensaje va literal (no la constante):
     * es el contrato que ve el cliente, y antes decía «El nombre de usuario es
     * obligatorio», falso cuando basta con mandar el avatar.
     */
    @ParameterizedTest
    @ValueSource(strings = { "{}", "{\"avatr\":\"cat\"}", "{\"username\":null}" })
    void unCuerpoSinCambiosDa400(String body) throws Exception {
        patchAsUser(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.username")
                        .value("Indica al menos un cambio: el nombre de usuario o el icono de perfil"));
    }

    @Test
    void sinTokenDa401() throws Exception {
        mockMvc.perform(patch(ME).contentType(MediaType.APPLICATION_JSON).content("{\"avatar\":\"cat\"}"))
                .andExpect(status().isUnauthorized());
    }

    // --- OpenAPI ---

    /**
     * La lista publicada es exactamente la de {@link Avatar} (más {@code null}),
     * el campo admite {@code null} en la petición y en la respuesta, y el
     * nombre ya no es obligatorio.
     */
    @Test
    void openApiPublicaLaListaDeAvataresYQueAdmitenNull() throws Exception {
        JsonNode docs = json(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()));
        JsonNode schemas = docs.get("components").get("schemas");

        JsonNode requestAvatar = schemas.get("UpdateProfileRequest").get("properties").get("avatar");
        List<String> published = new ArrayList<>();
        requestAvatar.get("enum").forEach(value -> published.add(value.isNull() ? null : value.asText()));
        List<String> expected = new ArrayList<>(Avatar.keys());
        expected.add(null);
        assertEquals(expected, published);
        assertEquals("[\"string\",\"null\"]", requestAvatar.get("type").toString());
        assertTrue(requestAvatar.get("description").asText().contains("Elige uno de los avatares disponibles"));

        // El tipo Java del campo (JsonNode) no debe colarse en el contrato como un esquema propio.
        assertTrue(!schemas.has("JsonNode"), "OpenAPI no debe documentar la estructura interna de JsonNode");

        JsonNode request = schemas.get("UpdateProfileRequest");
        assertTrue(request.get("required") == null
                || !request.get("required").toString().contains("username"), "username ya no es obligatorio");

        JsonNode responseAvatar = schemas.get("UserResponse").get("properties").get("avatar");
        assertEquals("[\"string\",\"null\"]", responseAvatar.get("type").toString());
        assertTrue(docs.get("paths").get(ME).get("patch").get("responses").get("400").get("description").asText()
                .contains("validationErrors.avatar"));
    }

    // --- Utilidades ---

    private ResultActions patchAsUser(String body) throws Exception {
        return mockMvc.perform(patch(ME).header("Authorization", "Bearer " + userJwt)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** El campo está en el JSON y vale {@code null} (no basta con que falte). */
    private static void assertNullField(JsonNode body, String field) {
        assertTrue(body.has(field), "el JSON debe llevar " + field + ": " + body);
        assertTrue(body.get(field).isNull(), field + " debe ser null: " + body);
    }

    private void setStoredAvatar(String key) {
        user.setAvatar(key);
        userRepository.saveAndFlush(user);
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

    /** Relee la cuenta de la base de datos (ver {@code UserProfileUpdateIntegrationTest#reload}). */
    private User reload(User u) {
        entityManager.flush();
        entityManager.clear();
        return userRepository.findById(u.getId()).orElseThrow();
    }
}
