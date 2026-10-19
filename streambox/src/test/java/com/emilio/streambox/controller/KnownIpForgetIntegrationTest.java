package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.AuthCookieService;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

/**
 * Cambiar la contraseña y cerrar sesión en todos los dispositivos hacen
 * olvidar las «IPs conocidas» de la cuenta (revisión de qa, plan §2.5).
 *
 * <p>
 * Una IP conocida se libra del bloqueo de la cuenta durante 30 días. Antes, ni
 * el cambio de contraseña ni el {@code logout-all} las olvidaban: quien entró
 * con la contraseña robada conservaba su IP conocida y, desde ella, seguía
 * probando la contraseña nueva con su propio contador sin sufrir el bloqueo de
 * la cuenta. Estos tests fallan sin el cambio: los fallos del intruso no
 * llenaban el contador de la cuenta y las IPs antiguas seguían entrando
 * durante un bloqueo.
 * </p>
 *
 * <p>
 * Sin {@code @Transactional}: el cambio de contraseña confirma de verdad (las
 * IPs se olvidan después del commit). Los usuarios del dominio
 * {@value #DOMAIN} se borran antes y después de cada test; los contadores
 * viven en memoria todo el contexto, así que cada test usa emails propios. El
 * bloqueo se baja a los 5 fallos de producción.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = "streambox.security.rate-limit.lockout.max-failures=5")
class KnownIpForgetIntegrationTest {

    private static final String DOMAIN = "@olvidar-ips.test";
    private static final String PASSWORD = "Contraseña-Antigua-2026";
    private static final String NEW_PASSWORD = "Otra-Clave-Distinta-2026!";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** IP habitual del titular (con login correcto: conocida). */
    private static final String HOME = "203.0.113.21";
    /** IP del intruso que inició sesión con la contraseña robada (conocida). */
    private static final String INTRUDER = "198.51.100.21";
    /** IP desde la que el titular cambia la contraseña; nunca había iniciado sesión desde ella. */
    private static final String MOBILE = "203.0.113.22";
    /** IP sin ningún login correcto. */
    private static final String NEW_NETWORK = "192.0.2.21";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void deleteUsersOfThisSuite() {
        userRepository.deleteAll(userRepository.findAll().stream()
                .filter(user -> user.getEmail().endsWith(DOMAIN))
                .toList());
    }

    /**
     * Tras cambiar la contraseña, la IP del intruso vuelve al contador de la
     * cuenta (sus fallos la bloquean para todos) y la IP antigua del titular
     * también queda sujeta al bloqueo; solo la IP desde la que se cambió sigue
     * siendo conocida.
     */
    @Test
    void cambiarLaContrasenaOlvidaLasIpsConocidasSalvoLaDeQuienLaCambia() throws Exception {
        String email = createUser();
        MvcResult ownerSession = login(email, PASSWORD, HOME).andExpect(status().isNoContent()).andReturn();
        login(email, PASSWORD, INTRUDER).andExpect(status().isNoContent());

        mockMvc.perform(put("/api/users/me/password")
                        .with(remoteAddr(MOBILE))
                        .cookie(new Cookie(AuthCookieService.COOKIE_NAME, accessCookie(ownerSession)))
                        .header("X-Requested-With", "StreamBox")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD))))
                .andExpect(status().isNoContent());

        lockFrom(email, INTRUDER);

        // Los fallos del intruso han bloqueado la cuenta: ya no tenía contador propio.
        assertLocked(login(email, NEW_PASSWORD, NEW_NETWORK));
        assertLocked(login(email, NEW_PASSWORD, HOME));
        // Quien cambió la contraseña la verificó desde MOBILE: su IP queda conocida.
        login(email, NEW_PASSWORD, MOBILE).andExpect(status().isNoContent());
    }

    /**
     * Tras «cerrar sesión en todos los dispositivos» ninguna IP sigue
     * librándose del bloqueo, tampoco la de la petición (no prueba la
     * contraseña).
     */
    @Test
    void cerrarSesionEnTodosLosDispositivosOlvidaTodasLasIpsConocidas() throws Exception {
        String email = createUser();
        MvcResult ownerSession = login(email, PASSWORD, HOME).andExpect(status().isNoContent()).andReturn();
        login(email, PASSWORD, INTRUDER).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/logout-all")
                        .with(remoteAddr(HOME))
                        .cookie(new Cookie(AuthCookieService.COOKIE_NAME, accessCookie(ownerSession)))
                        .header("X-Requested-With", "StreamBox"))
                .andExpect(status().isNoContent());

        lockFrom(email, INTRUDER);

        assertLocked(login(email, PASSWORD, NEW_NETWORK));
        assertLocked(login(email, PASSWORD, HOME));
    }

    /**
     * Un cambio de contraseña rechazado (actual incorrecta) no olvida nada:
     * la contraseña antigua sigue valiendo y la IP conocida del titular sigue
     * librándose del bloqueo.
     */
    @Test
    void unCambioRechazadoNoOlvidaLasIpsConocidas() throws Exception {
        String email = createUser();
        MvcResult ownerSession = login(email, PASSWORD, HOME).andExpect(status().isNoContent()).andReturn();

        mockMvc.perform(put("/api/users/me/password")
                        .with(remoteAddr(HOME))
                        .cookie(new Cookie(AuthCookieService.COOKIE_NAME, accessCookie(ownerSession)))
                        .header("X-Requested-With", "StreamBox")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("currentPassword", "No-Es-Mi-Contraseña-1", "newPassword", NEW_PASSWORD))))
                .andExpect(status().isBadRequest());

        lockFrom(email, NEW_NETWORK);

        login(email, PASSWORD, HOME).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Cinco fallos desde la IP: 401 con 4..1 intentos restantes y el quinto, 429. */
    private void lockFrom(String email, String ip) throws Exception {
        for (int remaining = 4; remaining >= 1; remaining--) {
            login(email, "mal", ip)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.remainingAttempts").value(remaining));
        }
        assertLocked(login(email, "mal", ip));
    }

    private static void assertLocked(ResultActions result) throws Exception {
        result.andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    private ResultActions login(String email, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(remoteAddr(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private String createUser() {
        String name = "olvidar" + SEQUENCE.getAndIncrement();
        User user = new User();
        user.setEmailVerifiedAt(Instant.now()); // cuenta verificada (V9)
        user.setUsername(name);
        user.setEmail(name + DOMAIN);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user).getEmail();
    }

    private static String accessCookie(MvcResult result) {
        Cookie cookie = result.getResponse().getCookie(AuthCookieService.COOKIE_NAME);
        assertNotNull(cookie, "el login no fija la cookie de sesión");
        assertEquals(false, cookie.getValue().isEmpty());
        return cookie.getValue();
    }

    private static RequestPostProcessor remoteAddr(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
