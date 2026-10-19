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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.emilio.streambox.dto.AcceptedResponse;
import com.emilio.streambox.email.EmailDispatcher;
import com.emilio.streambox.email.EmailProperties;
import com.emilio.streambox.email.EmailSender;
import com.emilio.streambox.email.PublicUrlProperties;
import com.emilio.streambox.entity.AccountToken;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.InvalidParameterException;
import com.emilio.streambox.exception.InvalidVerificationTokenException;
import com.emilio.streambox.repository.AccountTokenRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.ratelimit.LoginAttemptService;
import com.emilio.streambox.security.refresh.RefreshTokenService;

import jakarta.persistence.EntityManager;

/**
 * Tests unitarios de {@link EmailVerificationService}: forma de los tokens,
 * su hash, el rechazo de lo que no puede ser un token propio <b>sin tocar la
 * base de datos</b>, el reenvío fuera de la petición (B1) y la cuenta que
 * desaparece mientras se espera su bloqueo (B2). Los caminos completos están
 * en {@code EmailVerificationIntegrationTest}.
 */
class EmailVerificationServiceTest {

    private final AccountTokenRepository tokens = mock(AccountTokenRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final EmailSender sender = mock(EmailSender.class);

    /**
     * Tareas encoladas en el «ejecutor de correos»: no se ejecutan hasta que
     * el test las lanza, como cuando el ejecutor real está en otro hilo.
     */
    private final List<Runnable> queued = new ArrayList<>();

    private final EmailVerificationService service = new EmailVerificationService(tokens, users,
            mock(RefreshTokenService.class), mock(LoginAttemptService.class), new EmailDispatcher(sender, queued::add),
            new EmailProperties("a@b.test", Duration.ofHours(24), false,
                    new EmailProperties.Resend(3, Duration.ofHours(1), Duration.ofSeconds(60))),
            new PublicUrlProperties("https://streambox.test"), Clock.systemUTC(), transactionManager);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    /** 32 bytes aleatorios en Base64URL sin relleno: 43 caracteres que van tal cual en una URL. */
    @Test
    void cadaTokenTiene43CaracteresDeBase64UrlYNoSeRepite() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = EmailVerificationService.newToken();
            assertTrue(token.matches("[A-Za-z0-9_-]{43}"), token);
            assertTrue(seen.add(token), "Token repetido: " + token);
        }
    }

    /** En la base de datos va el SHA-256 en hexadecimal en minúsculas (vector conocido de «abc»). */
    @Test
    void elHashEsElSha256EnHexadecimalEnMinusculas() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                EmailVerificationService.sha256Hex("abc"));
    }

    /**
     * Lo que no tiene la forma exacta de un token propio se rechaza con el
     * mismo error sin abrir transacción ni consultar nada: no hay motivo para
     * gastar una consulta en algo que no puede existir.
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", " ", "abc", "q8Vb3c0mZ1yJx7pQ2rT5uW9aE4fH6kL1nB3dS8gV0i",
            "q8Vb3c0mZ1yJx7pQ2rT5uW9aE4fH6kL1nB3dS8gV0iYZ", "q8Vb3c0mZ1yJx7pQ2rT5uW9aE4fH6kL1nB3dS8gV0i=",
            "q8Vb3c0mZ1yJx7pQ2rT5uW9aE4fH6kL1nB3dS8gV0i+", " q8Vb3c0mZ1yJx7pQ2rT5uW9aE4fH6kL1nB3dS8gV0i",
            "q8Vb3c0mZ1yJx7pQ2rT5uW9aE4fH6kL1nB3dS8gV0iñ" })
    void loQueNoEsUnTokenSeRechazaSinConsultarLaBaseDeDatos(String token) {
        InvalidVerificationTokenException error = assertThrows(InvalidVerificationTokenException.class,
                () -> service.confirm(token));

        assertEquals(InvalidVerificationTokenException.MESSAGE, error.getMessage());
        verifyNoInteractions(tokens, users, transactionManager);
    }

    // ------------------------------------------------------------------
    // B1: el reenvío no hace nada en la petición
    // ------------------------------------------------------------------

    /**
     * <b>Tiempo del reenvío.</b> La petición solo encola el trabajo y responde:
     * no abre transacción ni consulta nada, exista o no la cuenta, así que el
     * tiempo de respuesta no depende de ello. Antes, un email sin cuenta
     * costaba una consulta y uno pendiente unas seis, y se podía medir.
     */
    @Test
    void elReenvioRespondeSinTocarLaBaseDeDatosYDejaElTrabajoEnElEjecutor() {
        User pending = pendingUser();
        when(users.findByEmail("ana@test.com")).thenReturn(Optional.of(pending));
        when(users.findForUpdateById(7L)).thenReturn(Optional.of(pending));
        when(users.getReferenceById(7L)).thenReturn(pending);

        assertSame(AcceptedResponse.RESEND, service.resend(" ANA@test.com "));

        verifyNoInteractions(users, tokens, transactionManager, sender);
        assertEquals(1, queued.size());

        // El gestor de transacciones simulado no activa la sincronización que
        // necesita sendAfterCommit (lo haría el real): se activa a mano.
        TransactionSynchronizationManager.initSynchronization();
        try {
            queued.get(0).run();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(users).findForUpdateById(7L);
        verify(tokens).save(any(AccountToken.class));
        verify(transactionManager).commit(any());
    }

    /** El email se normaliza en la petición: uno demasiado largo es un 400 que no encola nada. */
    @Test
    void unEmailQueAlNormalizarloEsDemasiadoLargoSeRechazaSinEncolarNada() {
        String email = "İ".repeat(60) + "@" + "d".repeat(35) + ".com";

        assertThrows(InvalidParameterException.class, () -> service.resend(email));

        assertTrue(queued.isEmpty());
    }

    // ------------------------------------------------------------------
    // B2: la cuenta desaparece mientras se espera su bloqueo
    // ------------------------------------------------------------------

    /**
     * La cuenta pendiente desaparece entre la lectura y el bloqueo (otro
     * registro la sustituyó): el reenvío lo trata como «sin cuenta», sin
     * error y sin emitir nada. Antes, {@code EntityManager.refresh} con
     * bloqueo lanzaba {@code EntityNotFoundException} (un 500 con el envío
     * síncrono). La entidad leída se desacopla antes de bloquear, para que la
     * consulta devuelva el estado actual y no el de antes.
     */
    @Test
    void siLaCuentaDesapareceAlBloquearlaElReenvioNoHaceNada() {
        User pending = pendingUser();
        when(users.findByEmail("ana@test.com")).thenReturn(Optional.of(pending));
        when(users.findForUpdateById(7L)).thenReturn(Optional.empty());

        service.resend("ana@test.com");
        queued.get(0).run();

        verify(entityManager).detach(pending);
        verify(tokens, never()).save(any());
        verify(tokens, never()).invalidateActiveTokens(any(), any(), any());
        verify(transactionManager).commit(any());
        verifyNoInteractions(sender);
    }

    /** Si al bloquearla resulta que ya se verificó, tampoco se reenvía nada. */
    @Test
    void siLaCuentaSeVerificoAlBloquearlaElReenvioNoHaceNada() {
        User stale = pendingUser();
        User verified = pendingUser();
        verified.setEmailVerifiedAt(Instant.now());
        when(users.findByEmail("ana@test.com")).thenReturn(Optional.of(stale));
        when(users.findForUpdateById(7L)).thenReturn(Optional.of(verified));

        service.resend("ana@test.com");
        queued.get(0).run();

        verify(tokens, never()).save(any());
    }

    // ------------------------------------------------------------------
    // QA-2: regla común del registro y de «Editar perfil»
    // ------------------------------------------------------------------

    /**
     * Pendiente creada por el registro (primer token de su alta) y sin enlace
     * vigente: se borra (con el borrado condicional) y se libera el nombre.
     */
    @Test
    void unaPendienteSustituibleSeBorra() {
        User stale = pendingUser();
        stale.setCreatedAt(Instant.parse("2026-05-01T10:00:00Z"));
        when(tokens.existsByUser_IdAndPurposeAndCreatedAtLessThanEqual(any(), any(), any())).thenReturn(true);
        when(tokens.hasActiveToken(any(), any(), any())).thenReturn(false);
        when(users.deleteIfEmailNotVerified(7L)).thenReturn(1);

        assertTrue(service.deleteIfReplaceable(stale));

        verify(users).deleteIfEmailNotVerified(7L);
    }

    /**
     * Heredada (ningún token de cuando se creó) o con un enlace vigente: no se
     * llega a borrar. Es lo que protege a las cuentas USER anteriores a la V9.
     */
    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void unaPendienteQueNoEsSustituibleNoSeBorra(boolean legacy) {
        User pending = pendingUser();
        pending.setCreatedAt(Instant.parse("2024-03-01T09:00:00Z"));
        when(tokens.existsByUser_IdAndPurposeAndCreatedAtLessThanEqual(any(), any(), any())).thenReturn(!legacy);
        when(tokens.hasActiveToken(any(), any(), any())).thenReturn(!legacy);

        assertEquals(false, service.deleteIfReplaceable(pending));

        verify(users, never()).deleteIfEmailNotVerified(any());
    }

    /** Si el borrado condicional no borra nada (se verificó entre medias), no se da el nombre por libre. */
    @Test
    void siElBorradoCondicionalNoBorraNadaNoSeLibera() {
        User stale = pendingUser();
        stale.setCreatedAt(Instant.parse("2026-05-01T10:00:00Z"));
        when(tokens.existsByUser_IdAndPurposeAndCreatedAtLessThanEqual(any(), any(), any())).thenReturn(true);
        when(tokens.hasActiveToken(any(), any(), any())).thenReturn(false);
        when(users.deleteIfEmailNotVerified(7L)).thenReturn(0);

        assertEquals(false, service.deleteIfReplaceable(stale));
    }

    private static User pendingUser() {
        User user = new User();
        user.setId(7L);
        user.setEmail("ana@test.com");
        user.setUsername("ana");
        user.setRole(Role.USER);
        return user;
    }
}
