package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
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

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.emilio.streambox.validation.Avatar;

import jakarta.persistence.EntityManager;

/**
 * Revisión de QA del icono de perfil: los huecos que no cubre
 * {@code UserAvatarIntegrationTest}.
 *
 * <ul>
 * <li><b>Aislamiento</b>: el avatar es de la cuenta del token; cambiar el mío
 * no toca el de nadie, y no hay ninguna ruta (ni de administrador) que cambie
 * el de otro usuario.</li>
 * <li><b>Atomicidad</b>: si el nombre de la misma petición no es válido, el
 * avatar tampoco cambia (un 400 no guarda la mitad).</li>
 * <li><b>Claves retiradas</b>: una clave guardada que ya no está en la lista
 * (lo que pasará si se quita un avatar) se devuelve tal cual, no se puede
 * volver a elegir y cambiar solo el nombre no la borra (el javadoc de
 * {@link Avatar} lo promete).</li>
 * <li><b>Cuerpos raros</b>: claves duplicadas, {@code null} como cuerpo,
 * arrays: nunca un 500 ni un avatar fuera de la lista guardado.</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class UserAvatarQaIntegrationTest {

    private static final String ME = "/api/users/me";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManager entityManager;

    private User ana;
    private User beto;
    private User admin;

    @BeforeEach
    void setUp() {
        ana = newUser("qa_ana", Role.USER);
        beto = newUser("qa_beto", Role.USER);
        admin = newUser("qa_admin", Role.ADMIN);
    }

    // --- Aislamiento entre cuentas ---

    /** Cada uno cambia solo el suyo: lo que guarda Ana no aparece en la cuenta de Beto ni al revés. */
    @Test
    void elAvatarDeUnUsuarioNoCambiaElDeOtro() throws Exception {
        patch(ana, "{\"avatar\":\"cat\"}").andExpect(status().isOk());
        patch(beto, "{\"avatar\":\"dog\"}").andExpect(status().isOk());

        me(ana).andExpect(jsonPath("$.avatar").value("cat"));
        me(beto).andExpect(jsonPath("$.avatar").value("dog"));
        assertEquals("cat", reload(ana).getAvatar());
        assertEquals("dog", reload(beto).getAvatar());
    }

    /**
     * Las rutas con id no existen para cambiar el perfil: ni un usuario ni un
     * administrador pueden cambiar el avatar de otro por {@code /api/users/{id}}
     * (no hay ningún endpoint así: 404 de ruta o 405, nunca un 200).
     */
    @Test
    void noHayRutaConIdParaCambiarElAvatarDeOtro() throws Exception {
        for (User caller : new User[] { ana, admin }) {
            int patchStatus = mockMvc.perform(patchRequest("/api/users/" + beto.getId(), caller, "{\"avatar\":\"cat\"}"))
                    .andReturn().getResponse().getStatus();
            int putStatus = mockMvc.perform(put("/api/users/" + beto.getId()).header("Authorization", bearer(caller))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"avatar\":\"cat\"}"))
                    .andReturn().getResponse().getStatus();
            assertTrue(patchStatus == 404 || patchStatus == 405 || patchStatus == 403, "PATCH: " + patchStatus);
            assertTrue(putStatus == 404 || putStatus == 405 || putStatus == 403, "PUT: " + putStatus);
        }
        assertNull(reload(beto).getAvatar());
    }

    /**
     * El alta (registro público y la del administrador por {@code POST /api/users})
     * no admite fijar el avatar: el campo se ignora y la cuenta nace sin icono.
     * Así nadie crea cuentas con un valor que no haya pasado la lista blanca.
     */
    @Test
    void elAltaIgnoraElAvatarYLaCuentaNaceSinIcono() throws Exception {
        mockMvc.perform(post("/api/users").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"qa_nueva\",\"email\":\"qa_nueva@test.com\","
                                + "\"password\":\"tres gatos pardos en el tejado\",\"avatar\":\"crown\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.avatar").doesNotExist());


        assertNull(userRepository.findByEmail("qa_nueva@test.com").orElseThrow().getAvatar());
    }

    // --- Atomicidad ---

    /** Nombre no válido + avatar válido: 400 del nombre y el avatar tampoco se guarda. */
    @ParameterizedTest
    @ValueSource(strings = { "   ", "ab", "   " })
    void siElNombreNoEsValidoElAvatarTampocoCambia(String username) throws Exception {
        setAvatar(ana, "leaf");

        patch(ana, "{\"username\":\"" + username + "\",\"avatar\":\"cat\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.username").exists());

        User reloaded = reload(ana);
        assertEquals("leaf", reloaded.getAvatar());
        assertEquals("qa_ana", reloaded.getUsername());
    }

    /** Nombre en uso + avatar válido: 409 y ni el nombre ni el avatar cambian. */
    @Test
    void siElNombreEstaEnUsoElAvatarTampocoCambia() throws Exception {
        patch(ana, "{\"username\":\"qa_beto\",\"avatar\":\"cat\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_EXISTS"));

        assertNull(reload(ana).getAvatar());
    }

    // --- Claves retiradas de la lista ---

    /**
     * Una clave guardada que ya no está en la lista (con formato válido para
     * la base de datos) sale tal cual en {@code UserResponse}, no se puede
     * volver a elegir y cambiar solo el nombre no la borra.
     */
    @Test
    void unaClaveRetiradaSeDevuelveTalCualPeroNoSePuedeElegir() throws Exception {
        setAvatar(ana, "planet");

        me(ana).andExpect(jsonPath("$.avatar").value("planet"));
        patch(ana, "{\"avatar\":\"planet\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.avatar").value("Elige uno de los avatares disponibles"));
        patch(ana, "{\"username\":\"qa_ana_2\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatar").value("planet"));
        assertEquals("planet", reload(ana).getAvatar());

        patch(ana, "{\"avatar\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatar").value((Object) null));
        assertNull(reload(ana).getAvatar());
    }

    // --- Cuerpos raros ---

    /**
     * Claves duplicadas en el JSON: valga la que valga, lo que se valida es lo
     * que se guarda (nunca se cuela una clave fuera de la lista) y no hay 500.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"avatar\":\"unicornio\",\"avatar\":\"cat\"}",
            "{\"avatar\":\"cat\",\"avatar\":\"unicornio\"}",
            "{\"avatar\":\"cat\",\"avatar\":5}"
    })
    void lasClavesDuplicadasNoSaltanseLaListaBlanca(String body) throws Exception {
        int statusCode = patch(ana, body).andReturn().getResponse().getStatus();

        assertTrue(statusCode == 200 || statusCode == 400, "estado " + statusCode);
        String stored = reload(ana).getAvatar();
        assertTrue(stored == null || Avatar.isKnownKey(stored), "guardado: " + stored);
    }

    /** Un cuerpo que no es un objeto JSON es un 400, nunca un 500, y no cambia nada. */
    @ParameterizedTest
    @ValueSource(strings = { "null", "[]", "\"cat\"", "5", "[{\"avatar\":\"cat\"}]" })
    void unCuerpoQueNoEsUnObjetoDa400(String body) throws Exception {
        patch(ana, body).andExpect(status().isBadRequest());

        assertNull(reload(ana).getAvatar());
    }

    /** El campo con otra grafía ({@code Avatar}, {@code avatar_key}) no cambia nada: 400 «sin cambios». */
    @ParameterizedTest
    @ValueSource(strings = { "{\"Avatar\":\"cat\"}", "{\"avatar_key\":\"cat\"}", "{\"AVATAR\":\"cat\"}" })
    void unCampoMalEscritoNoCambiaNadaYDa400(String body) throws Exception {
        patch(ana, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertNull(reload(ana).getAvatar());
    }

    /** Sin {@code Content-Type} JSON: 415, y el avatar no cambia. */
    @Test
    void sinContentTypeJsonDa415() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(ME)
                        .header("Authorization", bearer(ana))
                        .contentType(MediaType.TEXT_PLAIN).content("{\"avatar\":\"cat\"}"))
                .andExpect(status().isUnsupportedMediaType());

        assertNull(reload(ana).getAvatar());
    }

    // --- Utilidades ---

    private ResultActions patch(User caller, String body) throws Exception {
        return mockMvc.perform(patchRequest(ME, caller, body));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder patchRequest(
            String url, User caller, String body) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(url)
                .header("Authorization", bearer(caller))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.getBytes(StandardCharsets.UTF_8));
    }

    private ResultActions me(User caller) throws Exception {
        return mockMvc.perform(get(ME).header("Authorization", bearer(caller))).andExpect(status().isOk());
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateToken(user);
    }

    private void setAvatar(User user, String key) {
        user.setAvatar(key);
        userRepository.saveAndFlush(user);
    }

    private User newUser(String username, Role role) {
        User u = new User();
        u.setEmailVerifiedAt(Instant.now()); // cuenta verificada (V9)
        u.setUsername(username);
        u.setEmail(username + "@test.com");
        u.setPassword(passwordEncoder.encode("irrelevante-en-este-test"));
        u.setRole(role);
        u.setCreatedAt(Instant.now());
        return userRepository.save(u);
    }

    private User reload(User user) {
        entityManager.flush();
        entityManager.clear();
        return userRepository.findById(user.getId()).orElseThrow();
    }
}
