package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
import com.emilio.streambox.entity.AccountTokenPurpose;
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
 * Tests unitarios de {@link EmailVerificationService} (la forma de los tokens
 * y su hash están en {@code AccountTokensTest}): el rechazo de lo que no puede
 * ser un token propio <b>sin tocar la
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

    /** Hora fija del reloj del servicio (las reglas de sustitución dependen de ella). */
    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");

    private final EmailVerificationService service = new EmailVerificationService(tokens,
            new AccountTokens(tokens, users), users,
            mock(RefreshTokenService.class), mock(LoginAttemptService.class), new EmailDispatcher(sender, queued::add),
            new EmailProperties("a@b.test", Duration.ofHours(24), Duration.ofHours(1), false,
                    new EmailProperties.Resend(3, Duration.ofHours(1), Duration.ofSeconds(60))),
            new PublicUrlProperties("https://streambox.test"), Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
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
     * Pendiente creada por el registro (primer token de su alta) hace 24 h o
     * más: se borra (con el borrado condicional) y se libera el nombre.
     */
    @Test
    void unaPendienteQueYaNoConservaSuNombreSeBorra() {
        User stale = pendingUser();
        stale.setCreatedAt(NOW.minus(Duration.ofHours(25)));
        when(tokens.existsByUser_IdAndPurposeAndCreatedAtLessThanEqual(any(), any(), any())).thenReturn(true);
        when(users.deleteIfEmailNotVerified(7L)).thenReturn(1);

        assertTrue(service.deleteIfUsernameReleased(stale));

        verify(users).deleteIfEmailNotVerified(7L);
    }

    /**
     * Heredada (ningún token de cuando se creó), aunque se diera de alta hace
     * años: nunca se borra por el nombre. Es lo que protege a las cuentas USER
     * anteriores a la V9.
     */
    @Test
    void unaHeredadaNuncaPierdeSuNombre() {
        User legacy = pendingUser();
        legacy.setCreatedAt(Instant.parse("2024-03-01T09:00:00Z"));
        when(tokens.existsByUser_IdAndPurposeAndCreatedAtLessThanEqual(any(), any(), any())).thenReturn(false);

        assertEquals(false, service.releasesUsername(legacy));
        assertEquals(false, service.deleteIfUsernameReleased(legacy));

        verify(users, never()).deleteIfEmailNotVerified(any());
    }

    /** Una cuenta verificada nunca pierde su nombre, por vieja que sea. */
    @Test
    void unaVerificadaNuncaPierdeSuNombre() {
        User verified = pendingUser();
        verified.setCreatedAt(NOW.minus(Duration.ofDays(30)));
        verified.setEmailVerifiedAt(NOW.minus(Duration.ofDays(29)));

        assertEquals(false, service.deleteIfUsernameReleased(verified));

        verify(users, never()).deleteIfEmailNotVerified(any());
    }

    /**
     * El límite es exacto y es el de la reserva de nombre
     * ({@code UsernameReservations}, 24 h): un segundo antes, la pendiente aún
     * conserva su nombre; a las 24 h justas, ya no.
     */
    @Test
    void elNombreSeLiberaJustoALas24HorasDelAlta() {
        when(tokens.existsByUser_IdAndPurposeAndCreatedAtLessThanEqual(any(), any(), any())).thenReturn(true);
        User almost = pendingUser();
        almost.setCreatedAt(NOW.minus(Duration.ofHours(24)).plusSeconds(1));
        User exactly = pendingUser();
        exactly.setCreatedAt(NOW.minus(Duration.ofHours(24)));

        assertEquals(false, service.releasesUsername(almost));
        assertTrue(service.releasesUsername(exactly));
    }

    /**
     * <b>Incidencia MEDIA-1.</b> Creada por el registro hace más de 24 h, con
     * un enlace de verificación (reenvío) y otro de «He olvidado mi
     * contraseña» pedidos después y vigentes. <b>Por email</b> no es
     * sustituible: un registro nuevo con su email no la borra (protege el
     * enlace que el titular tiene en su buzón). <b>Por nombre</b> sí lo pierde:
     * si los enlaces nuevos alargaran la vida del nombre, pedir uno pasadas
     * 24 h haría que el nombre de un registro con un email nuevo diera 409 y
     * el de uno con un email que ya tenía cuenta (solo reservado 24 h), 202.
     */
    @Test
    void conEnlacesVigentesNoEsSustituiblePorEmailPeroPierdeSuNombre() {
        User pending = pendingUser();
        pending.setCreatedAt(NOW.minus(Duration.ofHours(25)));
        when(tokens.existsByUser_IdAndPurposeAndCreatedAtLessThanEqual(any(), any(), any())).thenReturn(true);
        when(tokens.hasActiveToken(eq(7L), eq(AccountTokenPurpose.EMAIL_VERIFICATION), any())).thenReturn(true);
        when(tokens.hasActiveToken(eq(7L), eq(AccountTokenPurpose.PASSWORD_RESET), any())).thenReturn(true);
        when(users.deleteIfEmailNotVerified(7L)).thenReturn(1);

        assertEquals(false, service.isReplaceable(pending));
        assertTrue(service.releasesUsername(pending));
        assertTrue(service.deleteIfUsernameReleased(pending));
    }

    /**
     * Regla por email: creada por el registro y con su enlace de verificación
     * caducado, pero con un enlace de «He olvidado mi contraseña» vigente: un
     * registro con su email no la sustituye (si lo hiciera, cualquiera podría
     * dejar sin valor el enlace del titular registrándose otra vez con su email).
     */
    @Test
    void unaPendienteConUnEnlaceDeRestablecimientoVigenteNoEsSustituiblePorEmail() {
        User pending = pendingUser();
        pending.setCreatedAt(NOW.minus(Duration.ofHours(25)));
        when(tokens.existsByUser_IdAndPurposeAndCreatedAtLessThanEqual(any(), any(), any())).thenReturn(true);
        when(tokens.hasActiveToken(eq(7L), eq(AccountTokenPurpose.EMAIL_VERIFICATION), any())).thenReturn(false);
        when(tokens.hasActiveToken(eq(7L), eq(AccountTokenPurpose.PASSWORD_RESET), any())).thenReturn(true);

        assertEquals(false, service.isReplaceable(pending));
    }

    /** Si el borrado condicional no borra nada (se verificó entre medias), no se da el nombre por libre. */
    @Test
    void siElBorradoCondicionalNoBorraNadaNoSeLibera() {
        User stale = pendingUser();
        stale.setCreatedAt(NOW.minus(Duration.ofHours(25)));
        when(tokens.existsByUser_IdAndPurposeAndCreatedAtLessThanEqual(any(), any(), any())).thenReturn(true);
        when(users.deleteIfEmailNotVerified(7L)).thenReturn(0);

        assertEquals(false, service.deleteIfUsernameReleased(stale));
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
