package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

/**
 * Base de los tests de la incidencia <b>NV-2</b> (auditoría de seguridad
 * run-3): si se <b>pierde</b> la reserva de un nombre
 * ({@code UsernameReservations}), vuelve el oráculo de emails registrados.
 *
 * <h2>La secuencia que se compara</h2>
 * <p>
 * El atacante registra dos nombres: {@code A} con un email nuevo (se crea una
 * cuenta pendiente que ocupa {@code A} 24 h, en la base de datos) y {@code B}
 * con el email de una cuenta que ya existe (no se crea nada: {@code B} solo
 * queda <b>reservado</b>). Mientras la reserva exista, pedir {@code A} o
 * {@code B} da el mismo 409. Si la reserva se pierde (antes vivía en memoria:
 * un reinicio o el tope de claves de los contadores la borraban), {@code A}
 * sigue dando 409 y {@code B} pasa a 202 (registro) o 200 («Editar perfil»):
 * el atacante sabría que el email de {@code B} tenía cuenta.
 * </p>
 *
 * <p>
 * Cada subclase provoca la pérdida de una forma y exige respuestas iguales.
 * Sin {@code @Transactional}: el registro confirma de verdad (como en
 * producción) y los datos de este dominio se borran antes y después de cada
 * test. Los nombres empiezan por {@value #NAME_PREFIX} para borrar solo las
 * reservas propias, sin tocar las de otras clases que compartan contexto.
 * </p>
 */
abstract class UsernameReservationLossTestSupport {

    /** Dominio de los emails de estos tests (para borrarlos sin tocar otros). */
    static final String DOMAIN = "@reserva-perdida.test";

    /** Prefijo de los nombres de estos tests (para borrar solo sus reservas). */
    static final String NAME_PREFIX = "nvdos";

    private static final String PASSWORD = "Paseo-Por-El-Retiro-2026";

    @Autowired protected MockMvc mockMvc;
    @Autowired protected UserRepository userRepository;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected JwtService jwtService;
    @Autowired protected JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanBefore() {
        cleanUp();
    }

    @AfterEach
    void cleanAfter() {
        cleanUp();
    }

    private void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
        // Los dueños de cada reserva se borran en cascada.
        jdbc.update("DELETE FROM username_reservations WHERE username LIKE ?", NAME_PREFIX + "%");
    }

    /** {@code POST /api/users} con la contraseña fija de estos tests. */
    protected MockHttpServletResponse register(String username, String email) throws Exception {
        return mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("username", username, "email", email, "password", PASSWORD))))
                .andReturn().getResponse();
    }

    /** {@code PATCH /api/users/me} con la sesión (cookie) de la cuenta. */
    protected MockHttpServletResponse renameTo(User user, String username) throws Exception {
        return mockMvc.perform(patch("/api/users/me")
                .cookie(new Cookie("streambox_token", jwtService.generateToken(user)))
                .header("X-Requested-With", "StreamBox")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", username))))
                .andReturn().getResponse();
    }

    /** Crea una cuenta con el correo ya verificado directamente en la base de datos. */
    protected User createVerifiedUser(String username, String email) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setEmailVerifiedAt(Instant.now());
        return userRepository.saveAndFlush(user);
    }

    /**
     * Comprueba que las dos respuestas son el mismo 409 con el mismo cuerpo
     * (salvo la hora): la del nombre ocupado por una cuenta pendiente (email
     * nuevo) y la del nombre reservado (email que ya tenía cuenta).
     */
    protected void assertSameConflict(MockHttpServletResponse fresh, MockHttpServletResponse taken)
            throws Exception {
        assertEquals(409, fresh.getStatus(), "control (email nuevo): " + body(fresh));
        assertEquals(fresh.getStatus(), taken.getStatus(),
                "Perder la reserva delata que el email ya tenía cuenta: email nuevo → " + fresh.getStatus()
                        + ", email con cuenta → " + taken.getStatus() + " " + body(taken));
        assertEquals(withoutTimestamp(fresh), withoutTimestamp(taken));
    }

    private Map<?, ?> withoutTimestamp(MockHttpServletResponse response) throws Exception {
        Map<?, ?> map = new HashMap<>(objectMapper.readValue(body(response), Map.class));
        map.remove("timestamp");
        return map;
    }

    private static String body(MockHttpServletResponse response) throws Exception {
        return response.getContentAsString(StandardCharsets.UTF_8);
    }
}
