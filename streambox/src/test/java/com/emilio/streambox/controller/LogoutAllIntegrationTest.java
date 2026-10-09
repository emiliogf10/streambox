package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.RefreshTokenRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.refresh.RefreshTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.Cookie;

/**
 * {@code POST /api/auth/logout-all}: revoca todas las sesiones del usuario,
 * también la actual, y borra las dos cookies; las de otros usuarios no se
 * tocan.
 *
 * <p>
 * Sin {@code @Transactional}: la revocación confirma de verdad y se comprueba
 * con refresh posteriores. Los usuarios del dominio {@value #DOMAIN} se borran
 * antes y después de cada test (sus tokens caen en cascada). El 500 con la base
 * de datos caída está en {@code RefreshAndLogoutDatabaseFailureIntegrationTest}.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LogoutAllIntegrationTest {

    private static final String DOMAIN = "@logout-all.test";
    private static final String PASSWORD = "Contraseña-Del-Cierre-2026";
    private static final String ACCESS = "streambox_token";
    private static final String REFRESH = "streambox_refresh";
    private static final String PATH = "/api/auth/logout-all";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
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
     * Todas las sesiones del usuario, incluida la de la petición, dejan de
     * renovarse; las cookies se borran; otro usuario conserva la suya. Y los
     * dispositivos que intentan renovar después no disparan el aviso de robo
     * ({@code WARN}): su sesión ya estaba cerrada, no hay dos copias en uso.
     */
    @Test
    void cierraTodasLasSesionesIncluidaLaActualYBorraLasCookies() throws Exception {
        User user = createUser("todas");
        User other = createUser("ajeno");
        MvcResult current = login(user);
        MvcResult laptop = login(user);
        MvcResult otherSession = login(other);

        MvcResult result = mockMvc.perform(post(PATH)
                        .cookie(new Cookie(ACCESS, cookie(current, ACCESS)))
                        .header("X-Requested-With", "StreamBox"))
                .andExpect(status().isNoContent())
                .andReturn();

        assertEquals("", result.getResponse().getContentAsString());
        assertClearsBothCookies(result);
        assertEquals(0, activeTokens(user));

        ListAppender<ILoggingEvent> logs = captureLogs();
        try {
            refresh(cookie(current, REFRESH), 401);
            refresh(cookie(laptop, REFRESH), 401);
        } finally {
            ((Logger) LoggerFactory.getLogger(RefreshTokenService.class)).detachAppender(logs);
        }
        assertTrue(logs.list.stream().noneMatch(event -> event.getLevel() == Level.WARN), logs.list.toString());

        refresh(cookie(otherSession, REFRESH), 204);
    }

    /** Un cliente de API con Bearer no necesita la cabecera CSRF. */
    @Test
    void conBearerNoHaceFaltaLaCabeceraCsrf() throws Exception {
        User user = createUser("bearer");
        MvcResult session = login(user);

        mockMvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + cookie(session, ACCESS)))
                .andExpect(status().isNoContent());

        refresh(cookie(session, REFRESH), 401);
    }

    /** Sin sesión: 401, sin borrar nada (una web ajena no puede cerrar la sesión de nadie). */
    @Test
    void sinSesionDa401YNoBorraLasCookies() throws Exception {
        MvcResult result = mockMvc.perform(post(PATH).header("X-Requested-With", "StreamBox"))
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertFalse(result.getResponse().containsHeader(HttpHeaders.SET_COOKIE));
    }

    /** Por cookie, sin la cabecera CSRF: 403, sin revocar ni borrar nada. */
    @Test
    void conCookieYSinCabeceraCsrfDa403YNoRevocaNada() throws Exception {
        User user = createUser("csrf");
        MvcResult session = login(user);

        MvcResult result = mockMvc.perform(post(PATH).cookie(new Cookie(ACCESS, cookie(session, ACCESS))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"))
                .andReturn();

        assertFalse(result.getResponse().containsHeader(HttpHeaders.SET_COOKIE));
        refresh(cookie(session, REFRESH), 204);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private User createUser(String name) {
        User user = new User();
        user.setUsername(name + "-logoutall");
        user.setEmail(name + DOMAIN);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user);
    }

    private MvcResult login(User user) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", user.getEmail(), "password", PASSWORD))))
                .andExpect(status().isNoContent())
                .andReturn();
    }

    private void refresh(String refreshToken, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "StreamBox")
                        .cookie(new Cookie(REFRESH, refreshToken)))
                .andReturn();
        assertEquals(expectedStatus, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    /** Tokens sin revocar del usuario ({@code getId()} de la referencia LAZY no necesita sesión). */
    private long activeTokens(User user) {
        return refreshTokenRepository.findAll().stream()
                .filter(token -> user.getId().equals(token.getUser().getId()) && token.getRevokedAt() == null)
                .count();
    }

    private static String cookie(MvcResult result, String name) {
        Cookie cookie = result.getResponse().getCookie(name);
        assertNotNull(cookie, "la respuesta no fija la cookie " + name);
        return cookie.getValue();
    }

    private static void assertClearsBothCookies(MvcResult result) {
        List<String> headers = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        assertEquals(2, headers.size(), headers.toString());
        assertTrue(headers.stream().anyMatch(h -> h.startsWith(ACCESS + "=;") && h.contains("Max-Age=0")
                && h.contains("Path=/api;")), headers.toString());
        assertTrue(headers.stream().anyMatch(h -> h.startsWith(REFRESH + "=;") && h.contains("Max-Age=0")
                && h.contains("Path=/api/auth;")), headers.toString());
    }

    private static ListAppender<ILoggingEvent> captureLogs() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(RefreshTokenService.class)).addAppender(appender);
        return appender;
    }
}
