package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.validation.Avatar;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Icono de perfil (avatar) a través de la API contra <b>PostgreSQL real</b>.
 *
 * <p>
 * El riesgo que cubre: la lista blanca vive en el backend ({@link Avatar}) y
 * la base de datos solo exige el formato con {@code ck_users_avatar} (V7),
 * escrito con {@code TRANSLATE}, {@code REPEAT} y {@code CHAR_LENGTH}, cuyo
 * comportamiento puede variar entre H2 y PostgreSQL. Si alguna clave de la
 * lista no pasara ese {@code CHECK} en PostgreSQL, la API la validaría y el
 * {@code UPDATE} fallaría con un 500. Aquí se guarda <b>cada</b> clave con
 * confirmación real, se lee de la base y por {@code GET /api/users/me}, y se
 * vuelve a {@code NULL}.
 * </p>
 */
class PostgresUserAvatarIntegrationTest extends PostgresIntegrationTestSupport {

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User user;
    private String token;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        user = saveUser("pgavatar", Role.USER);
        token = tokenFor(user);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @ParameterizedTest
    @EnumSource(Avatar.class)
    void cadaAvatarDeLaListaSeGuardaEnPostgresYSeLee(Avatar avatar) throws Exception {
        patchMe("{\"avatar\":\"" + avatar.key() + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatar").value(avatar.key()));

        assertEquals(avatar.key(), userRepository.findById(user.getId()).orElseThrow().getAvatar());
        mockMvc.perform(get("/api/users/me").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatar").value(avatar.key()));
    }

    @Test
    void guardarYVolverANullConfirmaEnLaBase() throws Exception {
        patchMe("{\"username\":\"pg_avatar_2\",\"avatar\":\"rocket\"}").andExpect(status().isOk());
        User saved = userRepository.findById(user.getId()).orElseThrow();
        assertEquals("rocket", saved.getAvatar());
        assertEquals("pg_avatar_2", saved.getUsername());

        JsonNode body = objectMapper.readTree(patchMe("{\"avatar\":null}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertTrue(body.has("avatar") && body.get("avatar").isNull(), body.toString());
        assertNull(userRepository.findById(user.getId()).orElseThrow().getAvatar());
    }

    /** Una clave con formato válido para el {@code CHECK} pero fuera de la lista no llega a la base. */
    @Test
    void unaClaveFueraDeLaListaDa400YNoSeGuarda() throws Exception {
        patchMe("{\"avatar\":\"unicornio\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.avatar").value("Elige uno de los avatares disponibles"));

        assertNull(userRepository.findById(user.getId()).orElseThrow().getAvatar());
    }

    private ResultActions patchMe(String body) throws Exception {
        return mockMvc.perform(patch("/api/users/me").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
