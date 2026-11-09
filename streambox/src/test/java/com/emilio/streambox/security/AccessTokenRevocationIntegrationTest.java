package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.stream.Stream;

import javax.crypto.spec.SecretKeySpec;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.AccountTokenPurpose;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.service.AccountTokens;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;

/**
 * Revocación inmediata de los JWT de acceso (BAJA-3 del plan): claim
 * {@code ver} frente a {@code users.token_version}.
 *
 * <p>
 * <b>Bug que protege.</b> «Cerrar sesión en todos los dispositivos», cambiar
 * la contraseña, restablecerla o confirmar el correo revocaban los refresh
 * tokens, pero el JWT de acceso que ya tuviera otro dispositivo (o un intruso)
 * seguía abriendo la API hasta caducar, hasta 15 minutos. Sin el incremento
 * de {@code RefreshTokenService.revokeAllSessions} o sin la comparación del
 * filtro, los tests de cada flujo fallan: el JWT anterior sigue dando 200.
 * Y si el cambio de contraseña firmara el JWT nuevo antes de incrementar (o
 * con la versión de la entidad en memoria), el de la sesión actual fallaría.
 * </p>
 *
 * <p>
 * Cada flujo se comprueba con el JWT por {@code Authorization: Bearer} y por
 * la cookie {@code streambox_token}: el filtro aplica la misma regla a los dos.
 * Sin {@code @Transactional} (las revocaciones confirman de verdad); las
 * cuentas del dominio {@value #DOMAIN} se borran antes y después de cada test.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AccessTokenRevocationIntegrationTest {

    private static final String DOMAIN = "@revocacion-jwt.test";
    private static final String PASSWORD = "Paseo-Por-El-Retiro-2026";
    private static final String NEW_PASSWORD = "Rio-Verde-Bajo-Un-Puente-77";
    private static final String ACCESS = AuthCookieService.COOKIE_NAME;
    private static final String REFRESH = "streambox_refresh";
    private static final String ME = "/api/users/me";
    private static final String CSRF = JwtAuthenticationFilter.CSRF_HEADER;
    private static final String CSRF_VALUE = JwtAuthenticationFilter.CSRF_HEADER_VALUE;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private AccountTokens accountTokens;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Clock clock;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void deleteUsersOfThisSuite() {
        userRepository.deleteAll(userRepository.findAll().stream()
                .filter(user -> user.getEmail().endsWith(DOMAIN))
                .toList());
    }

    // ------------------------------------------------------------------
    // Cada flujo que cierra todas las sesiones anula los JWT anteriores
    // ------------------------------------------------------------------

    /**
     * Tras {@code POST /api/auth/logout-all}, el JWT de otro dispositivo (y el
     * de la propia petición) da 401 al momento, por cabecera y por cookie.
     */
    @Test
    void cerrarSesionEnTodosLosDispositivosAnulaAlMomentoLosJwtDeAcceso() throws Exception {
        User user = createUser("todas", true);
        String current = accessCookie(login(user));
        String laptop = accessCookie(login(user));
        assertMeStatus(laptop, 200);

        mockMvc.perform(post("/api/auth/logout-all").cookie(new Cookie(ACCESS, current)).header(CSRF, CSRF_VALUE))
                .andExpect(status().isNoContent());

        assertMeStatus(laptop, 401);
        assertMeStatus(current, 401);
        assertEquals(1, tokenVersionOf(user));
    }

    /**
     * Cambiar la contraseña anula el JWT de los demás dispositivos y el
     * anterior de esta sesión, pero <b>la sesión actual sigue</b>: el JWT nuevo
     * de la respuesta lleva la versión nueva y abre la API, y su refresh
     * también renueva.
     */
    @Test
    void cambiarLaContrasenaAnulaLosDemasJwtYLaSesionActualSigue() throws Exception {
        User user = createUser("cambio", true);
        String current = accessCookie(login(user));
        String laptop = accessCookie(login(user));

        MvcResult changed = mockMvc.perform(put("/api/users/me/password")
                        .cookie(new Cookie(ACCESS, current)).header(CSRF, CSRF_VALUE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD))))
                .andExpect(status().isNoContent())
                .andReturn();

        String renewed = accessCookie(changed);
        assertEquals(1, jwtService.parseAccessToken(renewed).tokenVersion());
        assertMeStatus(renewed, 200);
        assertMeStatus(current, 401);
        assertMeStatus(laptop, 401);

        String refreshed = accessCookie(refresh(cookie(changed, REFRESH)));
        assertMeStatus(refreshed, 200);
    }

    /** Restablecer la contraseña con el enlace del correo anula los JWT de acceso. */
    @Test
    void restablecerLaContrasenaAnulaLosJwtDeAcceso() throws Exception {
        User user = createUser("restablecer", true);
        String session = accessCookie(login(user));
        String token = issueAccountToken(user, AccountTokenPurpose.PASSWORD_RESET);

        mockMvc.perform(post("/api/auth/password-reset/confirm").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", token, "newPassword", NEW_PASSWORD))))
                .andExpect(status().isNoContent());

        assertMeStatus(session, 401);
        assertEquals(1, tokenVersionOf(user));
    }

    /**
     * Confirmar el correo anula los JWT emitidos antes. Una cuenta pendiente
     * ya no abre la API (el filtro exige el correo confirmado), pero sin la
     * versión, en cuanto el titular confirmara, un JWT de antes (de una sesión
     * heredada de antes de la V9, o de quien registró ese email) volvería a
     * valer: el 401 del final sería un 200.
     */
    @Test
    void confirmarElCorreoAnulaLosJwtEmitidosAntes() throws Exception {
        User pending = createUser("pendiente", false);
        String before = jwtService.generateToken(pending);
        assertMeStatus(before, 401);
        String token = issueAccountToken(pending, AccountTokenPurpose.EMAIL_VERIFICATION);

        mockMvc.perform(post("/api/auth/email-verification").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", token))))
                .andExpect(status().isNoContent());

        assertMeStatus(before, 401);
        assertMeStatus(accessCookie(login(pending)), 200);
    }

    // ------------------------------------------------------------------
    // Los JWT nuevos llevan la versión actual
    // ------------------------------------------------------------------

    /**
     * Tras una revocación, el login y el refresh emiten JWT con la versión
     * nueva, que sí abren la API: la revocación no deja la cuenta inservible.
     */
    @Test
    void elLoginYElRefreshEmitenJwtDeLaVersionActual() throws Exception {
        User user = createUser("refresh", true);
        MvcResult first = login(user);
        assertEquals(0, jwtService.parseAccessToken(accessCookie(first)).tokenVersion());
        mockMvc.perform(post("/api/auth/logout-all").cookie(new Cookie(ACCESS, accessCookie(first)))
                        .header(CSRF, CSRF_VALUE))
                .andExpect(status().isNoContent());

        MvcResult second = login(user);
        assertEquals(1, jwtService.parseAccessToken(accessCookie(second)).tokenVersion());
        String refreshed = accessCookie(refresh(cookie(second, REFRESH)));

        assertEquals(1, jwtService.parseAccessToken(refreshed).tokenVersion());
        assertMeStatus(refreshed, 200);
    }

    // ------------------------------------------------------------------
    // Tokens con la firma correcta pero un «ver» no válido
    // ------------------------------------------------------------------

    /** Valores de {@code ver} que no deben abrir la API (la cuenta está en la versión 2). */
    static Stream<Arguments> invalidVersions() {
        return Stream.of(
                Arguments.of("sin ver", null),
                Arguments.of("anterior", 1),
                Arguments.of("inicial", 0),
                Arguments.of("posterior", 3),
                Arguments.of("texto", "2"),
                Arguments.of("decimal", 2.0),
                Arguments.of("negativo", -1),
                Arguments.of("booleano", true),
                Arguments.of("fuera de rango", 4_294_967_298L));
    }

    /**
     * Un token firmado con el secreto real, del emisor y vigente, pero sin
     * {@code ver} o con uno que no es la versión actual (o ni siquiera un
     * entero), da 401 por cabecera y por cookie; nunca un 500.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidVersions")
    void unTokenSinLaVersionActualDa401PorCabeceraYPorCookie(String caso, Object version) throws Exception {
        User user = createUser("version", true);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> userRepository.incrementTokenVersion(user.getId()));
        tx.executeWithoutResult(status -> userRepository.incrementTokenVersion(user.getId()));
        String valid = signed(user.getEmail(), 2);
        assertMeStatus(valid, 200);

        assertMeStatus(signed(user.getEmail(), version), 401);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private User createUser(String name, boolean verified) {
        User user = new User();
        user.setUsername(name + "_revjwt");
        user.setEmail(name + DOMAIN);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setEmailVerifiedAt(verified ? Instant.now() : null);
        return userRepository.saveAndFlush(user);
    }

    private MvcResult login(User user) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", user.getEmail(), "password", PASSWORD))))
                .andExpect(status().isNoContent())
                .andReturn();
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/refresh").header(CSRF, CSRF_VALUE)
                        .cookie(new Cookie(REFRESH, refreshToken)))
                .andExpect(status().isNoContent())
                .andReturn();
    }

    /**
     * Comprueba {@code GET /api/users/me} con el JWT por {@code Bearer} y por
     * cookie: los dos caminos del filtro deben responder lo mismo.
     */
    private void assertMeStatus(String jwt, int expected) throws Exception {
        int bearer = mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt))
                .andReturn().getResponse().getStatus();
        int cookie = mockMvc.perform(get(ME).cookie(new Cookie(ACCESS, jwt)))
                .andReturn().getResponse().getStatus();
        assertEquals(expected, bearer, "por Authorization: Bearer");
        assertEquals(expected, cookie, "por la cookie " + ACCESS);
    }

    /** Emite un token de cuenta (enlace del correo) como lo haría el servicio. */
    private String issueAccountToken(User user, AccountTokenPurpose purpose) {
        return new TransactionTemplate(transactionManager).execute(
                status -> accountTokens.issue(user.getId(), purpose, clock.instant(), Duration.ofHours(1)));
    }

    private int tokenVersionOf(User user) {
        return userRepository.findTokenVersionById(user.getId()).orElseThrow();
    }

    /**
     * JWT firmado con el secreto real (HS256, emisor y caducidad correctos) y
     * el {@code ver} indicado; {@code null} = sin el claim.
     */
    private String signed(String email, Object version) {
        var builder = Jwts.builder()
                .issuer(JwtService.ISSUER)
                .subject(email)
                .issuedAt(Date.from(clock.instant()))
                .expiration(Date.from(clock.instant().plus(Duration.ofMinutes(5))));
        if (version != null) {
            builder.claim(JwtService.TOKEN_VERSION_CLAIM, version);
        }
        return builder.signWith(new SecretKeySpec(
                jwtProperties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"), JwtService.ALGORITHM)
                .compact();
    }

    private String json(Map<String, ?> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private static String accessCookie(MvcResult result) {
        return cookie(result, ACCESS);
    }

    private static String cookie(MvcResult result, String name) {
        Cookie cookie = result.getResponse().getCookie(name);
        assertNotNull(cookie, "la respuesta no fija la cookie " + name);
        return cookie.getValue();
    }
}
