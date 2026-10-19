package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
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

import com.emilio.streambox.dto.AcceptedResponse;
import com.emilio.streambox.email.AccountEmails;
import com.emilio.streambox.email.MemoryEmailSender;
import com.emilio.streambox.email.MemoryEmailSender.SentEmail;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.emilio.streambox.support.AdjustableClock;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

/**
 * Registro con verificación del correo ({@code POST /api/users} → 202), contra
 * la base de datos y leyendo los correos de {@link MemoryEmailSender}.
 *
 * <p>
 * Lo que se comprueba en cada camino, y por qué falla sin el cambio:
 * </p>
 * <ul>
 *   <li><b>Sin enumeración</b>: el código y el cuerpo son idénticos para un
 *       email nuevo y uno ya registrado (antes, 201 frente a 409 «El correo
 *       electrónico ya está en uso»). Lo que cambia es el correo que llega al
 *       buzón.</li>
 *   <li><b>Cuentas heredadas</b> (USER anteriores a la V9, pendientes, sin
 *       tokens y quizá con favoritos): nunca se borran, ni pasadas 24 h; solo
 *       reciben un enlace.</li>
 *   <li><b>Sustitución</b>: solo una pendiente creada por el registro y con
 *       todos sus enlaces caducados se sustituye (y libera su nombre).</li>
 *   <li><b>Host</b>: el enlace sale de {@code streambox.public-url}
 *       ({@code https://streambox.test} en el perfil {@code test}), nunca de
 *       las cabeceras de la petición.</li>
 * </ul>
 *
 * <p>
 * Sin {@code @Transactional}: los correos salen <b>tras el commit</b>, y en
 * una transacción de test que se deshace no saldría ninguno. Los usuarios del
 * dominio {@value #DOMAIN} se borran antes y después de cada test. El reloj de
 * la aplicación es {@link AdjustableClock} (contexto propio), para caducar los
 * enlaces sin esperar 24 h.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RegistrationIntegrationTest {

    private static final String DOMAIN = "@registro.test";
    private static final String PASSWORD = "Paseo-Por-El-Retiro-2026";
    private static final String OTHER_PASSWORD = "Otra-Tarde-En-El-Prado-2026";
    private static final String USERNAME_TAKEN = "El nombre de usuario ya está en uso";
    private static final Pattern LINK = Pattern.compile(
            "https://streambox\\.test/verificar-email#token=([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");

    private static final AdjustableClock CLOCK = new AdjustableClock();

    @TestBean
    private Clock clock;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MemoryEmailSender inbox;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private Long movieId;

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
        if (movieId != null) {
            movieRepository.deleteById(movieId);
            movieId = null;
        }
    }

    // ------------------------------------------------------------------
    // Email nuevo
    // ------------------------------------------------------------------

    /**
     * Email nuevo: 202 con el cuerpo fijo, cuenta USER pendiente con la
     * contraseña cifrada, un token (solo su SHA-256, 24 h de vida) y un correo
     * con el enlace a la URL pública. El correo dice el nombre de usuario de
     * la cuenta, para que el dueño del buzón pueda ver si es suya (M2,
     * <i>pre-account hijacking</i>; ver {@code AccountEmails}).
     */
    @Test
    void unEmailNuevoCreaUnaCuentaPendienteYEnviaElEnlace() throws Exception {
        MockHttpServletResponse response = register("ana_nueva", "ana" + DOMAIN, PASSWORD)
                .andExpect(status().isAccepted())
                .andReturn().getResponse();

        assertEquals(Map.of("message", AcceptedResponse.REGISTRATION_MESSAGE), bodyAsMap(response));

        User user = userRepository.findByEmail("ana" + DOMAIN).orElseThrow();
        assertEquals(Role.USER, user.getRole());
        assertNull(user.getEmailVerifiedAt());
        assertTrue(passwordEncoder.matches(PASSWORD, user.getPassword()));

        SentEmail email = onlyEmailTo("ana" + DOMAIN);
        assertEquals(AccountEmails.VERIFICATION_SUBJECT, email.subject());
        assertTrue(email.body().contains("con la cuenta «ana_nueva»"), email.body());
        String token = tokenIn(email);

        List<Map<String, Object>> tokens = tokensOf(user.getId());
        assertEquals(1, tokens.size());
        assertEquals(sha256Hex(token), tokens.get(0).get("TOKEN_HASH"));
        assertNull(tokens.get(0).get("USED_AT"));
        assertEquals(CLOCK.instant(), instant(tokens.get(0).get("CREATED_AT")));
        assertEquals(CLOCK.instant().plus(Duration.ofHours(24)), instant(tokens.get(0).get("EXPIRES_AT")));
    }

    // ------------------------------------------------------------------
    // Email de una cuenta confirmada
    // ------------------------------------------------------------------

    /**
     * El registro con el email de una cuenta confirmada responde
     * <b>exactamente</b> lo mismo que con un email nuevo (código y cuerpo),
     * no toca la cuenta (ni contraseña, ni nombre, ni tokens) y solo envía al
     * buzón el aviso «Ya tienes una cuenta», sin enlace de verificación.
     */
    @Test
    void elEmailDeUnaCuentaConfirmadaRespondeIgualQueUnoNuevoYSoloAvisa() throws Exception {
        User existing = createUser("bea_confirmada", "bea" + DOMAIN, true, null);

        MockHttpServletResponse fresh = register("otro_nuevo", "otro" + DOMAIN, PASSWORD).andReturn().getResponse();
        MockHttpServletResponse taken = register("bea_impostora", "BEA" + DOMAIN, OTHER_PASSWORD)
                .andReturn().getResponse();

        assertEquals(202, taken.getStatus());
        assertEquals(fresh.getStatus(), taken.getStatus());
        assertEquals(fresh.getContentAsString(StandardCharsets.UTF_8), taken.getContentAsString(StandardCharsets.UTF_8));

        User after = userRepository.findByEmail("bea" + DOMAIN).orElseThrow();
        assertEquals(existing.getId(), after.getId());
        assertEquals("bea_confirmada", after.getUsername());
        assertTrue(passwordEncoder.matches(PASSWORD, after.getPassword()));
        assertTrue(userRepository.findByUsername("bea_impostora").isEmpty());
        assertTrue(tokensOf(existing.getId()).isEmpty());

        SentEmail notice = onlyEmailTo("bea" + DOMAIN);
        assertEquals(AccountEmails.ALREADY_REGISTERED_SUBJECT, notice.subject());
        assertTrue(notice.body().contains("https://streambox.test/login"), notice.body());
        assertFalse(notice.body().contains("#token="), notice.body());
        assertFalse(notice.body().contains("bea_impostora"), notice.body());
    }

    // ------------------------------------------------------------------
    // Email de una cuenta pendiente
    // ------------------------------------------------------------------

    /**
     * Registrarse otra vez con el email de una cuenta pendiente cuyo enlace
     * sigue vigente le reenvía un enlace nuevo (que invalida el anterior) y no
     * cambia nada más: ni el nombre ni la contraseña de la cuenta.
     */
    @Test
    void unaPendienteConElEnlaceVigenteRecibeOtroYNoCambia() throws Exception {
        register("carla", "carla" + DOMAIN, PASSWORD).andExpect(status().isAccepted());
        User pending = userRepository.findByEmail("carla" + DOMAIN).orElseThrow();
        String first = tokenIn(onlyEmailTo("carla" + DOMAIN));

        register("carla_bis", "carla" + DOMAIN, OTHER_PASSWORD)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(AcceptedResponse.REGISTRATION_MESSAGE));

        User after = userRepository.findByEmail("carla" + DOMAIN).orElseThrow();
        assertEquals(pending.getId(), after.getId());
        assertEquals("carla", after.getUsername());
        assertTrue(passwordEncoder.matches(PASSWORD, after.getPassword()));
        assertTrue(userRepository.findByUsername("carla_bis").isEmpty());

        List<SentEmail> emails = inbox.sentTo("carla" + DOMAIN);
        assertEquals(2, emails.size());
        String second = tokenIn(emails.get(0));
        assertNotEquals(first, second);
        // El correo nombra la cuenta que se confirmaría («carla»), no el nombre
        // de este registro: es lo que el dueño del buzón tiene que reconocer.
        assertTrue(emails.get(0).body().contains("con la cuenta «carla»"), emails.get(0).body());
        assertFalse(emails.get(0).body().contains("carla_bis"), emails.get(0).body());
        confirm(first).andExpect(status().isBadRequest());
        confirm(second).andExpect(status().isNoContent());
    }

    /**
     * Una pendiente creada por el registro cuyos enlaces caducaron se
     * <b>sustituye</b>: quien la creó nunca demostró que el email era suyo. La
     * cuenta nueva tiene el nombre y la contraseña nuevos, y el enlace viejo
     * deja de existir con ella.
     */
    @Test
    void unaPendienteConTodosLosEnlacesCaducadosSeSustituye() throws Exception {
        register("dani_viejo", "dani" + DOMAIN, PASSWORD).andExpect(status().isAccepted());
        Long oldId = userRepository.findByEmail("dani" + DOMAIN).orElseThrow().getId();
        String oldToken = tokenIn(onlyEmailTo("dani" + DOMAIN));

        CLOCK.advance(Duration.ofHours(24));
        register("dani_nuevo", "dani" + DOMAIN, OTHER_PASSWORD).andExpect(status().isAccepted());

        User replaced = userRepository.findByEmail("dani" + DOMAIN).orElseThrow();
        assertNotEquals(oldId, replaced.getId());
        assertTrue(userRepository.findById(oldId).isEmpty());
        assertEquals("dani_nuevo", replaced.getUsername());
        assertTrue(passwordEncoder.matches(OTHER_PASSWORD, replaced.getPassword()));
        assertNull(replaced.getEmailVerifiedAt());
        assertTrue(userRepository.findByUsername("dani_viejo").isEmpty());

        List<SentEmail> emails = inbox.sentTo("dani" + DOMAIN);
        assertEquals(2, emails.size());
        confirm(oldToken).andExpect(status().isBadRequest());
        confirm(tokenIn(emails.get(0))).andExpect(status().isNoContent());
    }

    /**
     * Justo antes de caducar el enlace, la pendiente aún no se sustituye: el
     * límite es la caducidad exacta (24 h), no un margen aproximado.
     */
    @Test
    void unSegundoAntesDeCaducarLaPendienteNoSeSustituye() throws Exception {
        register("eva_vieja", "eva" + DOMAIN, PASSWORD).andExpect(status().isAccepted());
        Long oldId = userRepository.findByEmail("eva" + DOMAIN).orElseThrow().getId();

        CLOCK.advance(Duration.ofHours(24).minusSeconds(1));
        register("eva_nueva", "eva" + DOMAIN, OTHER_PASSWORD).andExpect(status().isAccepted());

        User after = userRepository.findByEmail("eva" + DOMAIN).orElseThrow();
        assertEquals(oldId, after.getId());
        assertEquals("eva_vieja", after.getUsername());
    }

    // ------------------------------------------------------------------
    // Cuentas heredadas (anteriores a la V9)
    // ------------------------------------------------------------------

    /**
     * Una cuenta USER heredada (pendiente desde la V9, sin ningún token, con
     * favoritos y creada hace meses) <b>nunca</b> se borra: registrarse con su
     * email solo le envía un enlace, también pasadas 24 h desde ese enlace, y
     * su nombre sigue dando 409. Al confirmar, conserva su id y sus favoritos.
     */
    @Test
    void unaCuentaHeredadaConFavoritosNuncaSeBorra() throws Exception {
        User legacy = createUser("fede_heredado", "fede" + DOMAIN, false, Instant.parse("2025-06-01T10:00:00Z"));
        addFavorite(legacy);

        register("fede_nuevo", "fede" + DOMAIN, OTHER_PASSWORD).andExpect(status().isAccepted());
        assertEquals(1, inbox.sentTo("fede" + DOMAIN).size());

        CLOCK.advance(Duration.ofHours(25));
        register("fede_otra_vez", "fede" + DOMAIN, OTHER_PASSWORD).andExpect(status().isAccepted());
        register("fede_heredado", "intruso" + DOMAIN, OTHER_PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(USERNAME_TAKEN));

        User after = userRepository.findByEmail("fede" + DOMAIN).orElseThrow();
        assertEquals(legacy.getId(), after.getId());
        assertEquals("fede_heredado", after.getUsername());
        assertTrue(passwordEncoder.matches(PASSWORD, after.getPassword()));
        assertEquals(1, favoritesOf(legacy));
        assertTrue(userRepository.findByEmail("intruso" + DOMAIN).isEmpty());

        List<SentEmail> emails = inbox.sentTo("fede" + DOMAIN);
        assertEquals(2, emails.size());
        assertEquals(AccountEmails.VERIFICATION_SUBJECT, emails.get(0).subject());
        confirm(tokenIn(emails.get(0))).andExpect(status().isNoContent());
        assertEquals(1, favoritesOf(legacy));
        assertTrue(userRepository.findById(legacy.getId()).orElseThrow().isEmailVerified());
    }

    // ------------------------------------------------------------------
    // Nombre de usuario
    // ------------------------------------------------------------------

    /**
     * El nombre de una pendiente con su enlace vigente sigue reservado: 409
     * (aunque se cambie el email), y no se crea ni se envía nada para el email
     * nuevo.
     */
    @Test
    void elNombreDeUnaPendienteConElEnlaceVigenteDa409() throws Exception {
        register("gema", "gema" + DOMAIN, PASSWORD).andExpect(status().isAccepted());

        register("gema", "gema.otra" + DOMAIN, OTHER_PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value(USERNAME_TAKEN));

        assertTrue(userRepository.findByEmail("gema.otra" + DOMAIN).isEmpty());
        assertTrue(inbox.sentTo("gema.otra" + DOMAIN).isEmpty());
    }

    /** El nombre de una pendiente caducada se libera: la cuenta vieja se borra y se crea la nueva. */
    @Test
    void elNombreDeUnaPendienteCaducadaQuedaLibre() throws Exception {
        register("hugo", "hugo" + DOMAIN, PASSWORD).andExpect(status().isAccepted());

        CLOCK.advance(Duration.ofHours(25));
        register("hugo", "hugo.nuevo" + DOMAIN, OTHER_PASSWORD).andExpect(status().isAccepted());

        assertTrue(userRepository.findByEmail("hugo" + DOMAIN).isEmpty());
        User replaced = userRepository.findByUsername("hugo").orElseThrow();
        assertEquals("hugo.nuevo" + DOMAIN, replaced.getEmail());
        assertEquals(AccountEmails.VERIFICATION_SUBJECT, onlyEmailTo("hugo.nuevo" + DOMAIN).subject());
    }

    /** El nombre de una cuenta confirmada da 409 aunque pase el tiempo. */
    @Test
    void elNombreDeUnaCuentaConfirmadaDa409() throws Exception {
        createUser("ines", "ines" + DOMAIN, true, null);
        CLOCK.advance(Duration.ofDays(3));

        register("ines", "ines.otra" + DOMAIN, OTHER_PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(USERNAME_TAKEN));

        assertTrue(userRepository.findByEmail("ines" + DOMAIN).isPresent());
        assertTrue(userRepository.findByEmail("ines.otra" + DOMAIN).isEmpty());
    }

    // ------------------------------------------------------------------
    // Oráculo del nombre (M1)
    // ------------------------------------------------------------------

    /** Cuentas que ya pueden tener el email de un registro. */
    enum ExistingAccount { VERIFIED, PENDING, LEGACY }

    /**
     * <b>Oráculo del nombre.</b> Dos secuencias idénticas salvo por si el email
     * del primer registro ya tenía cuenta: «registro con el email y el nombre
     * U» seguido de «registro con otro email y el mismo U». Deben responder
     * exactamente igual (código y cuerpo, salvo la hora). Antes, con un email
     * nuevo el segundo daba 409 (la cuenta pendiente ocupaba U) y con uno que
     * ya tenía cuenta daba 202 (no se había creado nada): dos peticiones
     * bastaban para saber si un email estaba registrado. Para los tres tipos de
     * cuenta que puede tener el email: confirmada, pendiente con su enlace
     * vigente y heredada (anterior a la V9, sin tokens).
     */
    @ParameterizedTest
    @EnumSource(ExistingAccount.class)
    void elSegundoRegistroConElMismoNombreRespondeIgualExistaONoElEmail(ExistingAccount kind) throws Exception {
        // Nombres únicos por caso: las reservas viven en memoria durante todo el contexto.
        String suffix = "_" + kind.name().toLowerCase();
        String withEmail = "oraculo.b" + suffix + DOMAIN;
        switch (kind) {
            case VERIFIED -> createUser("cuenta_b" + suffix, withEmail, true, null);
            case PENDING -> register("cuenta_b" + suffix, withEmail, PASSWORD).andExpect(status().isAccepted());
            case LEGACY -> createUser("cuenta_b" + suffix, withEmail, false, Instant.now().minus(Duration.ofDays(400)));
        }

        MockHttpServletResponse newFirst = register("nuevo_a" + suffix, "oraculo.a" + suffix + DOMAIN, PASSWORD)
                .andReturn().getResponse();
        MockHttpServletResponse newSecond = register("nuevo_a" + suffix, "oraculo.c" + suffix + DOMAIN,
                OTHER_PASSWORD).andReturn().getResponse();
        MockHttpServletResponse existingFirst = register("nuevo_b" + suffix, withEmail, PASSWORD)
                .andReturn().getResponse();
        MockHttpServletResponse existingSecond = register("nuevo_b" + suffix, "oraculo.d" + suffix + DOMAIN,
                OTHER_PASSWORD).andReturn().getResponse();

        assertEquals(202, newFirst.getStatus());
        assertEquals(newFirst.getStatus(), existingFirst.getStatus());
        assertEquals(bodyAsMap(newFirst), bodyAsMap(existingFirst));
        assertEquals(409, newSecond.getStatus());
        assertEquals(newSecond.getStatus(), existingSecond.getStatus());
        assertEquals(withoutTimestamp(newSecond), withoutTimestamp(existingSecond));
        assertEquals(USERNAME_TAKEN, withoutTimestamp(existingSecond).get("message"));
        // Y el segundo registro no creó nada en ninguno de los dos casos.
        assertTrue(userRepository.findByEmail("oraculo.c" + suffix + DOMAIN).isEmpty());
        assertTrue(userRepository.findByEmail("oraculo.d" + suffix + DOMAIN).isEmpty());
    }

    /**
     * La reserva no estorba a la cuenta dueña del email: puede seguir
     * iniciando sesión (si está confirmada) y cambiarse el nombre al reservado
     * en «Editar perfil». Otra cuenta que lo intente recibe el mismo 409 que
     * si el nombre lo tuviera una cuenta (si no, sería el mismo oráculo con
     * una cuenta propia).
     */
    @Test
    void laReservaNoEstorbaAlDuenoDelEmailPeroSiAlResto() throws Exception {
        User owner = createUser("lola", "lola" + DOMAIN, true, null);
        User other = createUser("manu", "manu" + DOMAIN, true, null);
        register("lola_reservada", "lola" + DOMAIN, OTHER_PASSWORD).andExpect(status().isAccepted());

        mockMvc.perform(post("/api/auth/login")
                        .header("X-Requested-With", "StreamBox")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "lola" + DOMAIN,
                                "password", PASSWORD))))
                .andExpect(status().isNoContent());
        renameTo(other, "lola_reservada")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(USERNAME_TAKEN));
        renameTo(owner, "lola_reservada")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("lola_reservada"));
    }

    // ------------------------------------------------------------------
    // Caracteres de control en el email (I1)
    // ------------------------------------------------------------------

    /**
     * {@code @Email} deja pasar los caracteres de control de {@code U+0080} a
     * {@code U+009F} (los admite como «Unicode» en la parte local). Sin el
     * {@code @Pattern}, este registro respondía 202 y guardaba un email con un
     * {@code U+0085} («siguiente línea») que acabaría en la cabecera
     * {@code To} del correo y en los logs. Ahora es un 400 con el mensaje del
     * formato, en el registro y en el reenvío, sin crear ni enviar nada.
     */
    @ParameterizedTest
    @ValueSource(strings = { "\u0080", "\u0085", "\u009F" })
    void unEmailConUnCaracterDeControlC1Da400EnElRegistroYEnElReenvio(String control) throws Exception {
        String email = "c1" + control + "x" + DOMAIN;

        register("control_c1", email, PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.email")
                        .value("Debe tener un formato de correo electrónico válido"));
        mockMvc.perform(post("/api/auth/email-verification/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.email")
                        .value("Debe tener un formato de correo electrónico válido"));

        assertTrue(userRepository.findByUsername("control_c1").isEmpty());
        assertTrue(inbox.sentTo(email).isEmpty());
    }

    // ------------------------------------------------------------------
    // Enlace y cabecera Host
    // ------------------------------------------------------------------

    /**
     * El enlace del correo sale de {@code streambox.public-url}, nunca de la
     * petición: con {@code Host}, {@code X-Forwarded-Host} y
     * {@code Forwarded} apuntando a otro dominio, el correo sigue llevando
     * {@code https://streambox.test}. Si el enlace se construyera con la
     * petición, un atacante haría que StreamBox enviara a su víctima un enlace
     * a su dominio (y se quedaría con el token).
     */
    @Test
    void elEnlaceUsaLaUrlPublicaAunqueLaPeticionTraigaOtroHost() throws Exception {
        mockMvc.perform(post("/api/users")
                        .with(request -> {
                            request.setServerName("atacante.example");
                            request.setServerPort(443);
                            request.setScheme("https");
                            return request;
                        })
                        .header("Host", "atacante.example")
                        .header("X-Forwarded-Host", "atacante.example")
                        .header("X-Forwarded-Proto", "https")
                        .header("Forwarded", "host=atacante.example;proto=https")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registrationJson("juan", "juan" + DOMAIN, PASSWORD)))
                .andExpect(status().isAccepted());

        SentEmail email = onlyEmailTo("juan" + DOMAIN);
        assertFalse(email.body().contains("atacante"), email.body());
        assertTrue(LINK.matcher(email.body()).find(), email.body());
    }

    /**
     * Sin el perfil {@code e2e} la bandeja de correo no se publica: ni un
     * administrador la encuentra (404, la ruta no existe).
     */
    @Test
    void sinElPerfilE2eLaBandejaNoExiste() throws Exception {
        User admin = createUser("admin_registro", "admin" + DOMAIN, true, null);
        admin.setRole(Role.ADMIN);
        admin = userRepository.saveAndFlush(admin);
        register("kike", "kike" + DOMAIN, PASSWORD).andExpect(status().isAccepted());

        mockMvc.perform(get("/api/admin/e2e/emails").param("to", "kike" + DOMAIN)
                        .cookie(new Cookie("streambox_token", jwtService.generateToken(admin))))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private ResultActions register(String username, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registrationJson(username, email, password)));
    }

    private ResultActions renameTo(User user, String username) throws Exception {
        return mockMvc.perform(patch("/api/users/me")
                .cookie(new Cookie("streambox_token", jwtService.generateToken(user)))
                .header("X-Requested-With", "StreamBox")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", username))));
    }

    /** Cuerpo JSON sin la hora del error, lo único que puede cambiar entre dos respuestas iguales. */
    private Map<?, ?> withoutTimestamp(MockHttpServletResponse response) throws Exception {
        Map<?, ?> body = new java.util.HashMap<>(bodyAsMap(response));
        body.remove("timestamp");
        return body;
    }

    private ResultActions confirm(String token) throws Exception {
        return mockMvc.perform(post("/api/auth/email-verification")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("token", token))));
    }

    private String registrationJson(String username, String email, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("username", username, "email", email, "password", password));
    }

    /**
     * Crea una cuenta directamente en la base de datos.
     *
     * @param createdAt fecha de alta que se fuerza después del {@code INSERT}
     *                  ({@code @CreationTimestamp} la pisa al insertar), o
     *                  {@code null} para dejar la de ahora. Una cuenta heredada
     *                  se creó antes de desplegar la V9
     */
    private User createUser(String username, String email, boolean verified, Instant createdAt) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setEmailVerifiedAt(verified ? Instant.now() : null);
        User saved = userRepository.saveAndFlush(user);
        if (createdAt != null) {
            jdbc.update("UPDATE users SET created_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(createdAt), saved.getId());
        }
        return saved;
    }

    private void addFavorite(User user) {
        Movie movie = new Movie();
        movie.setTitle("Película del registro");
        movie.setDescription("Favorita de una cuenta heredada");
        movie.setDuration(100);
        movie.setReleaseYear(2001);
        movie.setImageUrl("https://example.com/registro.jpg");
        movie.setCreatedAt(Instant.now());
        movie.setGenres(new HashSet<>());
        movieId = movieRepository.saveAndFlush(movie).getId();
        jdbc.update("INSERT INTO user_favorite_movies (user_id, movie_id) VALUES (?, ?)", user.getId(), movieId);
    }

    private int favoritesOf(User user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_favorite_movies WHERE user_id = ?", Integer.class,
                user.getId());
    }

    private List<Map<String, Object>> tokensOf(Long userId) {
        return jdbc.queryForList("SELECT token_hash, created_at, expires_at, used_at FROM account_tokens "
                + "WHERE user_id = ? ORDER BY id", userId);
    }

    private SentEmail onlyEmailTo(String address) {
        List<SentEmail> emails = inbox.sentTo(address);
        assertEquals(1, emails.size(), () -> "Correos a " + address + ": " + emails);
        return emails.get(0);
    }

    private static String tokenIn(SentEmail email) {
        Matcher matcher = LINK.matcher(email.body());
        assertTrue(matcher.find(), email.body());
        return matcher.group(1);
    }

    private Map<?, ?> bodyAsMap(MockHttpServletResponse response) throws Exception {
        return objectMapper.readValue(response.getContentAsString(StandardCharsets.UTF_8), Map.class);
    }

    private static Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        throw new IllegalArgumentException("Tipo de fecha inesperado: " + value.getClass());
    }

    private static String sha256Hex(String token) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
    }
}
