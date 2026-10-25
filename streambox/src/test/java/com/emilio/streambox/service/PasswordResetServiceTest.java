package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import com.emilio.streambox.dto.AcceptedResponse;
import com.emilio.streambox.email.EmailDispatcher;
import com.emilio.streambox.email.EmailProperties;
import com.emilio.streambox.email.EmailSender;
import com.emilio.streambox.email.PublicUrlProperties;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.InvalidParameterException;
import com.emilio.streambox.exception.InvalidResetTokenException;
import com.emilio.streambox.repository.AccountTokenRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.ratelimit.LoginAttemptService;
import com.emilio.streambox.security.refresh.RefreshTokenService;

/**
 * Tests unitarios de {@link PasswordResetService}: lo que no debe tocar la base
 * de datos en la petición (un token de forma imposible; el trabajo de pedir el
 * enlace, que va en el ejecutor de los correos para que el tiempo no delate si
 * la cuenta existe) y la cuenta que desaparece mientras se espera su bloqueo.
 * Los caminos completos están en {@code PasswordResetIntegrationTest}.
 */
class PasswordResetServiceTest {

    private final AccountTokenRepository tokens = mock(AccountTokenRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final EmailVerificationService emailVerificationService = mock(EmailVerificationService.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final RefreshTokenService refreshTokenService = mock(RefreshTokenService.class);
    private final LoginAttemptService loginAttemptService = mock(LoginAttemptService.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final EmailSender sender = mock(EmailSender.class);

    /** Tareas encoladas en el «ejecutor de correos»: no se ejecutan hasta que el test las lanza. */
    private final List<Runnable> queued = new ArrayList<>();

    private final PasswordResetService service = new PasswordResetService(new AccountTokens(tokens, users), tokens,
            users, emailVerificationService, passwordEncoder, refreshTokenService, loginAttemptService,
            new EmailDispatcher(sender, queued::add),
            new EmailProperties("a@b.test", Duration.ofHours(24), Duration.ofHours(1), false,
                    new EmailProperties.Resend(3, Duration.ofHours(1), Duration.ofSeconds(60))),
            new PublicUrlProperties("https://streambox.test"), Clock.systemUTC(), transactionManager);

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    /**
     * Lo que no tiene la forma exacta de un token propio se rechaza con el
     * mismo error sin abrir transacción, consultar nada ni calcular BCrypt.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", " ", "abc", "q8Vb3c0mZ1yJx7pQ2rT5uW9aE4fH6kL1nB3dS8gV0i",
            "q8Vb3c0mZ1yJx7pQ2rT5uW9aE4fH6kL1nB3dS8gV0iYZ", "q8Vb3c0mZ1yJx7pQ2rT5uW9aE4fH6kL1nB3dS8gV0i=" })
    void loQueNoEsUnTokenSeRechazaSinConsultarNada(String token) {
        InvalidResetTokenException error = assertThrows(InvalidResetTokenException.class,
                () -> service.confirm(token, "Una-Contrasena-Larga-2026", "10.0.0.1"));

        assertEquals(InvalidResetTokenException.MESSAGE, error.getMessage());
        verifyNoInteractions(tokens, users, transactionManager, passwordEncoder, loginAttemptService);
    }

    /**
     * Un token bien formado que no existe (o no está vigente): el mismo error,
     * sin calcular BCrypt ni tocar sesiones, IPs conocidas o fallos de login.
     */
    @Test
    void unTokenQueNoEstaVigenteNoCambiaNada() {
        when(tokens.findByTokenHashAndPurpose(any(), any())).thenReturn(Optional.empty());

        assertThrows(InvalidResetTokenException.class,
                () -> service.confirm("q8Vb3c0mZ1yJx7pQ2rT5uW9aE4fH6kL1nB3dS8gV0iY", "Una-Contrasena-Larga-2026",
                        "10.0.0.1"));

        verifyNoInteractions(passwordEncoder, refreshTokenService, loginAttemptService);
        verify(tokens, never()).consume(any(), any(), any());
    }

    /**
     * <b>Tiempo de la petición.</b> Pedir el enlace solo encola el trabajo y
     * responde: no abre transacción ni consulta nada, exista o no la cuenta.
     */
    @Test
    void pedirElEnlaceRespondeSinTocarLaBaseDeDatosYDejaElTrabajoEnElEjecutor() {
        assertSame(AcceptedResponse.PASSWORD_RESET, service.request(" ANA@test.com "));

        verifyNoInteractions(users, tokens, transactionManager, sender);
        assertEquals(1, queued.size());
    }

    /** El email se normaliza en la petición: uno demasiado largo es un 400 que no encola nada. */
    @Test
    void unEmailQueAlNormalizarloEsDemasiadoLargoSeRechazaSinEncolarNada() {
        String email = "İ".repeat(60) + "@" + "d".repeat(35) + ".com";

        assertThrows(InvalidParameterException.class, () -> service.request(email));

        assertTrue(queued.isEmpty());
    }

    /**
     * La cuenta desaparece entre la lectura y el bloqueo: se trata como «sin
     * cuenta», sin error y sin emitir nada.
     */
    @Test
    void siLaCuentaDesapareceAlBloquearlaNoSeEmiteNada() {
        User user = new User();
        user.setId(7L);
        user.setEmail("ana@test.com");
        user.setUsername("ana");
        user.setRole(Role.USER);
        when(users.findByEmail("ana@test.com")).thenReturn(Optional.of(user));
        when(emailVerificationService.lockForUpdate(user)).thenReturn(Optional.empty());

        service.request("ana@test.com");
        queued.get(0).run();

        verify(tokens, never()).save(any());
        verify(tokens, never()).invalidateActiveTokens(any(), any(), any());
        verify(transactionManager).commit(any());
        verifyNoInteractions(sender);
    }
}
