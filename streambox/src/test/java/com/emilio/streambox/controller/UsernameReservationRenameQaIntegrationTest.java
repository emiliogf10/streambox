package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.emilio.streambox.email.MemoryEmailSender;
import com.emilio.streambox.email.MemoryEmailSender.SentEmail;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.repository.UsernameReservationRepository;
import com.emilio.streambox.security.JwtService;
import com.emilio.streambox.support.AdjustableClock;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

/**
 * Revisión de QA del lote NV-2 (2026-11-13): <b>la cuenta pendiente nueva
 * también reserva su nombre</b> y eso se ve después, cuando su dueño confirma
 * el correo y se cambia el nombre antes de que pasen 24 h.
 *
 * <p>
 * Desde el arreglo de la carrera del nombre, todo registro que responde 202
 * deja el nombre pedido reservado 24 h a nombre de su email, también el que
 * crea la cuenta. Estos tests fijan, de punta a punta y por HTTP, lo que eso
 * significa para el usuario y que sigue sin haber oráculo:
 * </p>
 * <ul>
 *   <li>Si la cuenta nueva deja su nombre antes de 24 h, ese nombre sigue
 *       ocupado para los demás (registro y «Editar perfil») hasta las 24 h del
 *       registro: el <b>mismo 409</b> que deja un registro con un email que ya
 *       tenía cuenta. Pasadas 24 h, los dos casos quedan libres a la vez.</li>
 *   <li>Su dueño sí puede volver a él (la reserva es de su email), y el nombre
 *       que deja al volver queda libre en el acto: «Editar perfil» nunca deja
 *       reservas.</li>
 * </ul>
 *
 * <p>
 * Sin {@code @Transactional} (los correos salen tras el commit): se borran los
 * usuarios del dominio {@value #DOMAIN} y las reservas antes y después de cada
 * test. Reloj ajustable para pasar las 24 h sin esperar.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class UsernameReservationRenameQaIntegrationTest {

    private static final String DOMAIN = "@qa-renombrar.test";
    private static final String PASSWORD = "Paseo-Por-El-Retiro-2026";
    private static final String OTHER_PASSWORD = "Contrasena-Del-Atacante-2026";
    private static final Pattern LINK = Pattern.compile(
            "https://streambox\\.test/verificar-email#token=([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");

    private static final AdjustableClock CLOCK = new AdjustableClock();

    @TestBean
    private Clock clock;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MemoryEmailSender inbox;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Fábrica del bean {@code clock} que usa {@link TestBean} (mismo nombre que el campo). */
    static Clock clock() {
        return CLOCK;
    }

    @BeforeEach
    void setUp() {
        CLOCK.reset();
        cleanUp();
        inbox.clear();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
        jdbc.update("DELETE FROM username_reservations");
    }

    /**
     * Comparación de los dos rastros que deja un registro:
     * <ul>
     *   <li><b>A</b>: email nuevo; su dueño confirma el correo y se cambia el
     *       nombre a otro antes de 24 h (su nombre de registro ya no lo tiene
     *       ninguna cuenta).</li>
     *   <li><b>B</b>: email de una cuenta confirmada; no se crea nada.</li>
     * </ul>
     * Dentro de las 24 h, el nombre de los dos registros da el mismo 409, con
     * el mismo cuerpo, a otro registro y a «Editar perfil» desde otra cuenta.
     * Pasadas 24 h, los dos quedan libres (202 y 200). Si la cuenta nueva no
     * reservara su nombre, en A el nombre quedaría libre al cambiarlo y el
     * comportamiento dependería de quién lo deja, no del reloj.
     */
    @Test
    void elNombreQueDejaUnaCuentaNuevaAntesDe24HorasSigueOcupadoComoElDeUnEmailConCuenta() throws Exception {
        String newEmail = "nueva" + DOMAIN;
        register("inicial_a", newEmail, PASSWORD).andExpect(status().isAccepted());
        confirm(tokenSentTo(newEmail)).andExpect(status().isNoContent());
        User owner = userRepository.findByEmail(newEmail).orElseThrow();
        renameTo(owner, "actual_a").andExpect(status().isOk());

        createUser("titular_b", "titular" + DOMAIN);
        register("inicial_b", "titular" + DOMAIN, OTHER_PASSWORD).andExpect(status().isAccepted());

        assertTrue(userRepository.findByUsername("inicial_a").isEmpty(), "ninguna cuenta tiene ya el nombre A");
        assertTrue(userRepository.findByUsername("inicial_b").isEmpty(), "ninguna cuenta tiene el nombre B");
        User other = createUser("otra_cuenta", "otra" + DOMAIN);

        MockHttpServletResponse registerA = register("inicial_a", "tercero_a" + DOMAIN, OTHER_PASSWORD)
                .andReturn().getResponse();
        MockHttpServletResponse registerB = register("inicial_b", "tercero_b" + DOMAIN, OTHER_PASSWORD)
                .andReturn().getResponse();
        assertEquals(409, registerA.getStatus(), registerA.getContentAsString(StandardCharsets.UTF_8));
        assertEquals(registerA.getStatus(), registerB.getStatus());
        assertEquals(withoutTimestamp(registerA), withoutTimestamp(registerB));

        MockHttpServletResponse renameA = renameTo(other, "inicial_a").andReturn().getResponse();
        MockHttpServletResponse renameB = renameTo(other, "inicial_b").andReturn().getResponse();
        assertEquals(409, renameA.getStatus(), renameA.getContentAsString(StandardCharsets.UTF_8));
        assertEquals(renameA.getStatus(), renameB.getStatus());
        assertEquals(withoutTimestamp(renameA), withoutTimestamp(renameB));

        CLOCK.advance(Duration.ofHours(25));

        renameTo(other, "inicial_a").andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("inicial_a"));
        register("inicial_b", "tercero_b" + DOMAIN, OTHER_PASSWORD).andExpect(status().isAccepted());
        assertEquals("inicial_b", userRepository.findByEmail("tercero_b" + DOMAIN).orElseThrow().getUsername());
    }

    /**
     * La reserva que deja el registro es de su email: la cuenta creada con él
     * puede volver a su nombre de registro antes de 24 h aunque esté
     * reservado. Y el nombre que deja al volver ({@code actual}) queda libre en
     * el acto para otra cuenta: «Editar perfil» no deja ninguna reserva (su
     * fila de cerrojo se quita antes de confirmar).
     */
    @Test
    void laCuentaNuevaPuedeVolverASuNombreYElQueDejaQuedaLibreEnElActo() throws Exception {
        String newEmail = "vuelve" + DOMAIN;
        register("de_registro", newEmail, PASSWORD).andExpect(status().isAccepted());
        confirm(tokenSentTo(newEmail)).andExpect(status().isNoContent());
        User owner = userRepository.findByEmail(newEmail).orElseThrow();

        renameTo(owner, "provisional").andExpect(status().isOk());
        renameTo(owner, "de_registro").andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("de_registro"));

        assertFalse(isReservedRow("provisional"), "«Editar perfil» no deja reservas");
        User other = createUser("otra_vuelve", "otra_vuelve" + DOMAIN);
        renameTo(other, "provisional").andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("provisional"));
        // La reserva del registro sigue (dura 24 h desde el registro), pero es
        // de su propio email: no le estorba.
        assertTrue(isReservedRow("de_registro"));
        assertFalse(jdbc.queryForObject("SELECT COUNT(*) FROM username_reservation_owners WHERE username = ?"
                + " AND email_hash = ?", Integer.class, "de_registro", UsernameReservationRepository.emailHash(
                        "otra_vuelve" + DOMAIN)) > 0);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private boolean isReservedRow(String username) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM username_reservations WHERE username = ?",
                Integer.class, username) > 0;
    }

    private String tokenSentTo(String email) throws InterruptedException {
        List<SentEmail> emails = inbox.awaitSentTo(email, 1, Duration.ofSeconds(10));
        assertEquals(1, emails.size(), emails::toString);
        Matcher matcher = LINK.matcher(emails.get(0).body());
        assertTrue(matcher.find(), emails.get(0).body());
        return matcher.group(1);
    }

    private ResultActions register(String username, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("username", username, "email", email, "password", password))));
    }

    private ResultActions confirm(String token) throws Exception {
        return mockMvc.perform(post("/api/auth/email-verification")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("token", token))));
    }

    private ResultActions renameTo(User user, String username) throws Exception {
        return mockMvc.perform(patch("/api/users/me")
                .cookie(new Cookie("streambox_token", jwtService.generateToken(user)))
                .header("X-Requested-With", "StreamBox")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", username))));
    }

    /** Crea una cuenta confirmada directamente en la base de datos. */
    private User createUser(String username, String email) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setEmailVerifiedAt(Instant.now());
        return userRepository.saveAndFlush(user);
    }

    /** Cuerpo sin la hora, lo único que cambia entre dos respuestas iguales. */
    private Map<?, ?> withoutTimestamp(MockHttpServletResponse response) throws Exception {
        Map<?, ?> body = new HashMap<>(objectMapper.readValue(response.getContentAsString(StandardCharsets.UTF_8),
                Map.class));
        body.remove("timestamp");
        return body;
    }
}
