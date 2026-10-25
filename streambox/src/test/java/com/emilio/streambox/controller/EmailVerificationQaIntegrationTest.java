package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

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
 * Revisión independiente de QA (2026-10-18) de la verificación del correo:
 * <b>enumeración</b> de emails con efectos posteriores al registro y
 * <b>cuentas heredadas</b> (USER anteriores a la V9, pendientes y sin tokens).
 *
 * <p>
 * El registro responde siempre el mismo 202, pero un atacante no se limita a
 * mirar esa respuesta: puede encadenar otra petición que dependa de lo que el
 * registro hizo por dentro (crear una cuenta o no). Cada test de enumeración
 * compara dos secuencias idénticas salvo por si el email del registro ya tenía
 * cuenta (confirmada, pendiente con el enlace vigente o heredada) y exige la
 * misma respuesta observable.
 * </p>
 *
 * <ul>
 *   <li><b>Login con la contraseña elegida en el registro</b>
 *       ({@link #entrarConLaContrasenaDelRegistroNoRevelaSiElEmailYaTeniaCuenta}):
 *       si el email era nuevo, la cuenta pendiente tiene la contraseña del
 *       atacante; si ya tenía cuenta, esa contraseña no es la suya. Antes el
 *       primer caso daba 403 {@code EMAIL_NOT_VERIFIED} y el segundo 401: un
 *       oráculo de dos peticiones (incidencia QA-1, corregida con la opción
 *       (a): los dos dan el mismo 401).</li>
 *   <li><b>Cambiarse el nombre al del registro</b> con una cuenta propia, en
 *       las primeras 24 h y pasadas 24 h (incidencia QA-2: pasadas 24 h la
 *       reserva caduca, pero la cuenta pendiente creada con un email nuevo
 *       sigue ocupando el nombre).</li>
 *   <li><b>Pedir otro enlace pasadas 24 h</b> (reenvío o «He olvidado mi
 *       contraseña») y después registrar o ponerse el nombre del registro
 *       (incidencia MEDIA-1: el enlace nuevo alargaba la vida del nombre de la
 *       pendiente, pero no la de la reserva).</li>
 *   <li><b>Cuentas heredadas</b> con favoritos y «me gusta»: pedir un enlace
 *       con el reenvío y dejarlo caducar no las vuelve sustituibles; confirman
 *       con el último enlace y después entran.</li>
 * </ul>
 *
 * <p>
 * Sin {@code @Transactional}: los correos salen tras el commit. Los usuarios
 * del dominio {@value #DOMAIN} se borran antes y después de cada test, y los
 * nombres llevan el sufijo del caso porque las reservas de nombre viven en
 * memoria durante todo el contexto. Reloj ajustable para caducar enlaces y
 * reservas sin esperar.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class EmailVerificationQaIntegrationTest {

    private static final String DOMAIN = "@qa-verificacion.test";
    private static final String OWNER_PASSWORD = "Paseo-Por-El-Retiro-2026";
    private static final String ATTACKER_PASSWORD = "Contrasena-Del-Atacante-2026";
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

    /** Cuenta que ya puede tener el email de un registro. */
    enum ExistingAccount { VERIFIED, PENDING, LEGACY }

    /** Cómo pide el atacante otro enlace para un email (MEDIA-1): los dos responden 202 a cualquiera. */
    enum NewLink { RESEND, PASSWORD_RESET }

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
    // Enumeración: efectos posteriores al registro
    // ------------------------------------------------------------------

    /**
     * <b>Oráculo del login (incidencia QA-1, corregida).</b> El atacante se
     * registra con el email de la víctima y una contraseña suya, y después
     * intenta entrar con esa contraseña. Con un email nuevo se creó una cuenta
     * pendiente con <i>su</i> contraseña (antes: 403 {@code EMAIL_NOT_VERIFIED}). Con
     * un email que ya tenía cuenta no se creó nada y la contraseña no es la del
     * titular: 401 {@code INVALID_CREDENTIALS}. Dos peticiones bastan para
     * saber si un email está registrado, que es justo lo que el 202 fijo del
     * registro quería impedir (y lo mismo que se corrigió en M1 con el nombre).
     *
     * <p>
     * La respuesta del login tras el registro debe ser la misma (código y
     * {@code code}) exista o no el email. Cómo arreglarlo es una decisión de
     * diseño (ver el informe de QA): por ejemplo, que el login de una cuenta
     * pendiente con la contraseña correcta responda el mismo 401 que una
     * contraseña incorrecta y que la interfaz ofrezca el reenvío en ese 401.
     * </p>
     */
    @ParameterizedTest
    @EnumSource(ExistingAccount.class)
    void entrarConLaContrasenaDelRegistroNoRevelaSiElEmailYaTeniaCuenta(ExistingAccount kind) throws Exception {
        String suffix = "_l" + kind.ordinal();
        String victimEmail = "victima" + suffix + DOMAIN;
        createExisting(kind, "titular" + suffix, victimEmail);
        String freshEmail = "libre" + suffix + DOMAIN;

        register("intruso_a" + suffix, freshEmail, ATTACKER_PASSWORD).andExpect(status().isAccepted());
        register("intruso_b" + suffix, victimEmail, ATTACKER_PASSWORD).andExpect(status().isAccepted());

        MockHttpServletResponse fresh = login(freshEmail, ATTACKER_PASSWORD).andReturn().getResponse();
        MockHttpServletResponse taken = login(victimEmail, ATTACKER_PASSWORD).andReturn().getResponse();

        assertEquals(fresh.getStatus(), taken.getStatus(),
                "El login tras el registro delata si el email ya tenía cuenta: email nuevo → "
                        + fresh.getContentAsString(StandardCharsets.UTF_8) + " / email con cuenta → "
                        + taken.getContentAsString(StandardCharsets.UTF_8));
        assertEquals(bodyAsMap(fresh).get("code"), bodyAsMap(taken).get("code"));
    }

    /**
     * Oráculo de «Editar perfil» en las primeras 24 h (petición de
     * {@code backend}): el atacante se registra con el email y un nombre U, y
     * después intenta ponerse U en su propia cuenta confirmada. Con un email
     * nuevo, U lo tiene la cuenta pendiente; con uno que ya tenía cuenta, U
     * está reservado ({@code UsernameReservations}). Debe ser el mismo 409 con
     * el mismo cuerpo en los dos casos, y para los tres tipos de cuenta.
     */
    @ParameterizedTest
    @EnumSource(ExistingAccount.class)
    void cambiarseElNombreAlDelRegistroRespondeIgualExistaONoElEmail(ExistingAccount kind) throws Exception {
        String suffix = "_p" + kind.ordinal();
        String victimEmail = "victima" + suffix + DOMAIN;
        createExisting(kind, "titular" + suffix, victimEmail);
        User attacker = createUser("atacante" + suffix, "atacante" + suffix + DOMAIN, true, null);

        register("nombre_a" + suffix, "libre" + suffix + DOMAIN, ATTACKER_PASSWORD).andExpect(status().isAccepted());
        register("nombre_b" + suffix, victimEmail, ATTACKER_PASSWORD).andExpect(status().isAccepted());

        MockHttpServletResponse fresh = renameTo(attacker, "nombre_a" + suffix).andReturn().getResponse();
        MockHttpServletResponse taken = renameTo(attacker, "nombre_b" + suffix).andReturn().getResponse();

        assertEquals(409, fresh.getStatus(), fresh.getContentAsString(StandardCharsets.UTF_8));
        assertEquals(fresh.getStatus(), taken.getStatus(), taken.getContentAsString(StandardCharsets.UTF_8));
        assertEquals(withoutVolatileFields(fresh), withoutVolatileFields(taken));
        assertEquals("atacante" + suffix, userRepository.findById(attacker.getId()).orElseThrow().getUsername());
    }

    /**
     * <b>Oráculo de «Editar perfil» pasadas 24 h (incidencia QA-2, hoy
     * falla).</b> La reserva del nombre caduca a las 24 h, pero la cuenta
     * pendiente que creó el registro con un email nuevo <b>no se borra</b> al
     * caducar su enlace (solo la borra otro registro): sigue ocupando el
     * nombre. Pasadas 25 h, «Editar perfil» con el nombre del registro da 409
     * si el email era nuevo y 200 si ya tenía cuenta. El registro, en cambio,
     * responde igual en los dos casos (libera el nombre de la pendiente
     * sustituible): la asimetría está en {@code UserService.updateProfile}.
     * Basta con esperar 24 h una sola vez para todos los emails probados.
     */
    @ParameterizedTest
    @EnumSource(ExistingAccount.class)
    void pasadas24HorasCambiarseElNombreAlDelRegistroRespondeIgual(ExistingAccount kind) throws Exception {
        String suffix = "_q" + kind.ordinal();
        String victimEmail = "victima" + suffix + DOMAIN;
        createExisting(kind, "titular" + suffix, victimEmail);
        User attacker = createUser("atacante" + suffix, "atacante" + suffix + DOMAIN, true, null);

        register("tarde_a" + suffix, "libre" + suffix + DOMAIN, ATTACKER_PASSWORD).andExpect(status().isAccepted());
        register("tarde_b" + suffix, victimEmail, ATTACKER_PASSWORD).andExpect(status().isAccepted());
        CLOCK.advance(Duration.ofHours(25));

        int fresh = renameTo(attacker, "tarde_a" + suffix).andReturn().getResponse().getStatus();
        int taken = renameTo(attacker, "tarde_b" + suffix).andReturn().getResponse().getStatus();

        assertEquals(fresh, taken, "Pasadas 24 h, «Editar perfil» delata si el email ya tenía cuenta: email nuevo → "
                + fresh + ", email con cuenta → " + taken);
    }

    /**
     * <b>Incidencia MEDIA-1 (revisión de seguridad de «He olvidado mi
     * contraseña»), en el registro.</b> El atacante registra (E, U) con un U
     * aleatorio, espera 24 h, pide otro enlace para E (reenvío o
     * restablecimiento: los dos responden 202 a cualquiera) y, antes de que
     * caduque, registra U con otro email. Antes: si E era nuevo, la pendiente
     * (E, U) tenía un enlace vigente y no era «sustituible», así que U daba
     * 409; si E ya tenía cuenta, U solo estaba reservado 24 h y daba 202. Ahora
     * el nombre de una pendiente dura lo mismo que la reserva (24 h desde el
     * alta) y los dos dan el mismo 202 con el mismo cuerpo.
     */
    @ParameterizedTest
    @MethodSource("newLinkAndExistingAccount")
    void pedirOtroEnlacePasadas24HorasNoDelataElEmailAlRegistrarseConElNombre(NewLink link, ExistingAccount kind)
            throws Exception {
        String suffix = "_r" + link.ordinal() + kind.ordinal();
        String victimEmail = "victima" + suffix + DOMAIN;
        createExisting(kind, "titular" + suffix, victimEmail);
        String freshEmail = "libre" + suffix + DOMAIN;

        register("enl_a" + suffix, freshEmail, ATTACKER_PASSWORD).andExpect(status().isAccepted());
        register("enl_b" + suffix, victimEmail, ATTACKER_PASSWORD).andExpect(status().isAccepted());
        CLOCK.advance(Duration.ofHours(25));
        requestLink(link, freshEmail).andExpect(status().isAccepted());
        requestLink(link, victimEmail).andExpect(status().isAccepted());

        MockHttpServletResponse fresh = register("enl_a" + suffix, "otro_a" + suffix + DOMAIN, ATTACKER_PASSWORD)
                .andReturn().getResponse();
        MockHttpServletResponse taken = register("enl_b" + suffix, "otro_b" + suffix + DOMAIN, ATTACKER_PASSWORD)
                .andReturn().getResponse();

        assertEquals(202, fresh.getStatus(), "Pedir otro enlace alarga la vida del nombre de la pendiente: "
                + fresh.getContentAsString(StandardCharsets.UTF_8));
        assertEquals(fresh.getStatus(), taken.getStatus(), taken.getContentAsString(StandardCharsets.UTF_8));
        assertEquals(withoutVolatileFields(fresh), withoutVolatileFields(taken));
        assertEquals(headers(fresh), headers(taken));
    }

    /**
     * MEDIA-1 en «Editar perfil»: la misma secuencia, pero el atacante se pone
     * U en su propia cuenta confirmada. Mismo 200 en los dos casos y mismo
     * cuerpo salvo el nombre pedido (que es distinto en cada caso).
     */
    @ParameterizedTest
    @MethodSource("newLinkAndExistingAccount")
    void pedirOtroEnlacePasadas24HorasNoDelataElEmailAlCambiarseElNombre(NewLink link, ExistingAccount kind)
            throws Exception {
        String suffix = "_s" + link.ordinal() + kind.ordinal();
        String victimEmail = "victima" + suffix + DOMAIN;
        createExisting(kind, "titular" + suffix, victimEmail);
        String freshEmail = "libre" + suffix + DOMAIN;
        User attacker = createUser("atacante" + suffix, "atacante" + suffix + DOMAIN, true, null);

        register("per_a" + suffix, freshEmail, ATTACKER_PASSWORD).andExpect(status().isAccepted());
        register("per_b" + suffix, victimEmail, ATTACKER_PASSWORD).andExpect(status().isAccepted());
        CLOCK.advance(Duration.ofHours(25));
        requestLink(link, freshEmail).andExpect(status().isAccepted());
        requestLink(link, victimEmail).andExpect(status().isAccepted());

        MockHttpServletResponse fresh = renameTo(attacker, "per_a" + suffix).andReturn().getResponse();
        MockHttpServletResponse taken = renameTo(attacker, "per_b" + suffix).andReturn().getResponse();

        assertEquals(200, fresh.getStatus(), "Pedir otro enlace alarga la vida del nombre de la pendiente: "
                + fresh.getContentAsString(StandardCharsets.UTF_8));
        assertEquals(fresh.getStatus(), taken.getStatus(), taken.getContentAsString(StandardCharsets.UTF_8));
        assertEquals("per_a" + suffix, bodyAsMap(fresh).get("username"));
        assertEquals("per_b" + suffix, bodyAsMap(taken).get("username"));
        assertEquals(withoutVolatileFields(fresh, "username"), withoutVolatileFields(taken, "username"));
    }

    /** Las 6 combinaciones de cómo se pide el enlace nuevo y qué cuenta tenía ya el email. */
    static Stream<Arguments> newLinkAndExistingAccount() {
        return Stream.of(NewLink.values())
                .flatMap(link -> Stream.of(ExistingAccount.values()).map(kind -> Arguments.of(link, kind)));
    }

    /**
     * El 202 del registro no lleva cookies ni cabeceras distintas según el
     * email: mismas cabeceras (nombres y valores, salvo las que cambian solas)
     * para un email nuevo y para uno con cuenta, y ninguna {@code Set-Cookie}
     * (el registro no inicia sesión).
     */
    @ParameterizedTest
    @EnumSource(ExistingAccount.class)
    void elRegistroNoDiferenciaCabecerasNiCookies(ExistingAccount kind) throws Exception {
        String suffix = "_c" + kind.ordinal();
        String victimEmail = "victima" + suffix + DOMAIN;
        createExisting(kind, "titular" + suffix, victimEmail);

        MockHttpServletResponse fresh = register("cab_a" + suffix, "libre" + suffix + DOMAIN, ATTACKER_PASSWORD)
                .andReturn().getResponse();
        MockHttpServletResponse taken = register("cab_b" + suffix, victimEmail, ATTACKER_PASSWORD)
                .andReturn().getResponse();

        assertEquals(202, fresh.getStatus());
        assertEquals(fresh.getStatus(), taken.getStatus());
        assertEquals(headers(fresh), headers(taken));
        assertTrue(fresh.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
        assertTrue(taken.getHeaders(HttpHeaders.SET_COOKIE).isEmpty());
    }

    // ------------------------------------------------------------------
    // Cuentas heredadas
    // ------------------------------------------------------------------

    /**
     * Una cuenta heredada con favoritos y «me gusta» pide un enlace con el
     * <b>reenvío</b> (no con el registro) y lo deja caducar. Pasadas 25 h, un
     * registro con su email <b>y su propio nombre</b> (el caso en que dueño
     * del email y dueño del nombre son la misma cuenta) no la sustituye: 409
     * del nombre, y la cuenta sigue intacta. Un registro con su email y otro
     * nombre solo le envía otro enlace. Con ese último enlace confirma, entra
     * con su contraseña de siempre y conserva favoritos y «me gusta».
     *
     * <p>
     * Falla si {@code isReplaceable} volviera a ser «pendiente y sin enlace
     * vigente»: el token del reenvío caducado la haría sustituible y el
     * registro la borraría con sus datos.
     * </p>
     */
    @Test
    void unaHeredadaQuePidioUnEnlaceYLoDejoCaducarNoSeSustituyeYPuedeConfirmarYEntrar() throws Exception {
        User legacy = createUser("hereda_qa", "hereda" + DOMAIN, false, Instant.parse("2024-03-01T09:00:00Z"));
        addFavoriteAndLike(legacy);

        // Pendiente: el mismo 401 que una contraseña incorrecta (incidencia QA-1).
        login("hereda" + DOMAIN, OWNER_PASSWORD).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        resend("hereda" + DOMAIN).andExpect(status().isAccepted());
        String expired = tokenIn(inbox.sentTo("hereda" + DOMAIN).get(0));

        CLOCK.advance(Duration.ofHours(25));
        register("hereda_qa", "hereda" + DOMAIN, ATTACKER_PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_EXISTS"));
        register("hereda_otro", "hereda" + DOMAIN, ATTACKER_PASSWORD).andExpect(status().isAccepted());

        User after = userRepository.findByEmail("hereda" + DOMAIN).orElseThrow();
        assertEquals(legacy.getId(), after.getId());
        assertEquals("hereda_qa", after.getUsername());
        assertTrue(passwordEncoder.matches(OWNER_PASSWORD, after.getPassword()));
        assertEquals(1, count("user_favorite_movies", legacy));
        assertEquals(1, count("user_liked_movies", legacy));

        List<SentEmail> emails = inbox.sentTo("hereda" + DOMAIN);
        assertEquals(2, emails.size(), emails::toString);
        assertEquals(AccountEmails.VERIFICATION_SUBJECT, emails.get(0).subject());
        assertTrue(emails.get(0).body().contains("«hereda_qa»"), emails.get(0).body());
        confirm(expired).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));
        MockHttpServletResponse confirmed = confirm(tokenIn(emails.get(0)))
                .andExpect(status().isNoContent()).andReturn().getResponse();
        assertTrue(confirmed.getHeaders(HttpHeaders.SET_COOKIE).isEmpty(), "Confirmar no inicia sesión");

        login("hereda" + DOMAIN, OWNER_PASSWORD).andExpect(status().isNoContent());
        login("hereda" + DOMAIN, ATTACKER_PASSWORD).andExpect(status().isUnauthorized());
        assertEquals(1, count("user_favorite_movies", legacy));
        assertEquals(1, count("user_liked_movies", legacy));
    }

    /**
     * Una heredada que inició sesión antes de la V9 (refresh token vivo) no
     * renueva mientras está pendiente, y al confirmar el correo esa sesión
     * sigue fuera: tras confirmar, el refresh antiguo da 401
     * {@code SESSION_EXPIRED} y solo un login nuevo entra.
     */
    @Test
    void lasSesionesDeUnaHeredadaAnterioresALaConfirmacionNoVuelvenAValer() throws Exception {
        User legacy = createUser("sesion_qa", "sesion" + DOMAIN, true, Instant.parse("2024-03-01T09:00:00Z"));
        Cookie refresh = login("sesion" + DOMAIN, OWNER_PASSWORD).andExpect(status().isNoContent())
                .andReturn().getResponse().getCookie("streambox_refresh");
        jdbc.update("UPDATE users SET email_verified_at = NULL WHERE id = ?", legacy.getId());

        resend("sesion" + DOMAIN).andExpect(status().isAccepted());
        confirm(tokenIn(inbox.sentTo("sesion" + DOMAIN).get(0))).andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh").header("X-Requested-With", "StreamBox").cookie(refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        login("sesion" + DOMAIN, OWNER_PASSWORD).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Crea la cuenta que ya tiene el email, según el caso. */
    private void createExisting(ExistingAccount kind, String username, String email) throws Exception {
        switch (kind) {
            case VERIFIED -> createUser(username, email, true, null);
            case PENDING -> register(username, email, OWNER_PASSWORD).andExpect(status().isAccepted());
            case LEGACY -> createUser(username, email, false, CLOCK.instant().minus(Duration.ofDays(400)));
        }
    }

    private ResultActions register(String username, String email, String password) throws Exception {
        return mockMvc.perform(post("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("username", username, "email", email, "password", password))));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private ResultActions resend(String email) throws Exception {
        return mockMvc.perform(post("/api/auth/email-verification/resend")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email))));
    }

    private ResultActions requestLink(NewLink link, String email) throws Exception {
        if (link == NewLink.RESEND) {
            return resend(email);
        }
        return mockMvc.perform(post("/api/auth/password-reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email))));
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

    /**
     * Crea una cuenta directamente en la base de datos (la contraseña es
     * {@link #OWNER_PASSWORD}).
     *
     * @param createdAt fecha de alta forzada tras el {@code INSERT}, o
     *                  {@code null} para la de ahora (una heredada es anterior a la V9)
     */
    private User createUser(String username, String email, boolean verified, Instant createdAt) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(OWNER_PASSWORD));
        user.setRole(Role.USER);
        user.setEmailVerifiedAt(verified ? Instant.now() : null);
        User saved = userRepository.saveAndFlush(user);
        if (createdAt != null) {
            jdbc.update("UPDATE users SET created_at = ? WHERE id = ?", Timestamp.from(createdAt), saved.getId());
        }
        return saved;
    }

    private void addFavoriteAndLike(User user) {
        Movie movie = new Movie();
        movie.setTitle("Película de la cuenta heredada (QA)");
        movie.setDescription("Favorita y con «me gusta» de una cuenta anterior a la V9");
        movie.setDuration(95);
        movie.setReleaseYear(1999);
        movie.setImageUrl("https://example.com/heredada.jpg");
        movie.setCreatedAt(Instant.now());
        movie.setGenres(new HashSet<>());
        movieId = movieRepository.saveAndFlush(movie).getId();
        jdbc.update("INSERT INTO user_favorite_movies (user_id, movie_id) VALUES (?, ?)", user.getId(), movieId);
        jdbc.update("INSERT INTO user_liked_movies (user_id, movie_id, created_at) VALUES (?, ?, ?)",
                user.getId(), movieId, Timestamp.from(Instant.now()));
    }

    private int count(String table, User user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class,
                user.getId());
    }

    private static String tokenIn(SentEmail email) {
        Matcher matcher = LINK.matcher(email.body());
        assertTrue(matcher.find(), email.body());
        return matcher.group(1);
    }

    private Map<?, ?> bodyAsMap(MockHttpServletResponse response) throws Exception {
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? Map.of() : objectMapper.readValue(body, Map.class);
    }

    /**
     * Cuerpo sin la hora, lo único que cambia entre dos respuestas iguales, ni
     * los campos indicados (que el test comprueba aparte).
     */
    private Map<?, ?> withoutVolatileFields(MockHttpServletResponse response, String... alsoIgnored)
            throws Exception {
        Map<?, ?> body = new HashMap<>(bodyAsMap(response));
        body.remove("timestamp");
        for (String field : alsoIgnored) {
            body.remove(field);
        }
        return body;
    }

    /** Cabeceras de la respuesta (nombre → valores), sin las que cambian solas entre peticiones. */
    private static Map<String, List<String>> headers(MockHttpServletResponse response) {
        Map<String, List<String>> headers = new HashMap<>();
        for (String name : new TreeSet<>(response.getHeaderNames())) {
            if (!name.equalsIgnoreCase(HttpHeaders.DATE)) {
                headers.put(name.toLowerCase(java.util.Locale.ROOT), response.getHeaders(name));
            }
        }
        return headers;
    }
}
