package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.emilio.streambox.email.AccountEmails;
import com.emilio.streambox.email.MemoryEmailSender;
import com.emilio.streambox.email.MemoryEmailSender.SentEmail;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.emilio.streambox.security.password.PasswordPolicy;
import com.emilio.streambox.support.AdjustableClock;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

/**
 * Revisión independiente (QA) de «He olvidado mi contraseña»: lo que no
 * cubren {@code PasswordResetIntegrationTest} ni
 * {@code EmailVerificationQaIntegrationTest}.
 *
 * <ul>
 *   <li><b>M2 de punta a punta</b>, con el atacante haciendo todo lo que puede
 *       hacer sin acceso al buzón (registrar el email de la víctima, reenviar
 *       el correo, bloquear la cuenta con fallos, pedir restablecimientos entre
 *       medias, volver a registrarse, pedir su nombre pasadas 24 h) y también
 *       la variante clásica en que la víctima pulsa el enlace del atacante y
 *       este llega a entrar.</li>
 *   <li><b>Enumeración con secuencias nuevas</b>: restablecimiento pedido
 *       <i>antes</i> de las 24 h (el enlace sobrevive a la caducidad del
 *       nombre) y repetir el registro con el mismo email y nombre (el caso
 *       «misma cuenta» de {@code RegistrationService.checkOwnUsernameReleased})
 *       antes de pedir el nombre con otro email.</li>
 *   <li><b>Propósitos separados en el otro sentido</b>: un token de
 *       restablecimiento no confirma el correo ni se gasta al intentarlo.</li>
 *   <li>La cuenta la decide <b>solo el token</b>: la sesión de otra cuenta en
 *       el mismo navegador no se toca.</li>
 *   <li>ADMIN de punta a punta; email con mayúsculas; política contra el nombre
 *       <b>actual</b> de la cuenta.</li>
 * </ul>
 *
 * <p>
 * Contexto propio: reloj ajustable y el bloqueo de cuentas de producción (5
 * fallos en 15 minutos). Los límites silenciosos de correos siguen desactivados
 * ({@code application-test.properties}) para que el atacante pueda reenviar sin
 * esperar. Sin {@code @Transactional}: los correos salen tras el commit. Las
 * cuentas usan el dominio {@value #DOMAIN} y cada test sus propias IPs y
 * nombres (las reservas de nombre viven en memoria todo el contexto).
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "streambox.security.rate-limit.lockout.max-failures=5",
        "streambox.security.rate-limit.lockout.window=15m"
})
class PasswordResetQaIntegrationTest {

    private static final String DOMAIN = "@qa-restablecer.test";
    private static final String OWNER_PASSWORD = "Paseo-Por-El-Retiro-2026";
    private static final String ATTACKER_PASSWORD = "Contrasena-Del-Atacante-2026";
    private static final String VICTIM_NEW_PASSWORD = "Rio-Verde-Bajo-Un-Puente-77";
    private static final String RESET = "/api/auth/password-reset";
    private static final String CONFIRM = "/api/auth/password-reset/confirm";
    private static final Pattern RESET_LINK = Pattern.compile(
            "https://streambox\\.test/restablecer-contrasena#token=([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");
    private static final Pattern VERIFICATION_LINK = Pattern.compile(
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

    /** Cuenta que ya puede tener el email que prueba el atacante. */
    enum ExistingAccount { VERIFIED, PENDING, LEGACY }

    @BeforeEach
    void setUp() {
        CLOCK.reset();
        cleanUp();
        inbox.clear();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
    }

    // ------------------------------------------------------------------
    // M2 de punta a punta
    // ------------------------------------------------------------------

    /**
     * <b>M2 con un atacante que lo intenta todo.</b> Registra el email de la
     * víctima con su contraseña, reenvía el correo de confirmación, bloquea la
     * cuenta fallando el login y, cuando la víctima pide el enlace, pide otro
     * para invalidárselo. La víctima usa el del correo más reciente (que solo
     * llega a su buzón) y fija su contraseña.
     *
     * <p>
     * Después: la cuenta es de la víctima (verificada, con su contraseña, que
     * entra nada más fijarla y desde una IP nueva aunque la cuenta estuviera
     * bloqueada); la contraseña del atacante y todos los enlaces anteriores
     * (los de confirmación que él provocó y el primero de restablecimiento) ya
     * no sirven; y nada de lo que el atacante puede hacer sin el buzón le
     * devuelve el control: repetir el registro (solo llega «Ya tienes una
     * cuenta»), reenviar la confirmación (no llega nada), pedir otro
     * restablecimiento (la contraseña no cambia) ni, pasadas 24 h, pedir el
     * nombre de la cuenta o volver a registrar su email (una verificada nunca
     * se borra).
     * </p>
     */
    @Test
    void m2ElAtacanteNoRecuperaElControlHagaLoQueHagaSinElBuzon() throws Exception {
        String victim = "victima_m2" + DOMAIN;
        String attackerIp = "10.251.0.1";
        register("suplantador_m2", victim, ATTACKER_PASSWORD).andExpect(status().isAccepted());
        Long id = userRepository.findByEmail(victim).orElseThrow().getId();
        resend(victim).andExpect(status().isAccepted());
        resend(victim).andExpect(status().isAccepted());
        for (int i = 0; i < 4; i++) {
            login(victim, ATTACKER_PASSWORD, attackerIp).andExpect(status().isUnauthorized());
        }
        login(victim, ATTACKER_PASSWORD, attackerIp).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        String victimFirstLink = requestAndGetToken(victim);
        String latestLink = requestAndGetToken(victim); // lo pide el atacante; llega al buzón de la víctima
        confirm(victimFirstLink, VICTIM_NEW_PASSWORD, "10.251.0.2")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"));
        confirm(latestLink, VICTIM_NEW_PASSWORD, "10.251.0.2").andExpect(status().isNoContent());

        User account = userRepository.findById(id).orElseThrow();
        assertTrue(account.isEmailVerified());
        assertTrue(passwordEncoder.matches(VICTIM_NEW_PASSWORD, account.getPassword()));
        login(victim, VICTIM_NEW_PASSWORD, "10.251.0.3").andExpect(status().isNoContent());
        login(victim, ATTACKER_PASSWORD, attackerIp).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        List<SentEmail> verificationEmails = emailsTo(victim, AccountEmails.VERIFICATION_SUBJECT);
        assertEquals(3, verificationEmails.size());
        for (SentEmail email : verificationEmails) {
            confirmEmail(tokenIn(email, VERIFICATION_LINK)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));
        }
        confirm(latestLink, ATTACKER_PASSWORD, attackerIp).andExpect(status().isBadRequest());

        // Lo que el atacante puede seguir haciendo sin el buzón.
        inbox.clear();
        register("suplantador_m2_bis", victim, ATTACKER_PASSWORD).andExpect(status().isAccepted());
        resend(victim).andExpect(status().isAccepted());
        requestReset(victim).andExpect(status().isAccepted());
        // La bandeja de pruebas devuelve primero el más reciente.
        assertEquals(List.of(AccountEmails.PASSWORD_RESET_SUBJECT, AccountEmails.ALREADY_REGISTERED_SUBJECT),
                inbox.sentTo(victim).stream().map(SentEmail::subject).toList(),
                "Solo «Ya tienes una cuenta» y el enlace (al buzón de la víctima); ninguna confirmación nueva");
        CLOCK.advance(Duration.ofHours(25));
        register("suplantador_m2", "otro.atacante_m2" + DOMAIN, ATTACKER_PASSWORD)
                .andExpect(status().isConflict());
        User attackerAccount = createUser("atacante_m2", "atacante_m2" + DOMAIN, Role.USER, true);
        renameTo(attackerAccount, "suplantador_m2").andExpect(status().isConflict());
        register("suplantador_m2_ter", victim, ATTACKER_PASSWORD).andExpect(status().isAccepted());

        User after = userRepository.findByEmail(victim).orElseThrow();
        assertEquals(id, after.getId(), "La cuenta de la víctima no se ha sustituido ni borrado");
        assertTrue(after.isEmailVerified());
        assertTrue(passwordEncoder.matches(VICTIM_NEW_PASSWORD, after.getPassword()));
        login(victim, ATTACKER_PASSWORD, attackerIp).andExpect(status().isUnauthorized());
    }

    /**
     * <b>M2 clásico: la víctima pulsa el enlace de confirmación</b> que provocó
     * el atacante (sin leer el aviso del nombre), la cuenta queda verificada con
     * la contraseña del atacante y este entra: tiene sesión y su IP pasa a ser
     * «conocida». Cuando la víctima restablece: el refresh del atacante da 401
     * {@code SESSION_EXPIRED}, su contraseña ya no entra y su IP deja de ser
     * conocida (si alguien bloquea la cuenta, desde ella ya no se entra ni con
     * la contraseña buena, y desde la IP que confirmó sí).
     */
    @Test
    void m2ClasicoAunqueElAtacanteLlegueAEntrarElRestablecimientoLoEcha() throws Exception {
        String victim = "victima_m2c" + DOMAIN;
        String attackerIp = "10.251.1.1";
        String victimIp = "10.251.1.2";
        register("suplantador_m2c", victim, ATTACKER_PASSWORD).andExpect(status().isAccepted());
        confirmEmail(tokenIn(emailsTo(victim, AccountEmails.VERIFICATION_SUBJECT).get(0), VERIFICATION_LINK))
                .andExpect(status().isNoContent());
        MockHttpServletResponse attackerLogin = login(victim, ATTACKER_PASSWORD, attackerIp)
                .andExpect(status().isNoContent()).andReturn().getResponse();
        Cookie attackerRefresh = attackerLogin.getCookie("streambox_refresh");
        assertNotNull(attackerRefresh);

        confirm(requestAndGetToken(victim), VICTIM_NEW_PASSWORD, victimIp).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh").with(remoteAddr(attackerIp))
                        .header("X-Requested-With", "StreamBox").cookie(attackerRefresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        login(victim, ATTACKER_PASSWORD, attackerIp).andExpect(status().isUnauthorized());
        for (int i = 0; i < 4; i++) {
            login(victim, "No-Es-La-Contrasena-" + i, "10.251.1.3");
        }
        // Junto con el fallo de arriba desde attackerIp (que ya no es conocida), 5: cuenta bloqueada.
        login(victim, VICTIM_NEW_PASSWORD, attackerIp).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
        login(victim, VICTIM_NEW_PASSWORD, victimIp).andExpect(status().isNoContent());
    }

    /**
     * <b>M2: el atacante se cruza con el enlace de la víctima.</b> Entre que la
     * víctima pide el enlace y lo usa, el atacante repite el registro con su
     * email (le llega otra confirmación a la víctima, pero el enlace de
     * restablecimiento sigue valiendo). Y el peor orden posible, el «coste
     * asumido» de MEDIA-1: pasadas 24 h borra la pendiente pidiendo su nombre
     * con otro email (el enlace de la víctima deja de servir) y vuelve a
     * registrar el email de la víctima con su contraseña. Aun así la víctima
     * gana: la pendiente nueva no se puede borrar por el nombre ni sustituir
     * por el email durante 24 h, y en ese tiempo un enlace nuevo la deja en sus
     * manos.
     */
    @Test
    void m2ElAtacanteSeCruzaConElEnlaceYLaVictimaSigueGanando() throws Exception {
        String victim = "victima_m2x" + DOMAIN;
        register("suplantador_m2x", victim, ATTACKER_PASSWORD).andExpect(status().isAccepted());

        String link = requestAndGetToken(victim);
        register("suplantador_m2x_bis", victim, ATTACKER_PASSWORD).andExpect(status().isAccepted());
        confirm(link, VICTIM_NEW_PASSWORD, "10.251.8.1").andExpect(status().isNoContent());
        login(victim, VICTIM_NEW_PASSWORD, "10.251.8.1").andExpect(status().isNoContent());

        // Segunda víctima, el peor orden.
        String other = "victima_m2y" + DOMAIN;
        register("suplantador_m2y", other, ATTACKER_PASSWORD).andExpect(status().isAccepted());
        CLOCK.advance(Duration.ofHours(24).plusMinutes(1));
        String stale = requestAndGetToken(other);
        register("suplantador_m2y", "comodin_m2y" + DOMAIN, ATTACKER_PASSWORD).andExpect(status().isAccepted());
        register("suplantador_m2y_2", other, ATTACKER_PASSWORD).andExpect(status().isAccepted());
        // Hibernate pone la fecha de alta con la hora real, que ahora va 24 h por detrás de este
        // reloj: sin esto la pendiente nueva parecería creada «antes» que su token (una heredada).
        jdbc.update("UPDATE users SET created_at = ? WHERE email = ?", Timestamp.from(CLOCK.instant()), other);
        confirm(stale, VICTIM_NEW_PASSWORD, "10.251.8.2").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"));

        CLOCK.advance(Duration.ofHours(23));
        register("suplantador_m2y_2", "comodin2_m2y" + DOMAIN, ATTACKER_PASSWORD).andExpect(status().isConflict());
        register("suplantador_m2y_3", other, ATTACKER_PASSWORD).andExpect(status().isAccepted());
        assertEquals("suplantador_m2y_2", userRepository.findByEmail(other).orElseThrow().getUsername());
        confirm(requestAndGetToken(other), VICTIM_NEW_PASSWORD, "10.251.8.2").andExpect(status().isNoContent());
        login(other, VICTIM_NEW_PASSWORD, "10.251.8.2").andExpect(status().isNoContent());
        login(other, ATTACKER_PASSWORD, "10.251.8.3").andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Enumeración: secuencias que no cubren los otros tests
    // ------------------------------------------------------------------

    /**
     * El atacante registra (E, U) con el email que quiere comprobar y (F, U')
     * con uno que sabe libre; a las <b>23 h 30 min</b> pide restablecer la
     * contraseña de los dos (el enlace sigue vigente pasadas las 24 h del alta,
     * cuando el nombre ya no es de la pendiente); entonces repite cada registro con el mismo email y nombre (caso «misma
     * cuenta») y después pide cada nombre con otro email, y lo intenta también
     * con «Editar perfil». Cada paso debe responder igual (código y cuerpo sin
     * la fecha) para E que para F: si no, el atacante sabría si E tenía cuenta.
     */
    @ParameterizedTest
    @EnumSource(ExistingAccount.class)
    void restablecerAntesDeLas24HorasYRepetirElRegistroNoDelataElEmail(ExistingAccount kind) throws Exception {
        String suffix = "_a" + kind.ordinal();
        String probed = "probado" + suffix + DOMAIN;
        String fresh = "libre" + suffix + DOMAIN;
        createExisting(kind, "titular" + suffix, probed);
        User attacker = createUser("atacante" + suffix, "atacante" + suffix + DOMAIN, Role.USER, true);
        List<String> probedSteps = new ArrayList<>();
        List<String> freshSteps = new ArrayList<>();

        step(probedSteps, register("nom_e" + suffix, probed, ATTACKER_PASSWORD));
        step(freshSteps, register("nom_f" + suffix, fresh, ATTACKER_PASSWORD));
        CLOCK.advance(Duration.ofHours(23).plusMinutes(30));
        step(probedSteps, requestReset(probed));
        step(freshSteps, requestReset(fresh));
        // Un minuto de margen: la fecha de alta la pone Hibernate con la hora real, que va algo por
        // delante de este reloj (parado en el segundo de reset() mientras se crean las cuentas).
        CLOCK.advance(Duration.ofMinutes(31));
        step(probedSteps, register("nom_e" + suffix, probed, ATTACKER_PASSWORD));
        step(freshSteps, register("nom_f" + suffix, fresh, ATTACKER_PASSWORD));
        step(probedSteps, renameTo(attacker, "nom_e" + suffix));
        step(freshSteps, renameTo(attacker, "nom_f" + suffix));
        step(probedSteps, register("nom_e" + suffix, "otro_e" + suffix + DOMAIN, ATTACKER_PASSWORD));
        step(freshSteps, register("nom_f" + suffix, "otro_f" + suffix + DOMAIN, ATTACKER_PASSWORD));

        assertEquals(freshSteps, probedSteps, "Las respuestas delatan si el email ya tenía cuenta");
    }

    /**
     * Igual, pero el restablecimiento se pide <b>pasadas 24 h</b> y después
     * se repite el registro con el mismo email y nombre (la pendiente
     * conserva su cuenta por la regla por email, pero el nombre ya no es
     * suyo) antes de pedir cada nombre con otro email y con «Editar perfil».
     */
    @ParameterizedTest
    @EnumSource(ExistingAccount.class)
    void restablecerPasadas24HorasYRepetirElRegistroNoDelataElEmail(ExistingAccount kind) throws Exception {
        String suffix = "_b" + kind.ordinal();
        String probed = "probado" + suffix + DOMAIN;
        String fresh = "libre" + suffix + DOMAIN;
        createExisting(kind, "titular" + suffix, probed);
        User attacker = createUser("atacante" + suffix, "atacante" + suffix + DOMAIN, Role.USER, true);
        List<String> probedSteps = new ArrayList<>();
        List<String> freshSteps = new ArrayList<>();

        step(probedSteps, register("nom_e" + suffix, probed, ATTACKER_PASSWORD));
        step(freshSteps, register("nom_f" + suffix, fresh, ATTACKER_PASSWORD));
        CLOCK.advance(Duration.ofHours(24).plusSeconds(30));
        step(probedSteps, requestReset(probed));
        step(freshSteps, requestReset(fresh));
        step(probedSteps, register("nom_e" + suffix, probed, ATTACKER_PASSWORD));
        step(freshSteps, register("nom_f" + suffix, fresh, ATTACKER_PASSWORD));
        step(probedSteps, register("nom_e" + suffix, "otro_e" + suffix + DOMAIN, ATTACKER_PASSWORD));
        step(freshSteps, register("nom_f" + suffix, "otro_f" + suffix + DOMAIN, ATTACKER_PASSWORD));
        step(probedSteps, renameTo(attacker, "nom_e" + suffix));
        step(freshSteps, renameTo(attacker, "nom_f" + suffix));
        step(probedSteps, login(probed, ATTACKER_PASSWORD, "10.251.2." + kind.ordinal()));
        step(freshSteps, login(fresh, ATTACKER_PASSWORD, "10.251.3." + kind.ordinal()));

        assertEquals(freshSteps, probedSteps, "Las respuestas delatan si el email ya tenía cuenta");
    }

    // ------------------------------------------------------------------
    // Token: propósitos separados, también al revés
    // ------------------------------------------------------------------

    /**
     * Un token de <b>restablecimiento</b> no sirve para confirmar el correo
     * ({@code POST /api/auth/email-verification}): 400
     * {@code INVALID_VERIFICATION_TOKEN}, la cuenta sigue pendiente y el token
     * <b>no se gasta</b> (después restablece con él). Es el sentido contrario
     * de {@code PasswordResetIntegrationTest#unTokenDeVerificacionNoSirveParaRestablecer}.
     */
    @Test
    void unTokenDeRestablecimientoNoConfirmaElCorreoNiSeGasta() throws Exception {
        String email = "pendiente_tok" + DOMAIN;
        register("pendiente_tok", email, OWNER_PASSWORD).andExpect(status().isAccepted());
        String resetToken = requestAndGetToken(email);

        confirmEmail(resetToken).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));

        assertFalse(userRepository.findByEmail(email).orElseThrow().isEmailVerified());
        confirm(resetToken, VICTIM_NEW_PASSWORD, "10.251.4.1").andExpect(status().isNoContent());
    }

    /**
     * La política se comprueba contra el nombre <b>actual</b> de la cuenta: si
     * cambia el nombre entre pedir el enlace y usarlo, una contraseña con el
     * nombre nuevo da 400 en {@code newPassword} sin gastar el token, y una con
     * el nombre antiguo (que ya no es un dato suyo) se acepta.
     */
    @Test
    void laPoliticaUsaElNombreActualDeLaCuenta() throws Exception {
        User user = createUser("nombre_viejo_qa", "cambia_nombre" + DOMAIN, Role.USER, true);
        String token = requestAndGetToken(user.getEmail());
        renameTo(user, "nombre_nuevo_qa").andExpect(status().isOk());

        confirm(token, "mi-clave-nombre_nuevo_qa-26", "10.251.5.1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.newPassword").value(PasswordPolicy.PERSONAL_DATA_MESSAGE));
        confirm(token, "mi-clave-nombre_viejo_qa-26", "10.251.5.1").andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // La cuenta la decide solo el token
    // ------------------------------------------------------------------

    /**
     * Confirmar desde un navegador con la sesión de <b>otra</b> cuenta (B)
     * abierta, y con un campo {@code email} de B colado en el cuerpo, cambia
     * solo la cuenta del token (A): la contraseña de B no cambia y su sesión
     * sigue viva (su refresh renueva), mientras que las de A se revocan.
     */
    @Test
    void conLaSesionDeOtraCuentaEnElNavegadorSoloCambiaLaCuentaDelToken() throws Exception {
        User a = createUser("cuenta_a_tok", "cuenta_a_tok" + DOMAIN, Role.USER, true);
        User b = createUser("cuenta_b_tok", "cuenta_b_tok" + DOMAIN, Role.USER, true);
        MockHttpServletResponse bLogin = login(b.getEmail(), OWNER_PASSWORD, "10.251.6.1")
                .andExpect(status().isNoContent()).andReturn().getResponse();
        Cookie bToken = bLogin.getCookie("streambox_token");
        Cookie bRefresh = bLogin.getCookie("streambox_refresh");
        String token = requestAndGetToken(a.getEmail());

        mockMvc.perform(post(CONFIRM).with(remoteAddr("10.251.6.1")).cookie(bToken, bRefresh)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", token, "newPassword", VICTIM_NEW_PASSWORD,
                                "email", b.getEmail()))))
                .andExpect(status().isNoContent());

        assertTrue(passwordEncoder.matches(VICTIM_NEW_PASSWORD, storedHash(a)));
        assertTrue(passwordEncoder.matches(OWNER_PASSWORD, storedHash(b)));
        mockMvc.perform(post("/api/auth/refresh").with(remoteAddr("10.251.6.1"))
                        .header("X-Requested-With", "StreamBox").cookie(bRefresh))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // ADMIN y normalización del email
    // ------------------------------------------------------------------

    /**
     * Una cuenta ADMIN restablece de punta a punta: entra con la contraseña
     * nueva, sigue siendo ADMIN (el restablecimiento no toca el rol) y la
     * sesión nueva abre las vistas de administración.
     */
    @Test
    void unaAdminRestableceEntraYSigueSiendoAdmin() throws Exception {
        User admin = createUser("jefa_qa", "jefa_qa" + DOMAIN, Role.ADMIN, true);

        confirm(requestAndGetToken(admin.getEmail()), VICTIM_NEW_PASSWORD, "10.251.7.1")
                .andExpect(status().isNoContent());

        assertEquals(Role.ADMIN, userRepository.findById(admin.getId()).orElseThrow().getRole());
        Cookie session = login(admin.getEmail(), VICTIM_NEW_PASSWORD, "10.251.7.1")
                .andExpect(status().isNoContent()).andReturn().getResponse().getCookie("streambox_token");
        mockMvc.perform(get("/api/admin/series").cookie(session)).andExpect(status().isOk());
    }

    /**
     * El email se normaliza como en el login: quien lo escribe con mayúsculas
     * recibe el enlace de su cuenta (el 202 es el mismo en cualquier caso; lo
     * que importa es que el correo llegue).
     */
    @Test
    void unEmailConMayusculasRecibeElEnlaceDeSuCuenta() throws Exception {
        User user = createUser("mayusculas_qa", "mayusculas_qa" + DOMAIN, Role.USER, true);

        requestReset("Mayusculas_QA" + DOMAIN.toUpperCase()).andExpect(status().isAccepted());

        assertEquals(1, emailsTo(user.getEmail(), AccountEmails.PASSWORD_RESET_SUBJECT).size());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Crea la cuenta que ya tiene el email, según el caso. */
    private void createExisting(ExistingAccount kind, String username, String email) throws Exception {
        switch (kind) {
            case VERIFIED -> createUser(username, email, Role.USER, true);
            case PENDING -> register(username, email, OWNER_PASSWORD).andExpect(status().isAccepted());
            case LEGACY -> {
                User legacy = createUser(username, email, Role.USER, false);
                jdbc.update("UPDATE users SET created_at = ? WHERE id = ?",
                        Timestamp.from(CLOCK.instant().minus(Duration.ofDays(400))), legacy.getId());
            }
        }
    }

    private User createUser(String username, String email, Role role, boolean verified) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(OWNER_PASSWORD));
        user.setRole(role);
        user.setEmailVerifiedAt(verified ? Instant.now() : null);
        return userRepository.saveAndFlush(user);
    }

    /** Anota lo que ve el atacante de una respuesta: código y cuerpo sin la fecha ni el id. */
    private void step(List<String> steps, ResultActions result) throws Exception {
        MockHttpServletResponse response = result.andReturn().getResponse();
        String content = response.getContentAsString(StandardCharsets.UTF_8);
        Map<String, Object> body = new HashMap<>();
        if (!content.isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(content, Map.class);
            body.putAll(parsed);
        }
        // Lo que cambia solo, o que el atacante ya conoce porque lo eligió él.
        body.remove("timestamp");
        body.remove("username");
        body.remove("id");
        body.remove("email");
        body.remove("createdAt");
        steps.add(response.getStatus() + " " + new java.util.TreeMap<>(body));
    }

    private ResultActions register(String username, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", username, "email", email, "password", password))));
    }

    private ResultActions login(String email, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/login").with(remoteAddr(ip)).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email, "password", password))));
    }

    private ResultActions resend(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/email-verification/resend").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email))));
    }

    private ResultActions requestReset(String email) throws Exception {
        return mockMvc.perform(post(RESET).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("email", email))));
    }

    private ResultActions confirm(String token, String newPassword, String ip) throws Exception {
        return mockMvc.perform(post(CONFIRM).with(remoteAddr(ip)).contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("token", token, "newPassword", newPassword))));
    }

    private ResultActions confirmEmail(String token) throws Exception {
        return mockMvc.perform(post("/api/auth/email-verification").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("token", token))));
    }

    private ResultActions renameTo(User user, String username) throws Exception {
        return mockMvc.perform(patch("/api/users/me")
                .cookie(new Cookie("streambox_token", jwtService.generateToken(user)))
                .header("X-Requested-With", "StreamBox")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("username", username))));
    }

    /** Pide el enlace y devuelve el token del correo más reciente (el primero de la bandeja). */
    private String requestAndGetToken(String email) throws Exception {
        int before = emailsTo(email, AccountEmails.PASSWORD_RESET_SUBJECT).size();
        requestReset(email).andExpect(status().isAccepted());
        List<SentEmail> emails = emailsTo(email, AccountEmails.PASSWORD_RESET_SUBJECT);
        assertEquals(before + 1, emails.size(), emails::toString);
        return tokenIn(emails.get(0), RESET_LINK);
    }

    private List<SentEmail> emailsTo(String email, String subject) {
        return inbox.sentTo(email).stream().filter(sent -> sent.subject().equals(subject)).toList();
    }

    private String storedHash(User user) {
        return jdbc.queryForObject("SELECT password FROM users WHERE id = ?", String.class, user.getId());
    }

    private String json(Map<String, ?> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private static String tokenIn(SentEmail email, Pattern link) {
        Matcher matcher = link.matcher(email.body());
        assertTrue(matcher.find(), email.body());
        return matcher.group(1);
    }

    private static RequestPostProcessor remoteAddr(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
