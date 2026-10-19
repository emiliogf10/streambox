package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.emilio.streambox.dto.AcceptedResponse;
import com.emilio.streambox.dto.CreateUserRequest;
import com.emilio.streambox.email.AccountEmails;
import com.emilio.streambox.email.EmailDispatcher;
import com.emilio.streambox.email.EmailMessage;
import com.emilio.streambox.email.EmailProperties;
import com.emilio.streambox.email.PublicUrlProperties;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.InvalidParameterException;
import com.emilio.streambox.exception.UserAlreadyExistsException;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.ratelimit.RateLimitProperties;

/**
 * Tests unitarios de {@link RegistrationService} con los repositorios
 * simulados: lo que una petición HTTP no puede provocar a voluntad (la carrera
 * con otro registro simultáneo) y el coste constante (BCrypt siempre). Los
 * caminos completos, contra la base de datos, están en
 * {@code RegistrationIntegrationTest}.
 */
class RegistrationServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private EmailVerificationService emailVerificationService;
    private EmailDispatcher emailDispatcher;
    private PlatformTransactionManager transactionManager;
    private UsernameReservations reservations;
    private RegistrationService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        emailVerificationService = mock(EmailVerificationService.class);
        emailDispatcher = mock(EmailDispatcher.class);
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        RateLimitProperties rateLimit = mock(RateLimitProperties.class);
        when(rateLimit.maxKeys()).thenReturn(1000);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        // Por defecto, el bloqueo devuelve la misma cuenta (nadie la cambió entre medias).
        when(emailVerificationService.lockForUpdate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));

        EmailProperties emailProperties = new EmailProperties("StreamBox <no-reply@streambox.local>",
                Duration.ofHours(24), false,
                new EmailProperties.Resend(3, Duration.ofHours(1), Duration.ofSeconds(60)));
        Clock clock = Clock.fixed(Instant.parse("2026-05-01T10:00:00Z"), ZoneOffset.UTC);
        reservations = new UsernameReservations(emailProperties, rateLimit, clock);
        service = new RegistrationService(userRepository, passwordEncoder, emailVerificationService,
                emailDispatcher, emailProperties, new PublicUrlProperties("https://streambox.test"), rateLimit,
                clock, transactionManager, reservations);
    }

    /**
     * Dos registros simultáneos con el mismo email nuevo: el segundo choca con
     * el {@code UNIQUE}. Antes era un 409 «El correo electrónico ya está en
     * uso», que revelaba el email; ahora es el mismo 202 que cualquier registro.
     */
    @Test
    void unaCarreraPorElEmailRespondeLoMismoQueCualquierRegistro() {
        when(userRepository.saveAndFlush(any())).thenThrow(uniqueViolation("uk_users_email"));

        assertSame(AcceptedResponse.REGISTRATION, service.register(registration()));
    }

    /** Por el nombre, el 409 de siempre; en H2 el nombre llega con esquema y sufijo. */
    @Test
    void unaCarreraPorElNombreDaEl409DelNombre() {
        when(userRepository.saveAndFlush(any()))
                .thenThrow(uniqueViolation("PUBLIC.UK_USERS_USERNAME_INDEX_4 ON PUBLIC.USERS(USERNAME)"));

        UserAlreadyExistsException error = assertThrows(UserAlreadyExistsException.class,
                () -> service.register(registration()));

        assertEquals("El nombre de usuario ya está en uso", error.getMessage());
    }

    /**
     * Si el motor no dice qué restricción falló no se adivina: un 409 de
     * nombre podría ser falso y un 202 dejaría esperando un correo que no llega.
     */
    @Test
    void unaCarreraSinNombreDeRestriccionSeRelanza() {
        DataIntegrityViolationException unnamed = violation("23505");
        when(userRepository.saveAndFlush(any())).thenThrow(unnamed);

        assertSame(unnamed, assertThrows(DataIntegrityViolationException.class,
                () -> service.register(registration())));
    }

    /** Lo que no es unicidad no se disfraza: se relanza (500 y log). */
    @Test
    void otraViolacionSeRelanzaTalCual() {
        DataIntegrityViolationException check = violation("23514");
        when(userRepository.saveAndFlush(any())).thenThrow(check);

        assertSame(check, assertThrows(DataIntegrityViolationException.class,
                () -> service.register(registration())));
    }

    /**
     * Mismo coste: con el email de una cuenta ya confirmada se cifra la
     * contraseña igual que con uno nuevo, aunque el hash no se use, y no se
     * guarda nada; solo se avisa al buzón.
     */
    @Test
    void conUnEmailYaRegistradoTambienSeCifraLaContrasenaYSoloSeAvisa() {
        User existing = user(7L, "ana.nueva@test.com", "otra_ana");
        existing.setEmailVerifiedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(userRepository.findByEmail("ana.nueva@test.com")).thenReturn(Optional.of(existing));

        assertSame(AcceptedResponse.REGISTRATION, service.register(registration()));

        verify(passwordEncoder).encode("un paseo por el retiro en otoño");
        verify(userRepository, never()).saveAndFlush(any());
        verify(emailVerificationService, never()).issueForNewAccount(any());
        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailDispatcher).sendAfterCommit(sent.capture());
        assertEquals(AccountEmails.ALREADY_REGISTERED_SUBJECT, sent.getValue().subject());
        assertEquals("ana.nueva@test.com", sent.getValue().to());
    }

    /** Un email nuevo crea una cuenta USER pendiente con el hash y emite su token. */
    @Test
    void unEmailNuevoCreaUnaCuentaUserPendienteYEmiteSuToken() {
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(9L);
            return saved;
        });

        service.register(registration());

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertEquals(Role.USER, saved.getValue().getRole());
        assertEquals("hash", saved.getValue().getPassword());
        assertEquals(null, saved.getValue().getEmailVerifiedAt());
        verify(emailVerificationService).issueForNewAccount(saved.getValue());
    }

    /**
     * Pasar a minúsculas puede alargar el email ({@code İ}, U+0130, son dos
     * caracteres): se rechaza con el mismo 400 que {@code @Size} sin consultar
     * ni guardar nada. Depende solo del texto enviado: no revela nada.
     */
    @Test
    void unEmailQueCreceAlPasarloAMinusculasSeRechazaSinTocarLaBase() {
        String email = "İ".repeat(60) + "@" + "d".repeat(35) + ".com";
        assertEquals(100, email.length());
        CreateUserRequest request = registration();
        request.setEmail(email);

        InvalidParameterException error = assertThrows(InvalidParameterException.class,
                () -> service.register(request));

        assertEquals("email", error.getParameter());
        assertEquals("El correo electrónico no puede tener más de 100 caracteres", error.getMessage());
        verify(userRepository, never()).findByEmail(anyString());
        verify(userRepository, never()).saveAndFlush(any());
    }

    /**
     * Pendiente con su enlace vigente (o heredada): no se sustituye; se le
     * reenvía el enlace con el límite silencioso y no se borra ni se guarda
     * nada.
     */
    @Test
    void unaPendienteQueNoSePuedeSustituirSoloRecibeOtroEnlace() {
        User pending = user(7L, "ana.nueva@test.com", "otra_ana");
        when(userRepository.findByEmail("ana.nueva@test.com")).thenReturn(Optional.of(pending));

        assertSame(AcceptedResponse.REGISTRATION, service.register(registration()));

        verify(emailVerificationService).lockForUpdate(pending);
        verify(emailVerificationService).issueIfAllowed(pending);
        verify(userRepository, never()).deleteIfEmailNotVerified(anyLong());
        verify(userRepository, never()).saveAndFlush(any());
        verify(emailDispatcher, never()).sendAfterCommit(any());
    }

    /** Pendiente sustituible: se borra (solo si sigue pendiente) y se crea la cuenta nueva. */
    @Test
    void unaPendienteSustituibleSeBorraYSeCreaLaNueva() {
        User stale = user(7L, "ana.nueva@test.com", "otra_ana");
        when(userRepository.findByEmail("ana.nueva@test.com")).thenReturn(Optional.of(stale));
        when(emailVerificationService.isReplaceable(stale)).thenReturn(true);
        when(userRepository.deleteIfEmailNotVerified(7L)).thenReturn(1);
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(8L);
            return saved;
        });

        service.register(registration());

        InOrder order = inOrder(userRepository, emailVerificationService);
        order.verify(userRepository).deleteIfEmailNotVerified(7L);
        order.verify(userRepository).saveAndFlush(any());
        order.verify(emailVerificationService).issueForNewAccount(any());
        verify(emailVerificationService, never()).issueIfAllowed(any());
    }

    /**
     * Si al borrarla resulta que ya no estaba pendiente (se verificó entre
     * medias), es una cuenta confirmada: solo el aviso, sin crear nada.
     */
    @Test
    void siLaSustituibleSeVerificoEntreMediasSoloSeAvisa() {
        User stale = user(7L, "ana.nueva@test.com", "otra_ana");
        when(userRepository.findByEmail("ana.nueva@test.com")).thenReturn(Optional.of(stale));
        when(emailVerificationService.isReplaceable(stale)).thenReturn(true);
        when(userRepository.deleteIfEmailNotVerified(7L)).thenReturn(0);

        assertSame(AcceptedResponse.REGISTRATION, service.register(registration()));

        verify(userRepository, never()).saveAndFlush(any());
        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailDispatcher).sendAfterCommit(sent.capture());
        assertEquals(AccountEmails.ALREADY_REGISTERED_SUBJECT, sent.getValue().subject());
    }

    /**
     * El nombre de una cuenta que no se puede sustituir (confirmada, pendiente
     * vigente o heredada) da 409, y no se borra ni se guarda nada. Una cuenta
     * heredada nunca llega a {@code deleteIfEmailNotVerified}.
     */
    @Test
    void elNombreDeUnaCuentaQueNoSePuedeSustituirDa409SinBorrarla() {
        User legacy = user(3L, "heredada@test.com", "ana_nueva");
        when(userRepository.findByUsername("ana_nueva")).thenReturn(Optional.of(legacy));

        UserAlreadyExistsException error = assertThrows(UserAlreadyExistsException.class,
                () -> service.register(registration()));

        assertEquals("El nombre de usuario ya está en uso", error.getMessage());
        verify(userRepository, never()).deleteIfEmailNotVerified(anyLong());
        verify(userRepository, never()).saveAndFlush(any());
        verify(emailDispatcher, never()).sendAfterCommit(any());
    }

    /** El nombre de una pendiente sustituible se libera: se borra esa cuenta y se crea la nueva. */
    @Test
    void elNombreDeUnaPendienteSustituibleSeLibera() {
        User stale = user(3L, "otra@test.com", "ana_nueva");
        when(userRepository.findByUsername("ana_nueva")).thenReturn(Optional.of(stale));
        // La regla común con «Editar perfil» (isReplaceable + borrado condicional).
        when(emailVerificationService.deleteIfReplaceable(stale)).thenReturn(true);
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.register(registration());

        InOrder order = inOrder(emailVerificationService, userRepository);
        order.verify(emailVerificationService).deleteIfReplaceable(stale);
        order.verify(userRepository).saveAndFlush(any());
    }

    /**
     * Misma cuenta dueña del email y del nombre, sustituible: se borra una sola
     * vez y se crea la nueva (no se intenta borrar dos veces ni se trata como
     * existente).
     */
    @Test
    void siElEmailYElNombreSonDeLaMismaPendienteSustituibleSeBorraUnaVez() {
        User stale = user(7L, "ana.nueva@test.com", "ana_nueva");
        when(userRepository.findByEmail("ana.nueva@test.com")).thenReturn(Optional.of(stale));
        when(userRepository.findByUsername("ana_nueva")).thenReturn(Optional.of(stale));
        when(emailVerificationService.deleteIfReplaceable(stale)).thenReturn(true);
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.register(registration());

        // Se borra como dueña del nombre; como dueña del email ya no se mira.
        verify(emailVerificationService, times(1)).deleteIfReplaceable(stale);
        verify(userRepository, never()).deleteIfEmailNotVerified(anyLong());
        verify(emailVerificationService, times(1)).lockForUpdate(stale);
        verify(userRepository).saveAndFlush(any());
    }

    /**
     * Las dos filas (dueño del email y dueño del nombre) se bloquean de menor
     * a mayor id: dos registros cruzados no pueden bloquearse mutuamente.
     */
    @Test
    void lasFilasSeBloqueanEnOrdenDeId() {
        User owner = user(9L, "ana.nueva@test.com", "otra_ana");
        owner.setEmailVerifiedAt(Instant.parse("2026-01-01T00:00:00Z"));
        User holder = user(3L, "otra@test.com", "ana_nueva");
        when(userRepository.findByEmail("ana.nueva@test.com")).thenReturn(Optional.of(owner));
        when(userRepository.findByUsername("ana_nueva")).thenReturn(Optional.of(holder));

        assertThrows(UserAlreadyExistsException.class, () -> service.register(registration()));

        InOrder order = inOrder(emailVerificationService);
        order.verify(emailVerificationService).lockForUpdate(holder);
        order.verify(emailVerificationService).lockForUpdate(owner);
    }

    // ------------------------------------------------------------------
    // M1: el nombre queda reservado cuando el email ya tenía cuenta
    // ------------------------------------------------------------------

    /**
     * Oráculo del nombre: tras un registro con un email que ya tiene cuenta
     * (confirmada, pendiente vigente o heredada), el nombre enviado queda tan
     * ocupado como si se hubiera creado la cuenta, para cualquier email
     * (también el mismo, como cuando la cuenta se crea de verdad). Sin la
     * reserva, el segundo registro respondía 202 y creaba la cuenta.
     */
    @Test
    void conUnEmailQueYaTieneCuentaElNombreQuedaReservado() {
        User existing = user(7L, "ana.nueva@test.com", "otra_ana");
        existing.setEmailVerifiedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(userRepository.findByEmail("ana.nueva@test.com")).thenReturn(Optional.of(existing));

        assertSame(AcceptedResponse.REGISTRATION, service.register(registration()));

        CreateUserRequest otherEmail = registration();
        otherEmail.setEmail("otra.persona@test.com");
        UserAlreadyExistsException error = assertThrows(UserAlreadyExistsException.class,
                () -> service.register(otherEmail));
        assertEquals("El nombre de usuario ya está en uso", error.getMessage());
        assertThrows(UserAlreadyExistsException.class, () -> service.register(registration()));
        verify(userRepository, never()).saveAndFlush(any());
    }

    /** Una pendiente que no se puede sustituir también reserva el nombre enviado. */
    @Test
    void conUnaPendienteVigenteElNombreTambienQuedaReservado() {
        User pending = user(7L, "ana.nueva@test.com", "otra_ana");
        when(userRepository.findByEmail("ana.nueva@test.com")).thenReturn(Optional.of(pending));

        service.register(registration());

        assertTrue(reservations.isReserved("ana_nueva"));
    }

    /**
     * Quien pierde la carrera por el email (la otra petición creó la cuenta)
     * tampoco crea nada: su nombre queda reservado igual.
     */
    @Test
    void quienPierdeLaCarreraPorElEmailTambienReservaElNombre() {
        when(userRepository.saveAndFlush(any())).thenThrow(uniqueViolation("uk_users_email"));

        service.register(registration());

        assertTrue(reservations.isReserved("ana_nueva"));
    }

    /** Un email nuevo crea la cuenta: el nombre lo ocupa ella, sin reserva aparte. */
    @Test
    void unEmailNuevoNoReservaNada() {
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.register(registration());

        assertFalse(reservations.isReserved("ana_nueva"));
    }

    // ------------------------------------------------------------------
    // B2: una cuenta que desaparece mientras se espera su bloqueo
    // ------------------------------------------------------------------

    /**
     * La cuenta pendiente del email desaparece mientras se espera su bloqueo
     * (otro registro la sustituyó): se deshace la transacción y se repite el
     * registro una vez con el estado nuevo, que aquí ya no tiene esa cuenta.
     * Antes, el {@code refresh} con bloqueo lanzaba
     * {@code EntityNotFoundException} y el registro respondía 500.
     */
    @Test
    void siLaCuentaDesapareceAlBloquearlaSeRepiteElRegistro() {
        User vanished = user(7L, "ana.nueva@test.com", "otra_ana");
        when(userRepository.findByEmail("ana.nueva@test.com"))
                .thenReturn(Optional.of(vanished))
                .thenReturn(Optional.empty());
        when(emailVerificationService.lockForUpdate(vanished)).thenReturn(Optional.empty());
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertSame(AcceptedResponse.REGISTRATION, service.register(registration()));

        verify(transactionManager, times(1)).rollback(any());
        verify(transactionManager, times(1)).commit(any());
        verify(userRepository, times(1)).saveAndFlush(any());
        verify(emailVerificationService).issueForNewAccount(any());
    }

    /**
     * Si el dueño del nombre se cambió el nombre mientras se esperaba su
     * bloqueo, tampoco se decide con la fila de antes (sería un 409 falso):
     * se repite el registro.
     */
    @Test
    void siElDuenoDelNombreLoCambiaAlBloquearloSeRepiteElRegistro() {
        User holder = user(3L, "otra@test.com", "ana_nueva");
        User renamed = user(3L, "otra@test.com", "nombre_nuevo");
        when(userRepository.findByUsername("ana_nueva"))
                .thenReturn(Optional.of(holder))
                .thenReturn(Optional.empty());
        when(emailVerificationService.lockForUpdate(holder)).thenReturn(Optional.of(renamed));
        when(userRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertSame(AcceptedResponse.REGISTRATION, service.register(registration()));

        verify(transactionManager, times(1)).rollback(any());
        verify(userRepository, times(1)).saveAndFlush(any());
    }

    /** Dos veces seguidas: no se reintenta sin fin; es un 500 legítimo. */
    @Test
    void siLaCuentaDesapareceDosVecesSeguidasNoSeReintentaMas() {
        User vanished = user(7L, "ana.nueva@test.com", "otra_ana");
        when(userRepository.findByEmail("ana.nueva@test.com")).thenReturn(Optional.of(vanished));
        when(emailVerificationService.lockForUpdate(vanished)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.register(registration()));

        verify(transactionManager, times(RegistrationService.MAX_ATTEMPTS)).rollback(any());
        verify(userRepository, never()).saveAndFlush(any());
    }

    /**
     * Límite silencioso del aviso «Ya tienes una cuenta» (en memoria, con el
     * reloj de la aplicación): dos registros seguidos con el mismo email
     * confirmado responden igual, pero solo el primero manda el aviso.
     */
    @Test
    void elAvisoDeCuentaExistenteNoSeRepiteDentroDelIntervalo() {
        User existing = user(7L, "ana.nueva@test.com", "otra_ana");
        existing.setEmailVerifiedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(userRepository.findByEmail("ana.nueva@test.com")).thenReturn(Optional.of(existing));

        assertSame(AcceptedResponse.REGISTRATION, service.register(registration()));
        // Con otro nombre: el primero quedó reservado (repetirlo daría 409).
        CreateUserRequest again = registration();
        again.setUsername("ana_otra_vez");
        assertSame(AcceptedResponse.REGISTRATION, service.register(again));

        verify(emailDispatcher, times(1)).sendAfterCommit(any());
    }

    private static User user(Long id, String email, String username) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setUsername(username);
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return user;
    }

    private static CreateUserRequest registration() {
        CreateUserRequest request = new CreateUserRequest();
        request.setUsername("ana_nueva");
        request.setEmail("ana.nueva@test.com");
        request.setPassword("un paseo por el retiro en otoño");
        return request;
    }

    /** Violación de unicidad con el nombre de la restricción, como la entrega Hibernate. */
    private static DataIntegrityViolationException uniqueViolation(String constraintName) {
        SQLException driver = new SQLException("violación de integridad", "23505");
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", driver, constraintName));
    }

    /**
     * Forma real de la excepción: Spring envuelve a Hibernate y este al
     * {@link SQLException} del driver, que es quien lleva el SQLSTATE.
     */
    private static DataIntegrityViolationException violation(String sqlState) {
        SQLException driver = new SQLException("violación de integridad", sqlState);
        return new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("hibernate", driver));
    }
}
