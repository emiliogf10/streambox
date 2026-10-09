package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.CurrentPasswordIncorrectException;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.password.PasswordPolicy;
import com.emilio.streambox.service.PasswordChangeService;

import jakarta.servlet.http.Cookie;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code PUT /api/users/me/password} de punta a punta: contraseña en la base de
 * datos, login con la vieja y la nueva, y el efecto sobre las sesiones (la
 * actual sigue con una familia nueva; las demás, y las copias anteriores de la
 * actual, ya no pueden renovarse).
 *
 * <p>
 * Sin {@code @Transactional}: el cambio confirma de verdad (la revocación y la
 * familia nueva se comprueban con peticiones posteriores). Los usuarios de la
 * clase (dominio {@value #DOMAIN}) se borran antes y después de cada test y
 * sus refresh tokens caen en cascada. El límite de intentos se prueba en
 * {@link PasswordChangeAttemptLimitIntegrationTest}, con el máximo bajado.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PasswordChangeIntegrationTest {

    private static final String DOMAIN = "@pwd-change.test";
    private static final String PASSWORD = "Contraseña-Del-Cambio-2026";
    private static final String NEW_PASSWORD = "Otra-Clave-Distinta-2026!";
    private static final String ACCESS = "streambox_token";
    private static final String REFRESH = "streambox_refresh";
    private static final String PATH = "/api/users/me/password";

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

    // ------------------------------------------------------------------
    // Cambio correcto
    // ------------------------------------------------------------------

    /**
     * El caso completo: la contraseña cambia, la sesión actual sigue (con
     * cookies nuevas que sirven), las otras sesiones y una copia anterior del
     * refresh token de la actual ya no renuevan.
     */
    @Test
    void cambiarLaContrasenaMantieneLaSesionActualYCierraLasDemas() throws Exception {
        User user = createUser("correcto");
        MvcResult current = login(user.getEmail(), PASSWORD, 204);
        MvcResult other = login(user.getEmail(), PASSWORD, 204);

        MvcResult changed = mockMvc.perform(withCookieAndCsrf(change(PASSWORD, NEW_PASSWORD), current))
                .andExpect(status().isNoContent())
                .andReturn();

        assertEquals("", changed.getResponse().getContentAsString());
        String newAccess = cookie(changed, ACCESS);
        String newRefresh = cookie(changed, REFRESH);
        assertNotEquals(cookie(current, REFRESH), newRefresh);
        assertTrue(setCookie(changed, REFRESH).contains("Path=/api/auth;"));
        assertTrue(setCookie(changed, ACCESS).contains("HttpOnly"));

        // Base de datos y login.
        assertTrue(passwordEncoder.matches(NEW_PASSWORD, storedHash(user)));
        login(user.getEmail(), PASSWORD, 401);
        login(user.getEmail(), NEW_PASSWORD, 204);

        // La sesión actual sigue: su JWT nuevo sirve y su refresh nuevo renueva.
        mockMvc.perform(get("/api/users/me").cookie(new Cookie(ACCESS, newAccess)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.getEmail()));
        refresh(newRefresh, 204);

        // Ni la copia anterior del refresh de esta sesión ni la otra sesión renuevan.
        refresh(cookie(current, REFRESH), 401);
        refresh(cookie(other, REFRESH), 401);
    }

    /**
     * Un cliente de API con {@code Authorization: Bearer} no necesita la
     * cabecera CSRF (el navegador no adjunta el Bearer solo).
     */
    @Test
    void conBearerNoHaceFaltaLaCabeceraCsrf() throws Exception {
        User user = createUser("bearer");
        MvcResult session = login(user.getEmail(), PASSWORD, 204);

        mockMvc.perform(change(PASSWORD, NEW_PASSWORD)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + cookie(session, ACCESS)))
                .andExpect(status().isNoContent());

        assertTrue(passwordEncoder.matches(NEW_PASSWORD, storedHash(user)));
    }

    // ------------------------------------------------------------------
    // Errores: no cambian nada
    // ------------------------------------------------------------------

    /**
     * Contraseña actual incorrecta: 400 (no 401: la sesión es válida) con su
     * código y el mensaje en el campo; ni la contraseña ni las sesiones cambian.
     */
    @Test
    void conLaContrasenaActualIncorrectaDa400YNoCambiaNada() throws Exception {
        User user = createUser("incorrecta");
        MvcResult current = login(user.getEmail(), PASSWORD, 204);
        MvcResult other = login(user.getEmail(), PASSWORD, 204);
        String hashBefore = storedHash(user);

        MvcResult result = mockMvc.perform(withCookieAndCsrf(change("No-Es-Mi-Contraseña-1", NEW_PASSWORD), current))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INCORRECT"))
                .andExpect(jsonPath("$.message").value(CurrentPasswordIncorrectException.MESSAGE))
                .andExpect(jsonPath("$.validationErrors.currentPassword")
                        .value(CurrentPasswordIncorrectException.MESSAGE))
                .andReturn();

        assertNoCookies(result);
        assertEquals(hashBefore, storedHash(user));
        refresh(cookie(other, REFRESH), 204);
        refresh(cookie(current, REFRESH), 204);
    }

    /** La contraseña nueva pasa por la política, contra el usuario y el email de la cuenta. */
    @ParameterizedTest
    @MethodSource("invalidNewPasswords")
    void unaContrasenaNuevaQueNoCumpleLaPoliticaDa400(String newPassword, String expectedMessage) throws Exception {
        User user = createUser("politica");
        MvcResult session = login(user.getEmail(), PASSWORD, 204);
        String hashBefore = storedHash(user);

        MvcResult result = mockMvc.perform(withCookieAndCsrf(change(PASSWORD, newPassword), session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.newPassword").value(expectedMessage))
                .andReturn();

        assertNoCookies(result);
        assertEquals(hashBefore, storedHash(user));
        refresh(cookie(session, REFRESH), 204);
    }

    static Stream<Arguments> invalidNewPasswords() {
        return Stream.of(
                Arguments.of(null, PasswordPolicy.REQUIRED_MESSAGE),
                Arguments.of("corta-123", PasswordPolicy.LENGTH_MESSAGE),
                Arguments.of("x".repeat(PasswordPolicy.MAX_LENGTH + 1), PasswordPolicy.LENGTH_MESSAGE),
                Arguments.of("netflixpassword", PasswordPolicy.COMMON_MESSAGE),
                // 30 caracteres de 3 bytes: longitud válida, 90 bytes.
                Arguments.of("€".repeat(15) + "ñandú-€€€€€€€€€", PasswordPolicy.BYTES_MESSAGE),
                // Contiene la parte local del email (politica) y el nombre de usuario.
                Arguments.of("mi-politica-segura-2026", PasswordPolicy.PERSONAL_DATA_MESSAGE));
    }

    /** La nueva no puede ser la actual (se comprueba solo tras verificar la actual). */
    @Test
    void unaContrasenaNuevaIgualALaActualDa400() throws Exception {
        User user = createUser("igual");
        MvcResult session = login(user.getEmail(), PASSWORD, 204);
        MvcResult other = login(user.getEmail(), PASSWORD, 204);

        MvcResult result = mockMvc.perform(withCookieAndCsrf(change(PASSWORD, PASSWORD), session))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.newPassword")
                        .value(PasswordChangeService.SAME_PASSWORD_MESSAGE))
                .andReturn();

        assertNoCookies(result);
        refresh(cookie(other, REFRESH), 204);
    }

    /** La contraseña actual vacía o desmesurada se rechaza antes de BCrypt. */
    @Test
    void unaContrasenaActualVaciaOEnormeDa400() throws Exception {
        User user = createUser("vacia");
        MvcResult session = login(user.getEmail(), PASSWORD, 204);

        for (String current : new String[] { "", "a".repeat(1025) }) {
            mockMvc.perform(withCookieAndCsrf(change(current, NEW_PASSWORD), session))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.validationErrors.currentPassword").exists());
        }
    }

    // ------------------------------------------------------------------
    // Seguridad de la ruta
    // ------------------------------------------------------------------

    @Test
    void sinSesionDa401() throws Exception {
        mockMvc.perform(change(PASSWORD, NEW_PASSWORD).header("X-Requested-With", "StreamBox"))
                .andExpect(status().isUnauthorized());
    }

    /** Por cookie, sin la cabecera CSRF: 403 y la contraseña no cambia. */
    @Test
    void conCookieYSinCabeceraCsrfDa403YNoCambiaNada() throws Exception {
        User user = createUser("csrf");
        MvcResult session = login(user.getEmail(), PASSWORD, 204);
        String hashBefore = storedHash(user);

        MvcResult result = mockMvc.perform(change(PASSWORD, NEW_PASSWORD)
                        .cookie(new Cookie(ACCESS, cookie(session, ACCESS))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"))
                .andReturn();

        assertNoCookies(result);
        assertEquals(hashBefore, storedHash(user));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private User createUser(String name) {
        User user = new User();
        user.setUsername(name + "-pwd");
        user.setEmail(name + DOMAIN);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user);
    }

    private String storedHash(User user) {
        return userRepository.findById(user.getId()).orElseThrow().getPassword();
    }

    private MockHttpServletRequestBuilder change(String currentPassword, String newPassword) throws Exception {
        java.util.Map<String, String> body = new java.util.HashMap<>();
        body.put("currentPassword", currentPassword);
        body.put("newPassword", newPassword);
        return put(PATH).contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
    }

    private static MockHttpServletRequestBuilder withCookieAndCsrf(MockHttpServletRequestBuilder request,
            MvcResult session) {
        return request.cookie(new Cookie(ACCESS, cookie(session, ACCESS))).header("X-Requested-With", "StreamBox");
    }

    private MvcResult login(String email, String password, int expectedStatus) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of("email", email, "password", password));
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        assertEquals(expectedStatus, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return result;
    }

    private void refresh(String refreshToken, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "StreamBox")
                        .cookie(new Cookie(REFRESH, refreshToken)))
                .andReturn();
        assertEquals(expectedStatus, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    private static String cookie(MvcResult result, String name) {
        Cookie cookie = result.getResponse().getCookie(name);
        assertNotNull(cookie, "la respuesta no fija la cookie " + name);
        return cookie.getValue();
    }

    private static String setCookie(MvcResult result, String name) {
        return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(name + "="))
                .findFirst()
                .orElseThrow();
    }

    private static void assertNoCookies(MvcResult result) {
        assertFalse(result.getResponse().containsHeader(HttpHeaders.SET_COOKIE),
                "un error no debe tocar las cookies: " + result.getResponse().getHeaders(HttpHeaders.SET_COOKIE));
    }
}
