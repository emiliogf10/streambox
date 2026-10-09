package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.SessionExpiredException;
import com.emilio.streambox.repository.RefreshTokenRepository;
import com.emilio.streambox.security.refresh.IssuedRefreshToken;
import com.emilio.streambox.security.refresh.RefreshConcurrencyScenario;
import com.emilio.streambox.security.refresh.RefreshTokenService;
import com.emilio.streambox.security.refresh.SessionTokens;

/**
 * {@link RefreshTokenService#refresh(String)} con peticiones simultáneas del
 * mismo token sobre un <b>PostgreSQL real</b>: el bloqueo de fila
 * ({@code SELECT ... FOR NO KEY UPDATE}) hace que solo una rote y que las
 * demás esperen y entren en la gracia. El bloqueo en sí (a nivel de
 * repositorio) lo prueba {@code PostgresRefreshTokenIntegrationTest}; aquí se
 * prueba el servicio completo.
 */
class PostgresRefreshTokenServiceConcurrencyIntegrationTest extends PostgresIntegrationTestSupport {

    @Autowired private RefreshTokenService refreshTokenService;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    @AfterEach
    void clean() {
        cleanDatabase();
    }

    @Test
    void variosRefreshSimultaneosDelMismoTokenSoloRotanUnaVez() throws Exception {
        User user = saveUser("concurrente", Role.USER);

        RefreshConcurrencyScenario.assertSingleRotation(refreshTokenService, refreshTokenRepository,
                refreshTokenService.startFamily(user.getId()), 8);
    }

    /**
     * Carrera de READ COMMITTED: se reutiliza un token viejo (A, fuera de la
     * gracia) <b>mientras</b> otra transacción rota el token vigente (B → C).
     *
     * <p>
     * El {@code UPDATE} que revoca la familia espera al bloqueo de B y, cuando
     * la rotación confirma, PostgreSQL vuelve a evaluar B (ya revocado: lo
     * salta), pero C no existía al empezar la sentencia y no la ve. Con una
     * sola pasada, C quedaba <b>vivo</b> pese a la reutilización: quien lo
     * tuviera (p. ej. el ladrón, si era él quien rotaba) conservaba la sesión.
     * La segunda pasada de {@code RefreshTokenService} lo revoca.
     * </p>
     */
    @Test
    void unaReutilizacionDuranteUnaRotacionRevocaTambienElSucesorRecienCreado() throws Exception {
        assertRevocationDuringRotationRevokesSuccessor("reutilizacion", oldToken -> {
            try {
                refreshTokenService.refresh(oldToken);
                return "renovada";
            } catch (SessionExpiredException e) {
                return "SESSION_EXPIRED";
            }
        }, "SESSION_EXPIRED");
    }

    /**
     * La misma carrera con un logout que presenta el token viejo (la víctima
     * cierra sesión con A mientras quien tiene B lo rota): sin la segunda
     * pasada, C sobrevivía al logout.
     */
    @Test
    void unLogoutDuranteUnaRotacionRevocaTambienElSucesorRecienCreado() throws Exception {
        assertRevocationDuringRotationRevokesSuccessor("logout", oldToken -> {
            refreshTokenService.revokeFamilyOf(oldToken);
            return "revocada";
        }, "revocada");
    }

    /**
     * Prepara la familia A → B (A rotado hace un minuto, fuera de la gracia),
     * deja sin confirmar una rotación real B → C y, mientras, ejecuta
     * {@code revocation} con A. Comprueba que la revocación espera a la
     * rotación y que, al final, no queda ningún token activo en la familia.
     *
     * @param userName   nombre del usuario de la prueba
     * @param revocation operación que se ejecuta con el token A
     * @param expected   resultado esperado de {@code revocation}
     */
    private void assertRevocationDuringRotationRevokesSuccessor(String userName,
            Function<String, String> revocation, String expected) throws Exception {
        User user = saveUser(userName, Role.USER);
        IssuedRefreshToken a = refreshTokenService.startFamily(user.getId());
        SessionTokens first = refreshTokenService.refresh(a.value());
        String b = first.refreshToken().value();
        UUID family = jdbc.queryForObject("SELECT family_id FROM refresh_tokens WHERE token_hash = ?",
                UUID.class, sha256Hex(b));
        // A se rotó hace un minuto: ya no está en la gracia, reutilizarlo es robo.
        jdbc.update("UPDATE refresh_tokens SET revoked_at = ? WHERE token_hash = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minus(Duration.ofMinutes(1)), sha256Hex(a.value()));

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch rotated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        try {
            // Rotación B -> C que se queda sin confirmar (con B bloqueado)
            Future<SessionTokens> rotation = pool.submit(() -> tx.execute(status -> {
                SessionTokens tokens = refreshTokenService.refresh(b);
                rotated.countDown();
                await(commit);
                return tokens;
            }));
            assertTrue(rotated.await(10, TimeUnit.SECONDS), "la rotación no llegó a ejecutarse");

            Future<String> revoke = pool.submit(() -> revocation.apply(a.value()));
            awaitBackendWaitingForLock();
            assertFalse(revoke.isDone(), "la revocación debe esperar a la rotación en curso");

            commit.countDown();
            assertNotNull(rotation.get(10, TimeUnit.SECONDS).refreshToken(), "B debía rotar a C");
            assertEquals(expected, revoke.get(10, TimeUnit.SECONDS));

            assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE family_id = ?",
                    Integer.class, family), "A, B y C");
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens"
                    + " WHERE family_id = ? AND revoked_at IS NULL", Integer.class, family),
                    "la revocación debe alcanzar también a C, creado mientras se revocaba");
        } finally {
            commit.countDown();
            pool.shutdownNow();
        }
    }

    /** Espera (máx. 10 s) a que alguna conexión esté bloqueada esperando una fila de refresh_tokens. */
    private void awaitBackendWaitingForLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity"
                    + " WHERE datname = current_database() AND wait_event_type = 'Lock'"
                    + " AND query ILIKE '%refresh_tokens%'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("ninguna transacción llegó a esperar el bloqueo de la fila");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("tiempo de espera agotado");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** SHA-256 en hexadecimal, como lo guarda {@link RefreshTokenService}. */
    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
