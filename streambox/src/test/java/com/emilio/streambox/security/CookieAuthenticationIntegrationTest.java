package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;

import jakarta.servlet.http.Cookie;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Sesión por cookie HttpOnly (tarea 29): atributos de la cookie, autenticación
 * por cookie o Bearer, defensa CSRF por cabecera, logout y tokens inválidos.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "streambox.auth.cookie.secure=true")
class CookieAuthenticationIntegrationTest {

    private static final String EMAIL = "cookieuser@test.com";
    private static final String PASSWORD = "correct-password";
    private static final String PROTECTED_WRITE = "/api/users/me/favorites";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private JwtProperties jwtProperties;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setUsername("cookieuser");
        user.setEmail(EMAIL);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        userRepository.save(user);
    }

    private MvcResult login() throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent())
                .andReturn();
    }

    @Test
    void laCookieDelLoginLlevaTodosLosAtributos() throws Exception {
        String setCookie = login().getResponse().getHeader(HttpHeaders.SET_COOKIE);

        assertTrue(setCookie.startsWith("streambox_token="), setCookie);
        assertTrue(setCookie.contains("HttpOnly"), setCookie);
        assertTrue(setCookie.contains("Secure"), setCookie);
        assertTrue(setCookie.contains("SameSite=Strict"), setCookie);
        assertTrue(setCookie.contains("Path=/api"), setCookie);
        assertTrue(setCookie.contains("Max-Age=" + jwtProperties.expirationHours() * 3600), setCookie);
    }

    @Test
    void sePuedeAutenticarSoloConLaCookie() throws Exception {
        Cookie cookie = login().getResponse().getCookie("streambox_token");

        mockMvc.perform(get("/api/users/me").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL));
    }

    @Test
    void bearerSigueFuncionandoSinCabeceraCsrf() throws Exception {
        String token = jwtService.generateToken(user);

        mockMvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(delete(PROTECTED_WRITE).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    @Test
    void bearerTienePrecedenciaSobreLaCookie() throws Exception {
        Cookie valid = new Cookie("streambox_token", jwtService.generateToken(user));

        // Un Bearer inválido no se "rescata" con una cookie válida.
        mockMvc.perform(get("/api/users/me").cookie(valid).header("Authorization", "Bearer basura"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void sinCabeceraCsrfUnaPeticionNoSeguraPorCookieDa403() throws Exception {
        Cookie cookie = login().getResponse().getCookie("streambox_token");

        mockMvc.perform(delete(PROTECTED_WRITE).cookie(cookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        mockMvc.perform(delete(PROTECTED_WRITE).cookie(cookie).header("X-Requested-With", "otra-cosa"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
    }

    @Test
    void conCabeceraCsrfLaPeticionNoSeguraPorCookiePasa() throws Exception {
        Cookie cookie = login().getResponse().getCookie("streambox_token");

        mockMvc.perform(delete(PROTECTED_WRITE).cookie(cookie).header("X-Requested-With", "StreamBox"))
                .andExpect(status().isNoContent());
    }

    @Test
    void getPorCookieNoExigeLaCabeceraCsrf() throws Exception {
        Cookie cookie = login().getResponse().getCookie("streambox_token");

        mockMvc.perform(get("/api/users/me").cookie(cookie)).andExpect(status().isOk());
    }

    @Test
    void loginRegistroYLogoutIgnoranUnaCookieViejaOManipulada() throws Exception {
        Cookie stale = new Cookie("streambox_token", "manipulada");

        mockMvc.perform(post("/api/auth/login").cookie(stale).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());
        // Con una cookie válida pero sin cabecera CSRF, el login tampoco se bloquea.
        Cookie valid = new Cookie("streambox_token", jwtService.generateToken(user));
        mockMvc.perform(post("/api/auth/login").cookie(valid).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/auth/logout").cookie(valid)).andExpect(status().isNoContent());
    }

    @Test
    void logoutBorraLaCookieYEsIdempotente() throws Exception {
        for (int i = 0; i < 2; i++) {
            String setCookie = mockMvc.perform(post("/api/auth/logout"))
                    .andExpect(status().isNoContent())
                    .andReturn().getResponse().getHeader(HttpHeaders.SET_COOKIE);

            assertTrue(setCookie.startsWith("streambox_token=;"), setCookie);
            assertTrue(setCookie.contains("Max-Age=0"), setCookie);
            assertTrue(setCookie.contains("HttpOnly"), setCookie);
            assertTrue(setCookie.contains("Secure"), setCookie);
            assertTrue(setCookie.contains("SameSite=Strict"), setCookie);
            assertTrue(setCookie.contains("Path=/api"), setCookie);
        }
    }

    @Test
    void cookieManipuladaOVaciaDa401() throws Exception {
        mockMvc.perform(get("/api/users/me").cookie(new Cookie("streambox_token", "no.es.un.jwt")))
                .andExpect(status().isUnauthorized());
        String valid = jwtService.generateToken(user);
        mockMvc.perform(get("/api/users/me")
                        .cookie(new Cookie("streambox_token", valid.substring(0, valid.length() - 3) + "abc")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/users/me").cookie(new Cookie("streambox_token", "")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cookieConTokenCaducadoDa401() throws Exception {
        String expired = Jwts.builder()
                .issuer(JwtService.ISSUER)
                .subject(EMAIL)
                .issuedAt(new Date(System.currentTimeMillis() - 7_200_000))
                .expiration(new Date(System.currentTimeMillis() - 3_600_000))
                .signWith(Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8)))
                .compact();

        mockMvc.perform(get("/api/users/me").cookie(new Cookie("streambox_token", expired)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void elCuerpoDelLoginNoContieneElToken() throws Exception {
        MvcResult result = login();

        assertEquals("", result.getResponse().getContentAsString());
        assertTrue(!result.getResponse().getContentAsString()
                .contains(result.getResponse().getCookie("streambox_token").getValue()));
    }
}
