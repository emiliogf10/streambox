package com.emilio.streambox.security.refresh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.emilio.streambox.entity.RefreshToken;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.SessionExpiredException;
import com.emilio.streambox.repository.RefreshTokenRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;

/**
 * Pruebas unitarias (Mockito) de {@link RefreshTokenService} centradas en el
 * <b>orden</b> de las lecturas y en qué versión de tokens lleva el JWT, que
 * las pruebas de integración no pueden fijar sin una carrera real.
 *
 * <ul>
 *   <li><b>BAJA-A:</b> {@code users.token_version} se lee justo tras bloquear
 *       el token presentado y <b>antes</b> de mirar su sucesor (gracia), y el
 *       JWT se firma con ese valor, no con {@code user.getTokenVersion()}. Sin
 *       el arreglo, el refresh nunca llamaba a {@code findTokenVersionById} y
 *       firmaba con la entidad (cargada después del sucesor): estos tests
 *       fallan.</li>
 *   <li><b>BAJA-B:</b> el refresh de una cuenta pendiente revoca sus refresh
 *       (dos pasadas) pero <b>no</b> sube la versión: así no bloquea la fila
 *       de {@code users} después de la de {@code refresh_tokens}, el orden
 *       contrario al de la confirmación del correo y el restablecimiento. Sin
 *       el arreglo, {@code incrementTokenVersion} se llamaba y estos tests
 *       fallan.</li>
 * </ul>
 */
class RefreshTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");
    private static final Long USER_ID = 42L;

    /** Versión de la base de datos; la entidad trae 0 (desfasada a propósito). */
    private static final int DB_VERSION = 7;

    /** Un valor con el formato de un token propio (43 caracteres Base64URL). */
    private static final String PRESENTED = "A".repeat(RefreshTokenService.TOKEN_LENGTH);

    private RefreshTokenRepository refreshTokenRepository;
    private UserRepository userRepository;
    private JwtService jwtService;
    private RefreshTokenService service;

    @BeforeEach
    void setUp() {
        refreshTokenRepository = mock(RefreshTokenRepository.class);
        userRepository = mock(UserRepository.class);
        jwtService = mock(JwtService.class);
        RefreshTokenProperties properties = new RefreshTokenProperties(Duration.ofDays(7), Duration.ofDays(30),
                Duration.ofSeconds(10),
                new RefreshTokenProperties.Cleanup(true, Duration.ofHours(1), Duration.ofMinutes(1),
                        Duration.ofDays(3)));
        service = new RefreshTokenService(refreshTokenRepository, userRepository, jwtService, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(userRepository.findTokenVersionById(USER_ID)).thenReturn(Optional.of(DB_VERSION));
        when(jwtService.generateToken(any(User.class), anyInt())).thenReturn("jwt");
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(call -> call.getArgument(0));
    }

    // ------------------------------------------------------------------
    // BAJA-A: la versión, antes que el sucesor, y el JWT con ella
    // ------------------------------------------------------------------

    /**
     * Gracia de las pestañas simultáneas: la versión se lee después del
     * bloqueo y antes del estado del sucesor, y el JWT lleva esa versión (7),
     * no la de la entidad (0).
     *
     * <p>
     * Es el orden que impide la carrera: si un «cerrar todas las sesiones»
     * confirma antes de leer el sucesor, este se ve revocado y no hay gracia;
     * si confirma después, el JWT lleva la versión vieja y el filtro lo
     * rechaza.
     * </p>
     */
    @Test
    void enLaGraciaLaVersionSeLeeAntesQueElSucesorYElJwtLaLleva() {
        User user = verifiedUser();
        RefreshToken successor = liveSuccessorMock();
        RefreshToken presented = token(user, NOW.minusSeconds(1), successor);
        when(refreshTokenRepository.findByTokenHashForUpdate(RefreshTokenService.sha256Hex(PRESENTED)))
                .thenReturn(Optional.of(presented));

        SessionTokens tokens = service.refresh(PRESENTED);

        assertEquals("jwt", tokens.accessToken());
        assertNull(tokens.refreshToken(), "en la gracia no se rota");
        InOrder order = inOrder(refreshTokenRepository, userRepository, successor, jwtService);
        order.verify(refreshTokenRepository).findByTokenHashForUpdate(any());
        order.verify(userRepository).findTokenVersionById(USER_ID);
        order.verify(successor).getRevokedAt();
        order.verify(jwtService).generateToken(user, DB_VERSION);
        verify(jwtService, never()).generateToken(any(User.class));
    }

    /**
     * Rotación normal: la misma versión leída tras el bloqueo (antes de crear
     * el sucesor) es la que lleva el JWT.
     */
    @Test
    void alRotarElJwtLlevaLaVersionLeidaTrasElBloqueo() {
        User user = verifiedUser();
        RefreshToken presented = token(user, null, null);
        when(refreshTokenRepository.findByTokenHashForUpdate(RefreshTokenService.sha256Hex(PRESENTED)))
                .thenReturn(Optional.of(presented));

        SessionTokens tokens = service.refresh(PRESENTED);

        assertNotNull(tokens.refreshToken(), "debía rotar");
        assertNotNull(presented.getReplacedBy());
        InOrder order = inOrder(refreshTokenRepository, userRepository, jwtService);
        order.verify(refreshTokenRepository).findByTokenHashForUpdate(any());
        order.verify(userRepository).findTokenVersionById(USER_ID);
        order.verify(refreshTokenRepository).save(any(RefreshToken.class));
        order.verify(jwtService).generateToken(user, DB_VERSION);
        verify(jwtService, never()).generateToken(any(User.class));
    }

    /**
     * Sin fila de usuario (la cuenta se está borrando): 401 de sesión, sin
     * emitir nada.
     */
    @Test
    void sinVersionNoSeEmiteNada() {
        User user = verifiedUser();
        when(refreshTokenRepository.findByTokenHashForUpdate(RefreshTokenService.sha256Hex(PRESENTED)))
                .thenReturn(Optional.of(token(user, null, null)));
        when(userRepository.findTokenVersionById(USER_ID)).thenReturn(Optional.empty());

        assertThrows(SessionExpiredException.class, () -> service.refresh(PRESENTED));

        verify(jwtService, never()).generateToken(any(User.class), anyInt());
        verify(refreshTokenRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // BAJA-B: cuenta pendiente, sin subir la versión
    // ------------------------------------------------------------------

    /**
     * Refresh de una cuenta pendiente (token vivo): 401, sus refresh se
     * revocan en dos pasadas y la versión <b>no</b> se toca (ni se emite nada).
     */
    @Test
    void unaCuentaPendienteRevocaSusRefreshSinSubirLaVersion() {
        User user = pendingUser();
        when(refreshTokenRepository.findByTokenHashForUpdate(RefreshTokenService.sha256Hex(PRESENTED)))
                .thenReturn(Optional.of(token(user, null, null)));

        assertThrows(SessionExpiredException.class, () -> service.refresh(PRESENTED));

        verify(refreshTokenRepository, times(2)).revokeAllByUserId(USER_ID, NOW);
        verify(userRepository, never()).incrementTokenVersion(any());
        verify(jwtService, never()).generateToken(any(User.class), anyInt());
        verify(refreshTokenRepository, never()).save(any());
    }

    /** Lo mismo en la gracia: tampoco ahí se sube la versión ni se emite nada. */
    @Test
    void unaCuentaPendienteEnLaGraciaTampocoSubeLaVersion() {
        User user = pendingUser();
        RefreshToken successor = liveSuccessorMock();
        when(refreshTokenRepository.findByTokenHashForUpdate(RefreshTokenService.sha256Hex(PRESENTED)))
                .thenReturn(Optional.of(token(user, NOW.minusSeconds(1), successor)));

        assertThrows(SessionExpiredException.class, () -> service.refresh(PRESENTED));

        verify(refreshTokenRepository, times(2)).revokeAllByUserId(USER_ID, NOW);
        verify(userRepository, never()).incrementTokenVersion(any());
        verify(jwtService, never()).generateToken(any(User.class), anyInt());
    }

    /**
     * Control de lo anterior: {@code revokeAllSessions} (logout-all, cambio y
     * restablecimiento de la contraseña, confirmación del correo) sí sube la
     * versión, antes de las dos pasadas.
     */
    @Test
    void revokeAllSessionsSubeLaVersionAntesDeLasDosPasadas() {
        when(refreshTokenRepository.revokeAllByUserId(USER_ID, NOW)).thenReturn(2, 1);

        assertEquals(3, service.revokeAllSessions(USER_ID));

        InOrder order = inOrder(userRepository, refreshTokenRepository);
        order.verify(userRepository).incrementTokenVersion(USER_ID);
        order.verify(refreshTokenRepository, times(2)).revokeAllByUserId(USER_ID, NOW);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static User verifiedUser() {
        return user(NOW.minus(Duration.ofDays(1)));
    }

    private static User pendingUser() {
        return user(null);
    }

    private static User user(Instant emailVerifiedAt) {
        User user = new User();
        user.setId(USER_ID);
        user.setEmail("refresh@unit.test");
        user.setUsername("refresh");
        user.setPassword("hash");
        user.setRole(Role.USER);
        user.setEmailVerifiedAt(emailVerifiedAt);
        return user;
    }

    /**
     * El token presentado: vivo ({@code revokedAt} nulo) o rotado en
     * {@code revokedAt} hacia {@code successor}.
     */
    private static RefreshToken token(User user, Instant revokedAt, RefreshToken successor) {
        RefreshToken token = new RefreshToken();
        token.setId(1L);
        token.setUser(user);
        token.setTokenHash(RefreshTokenService.sha256Hex(PRESENTED));
        token.setFamilyId(UUID.randomUUID());
        token.setCreatedAt(NOW.minus(Duration.ofDays(1)));
        token.setExpiresAt(NOW.plus(Duration.ofDays(6)));
        token.setFamilyExpiresAt(NOW.plus(Duration.ofDays(29)));
        token.setRevokedAt(revokedAt);
        token.setReplacedBy(successor);
        return token;
    }

    /**
     * Sucesor vivo como mock, para que {@link InOrder} vea cuándo se lee su
     * estado (en la aplicación es un proxy LAZY: leerlo es una consulta).
     */
    private static RefreshToken liveSuccessorMock() {
        RefreshToken successor = mock(RefreshToken.class);
        when(successor.getRevokedAt()).thenReturn(null);
        when(successor.getExpiresAt()).thenReturn(NOW.plus(Duration.ofDays(7)));
        when(successor.getFamilyExpiresAt()).thenReturn(NOW.plus(Duration.ofDays(29)));
        return successor;
    }
}
