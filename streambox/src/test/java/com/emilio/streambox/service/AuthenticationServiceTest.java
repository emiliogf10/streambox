package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.InvalidCredentialsException;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.emilio.streambox.security.ratelimit.LoginAttemptService;
import com.emilio.streambox.security.ratelimit.LoginAttemptService.LoginAttempt;
import com.emilio.streambox.security.ratelimit.SlidingWindowCounter.Reservation;
import com.emilio.streambox.security.refresh.RefreshTokenService;

/**
 * Pruebas unitarias de {@link AuthenticationService} centradas en la cuenta
 * pendiente de confirmar el correo (incidencia QA-1).
 *
 * <p>
 * Con la contraseña correcta, una cuenta pendiente debe seguir <b>el mismo
 * camino</b> que una contraseña incorrecta: el intento se cierra con
 * {@link LoginAttemptService#recordFailure(LoginAttempt)} (que decide intentos
 * restantes y bloqueo) y nunca con {@code recordSuccess} (que borraría los
 * fallos y haría «conocida» la IP), y no se emite ningún token. Los tests de
 * integración ({@code EmailVerificationSecurityIntegrationTest}) comprueban la
 * respuesta HTTP; aquí se fija qué se llama, que es lo que garantiza que las
 * dos respuestas no puedan desincronizarse.
 * </p>
 */
class AuthenticationServiceTest {

    private static final String EMAIL = "pendiente@unit.test";
    private static final String PASSWORD = "Contrasena-Correcta-2026";
    private static final String IP = "203.0.113.7";

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private LoginAttemptService loginAttemptService;
    private JwtService jwtService;
    private RefreshTokenService refreshTokenService;
    private AuthenticationService service;

    private final LoginAttempt attempt = new LoginAttempt(EMAIL, IP, false, new Reservation(1, 1));

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        loginAttemptService = mock(LoginAttemptService.class);
        jwtService = mock(JwtService.class);
        refreshTokenService = mock(RefreshTokenService.class);
        when(passwordEncoder.encode(any())).thenReturn("hash-falso");
        when(loginAttemptService.reserveAttempt(EMAIL, IP)).thenReturn(attempt);
        service = new AuthenticationService(userRepository, passwordEncoder, loginAttemptService, jwtService,
                refreshTokenService);
    }

    /**
     * Cuenta pendiente + contraseña correcta: {@code recordFailure} (con sus
     * intentos restantes en la excepción), el mensaje único del 401, ningún
     * {@code recordSuccess} y ningún token. Falla si el login vuelve a tratar
     * la cuenta pendiente como un caso aparte.
     */
    @Test
    void unaCuentaPendienteConLaContrasenaCorrectaSeCierraComoUnFallo() {
        User pending = user(null);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(pending));
        when(passwordEncoder.matches(PASSWORD, pending.getPassword())).thenReturn(true);
        when(loginAttemptService.recordFailure(attempt)).thenReturn(3);

        InvalidCredentialsException exception = assertThrows(InvalidCredentialsException.class,
                () -> service.login(EMAIL, PASSWORD, IP));

        assertEquals(AuthenticationService.INVALID_CREDENTIALS_MESSAGE, exception.getMessage());
        assertEquals(3, exception.getRemainingAttempts());
        verify(loginAttemptService).recordFailure(attempt);
        verify(loginAttemptService, never()).recordSuccess(any());
        verifyNoInteractions(jwtService, refreshTokenService);
    }

    /**
     * La misma cuenta con la contraseña incorrecta: exactamente las mismas
     * llamadas y la misma excepción (control del test anterior).
     */
    @Test
    void unaCuentaPendienteConLaContrasenaIncorrectaSeCierraIgual() {
        User pending = user(null);
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(pending));
        when(passwordEncoder.matches("mal", pending.getPassword())).thenReturn(false);
        when(loginAttemptService.recordFailure(attempt)).thenReturn(3);

        InvalidCredentialsException exception = assertThrows(InvalidCredentialsException.class,
                () -> service.login(EMAIL, "mal", IP));

        assertEquals(AuthenticationService.INVALID_CREDENTIALS_MESSAGE, exception.getMessage());
        assertEquals(3, exception.getRemainingAttempts());
        verify(loginAttemptService, never()).recordSuccess(any());
        verifyNoInteractions(jwtService, refreshTokenService);
    }

    /** Control: confirmada y con la contraseña correcta, acierto y sesión. */
    @Test
    void unaCuentaConfirmadaConLaContrasenaCorrectaIniciaSesion() {
        User verified = user(Instant.parse("2026-10-01T10:00:00Z"));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(verified));
        when(passwordEncoder.matches(PASSWORD, verified.getPassword())).thenReturn(true);
        when(jwtService.generateToken(verified)).thenReturn("jwt");

        service.login(EMAIL, PASSWORD, IP);

        verify(loginAttemptService).recordSuccess(attempt);
        verify(loginAttemptService, never()).recordFailure(any());
        verify(refreshTokenService).startFamily(42L);
    }

    private static User user(Instant emailVerifiedAt) {
        User user = new User();
        user.setId(42L);
        user.setEmail(EMAIL);
        user.setUsername("pendiente");
        user.setPassword("hash-de-la-cuenta");
        user.setRole(Role.USER);
        user.setEmailVerifiedAt(emailVerifiedAt);
        return user;
    }
}
