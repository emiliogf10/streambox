package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.CurrentPasswordIncorrectException;
import com.emilio.streambox.exception.InvalidParameterException;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.emilio.streambox.security.ratelimit.PasswordChangeAttemptService;
import com.emilio.streambox.security.ratelimit.PasswordChangeAttemptService.Attempt;
import com.emilio.streambox.security.ratelimit.SlidingWindowCounter.Reservation;
import com.emilio.streambox.security.refresh.IssuedRefreshToken;
import com.emilio.streambox.security.refresh.RefreshTokenService;
import com.emilio.streambox.security.refresh.SessionTokens;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * Tests unitarios de {@link PasswordChangeService}: el orden de las
 * comprobaciones y la carrera entre dos cambios de contraseña, que no se puede
 * reproducir con MockMvc.
 */
class PasswordChangeServiceTest {

    private static final Long USER_ID = 7L;
    private static final String CURRENT = "Contraseña-Actual-2026";
    private static final String NEW = "Otra-Clave-Distinta-2026!";
    private static final String HASH = "$2a$10$hash-verificado";

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final PasswordChangeAttemptService attempts = mock(PasswordChangeAttemptService.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final JwtService jwtService = mock(JwtService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final Attempt attempt = new Attempt("7", new Reservation(1, 1));

    private PasswordChangeService service;

    @BeforeEach
    void setUp() {
        service = new PasswordChangeService(userRepository, passwordEncoder, attempts, refreshTokenService,
                jwtService, transactionManager);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user(HASH)));
        when(attempts.reserve(USER_ID)).thenReturn(attempt);
        when(passwordEncoder.matches(CURRENT, HASH)).thenReturn(true);
        when(passwordEncoder.matches(NEW, HASH)).thenReturn(false);
        when(passwordEncoder.encode(NEW)).thenReturn("$2a$10$hash-nuevo");
    }

    /** Guarda el hash, revoca todo y solo después abre la familia de la sesión actual. */
    @Test
    void cambiaElHashRevocaTodasLasSesionesYAbreUnaNueva() {
        User locked = user(HASH);
        IssuedRefreshToken issued = new IssuedRefreshToken("r".repeat(43), java.time.Duration.ofDays(7));
        when(entityManager.find(User.class, USER_ID, LockModeType.PESSIMISTIC_WRITE)).thenReturn(locked);
        when(jwtService.generateToken(locked)).thenReturn("jwt");
        when(refreshTokenService.startFamily(USER_ID)).thenReturn(issued);

        SessionTokens tokens = service.changePassword(USER_ID, CURRENT, NEW);

        assertEquals("$2a$10$hash-nuevo", locked.getPassword());
        assertEquals(new SessionTokens("jwt", issued), tokens);
        InOrder order = inOrder(refreshTokenService);
        order.verify(refreshTokenService).revokeAllSessions(USER_ID);
        order.verify(refreshTokenService).startFamily(USER_ID);
        verify(attempts).recordSuccess(attempt);
    }

    /**
     * Dos cambios cruzados: entre la comprobación con BCrypt (fuera de la
     * transacción) y la escritura, otro cambio ya confirmó otra contraseña. El
     * segundo no debe sobrescribirla ni cerrar sesiones: su «actual» ya no lo
     * es.
     */
    @Test
    void siOtroCambioSeConfirmoEnMedioNoSobrescribeNiRevoca() {
        User locked = user("$2a$10$hash-de-otro-cambio");
        when(entityManager.find(User.class, USER_ID, LockModeType.PESSIMISTIC_WRITE)).thenReturn(locked);

        assertThrows(CurrentPasswordIncorrectException.class, () -> service.changePassword(USER_ID, CURRENT, NEW));

        assertEquals("$2a$10$hash-de-otro-cambio", locked.getPassword());
        verifyNoInteractions(refreshTokenService);
    }

    /** Con la actual incorrecta se anota el fallo y no se toca nada más. */
    @Test
    void conLaActualIncorrectaAnotaElFalloYNoEscribe() {
        when(passwordEncoder.matches(CURRENT, HASH)).thenReturn(false);

        assertThrows(CurrentPasswordIncorrectException.class, () -> service.changePassword(USER_ID, CURRENT, NEW));

        verify(attempts).recordFailure(attempt);
        verify(attempts, never()).recordSuccess(any());
        verifyNoInteractions(transactionManager, refreshTokenService, entityManager);
    }

    /**
     * Un error de la política se da antes de reservar intento: no gasta
     * intentos ni ejecuta BCrypt.
     */
    @Test
    void unErrorDePoliticaNoGastaIntento() {
        assertThrows(InvalidParameterException.class,
                () -> service.changePassword(USER_ID, CURRENT, "netflixpassword"));

        verifyNoInteractions(attempts, passwordEncoder, transactionManager);
    }

    private static User user(String hash) {
        User user = new User();
        user.setId(USER_ID);
        user.setUsername("ana-perfil");
        user.setEmail("ana@streambox.test");
        user.setPassword(hash);
        user.setRole(Role.USER);
        return user;
    }
}
