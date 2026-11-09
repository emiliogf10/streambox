package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import com.emilio.streambox.security.refresh.RefreshTokenService;
import com.emilio.streambox.service.AccountTokens;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

/**
 * Fija las <b>decisiones</b> del lote de {@code token_version} sobre cuándo
 * <i>no</i> se sube la versión, de punta a punta (MockMvc, base de datos con
 * commits reales).
 *
 * <p>
 * {@code AccessTokenRevocationIntegrationTest} prueba que los flujos que cierran
 * todas las sesiones anulan los JWT de acceso. Este prueba lo contrario, que es
 * igual de importante para el usuario: los flujos que cierran <b>una</b> sesión
 * no deben echar a los demás dispositivos.
 * </p>
 * <ul>
 *   <li><b>Reutilización de un refresh ya rotado</b> (posible robo): revoca solo
 *       la familia robada ({@code revokeFamilyOnReuse}); si subiera la versión,
 *       el robo de una sesión cerraría todas las del titular.</li>
 *   <li><b>Logout normal</b>: revoca solo su familia; si subiera la versión,
 *       salir en el móvil echaría también al portátil.</li>
 *   <li><b>Refresh de una cuenta pendiente</b> (BAJA-B): solo revoca sus
 *       refresh, sin bloquear la fila de {@code users}; la confirmación
 *       posterior del correo es la que sube la versión, y desde entonces ningún
 *       JWT anterior abre la API.</li>
 * </ul>
 *
 * <p>
 * <b>Cómo se sabe que cada test detectaría el cambio.</b> Cada uno comprueba la
 * columna ({@code users.token_version} sigue en 0) y que el JWT del otro
 * dispositivo sigue dando 200. Si la reutilización o el logout llamaran a
 * {@code revokeAllSessions}, la columna valdría 1 y ese JWT daría 401 (el
 * filtro compara por igualdad). Al final de los dos primeros se hace la
 * contraprueba: tras un {@code logout-all} real, el mismo JWT da 401 con la
 * misma comprobación, así que la aserción no pasa por casualidad.
 * </p>
 *
 * <p>
 * Sin {@code @Transactional} (las revocaciones confirman de verdad); las
 * cuentas del dominio {@value #DOMAIN} se borran antes y después de cada test
 * (sus refresh tokens caen en cascada).
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AccessTokenVersionDecisionsIntegrationTest {

    private static final String DOMAIN = "@decisiones-version.test";
    private static final String PASSWORD = "Faro-De-Finisterre-2026";
    private static final String ACCESS = AuthCookieService.COOKIE_NAME;
    private static final String REFRESH = AuthCookieService.REFRESH_COOKIE_NAME;
    private static final String ME = "/api/users/me";
    private static final String CSRF = JwtAuthenticationFilter.CSRF_HEADER;
    private static final String CSRF_VALUE = JwtAuthenticationFilter.CSRF_HEADER_VALUE;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshTokenService refreshTokenService;
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

    /**
     * Presentar un refresh ya rotado (fuera de la gracia: su sucesor también
     * está rotado) revoca la familia robada, pero <b>no</b> sube la versión: el
     * JWT y el refresh del otro dispositivo siguen sirviendo, y el JWT de
     * acceso de la familia robada vale hasta su {@code exp} (riesgo aceptado y
     * documentado en {@code RefreshTokenService.revokeAllSessions}).
     */
    @Test
    void reutilizarUnRefreshYaRotadoNoSubeLaVersionNiEchaALosDemasDispositivos() throws Exception {
        User user = createUser("robo", true);
        MvcResult phone = login(user);
        MvcResult laptop = login(user);
        String laptopJwt = cookie(laptop, ACCESS);

        // Familia del móvil: r1 -> r2 -> r3. Presentar r1 ya no tiene gracia
        // (su sucesor r2 está revocado): es la reutilización de un token robado.
        String r1 = cookie(phone, REFRESH);
        MvcResult second = refresh(r1, 204);
        String phoneJwt = cookie(second, ACCESS);
        String r3 = cookie(refresh(cookie(second, REFRESH), 204), REFRESH);

        assertSessionExpired(refresh(r1, 401));
        // Control de que se ha recorrido la rama de reutilización: la familia
        // entera queda revocada, también el último token del móvil.
        assertSessionExpired(refresh(r3, 401));

        assertEquals(0, tokenVersionOf(user), "la reutilización no debe subir users.token_version");
        assertMeStatus(laptopJwt, 200);
        assertMeStatus(phoneJwt, 200);
        String laptopRefreshed = cookie(refresh(cookie(laptop, REFRESH), 204), ACCESS);
        assertMeStatus(laptopRefreshed, 200);

        // Contraprueba: con una revocación de verdad, la misma comprobación da 401.
        logoutAll(laptopRefreshed);
        assertMeStatus(laptopJwt, 401);
    }

    /**
     * El logout normal revoca solo la familia de esa sesión: no sube la versión
     * y el otro dispositivo sigue dentro (JWT y refresh). La copia del JWT de
     * acceso de la sesión cerrada sigue valiendo hasta su {@code exp}: es la
     * limitación que documentan {@code JwtProperties} y el {@code @Operation}
     * del logout (el logout no anula un token concreto).
     */
    @Test
    void elLogoutNormalNoSubeLaVersionNiEchaALosDemasDispositivos() throws Exception {
        User user = createUser("logout", true);
        MvcResult phone = login(user);
        MvcResult laptop = login(user);
        String phoneJwt = cookie(phone, ACCESS);
        String laptopJwt = cookie(laptop, ACCESS);

        mockMvc.perform(post("/api/auth/logout").header(CSRF, CSRF_VALUE)
                        .cookie(new Cookie(ACCESS, phoneJwt), new Cookie(REFRESH, cookie(phone, REFRESH))))
                .andReturn();

        assertSessionExpired(refresh(cookie(phone, REFRESH), 401));
        assertEquals(0, tokenVersionOf(user), "el logout normal no debe subir users.token_version");
        assertMeStatus(laptopJwt, 200);
        assertMeStatus(phoneJwt, 200);
        String laptopRefreshed = cookie(refresh(cookie(laptop, REFRESH), 204), ACCESS);
        assertMeStatus(laptopRefreshed, 200);

        // Contraprueba: con una revocación de verdad, la misma comprobación da 401.
        logoutAll(laptopRefreshed);
        assertMeStatus(laptopJwt, 401);
    }

    /**
     * Cuenta pendiente con una sesión heredada (JWT y refresh de antes de la
     * V9): su refresh da 401 y revoca sus refresh tokens <b>sin</b> subir la
     * versión (BAJA-B: no bloquea la fila de {@code users}). Al confirmar el
     * correo la versión sube, y ningún JWT anterior, ni el de antes del refresh
     * ni uno firmado con la versión que había entre el refresh y la
     * confirmación, abre la API; el login posterior sí.
     *
     * <p>
     * Sin la subida en la confirmación, los dos JWT darían 200 al final (la
     * cuenta ya está verificada y su {@code ver} coincidiría). Si el refresh
     * de la pendiente volviera a usar {@code revokeAllSessions}, la versión
     * tras el refresh sería 1, no 0.
     * </p>
     */
    @Test
    void trasElRefreshDeUnaPendienteYLaConfirmacionNingunJwtAnteriorAbreLaApi() throws Exception {
        User pending = createUser("pendiente", false);
        String inheritedJwt = jwtService.generateToken(pending, 0);
        String inheritedRefresh = refreshTokenService.startFamily(pending.getId()).value();
        String otherRefresh = refreshTokenService.startFamily(pending.getId()).value();
        assertMeStatus(inheritedJwt, 401);

        assertSessionExpired(refresh(inheritedRefresh, 401));
        assertEquals(0, tokenVersionOf(pending), "el refresh de una pendiente no debe subir la versión (BAJA-B)");
        // También revoca la otra sesión heredada, no solo la presentada.
        assertSessionExpired(refresh(otherRefresh, 401));
        // Un JWT con la versión vigente entre el refresh y la confirmación.
        String betweenJwt = jwtService.generateToken(pending, tokenVersionOf(pending));

        String link = issueAccountToken(pending, AccountTokenPurpose.EMAIL_VERIFICATION);
        mockMvc.perform(post("/api/auth/email-verification").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", link))))
                .andExpect(result -> assertEquals(204, result.getResponse().getStatus(),
                        result.getResponse().getContentAsString()));

        assertEquals(1, tokenVersionOf(pending));
        assertMeStatus(inheritedJwt, 401);
        assertMeStatus(betweenJwt, 401);
        assertSessionExpired(refresh(inheritedRefresh, 401));
        assertSessionExpired(refresh(otherRefresh, 401));

        MvcResult fresh = login(pending);
        assertEquals(1, jwtService.parseAccessToken(cookie(fresh, ACCESS)).tokenVersion());
        assertMeStatus(cookie(fresh, ACCESS), 200);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private User createUser(String name, boolean verified) {
        User user = new User();
        user.setUsername(name + "_decver");
        user.setEmail(name + DOMAIN);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setEmailVerifiedAt(verified ? Instant.now() : null);
        return userRepository.saveAndFlush(user);
    }

    private MvcResult login(User user) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", user.getEmail(), "password", PASSWORD))))
                .andReturn();
        assertEquals(204, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return result;
    }

    private MvcResult refresh(String refreshToken, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/refresh").header(CSRF, CSRF_VALUE)
                        .cookie(new Cookie(REFRESH, refreshToken)))
                .andReturn();
        assertEquals(expectedStatus, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        return result;
    }

    private void logoutAll(String jwt) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/logout-all").header(CSRF, CSRF_VALUE)
                        .cookie(new Cookie(ACCESS, jwt)))
                .andReturn();
        assertEquals(204, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }

    /** {@code GET /api/users/me} por {@code Bearer} y por cookie: las dos vías del filtro. */
    private void assertMeStatus(String jwt, int expected) throws Exception {
        int bearer = mockMvc.perform(get(ME).header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt))
                .andReturn().getResponse().getStatus();
        int cookie = mockMvc.perform(get(ME).cookie(new Cookie(ACCESS, jwt)))
                .andReturn().getResponse().getStatus();
        assertEquals(expected, bearer, "por Authorization: Bearer");
        assertEquals(expected, cookie, "por la cookie " + ACCESS);
    }

    /** 401 {@code SESSION_EXPIRED} del refresh. */
    private static void assertSessionExpired(MvcResult result) throws Exception {
        assertEquals(401, result.getResponse().getStatus());
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"code\":\"SESSION_EXPIRED\""), body);
    }

    private String issueAccountToken(User user, AccountTokenPurpose purpose) {
        return new TransactionTemplate(transactionManager).execute(
                status -> accountTokens.issue(user.getId(), purpose, clock.instant(), Duration.ofHours(1)));
    }

    private int tokenVersionOf(User user) {
        return userRepository.findTokenVersionById(user.getId()).orElseThrow();
    }

    private String json(Map<String, ?> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private static String cookie(MvcResult result, String name) {
        Cookie cookie = result.getResponse().getCookie(name);
        assertNotNull(cookie, "la respuesta no fija la cookie " + name);
        return cookie.getValue();
    }
}
