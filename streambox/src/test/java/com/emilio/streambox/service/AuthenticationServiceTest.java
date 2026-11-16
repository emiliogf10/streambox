package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.InvalidCredentialsException;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.emilio.streambox.security.device.DeviceCookieService;
import com.emilio.streambox.security.device.IssuedDeviceToken;
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
    private DeviceCookieService deviceCookieService;
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
        when(loginAttemptService.reserveAttempt(EMAIL, IP, null)).thenReturn(attempt);
        deviceCookieService = mock(DeviceCookieService.class);
        service = new AuthenticationService(userRepository, passwordEncoder, loginAttemptService, jwtService,
                refreshTokenService, deviceCookieService);
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
                () -> service.login(EMAIL, PASSWORD, IP, null));

        assertEquals(AuthenticationService.INVALID_CREDENTIALS_MESSAGE, exception.getMessage());
        assertEquals(3, exception.getRemainingAttempts());
        verify(loginAttemptService).recordFailure(attempt);
        verify(loginAttemptService, never()).recordSuccess(any());
        verifyNoInteractions(jwtService, refreshTokenService);
        verify(deviceCookieService, never()).issue(any(), anyInt(), any());
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
                () -> service.login(EMAIL, "mal", IP, null));

        assertEquals(AuthenticationService.INVALID_CREDENTIALS_MESSAGE, exception.getMessage());
        assertEquals(3, exception.getRemainingAttempts());
        verify(loginAttemptService, never()).recordSuccess(any());
        verifyNoInteractions(jwtService, refreshTokenService);
        verify(deviceCookieService, never()).issue(any(), anyInt(), any());
    }

    /** Control: confirmada y con la contraseña correcta, acierto y sesión. */
    @Test
    void unaCuentaConfirmadaConLaContrasenaCorrectaIniciaSesion() {
        User verified = user(Instant.parse("2026-10-01T10:00:00Z"));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(verified));
        when(passwordEncoder.matches(PASSWORD, verified.getPassword())).thenReturn(true);
        when(jwtService.generateToken(verified, 0)).thenReturn("jwt");

        assertEquals("jwt", service.login(EMAIL, PASSWORD, IP, null).accessToken());

        // La versión va explícita (INFO 7): la de la entidad recién leída.
        verify(jwtService).generateToken(verified, 0);
        verify(loginAttemptService).recordSuccess(attempt);
        verify(loginAttemptService, never()).recordFailure(any());
        verify(refreshTokenService).startFamily(42L);
        // Sin cookie válida, una cookie de dispositivo nueva (id null = nuevo).
        verify(deviceCookieService).issue(42L, 0, null);
    }

    /**
     * Con una cookie de dispositivo válida para la cuenta, el intento se
     * reserva en el contador del dispositivo y, tras el acierto, la cookie se
     * renueva conservando el id de dispositivo y con la versión de la cuenta.
     */
    @Test
    void conCookieValidaReservaEnElDispositivoYLaRenuevaConElMismoId() {
        User verified = user(Instant.parse("2026-10-01T10:00:00Z"));
        LoginAttempt deviceAttempt = new LoginAttempt(EMAIL, IP, false, new Reservation(2, 1), "dispositivo");
        IssuedDeviceToken renewed = new IssuedDeviceToken("v1.nueva.firma", java.time.Duration.ofDays(30));
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.of(verified));
        when(deviceCookieService.deviceIdForAny(List.of("cookie"), verified)).thenReturn(Optional.of("dispositivo"));
        when(loginAttemptService.reserveAttempt(EMAIL, IP, "dispositivo")).thenReturn(deviceAttempt);
        when(passwordEncoder.matches(PASSWORD, verified.getPassword())).thenReturn(true);
        when(deviceCookieService.issue(42L, 0, "dispositivo")).thenReturn(renewed);

        assertEquals(renewed, service.login(EMAIL, PASSWORD, IP, List.of("cookie")).deviceToken());

        verify(loginAttemptService).recordSuccess(deviceAttempt);
        verify(loginAttemptService, never()).reserveAttempt(EMAIL, IP, null);
    }

    /**
     * Un email inexistente: la cookie se comprueba (mismo trabajo) contra un
     * usuario {@code null}, y el intento va al contador de siempre.
     */
    @Test
    void conUnEmailInexistenteLaCookieNoCambiaElContador() {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());
        when(passwordEncoder.matches(PASSWORD, "hash-falso")).thenReturn(false);
        when(loginAttemptService.recordFailure(attempt)).thenReturn(4);

        assertThrows(InvalidCredentialsException.class, () -> service.login(EMAIL, PASSWORD, IP, List.of("cookie")));

        verify(deviceCookieService).deviceIdForAny(List.of("cookie"), null);
        verify(loginAttemptService).reserveAttempt(EMAIL, IP, null);
        verify(deviceCookieService, never()).issue(any(), anyInt(), any());
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
