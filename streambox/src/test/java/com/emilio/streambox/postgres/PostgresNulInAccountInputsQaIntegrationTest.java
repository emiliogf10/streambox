package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;

/**
 * Revisión de QA: el carácter nulo ({@code U+0000}) en los textos de la
 * <b>cuenta</b> (registro, login, perfil, contraseña) y en el nombre de un
 * género, contra PostgreSQL real.
 *
 * <p>
 * <b>Por qué hace falta.</b> PostgreSQL no admite el NUL en un texto
 * ({@code SQLSTATE 22021}) y Spring lo traduce a una
 * {@code DataIntegrityViolationException}. Desde la tanda del 2026-10-16 esa
 * excepción, si nadie la traduce, es un <b>500</b> (antes un 409 falso). La
 * tanda puso la regla de {@code ControlCharacters} en títulos, sinopsis y
 * {@code by-title}, pero no en estos campos: aquí se comprueba que ninguno de
 * ellos puede llevar el NUL hasta la base de datos. H2 sí guarda el NUL, así
 * que la suite normal no vería el fallo.
 * </p>
 *
 * <p>
 * Lo que protege cada campo hoy (comprobado en el código):
 * </p>
 * <ul>
 * <li>nombre de usuario y nombre de género: el servicio convierte los
 * caracteres de control interiores en un espacio y quita los de los extremos
 * ({@code INNER_SPACES}/{@code EDGES}); se guarda sin NUL;</li>
 * <li>email: {@code @Email} no admite el NUL (ni en el registro ni en el
 * login), así que es un 400 antes de consultar nada;</li>
 * <li>contraseña: nunca llega a la base de datos (solo su hash BCrypt), así
 * que el NUL no puede romper nada, y BCrypt no corta en el NUL (a diferencia de
 * la implementación en C): «abc\0xyz» no equivale a «abc».</li>
 * </ul>
 *
 * <p>
 * Sin {@code @Transactional} (commit real, como el resto del paquete); cada
 * test usa emails propios porque los contadores de login viven en memoria.
 * </p>
 */
class PostgresNulInAccountInputsQaIntegrationTest extends PostgresIntegrationTestSupport {

    /** Contraseña que cumple la política (12–64, no común, sin nombre ni email). */
    private static final String PASSWORD = "tres gatos pardos en el tejado";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;

    private String adminToken;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        adminToken = tokenFor(saveUser("pgnuladmin", Role.ADMIN));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Registro
    // ------------------------------------------------------------------

    /**
     * Un NUL en medio del nombre de usuario se convierte en un espacio (la
     * misma normalización que los espacios repetidos) y la cuenta se crea sin
     * NUL: ni 500 ni el texto invisible guardado.
     */
    @Test
    void unNulEnElNombreDelRegistroSeGuardaComoEspacioYNoDa500() throws Exception {
        int n = SEQUENCE.incrementAndGet();

        register("{\"username\":\"ana\\u0000pg" + n + "\",\"email\":\"nulnombre" + n + "@test.com\","
                + "\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("ana pg" + n));

        User saved = userRepository.findByEmail("nulnombre" + n + "@test.com").orElseThrow();
        assertFalse(saved.getUsername().contains("\u0000"));
    }

    /** Un NUL en el email del registro es un 400 de validación del campo, sin llegar a la base de datos. */
    @Test
    void unNulEnElEmailDelRegistroDa400() throws Exception {
        long before = userRepository.count();

        register("{\"username\":\"nulmail\",\"email\":\"nul\\u0000mail@test.com\","
                + "\"password\":\"" + PASSWORD + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.email").exists());

        assertEquals(before, userRepository.count());
    }

    /**
     * Una contraseña con un NUL en medio se acepta (solo se guarda su hash) y
     * BCrypt la usa entera: entrar con lo que hay antes del NUL falla. Si la
     * implementación cortara en el NUL (como el {@code crypt} de C), cualquiera
     * que supiera el principio entraría.
     */
    @Test
    void unaContrasenaConNulSeUsaEnteraYSuPrefijoNoAbreLaSesion() throws Exception {
        int n = SEQUENCE.incrementAndGet();
        String email = "nulpass" + n + "@test.com";
        String prefix = "tres gatos pardos";

        register("{\"username\":\"nulpass" + n + "\",\"email\":\"" + email + "\","
                + "\"password\":\"" + prefix + "\\u0000 en el tejado\"}")
                .andExpect(status().isCreated());

        login("{\"email\":\"" + email + "\",\"password\":\"" + prefix + "\"}", "10.77.0." + n)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        login("{\"email\":\"" + email + "\",\"password\":\"" + prefix + "\\u0000 en el tejado\"}", "10.77.0." + n)
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // Login
    // ------------------------------------------------------------------

    /** Un NUL en el email del login es un 400: no se consulta la base de datos con él. */
    @Test
    void unNulEnElEmailDelLoginDa400() throws Exception {
        login("{\"email\":\"nul\\u0000login@test.com\",\"password\":\"" + PASSWORD + "\"}", "10.77.1.1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.email").exists());
    }

    /** Un NUL en la contraseña del login es una contraseña incorrecta más (401), nunca un 500. */
    @Test
    void unNulEnLaContrasenaDelLoginDa401() throws Exception {
        saveUser("nulloginpass", Role.USER);

        login("{\"email\":\"nulloginpass@test.com\",\"password\":\"password\\u0000123\"}", "10.77.1.2")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    // ------------------------------------------------------------------
    // Perfil y contraseña
    // ------------------------------------------------------------------

    /** El nombre del perfil sigue la misma normalización que el registro: el NUL acaba en un espacio. */
    @Test
    void unNulEnElNombreDelPerfilSeGuardaComoEspacio() throws Exception {
        User user = saveUser("nulperfil", Role.USER);

        mockMvc.perform(withJson(patch("/api/users/me"), "{\"username\":\"nuevo\\u0000nombre\"}")
                        .header("Authorization", tokenFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("nuevo nombre"));

        assertEquals("nuevo nombre", userRepository.findById(user.getId()).orElseThrow().getUsername());
    }

    /** Un avatar con NUL no es una clave de la lista: 400 del campo y nada guardado. */
    @Test
    void unAvatarConNulDa400() throws Exception {
        User user = saveUser("nulavatar", Role.USER);

        mockMvc.perform(withJson(patch("/api/users/me"), "{\"avatar\":\"cat\\u0000\"}")
                        .header("Authorization", tokenFor(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.avatar").value("Elige uno de los avatares disponibles"));

        assertEquals(null, userRepository.findById(user.getId()).orElseThrow().getAvatar());
    }

    /** Un NUL en la contraseña actual del cambio de contraseña es «no es correcta» (400), nunca un 500. */
    @Test
    void unNulEnLaContrasenaActualDelCambioDa400() throws Exception {
        User user = saveUser("nulcambio", Role.USER);

        mockMvc.perform(withJson(put("/api/users/me/password"),
                        "{\"currentPassword\":\"password\\u0000123\",\"newPassword\":\"" + PASSWORD + "\"}")
                        .header("Authorization", tokenFor(user)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INCORRECT"));
    }

    // ------------------------------------------------------------------
    // Géneros
    // ------------------------------------------------------------------

    /** El nombre de un género se normaliza igual: el NUL interior acaba en un espacio y no da 500. */
    @Test
    void unNulEnElNombreDeUnGeneroSeGuardaComoEspacio() throws Exception {
        mockMvc.perform(withJson(post("/api/genres"), "{\"name\":\"cine\\u0000negro\"}")
                        .header("Authorization", adminToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Cine negro"));

        assertTrue(genreRepository.findAll().stream().noneMatch(g -> g.getName().contains("\u0000")));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private ResultActions register(String json) throws Exception {
        return mockMvc.perform(withJson(post("/api/users"), json));
    }

    private ResultActions login(String json, String ip) throws Exception {
        return mockMvc.perform(withJson(post("/api/auth/login"), json)
                .header("X-Requested-With", "StreamBox")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                }));
    }

    /** JSON en UTF-8 (el texto lleva las secuencias {@code \u0000} sin decodificar: las decodifica Jackson). */
    private static MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder builder, String json) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(json.getBytes(StandardCharsets.UTF_8));
    }
}
