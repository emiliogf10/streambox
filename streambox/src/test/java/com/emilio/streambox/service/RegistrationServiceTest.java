package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.invocation.InvocationOnMock;
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
 * {@code RegistrationIntegrationTest}; las carreras de verdad, contra
 * PostgreSQL, en {@code PostgresUsernameRaceIntegrationTest}.
 *
 * <p>
 * La «base de datos» de las cuentas es mínima: {@code insertPendingUserIfAbsent}
 * guarda la cuenta creada en {@link #created} y {@code findByEmail} la
 * devuelve después (los tests que necesitan otra cosa lo sobrescriben). Los
 * puntos de guardado ({@link TransactionSavepoints}) son un doble: lo que se
 * comprueba es <b>cuándo</b> se usan, no la base de datos.
 * </p>
 */
class RegistrationServiceTest {

    /** Instante del reloj fijo de los tests: el «ahora» de la reserva y de la cuenta nueva. */
    private static final Instant NOW = Instant.parse("2026-05-01T10:00:00Z");

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private EmailVerificationService emailVerificationService;
    private EmailDispatcher emailDispatcher;
    private PlatformTransactionManager transactionManager;
    private UsernameReservations reservations;
    private InMemoryUsernameReservationRepository reservationRepository;
    private TransactionSavepoints savepoints;
    private Savepoint savepoint;
    private RegistrationService service;

    /** Cuenta creada por {@code insertPendingUserIfAbsent} (la «base de datos» del test). */
    private final AtomicReference<User> created = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        emailVerificationService = mock(EmailVerificationService.class);
        emailDispatcher = mock(EmailDispatcher.class);
        transactionManager = mock(PlatformTransactionManager.class);
        savepoints = mock(TransactionSavepoints.class);
        savepoint = mock(Savepoint.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(savepoints.create()).thenReturn(savepoint);
        // Como la base de datos: el INSERT crea la cuenta y findByEmail la encuentra después.
        when(userRepository.insertPendingUserIfAbsent(anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> {
                    created.set(user(9L, invocation.getArgument(1), invocation.getArgument(0)));
                    return 1;
                });
        when(userRepository.findByEmail(anyString())).thenAnswer(this::createdWithEmail);
        when(userRepository.findByUsername(anyString())).thenReturn(Optional.empty());
        // Por defecto, el bloqueo devuelve la misma cuenta (nadie la cambió entre medias).
        when(emailVerificationService.lockForUpdate(any()))
                .thenAnswer(invocation -> Optional.of(invocation.getArgument(0)));

        // Espía sobre el doble en memoria (para el orden de las llamadas) y, como
        // en la base de datos, una transacción deshecha se lleva sus reservas.
        reservationRepository = spy(new InMemoryUsernameReservationRepository());
        doAnswer(invocation -> {
            reservationRepository.commit();
            return null;
        }).when(transactionManager).commit(any());
        doAnswer(invocation -> {
            reservationRepository.rollback();
            return null;
        }).when(transactionManager).rollback(any());
        service = newService(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // ------------------------------------------------------------------
    // Caminos básicos
    // ------------------------------------------------------------------

    /**
     * Mismo coste: con el email de una cuenta ya confirmada se cifra la
     * contraseña igual que con uno nuevo, aunque el hash no se use, y no se
     * guarda nada; solo se avisa al buzón.
     */
    @Test
    void conUnEmailYaRegistradoTambienSeCifraLaContrasenaYSoloSeAvisa() {
        User existing = verified(user(7L, "ana.nueva@test.com", "otra_ana"));
        when(userRepository.findByEmail("ana.nueva@test.com")).thenReturn(Optional.of(existing));

        assertSame(AcceptedResponse.REGISTRATION, service.register(registration()));

        verify(passwordEncoder).encode("un paseo por el retiro en otoño");
        verify(userRepository, never()).insertPendingUserIfAbsent(anyString(), anyString(), anyString(), any());
        verify(emailVerificationService, never()).issueForNewAccount(any(), any());
        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailDispatcher).sendAfterCommit(sent.capture());
        assertEquals(AccountEmails.ALREADY_REGISTERED_SUBJECT, sent.getValue().subject());
        assertEquals("ana.nueva@test.com", sent.getValue().to());
    }

    /**
     * Un email nuevo crea una cuenta pendiente (el rol {@code USER} va fijo en
     * la sentencia) con el hash, y emite el token de la cuenta que lee después
     * por su email.
     */
    @Test
    void unEmailNuevoCreaUnaCuentaUserPendienteYEmiteSuToken() {
        service.register(registration());

        verify(userRepository).insertPendingUserIfAbsent("ana_nueva", "ana.nueva@test.com", "hash", NOW);
        verify(emailVerificationService).issueForNewAccount(created.get(), NOW);
        assertEquals(9L, created.get().getId());
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
        verify(userRepository, never()).insertPendingUserIfAbsent(anyString(), anyString(), anyString(), any());
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
        verify(userRepository, never()).insertPendingUserIfAbsent(anyString(), anyString(), anyString(), any());
        verify(emailDispatcher, never()).sendAfterCommit(any());
    }

    /** Pendiente sustituible: se borra (solo si sigue pendiente) y se crea la cuenta nueva. */
    @Test
    void unaPendienteSustituibleSeBorraYSeCreaLaNueva() {
        User stale = user(7L, "ana.nueva@test.com", "otra_ana");
        when(userRepository.findByEmail("ana.nueva@test.com"))
                .thenReturn(Optional.of(stale))
                .thenAnswer(this::createdWithEmail);
        when(emailVerificationService.isReplaceable(stale)).thenReturn(true);
        when(userRepository.deleteIfEmailNotVerified(7L)).thenReturn(1);

        service.register(registration());

        InOrder order = inOrder(userRepository, emailVerificationService);
        order.verify(userRepository).deleteIfEmailNotVerified(7L);
        order.verify(userRepository).insertPendingUserIfAbsent("ana_nueva", "ana.nueva@test.com", "hash", NOW);
        order.verify(emailVerificationService).issueForNewAccount(created.get(), NOW);
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

        verify(userRepository, never()).insertPendingUserIfAbsent(anyString(), anyString(), anyString(), any());
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
        verify(userRepository, never()).insertPendingUserIfAbsent(anyString(), anyString(), anyString(), any());
        verify(emailDispatcher, never()).sendAfterCommit(any());
    }

    /**
     * El nombre de una pendiente que ya no lo conserva se libera: se borra esa
     * cuenta y se crea la nueva. Se usa la regla por nombre (la común con
     * «Editar perfil»), nunca la regla por email.
     */
    @Test
    void elNombreDeUnaPendienteQueYaNoLoConservaSeLibera() {
        User stale = user(3L, "otra@test.com", "ana_nueva");
        when(userRepository.findByUsername("ana_nueva")).thenReturn(Optional.of(stale));
        when(emailVerificationService.deleteIfUsernameReleased(stale)).thenReturn(true);

        service.register(registration());

        InOrder order = inOrder(emailVerificationService, userRepository);
        order.verify(emailVerificationService).deleteIfUsernameReleased(stale);
        order.verify(userRepository).insertPendingUserIfAbsent(anyString(), anyString(), anyString(), any());
        verify(emailVerificationService, never()).isReplaceable(stale);
    }

    /**
     * Misma cuenta dueña del email y del nombre, que ya no conserva el nombre y
     * es sustituible por email: se borra una sola vez (por la regla del email)
     * y se crea la nueva.
     */
    @Test
    void siElEmailYElNombreSonDeLaMismaPendienteSustituibleSeBorraUnaVez() {
        User stale = user(7L, "ana.nueva@test.com", "ana_nueva");
        when(userRepository.findByEmail("ana.nueva@test.com"))
                .thenReturn(Optional.of(stale))
                .thenAnswer(this::createdWithEmail);
        when(userRepository.findByUsername("ana_nueva")).thenReturn(Optional.of(stale));
        when(emailVerificationService.releasesUsername(stale)).thenReturn(true);
        when(emailVerificationService.isReplaceable(stale)).thenReturn(true);
        when(userRepository.deleteIfEmailNotVerified(7L)).thenReturn(1);

        service.register(registration());

        verify(emailVerificationService, never()).deleteIfUsernameReleased(any());
        verify(userRepository, times(1)).deleteIfEmailNotVerified(7L);
        verify(emailVerificationService, times(1)).lockForUpdate(stale);
        verify(userRepository).insertPendingUserIfAbsent(anyString(), anyString(), anyString(), any());
    }

    /**
     * Misma cuenta dueña del email y del nombre, creada hace más de 24 h pero
     * con un enlace vigente pedido después (p. ej. «He olvidado mi
     * contraseña»). Por el nombre no hay 409 (con cualquier otro email el
     * nombre ya estaría libre: MEDIA-1), pero tampoco se borra: decide la
     * regla por email, que la conserva y solo le reenvía el enlace. Si el
     * nombre la borrara, repetir el registro con su email y su nombre dejaría
     * sin valor el enlace del titular.
     */
    @Test
    void siElEmailYElNombreSonDeLaMismaPendienteConUnEnlaceVigenteSeConservaYSoloRecibeOtroEnlace() {
        User pending = user(7L, "ana.nueva@test.com", "ana_nueva");
        when(userRepository.findByEmail("ana.nueva@test.com")).thenReturn(Optional.of(pending));
        when(userRepository.findByUsername("ana_nueva")).thenReturn(Optional.of(pending));
        when(emailVerificationService.releasesUsername(pending)).thenReturn(true);
        when(emailVerificationService.isReplaceable(pending)).thenReturn(false);

        assertSame(AcceptedResponse.REGISTRATION, service.register(registration()));

        verify(emailVerificationService).issueIfAllowed(pending);
        verify(emailVerificationService, never()).deleteIfUsernameReleased(any());
        verify(userRepository, never()).deleteIfEmailNotVerified(anyLong());
        verify(userRepository, never()).insertPendingUserIfAbsent(anyString(), anyString(), anyString(), any());
    }

    /**
     * Misma cuenta dueña del email y del nombre, que aún conserva el nombre
     * (creada hace menos de 24 h, o heredada): el mismo 409 que daría el nombre
     * con cualquier otro email, sin borrar nada.
     */
    @Test
    void siElEmailYElNombreSonDeLaMismaCuentaQueConservaElNombreDa409() {
        User pending = user(7L, "ana.nueva@test.com", "ana_nueva");
        when(userRepository.findByEmail("ana.nueva@test.com")).thenReturn(Optional.of(pending));
        when(userRepository.findByUsername("ana_nueva")).thenReturn(Optional.of(pending));
        when(emailVerificationService.releasesUsername(pending)).thenReturn(false);

        assertThrows(UserAlreadyExistsException.class, () -> service.register(registration()));

        verify(userRepository, never()).deleteIfEmailNotVerified(anyLong());
        verify(emailVerificationService, never()).issueIfAllowed(any());
    }

    /**
     * Las dos filas (dueño del email y dueño del nombre) se bloquean de menor
     * a mayor id: dos registros cruzados no pueden bloquearse mutuamente.
     */
    @Test
    void lasFilasSeBloqueanEnOrdenDeId() {
        User owner = verified(user(9L, "ana.nueva@test.com", "otra_ana"));
        User holder = user(3L, "otra@test.com", "ana_nueva");
        when(userRepository.findByEmail("ana.nueva@test.com")).thenReturn(Optional.of(owner));
        when(userRepository.findByUsername("ana_nueva")).thenReturn(Optional.of(holder));

        assertThrows(UserAlreadyExistsException.class, () -> service.register(registration()));

        InOrder order = inOrder(emailVerificationService);
        order.verify(emailVerificationService).lockForUpdate(holder);
        order.verify(emailVerificationService).lockForUpdate(owner);
    }

    /**
     * Lo que no es un choque de unicidad (aquí, un {@code CHECK}) no se
     * disfraza: sale tal cual (500 y log). El {@code ON CONFLICT DO NOTHING}
     * solo cubre los {@code UNIQUE}.
     */
    @Test
    void otraViolacionSeRelanzaTalCual() {
        DataIntegrityViolationException check = violation("23514");
        when(userRepository.insertPendingUserIfAbsent(anyString(), anyString(), anyString(), any()))
                .thenThrow(check);

        assertSame(check, assertThrows(DataIntegrityViolationException.class,
                () -> service.register(registration())));
        verify(savepoints, never()).rollbackTo(any());
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
        User existing = verified(user(7L, "ana.nueva@test.com", "otra_ana"));
        when(userRepository.findByEmail("ana.nueva@test.com")).thenReturn(Optional.of(existing));

        assertSame(AcceptedResponse.REGISTRATION, service.register(registration()));

        CreateUserRequest otherEmail = registration();
        otherEmail.setEmail("otra.persona@test.com");
        UserAlreadyExistsException error = assertThrows(UserAlreadyExistsException.class,
                () -> service.register(otherEmail));
        assertEquals("El nombre de usuario ya está en uso", error.getMessage());
        assertThrows(UserAlreadyExistsException.class, () -> service.register(registration()));
        verify(userRepository, never()).insertPendingUserIfAbsent(anyString(), anyString(), anyString(), any());
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
     * Un email nuevo crea la cuenta y su nombre queda <b>también</b>
     * reservado, a nombre de ese email: la fila de la reserva es el cerrojo
     * del nombre (ver {@code UsernameReservations}, «Concurrencia»).
     */
    @Test
    void unEmailNuevoTambienReservaElNombreASuEmail() {
        service.register(registration());

        assertTrue(reservations.isReserved("ana_nueva"));
        assertFalse(reservations.isReservedForOthers("ana_nueva", "ana.nueva@test.com"));
        assertTrue(reservations.isReservedForOthers("ana_nueva", "otra@test.com"));
    }

    /**
     * El nombre se reclama <b>antes</b> de leer nada, y el punto de guardado
     * se toma justo después: si se leyera antes, una reserva o una cuenta sin
     * confirmar de una petición simultánea pasarían desapercibidas; si el punto
     * se tomara antes de reclamar el nombre, volver a él lo soltaría (la
     * carrera en sí se prueba contra PostgreSQL en
     * {@code PostgresUsernameRaceIntegrationTest}).
     */
    @Test
    void elNombreSeReclamaAntesDeLeerNadaYElPuntoDeGuardadoJustoDespues() {
        service.register(registration());

        InOrder order = inOrder(reservationRepository, savepoints, userRepository);
        order.verify(reservationRepository).reserve(eq("ana_nueva"), eq("ana.nueva@test.com"), any(), any());
        order.verify(savepoints).create();
        order.verify(userRepository).findByEmail("ana.nueva@test.com");
        verify(reservationRepository, times(1)).reserve(anyString(), anyString(), any(), any());
    }

    /**
     * Si el nombre ya tenía una reserva vigente, 409 aunque la reserva fuera
     * del mismo email (el registro no tiene la excepción del dueño), sin
     * guardar nada.
     */
    @Test
    void unNombreYaReservadoDa409AunqueSeaDelMismoEmail() {
        reservations.reserve("ana_nueva", "ana.nueva@test.com");
        reservationRepository.commit();

        assertThrows(UserAlreadyExistsException.class, () -> service.register(registration()));

        verify(userRepository, never()).insertPendingUserIfAbsent(anyString(), anyString(), anyString(), any());
    }

    // ------------------------------------------------------------------
    // BAJA-A: la reserva y la cuenta nueva, con el mismo instante
    // ------------------------------------------------------------------

    /**
     * La reserva del nombre y la fecha de alta de la cuenta nueva usan
     * <b>el mismo instante</b>, truncado a microsegundos (la precisión de las
     * fechas en PostgreSQL y H2): así la pendiente libera su nombre justo
     * cuando caduca la reserva. Antes, la fecha de alta la ponía
     * {@code @CreationTimestamp} después y con la hora del sistema. El primer
     * token de la cuenta también se emite con ese instante (ver
     * {@code EmailVerificationServiceTest}).
     */
    @Test
    void laReservaYLaCuentaNuevaUsanElMismoInstanteTruncadoAMicrosegundos() {
        Instant withNanos = Instant.parse("2026-05-01T10:00:00.123456789Z");
        Instant micros = Instant.parse("2026-05-01T10:00:00.123456Z");
        service = newService(Clock.fixed(withNanos, ZoneOffset.UTC));

        service.register(registration());

        verify(reservationRepository).reserve(eq("ana_nueva"), eq("ana.nueva@test.com"), eq(micros), any());
        verify(userRepository).insertPendingUserIfAbsent("ana_nueva", "ana.nueva@test.com", "hash", micros);
        verify(emailVerificationService).issueForNewAccount(created.get(), micros);
    }

    // ------------------------------------------------------------------
    // MEDIA-A: la decisión se repite sin soltar el nombre
    // ------------------------------------------------------------------

    /**
     * Dos registros simultáneos con el mismo email nuevo: el {@code INSERT}
     * del segundo no crea nada (la otra cuenta ya está confirmada). Se vuelve
     * al punto de guardado y se repite la decisión, que ya ve esa cuenta y
     * sigue el camino «el email ya tiene cuenta»: el mismo 202, sin crear nada
     * más y con el nombre reservado. Lo importante: la transacción <b>no</b>
     * se deshace y el nombre se reclama <b>una sola vez</b>. Antes, la
     * violación de {@code uk_users_email} deshacía la transacción con su
     * reserva y el nombre se volvía a pedir en otra: en ese hueco, otra
     * petición que esperaba por él lo conseguía (incidencia MEDIA-A).
     */
    @Test
    void quienPierdeLaCarreraPorElEmailRepiteLaDecisionSinSoltarElNombre() {
        User winner = user(8L, "ana.nueva@test.com", "otra_ana");
        when(userRepository.insertPendingUserIfAbsent(anyString(), anyString(), anyString(), any()))
                .thenReturn(0);
        when(userRepository.findByEmail("ana.nueva@test.com"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));

        assertSame(AcceptedResponse.REGISTRATION, service.register(registration()));

        verify(savepoints, times(1)).rollbackTo(savepoint);
        verify(emailVerificationService).issueIfAllowed(winner);
        verify(emailVerificationService, never()).issueForNewAccount(any(), any());
        verify(userRepository, times(1)).insertPendingUserIfAbsent(anyString(), anyString(), anyString(), any());
        verify(reservationRepository, times(1)).reserve(anyString(), anyString(), any(), any());
        verify(transactionManager, never()).rollback(any());
        verify(transactionManager, times(1)).commit(any());
        assertTrue(reservations.isReserved("ana_nueva"), "el nombre del perdedor queda reservado");
    }

    /**
     * Si el {@code INSERT} no crea nada porque el <b>nombre</b> ya es de otra
     * cuenta (alguien se lo dio sin pasar por su cerrojo), la decisión repetida
     * ve a su dueño y responde el 409 de siempre.
     */
    @Test
    void siElInsertChocaPorElNombreLaDecisionRepetidaDaEl409DelNombre() {
        User holder = verified(user(3L, "otra@test.com", "ana_nueva"));
        when(userRepository.insertPendingUserIfAbsent(anyString(), anyString(), anyString(), any()))
                .thenReturn(0);
        when(userRepository.findByUsername("ana_nueva"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(holder));

        UserAlreadyExistsException error = assertThrows(UserAlreadyExistsException.class,
                () -> service.register(registration()));

        assertEquals("El nombre de usuario ya está en uso", error.getMessage());
        verify(savepoints, times(1)).rollbackTo(savepoint);
        assertFalse(reservations.isReserved("ana_nueva"), "el 409 deshace la reserva");
    }

    /**
     * La cuenta pendiente del email desaparece mientras se espera su bloqueo
     * (otro registro la sustituyó): se vuelve al punto de guardado y se repite
     * la decisión con el estado nuevo, que aquí ya no tiene esa cuenta. Antes
     * se deshacía toda la transacción (soltando el nombre, MEDIA-A); y antes
     * aún, el {@code refresh} con bloqueo lanzaba
     * {@code EntityNotFoundException} y el registro respondía 500.
     */
    @Test
    void siLaCuentaDesapareceAlBloquearlaSeRepiteLaDecisionSinSoltarElNombre() {
        User vanished = user(7L, "ana.nueva@test.com", "otra_ana");
        when(userRepository.findByEmail("ana.nueva@test.com"))
                .thenReturn(Optional.of(vanished))
                .thenAnswer(this::createdWithEmail);
        when(emailVerificationService.lockForUpdate(vanished)).thenReturn(Optional.empty());

        assertSame(AcceptedResponse.REGISTRATION, service.register(registration()));

        verify(savepoints, times(1)).rollbackTo(savepoint);
        verify(transactionManager, never()).rollback(any());
        verify(transactionManager, times(1)).commit(any());
        verify(reservationRepository, times(1)).reserve(anyString(), anyString(), any(), any());
        verify(userRepository, times(1)).insertPendingUserIfAbsent(anyString(), anyString(), anyString(), any());
        verify(emailVerificationService).issueForNewAccount(created.get(), NOW);
    }

    /**
     * Si el dueño del nombre se cambió el nombre mientras se esperaba su
     * bloqueo, tampoco se decide con la fila de antes (sería un 409 falso):
     * se repite la decisión.
     */
    @Test
    void siElDuenoDelNombreLoCambiaAlBloquearloSeRepiteLaDecision() {
        User holder = user(3L, "otra@test.com", "ana_nueva");
        User renamed = user(3L, "otra@test.com", "nombre_nuevo");
        when(userRepository.findByUsername("ana_nueva"))
                .thenReturn(Optional.of(holder))
                .thenReturn(Optional.empty());
        when(emailVerificationService.lockForUpdate(holder)).thenReturn(Optional.of(renamed));

        assertSame(AcceptedResponse.REGISTRATION, service.register(registration()));

        verify(savepoints, times(1)).rollbackTo(savepoint);
        verify(transactionManager, never()).rollback(any());
        verify(userRepository, times(1)).insertPendingUserIfAbsent(anyString(), anyString(), anyString(), any());
    }

    /**
     * {@value RegistrationService#MAX_ATTEMPTS} veces seguidas: no se repite
     * sin fin; es un 500 legítimo y la transacción (con la reserva) se deshace
     * entera.
     */
    @Test
    void siLaCuentaDesapareceEnTodosLosIntentosNoSeReintentaMas() {
        User vanished = user(7L, "ana.nueva@test.com", "otra_ana");
        when(userRepository.findByEmail("ana.nueva@test.com")).thenReturn(Optional.of(vanished));
        when(emailVerificationService.lockForUpdate(vanished)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.register(registration()));

        verify(savepoints, times(RegistrationService.MAX_ATTEMPTS - 1)).rollbackTo(savepoint);
        verify(transactionManager, times(1)).rollback(any());
        verify(userRepository, never()).insertPendingUserIfAbsent(anyString(), anyString(), anyString(), any());
        assertFalse(reservations.isReserved("ana_nueva"));
    }

    /**
     * <b>Incidencia BAJA-1.</b> Mientras el registro tiene el cerrojo del
     * nombre, un atacante puede provocar, una vez cada uno, los tres cambios
     * que obligan a repetir la decisión: (1) el dueño del nombre se lo cambia;
     * (2) desaparece la pendiente del email (el atacante pidió con otra cuenta
     * el nombre de esa pendiente suya, creada hace 24 h o más); (3) el
     * {@code INSERT} pierde la carrera frente a otro registro con el mismo
     * email. Con uno en cada intento, el cuarto ve la cuenta ganadora (nueva,
     * con su enlace vigente) y responde el 202 de siempre. Con
     * {@code MAX_ATTEMPTS = 3} este registro daba 500, cuando con el email
     * verificado solo cabe el primer cambio y responde 202: un oráculo de
     * emails registrados.
     */
    @Test
    void tresCambiosProvocablesSeguidosNoAgotanLosIntentos() {
        User holder = user(3L, "otra@test.com", "ana_nueva");
        User renamed = user(3L, "otra@test.com", "nombre_nuevo");
        User stalePending = user(7L, "ana.nueva@test.com", "nombre_viejo");
        User winner = user(8L, "ana.nueva@test.com", "el_ganador");
        // Intento 1: el dueño del nombre se lo cambia mientras se espera su bloqueo.
        when(userRepository.findByUsername("ana_nueva"))
                .thenReturn(Optional.of(holder))
                .thenReturn(Optional.empty());
        when(emailVerificationService.lockForUpdate(holder)).thenReturn(Optional.of(renamed));
        // Intento 2: la pendiente del email ya no existe al bloquearla.
        // Intento 3: el email está libre, pero el INSERT pierde la carrera.
        // Intento 4: la cuenta del ganador, que no se puede sustituir.
        when(userRepository.findByEmail("ana.nueva@test.com"))
                .thenReturn(Optional.of(stalePending))
                .thenReturn(Optional.of(stalePending))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(emailVerificationService.lockForUpdate(stalePending))
                .thenReturn(Optional.of(stalePending))
                .thenReturn(Optional.empty());
        when(userRepository.insertPendingUserIfAbsent(anyString(), anyString(), anyString(), any()))
                .thenReturn(0);

        assertSame(AcceptedResponse.REGISTRATION, service.register(registration()));

        verify(savepoints, times(3)).rollbackTo(savepoint);
        verify(userRepository, times(1)).insertPendingUserIfAbsent(anyString(), anyString(), anyString(), any());
        verify(emailVerificationService).issueIfAllowed(winner);
        verify(emailVerificationService, never()).issueForNewAccount(any(), any());
        verify(reservationRepository, times(1)).reserve(anyString(), anyString(), any(), any());
        verify(transactionManager, never()).rollback(any());
        verify(transactionManager, times(1)).commit(any());
        assertTrue(reservations.isReserved("ana_nueva"), "el nombre queda reservado, como en cualquier 202");
    }

    /**
     * Un intento descartado no deja correos programados: el aviso o el enlace
     * solo salen del intento que termina. Aquí, el primer intento ve la
     * pendiente sustituible del email y la borra, pero el {@code INSERT}
     * pierde contra otro registro; el segundo ve la cuenta del otro y solo
     * reenvía (con el límite silencioso).
     */
    @Test
    void unIntentoDescartadoNoProgramaNingunCorreo() {
        User stale = user(7L, "ana.nueva@test.com", "otra_ana");
        User winner = user(8L, "ana.nueva@test.com", "otro_nombre");
        when(userRepository.findByEmail("ana.nueva@test.com"))
                .thenReturn(Optional.of(stale))
                .thenReturn(Optional.of(winner));
        when(emailVerificationService.isReplaceable(stale)).thenReturn(true);
        when(userRepository.deleteIfEmailNotVerified(7L)).thenReturn(1);
        when(userRepository.insertPendingUserIfAbsent(anyString(), anyString(), anyString(), any()))
                .thenReturn(0);

        assertSame(AcceptedResponse.REGISTRATION, service.register(registration()));

        verify(savepoints, times(1)).rollbackTo(savepoint);
        verify(emailVerificationService, never()).issueForNewAccount(any(), any());
        verify(emailVerificationService).issueIfAllowed(winner);
        verify(emailDispatcher, never()).sendAfterCommit(any());
    }

    /**
     * Límite silencioso del aviso «Ya tienes una cuenta» (en memoria, con el
     * reloj de la aplicación): dos registros seguidos con el mismo email
     * confirmado responden igual, pero solo el primero manda el aviso.
     */
    @Test
    void elAvisoDeCuentaExistenteNoSeRepiteDentroDelIntervalo() {
        User existing = verified(user(7L, "ana.nueva@test.com", "otra_ana"));
        when(userRepository.findByEmail("ana.nueva@test.com")).thenReturn(Optional.of(existing));

        assertSame(AcceptedResponse.REGISTRATION, service.register(registration()));
        // Con otro nombre: el primero quedó reservado (repetirlo daría 409).
        CreateUserRequest again = registration();
        again.setUsername("ana_otra_vez");
        assertSame(AcceptedResponse.REGISTRATION, service.register(again));

        verify(emailDispatcher, times(1)).sendAfterCommit(any());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Servicio con el reloj dado (y reservas con ese mismo reloj) sobre los dobles del test. */
    private RegistrationService newService(Clock clock) {
        EmailProperties emailProperties = new EmailProperties("StreamBox <no-reply@streambox.local>",
                Duration.ofHours(24), Duration.ofHours(1), false,
                new EmailProperties.Resend(3, Duration.ofHours(1), Duration.ofSeconds(60)));
        RateLimitProperties rateLimit = mock(RateLimitProperties.class);
        when(rateLimit.maxKeys()).thenReturn(1000);
        reservations = new UsernameReservations(reservationRepository, emailProperties, clock);
        return new RegistrationService(userRepository, passwordEncoder, emailVerificationService,
                emailDispatcher, emailProperties, new PublicUrlProperties("https://streambox.test"), rateLimit,
                clock, transactionManager, reservations, savepoints);
    }

    /** Respuesta por defecto de {@code findByEmail}: la cuenta que creó el INSERT, si es de ese email. */
    private Optional<User> createdWithEmail(InvocationOnMock invocation) {
        String email = invocation.getArgument(0);
        return Optional.ofNullable(created.get()).filter(user -> email.equals(user.getEmail()));
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

    private static User verified(User user) {
        user.setEmailVerifiedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return user;
    }

    private static CreateUserRequest registration() {
        CreateUserRequest request = new CreateUserRequest();
        request.setUsername("ana_nueva");
        request.setEmail("ana.nueva@test.com");
        request.setPassword("un paseo por el retiro en otoño");
        return request;
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
