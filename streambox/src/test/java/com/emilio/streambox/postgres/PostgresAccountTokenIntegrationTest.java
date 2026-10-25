package com.emilio.streambox.postgres;

import static com.emilio.streambox.entity.AccountTokenPurpose.EMAIL_VERIFICATION;
import static com.emilio.streambox.entity.AccountTokenPurpose.PASSWORD_RESET;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.AccountToken;
import com.emilio.streambox.entity.AccountTokenPurpose;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.AccountTokenRepository;

/**
 * Tabla {@code account_tokens} y columna {@code users.email_verified_at} de la
 * {@code V9}, y {@link AccountTokenRepository}, sobre un <b>PostgreSQL real</b>.
 *
 * <p>
 * Repite lo que depende del motor: tipos reales, índices, cada restricción con
 * su {@code SQLSTATE} y su nombre, la cascada y, sobre todo, el <b>consumo
 * atómico</b> con transacciones concurrentes de verdad: el segundo
 * {@code UPDATE} espera al bloqueo de fila del primero y, en
 * {@code READ COMMITTED}, vuelve a evaluar su {@code WHERE} sobre la fila ya
 * actualizada. H2 no reproduce fielmente ese comportamiento.
 * </p>
 *
 * <p>
 * Los datos se confirman de verdad (sin {@code @Transactional}) y se limpian
 * antes y después de cada test (los tokens caen en cascada al borrar los
 * usuarios). Sin Docker la clase se omite (ver {@link PostgresIntegrationTestSupport}).
 * La migración sobre una base con cuentas se prueba en
 * {@link PostgresEmailVerificationMigrationIntegrationTest}.
 * </p>
 */
class PostgresAccountTokenIntegrationTest extends PostgresIntegrationTestSupport {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";
    private static final String NOT_NULL_VIOLATION = "23502";

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final Duration TTL = Duration.ofHours(24);
    /** Caducidad de un enlace de restablecimiento (la del contrato: 1 hora). */
    private static final Duration RESET_TTL = Duration.ofHours(1);

    @Autowired private AccountTokenRepository accountTokenRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private User alice;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        cleanDatabase();
        alice = savePendingUser("alice");
    }

    /**
     * Cuenta <b>pendiente</b> de verificar. {@code saveUser} de la clase base
     * crea las cuentas ya verificadas (lo que necesitan las demás suites para
     * iniciar sesión), pero aquí se prueba justo el paso de pendiente a
     * verificada: con una cuenta ya verificada, {@code markEmailVerified} y
     * {@code deleteIfEmailNotVerified} devuelven 0 a propósito.
     */
    private User savePendingUser(String name) {
        User user = saveUser(name, Role.USER);
        jdbc.update("UPDATE users SET email_verified_at = NULL WHERE id = ?", user.getId());
        return userRepository.findById(user.getId()).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Esquema
    // ------------------------------------------------------------------

    @Test
    void losTiposDeColumnaSonLosEsperados() {
        Map<String, String> types = new HashMap<>();
        Set<String> nullable = new java.util.HashSet<>();
        jdbc.queryForList("SELECT column_name, data_type, character_maximum_length, is_nullable"
                        + " FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'account_tokens'")
                .forEach(c -> {
                    Object length = c.get("character_maximum_length");
                    types.put((String) c.get("column_name"),
                            c.get("data_type") + (length == null ? "" : "(" + length + ")"));
                    if ("YES".equals(c.get("is_nullable"))) {
                        nullable.add((String) c.get("column_name"));
                    }
                });

        assertEquals(Map.of(
                "id", "bigint",
                "user_id", "bigint",
                "purpose", "character varying(32)",
                "token_hash", "character varying(64)",
                "created_at", "timestamp with time zone",
                "expires_at", "timestamp with time zone",
                "used_at", "timestamp with time zone"), types);
        assertEquals(Set.of("used_at"), nullable);
        assertEquals("YES", jdbc.queryForObject("SELECT is_identity FROM information_schema.columns"
                + " WHERE table_name = 'account_tokens' AND column_name = 'id'", String.class));

        Map<String, Object> verifiedAt = jdbc.queryForMap("SELECT data_type, is_nullable, column_default"
                + " FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'users'"
                + " AND column_name = 'email_verified_at'");
        assertEquals("timestamp with time zone", verifiedAt.get("data_type"));
        assertEquals("YES", verifiedAt.get("is_nullable"));
        assertNull(verifiedAt.get("column_default"), "sin DEFAULT: una cuenta nueva nace pendiente");
    }

    @Test
    void existenLosIndicesYLaUnicidadSobreLasColumnasCorrectas() {
        Map<String, String> definitions = new HashMap<>();
        jdbc.queryForList("SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'account_tokens'")
                .forEach(r -> definitions.put((String) r.get("indexname"), (String) r.get("indexdef")));

        assertEquals(Set.of("pk_account_tokens", "uk_account_tokens_token_hash", "idx_account_tokens_user_purpose"),
                definitions.keySet());
        assertTrue(definitions.get("uk_account_tokens_token_hash")
                .contains("UNIQUE INDEX uk_account_tokens_token_hash ON public.account_tokens USING btree (token_hash)"));
        assertTrue(definitions.get("idx_account_tokens_user_purpose").contains("USING btree (user_id, purpose)"));
    }

    @Test
    void laClaveForaneaYLosCheckTienenNombreYPolitica() {
        assertEquals("CASCADE", jdbc.queryForObject("SELECT delete_rule FROM information_schema.referential_constraints"
                + " WHERE constraint_schema = 'public' AND constraint_name = 'fk_account_tokens_user'", String.class));

        Set<String> checks = Set.copyOf(jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE contype = 'c' AND conrelid = 'public.account_tokens'::regclass",
                String.class));
        assertEquals(Set.of("ck_account_tokens_purpose", "ck_account_tokens_token_hash_format",
                "ck_account_tokens_expires_at", "ck_account_tokens_used_before_expiry"), checks);
    }

    /** Cada CHECK rechaza lo suyo con 23514 y su nombre; el propósito nulo, con 23502. */
    @Test
    void cadaRestriccionCheckRechazaLoSuyo() {
        long userId = alice.getId();
        assertCheck("ck_account_tokens_purpose", () -> insert(userId, "LOGIN_LINK", hash(1), NOW, NOW.plus(TTL), null));
        assertCheck("ck_account_tokens_purpose", () -> insert(userId, "password_reset", hash(1), NOW, NOW.plus(TTL), null));
        assertCheck("ck_account_tokens_purpose", () -> insert(userId, "PASSWORD_RESET ", hash(1), NOW, NOW.plus(TTL), null));
        assertCheck("ck_account_tokens_purpose", () -> insert(userId, "email_verification", hash(1), NOW, NOW.plus(TTL), null));
        assertCheck("ck_account_tokens_token_hash_format", () -> insert(userId, "EMAIL_VERIFICATION", "a".repeat(63), NOW, NOW.plus(TTL), null));
        assertCheck("ck_account_tokens_token_hash_format", () -> insert(userId, "EMAIL_VERIFICATION", "A".repeat(64), NOW, NOW.plus(TTL), null));
        assertCheck("ck_account_tokens_token_hash_format", () -> insert(userId, "EMAIL_VERIFICATION", "Zq3-_x" + "a".repeat(58), NOW, NOW.plus(TTL), null));
        assertCheck("ck_account_tokens_expires_at", () -> insert(userId, "EMAIL_VERIFICATION", hash(1), NOW, NOW, null));
        assertCheck("ck_account_tokens_used_before_expiry", () -> insert(userId, "EMAIL_VERIFICATION", hash(1), NOW, NOW.plus(TTL), NOW.plus(TTL)));
        DataIntegrityViolationException noPurpose = assertThrows(DataIntegrityViolationException.class,
                () -> insert(userId, null, hash(1), NOW, NOW.plus(TTL), null));
        assertEquals(NOT_NULL_VIOLATION, sqlState(noPurpose));
        assertEquals(0, count());
    }

    /**
     * V10: el CHECK del propósito, sustituido conservando su nombre, admite
     * exactamente los dos propósitos (su definición real en
     * {@code pg_constraint}, no solo que acepte dos inserciones) y está
     * validado sobre las filas existentes.
     */
    @Test
    void elCheckDePropositoAdmiteLosDosPropositosDeLaV10() {
        Map<String, Object> constraint = jdbc.queryForMap("SELECT pg_get_constraintdef(oid) AS def, convalidated"
                + " FROM pg_constraint WHERE conname = 'ck_account_tokens_purpose'"
                + " AND conrelid = 'public.account_tokens'::regclass");
        String definition = (String) constraint.get("def");

        assertTrue(definition.contains("'EMAIL_VERIFICATION'") && definition.contains("'PASSWORD_RESET'"), definition);
        // PostgreSQL reescribe el IN como «= ANY (ARRAY[...])» con conversiones de
        // tipo: se cuentan los literales entre comillas, sea cual sea esa forma
        assertEquals(2, java.util.regex.Pattern.compile("'[^']*'").matcher(definition).results().count(),
                "exactamente dos valores: " + definition);
        assertEquals(Boolean.TRUE, constraint.get("convalidated"));

        insert(alice.getId(), "EMAIL_VERIFICATION", hash(1), NOW, NOW.plus(TTL), null);
        insert(alice.getId(), "PASSWORD_RESET", hash(2), NOW, NOW.plus(RESET_TTL), null);
        assertEquals(2, count());
    }

    @Test
    void elHashEsUnicoYElUsuarioDebeExistir() {
        insert(alice.getId(), "EMAIL_VERIFICATION", hash(1), NOW, NOW.plus(TTL), null);

        DataIntegrityViolationException duplicate = assertThrows(DataIntegrityViolationException.class,
                () -> insert(alice.getId(), "EMAIL_VERIFICATION", hash(1), NOW, NOW.plus(TTL), null));
        assertEquals(UNIQUE_VIOLATION, sqlState(duplicate));
        assertTrue(duplicate.getMessage().contains("uk_account_tokens_token_hash"), duplicate.getMessage());

        DataIntegrityViolationException orphan = assertThrows(DataIntegrityViolationException.class,
                () -> insert(987654L, "EMAIL_VERIFICATION", hash(2), NOW, NOW.plus(TTL), null));
        assertEquals(FOREIGN_KEY_VIOLATION, sqlState(orphan));
        assertTrue(orphan.getMessage().contains("fk_account_tokens_user"), orphan.getMessage());
    }

    /** Borrar una cuenta borra sus tokens (no los de otras), por SQL y por JPA. */
    @Test
    void laCascadaFuncionaEnPostgres() {
        User bob = saveUser("bob", Role.USER);
        User carol = saveUser("carol", Role.USER);
        insert(alice.getId(), "EMAIL_VERIFICATION", hash(1), NOW, NOW.plus(TTL), null);
        insert(bob.getId(), "EMAIL_VERIFICATION", hash(2), NOW, NOW.plus(TTL), null);
        insert(carol.getId(), "EMAIL_VERIFICATION", hash(3), NOW, NOW.plus(TTL), null);

        jdbc.update("DELETE FROM users WHERE id = ?", alice.getId());
        userRepository.deleteById(bob.getId());

        assertEquals(List.of(hash(3)), jdbc.queryForList("SELECT token_hash FROM account_tokens", String.class));
    }

    // ------------------------------------------------------------------
    // Consultas del repositorio en PostgreSQL
    // ------------------------------------------------------------------

    /**
     * El ciclo completo con las comparaciones de {@code timestamptz} reales:
     * vigente, invalidar al reenviar, consumir el nuevo, verificar la cuenta y
     * no volver a consumir; y sustituir una cuenta pendiente (solo si lo sigue).
     */
    @Test
    void lasConsultasDelRepositorioFuncionanEnPostgres() {
        save(alice, hash(1), NOW, NOW.plus(TTL));
        assertTrue(accountTokenRepository.hasActiveToken(alice.getId(), EMAIL_VERIFICATION, NOW));
        assertFalse(accountTokenRepository.hasActiveToken(alice.getId(), EMAIL_VERIFICATION, NOW.plus(TTL)));

        // Reenvío: invalida el anterior y emite otro
        assertEquals(1, (int) tx.execute(status ->
                accountTokenRepository.invalidateActiveTokens(alice.getId(), EMAIL_VERIFICATION, NOW.plusSeconds(60))));
        save(alice, hash(2), NOW.plusSeconds(60), NOW.plusSeconds(60).plus(TTL));
        assertEquals(2, accountTokenRepository.countIssuedSince(alice.getId(), EMAIL_VERIFICATION, NOW.minusSeconds(1)));

        assertEquals(0, consume(hash(1), NOW.plusSeconds(120)), "el invalidado ya no sirve");
        Instant clickAt = NOW.plusSeconds(120);
        int[] rows = tx.execute(status -> {
            int consumed = accountTokenRepository.consume(hash(2), EMAIL_VERIFICATION, clickAt);
            Long userId = accountTokenRepository.findUserIdByTokenHashAndPurpose(hash(2), EMAIL_VERIFICATION).orElseThrow();
            return new int[] { consumed, userRepository.markEmailVerified(userId, clickAt) };
        });
        assertArrayEquals(new int[] { 1, 1 }, rows, "consumido y cuenta verificada");
        assertEquals(0, consume(hash(2), clickAt.plusSeconds(1)));
        assertEquals(clickAt, userRepository.findById(alice.getId()).orElseThrow().getEmailVerifiedAt());

        // Una cuenta verificada no se sustituye; una pendiente sí, con sus tokens
        User pending = savePendingUser("pendiente");
        save(pending, hash(3), NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(1)));
        assertEquals(0, (int) tx.execute(status -> userRepository.deleteIfEmailNotVerified(alice.getId())));
        assertEquals(1, (int) tx.execute(status -> userRepository.deleteIfEmailNotVerified(pending.getId())));
        assertEquals(List.of(hash(1), hash(2)),
                jdbc.queryForList("SELECT token_hash FROM account_tokens ORDER BY token_hash", String.class));

        assertEquals(2, accountTokenRepository.deleteExpired(NOW.plus(Duration.ofDays(30))));
        assertEquals(0, count());
    }

    /**
     * V10 en PostgreSQL: las consultas separan los dos propósitos (el enum se
     * compara como {@code varchar} con el valor guardado) e
     * {@code invalidateAllActiveTokens} abarca los dos de una cuenta y nada más.
     */
    @Test
    void lasConsultasSeparanLosPropositosEInvalidarTodosAbarcaLosDos() {
        User bob = saveUser("bob", Role.USER);
        save(alice, EMAIL_VERIFICATION, hash(1), NOW, NOW.plus(TTL));
        save(alice, PASSWORD_RESET, hash(2), NOW, NOW.plus(RESET_TTL));
        save(bob, PASSWORD_RESET, hash(3), NOW, NOW.plus(RESET_TTL));

        assertEquals(0, consume(hash(1), PASSWORD_RESET, NOW), "un enlace de verificación no cambia contraseñas");
        assertEquals(0, consume(hash(2), EMAIL_VERIFICATION, NOW));
        assertNull(usedAt(hash(1)));
        assertNull(usedAt(hash(2)));
        assertEquals(1, accountTokenRepository.countIssuedSince(alice.getId(), PASSWORD_RESET, NOW.minusSeconds(1)));
        assertTrue(accountTokenRepository.hasActiveToken(alice.getId(), PASSWORD_RESET, NOW));
        assertFalse(accountTokenRepository.hasActiveToken(alice.getId(), PASSWORD_RESET, NOW.plus(RESET_TTL)));

        assertEquals(1, (int) tx.execute(status ->
                accountTokenRepository.invalidateActiveTokens(alice.getId(), PASSWORD_RESET, NOW.plusSeconds(5))));
        assertNull(usedAt(hash(1)), "invalidar los de restablecimiento no toca la verificación");

        save(alice, PASSWORD_RESET, hash(4), NOW.plusSeconds(10), NOW.plusSeconds(10).plus(RESET_TTL));
        assertEquals(2, (int) tx.execute(status ->
                accountTokenRepository.invalidateAllActiveTokens(alice.getId(), NOW.plusSeconds(20))));
        assertEquals(NOW.plusSeconds(20), usedAt(hash(1)));
        assertEquals(NOW.plusSeconds(5), usedAt(hash(2)), "el ya invalidado conserva su fecha");
        assertEquals(NOW.plusSeconds(20), usedAt(hash(4)));
        assertNull(usedAt(hash(3)), "los de otra cuenta siguen vigentes");
    }

    // ------------------------------------------------------------------
    // Consumo atómico: concurrencia real
    // ------------------------------------------------------------------

    /**
     * Mientras la transacción A tiene el token consumido sin confirmar, el
     * {@code UPDATE} de B con el mismo token <b>espera</b> (se comprueba en
     * {@code pg_stat_activity}) y, cuando A confirma, afecta a 0 filas: la
     * fila ya tiene {@code used_at}. Es lo que impide verificar dos veces con
     * el mismo enlace.
     */
    @Test
    void unSegundoConsumoDelMismoTokenEsperaAlPrimeroYNoLoGasta() throws Exception {
        save(alice, hash(1), NOW, NOW.plus(TTL));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch aConsumed = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        try {
            Future<Integer> a = pool.submit(() -> tx.execute(status -> {
                int rows = accountTokenRepository.consume(hash(1), EMAIL_VERIFICATION, NOW);
                aConsumed.countDown();
                await(releaseA);
                return rows;
            }));
            assertTrue(aConsumed.await(10, TimeUnit.SECONDS), "A no llegó a consumir el token");

            Future<Integer> b = pool.submit(() -> tx.execute(status ->
                    accountTokenRepository.consume(hash(1), EMAIL_VERIFICATION, NOW.plusSeconds(1))));

            awaitBackendWaitingForLock();
            assertFalse(b.isDone(), "B no debe avanzar mientras A no confirme");

            releaseA.countDown();
            assertEquals(1, a.get(10, TimeUnit.SECONDS));
            assertEquals(0, b.get(10, TimeUnit.SECONDS), "B debe ver el token ya gastado por A");
            assertEquals(NOW, usedAt(hash(1)), "queda la fecha de A");
        } finally {
            releaseA.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * Contraste: si A se deshace (p. ej. falla al verificar la cuenta), B, que
     * esperaba, ve la fila original y sí consume el token: el consumo no se
     * pierde por un fallo ajeno.
     */
    @Test
    void siElPrimeroSeDeshaceElSegundoConsumeElToken() throws Exception {
        save(alice, hash(1), NOW, NOW.plus(TTL));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch aConsumed = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        try {
            Future<?> a = pool.submit(() -> tx.executeWithoutResult(status -> {
                accountTokenRepository.consume(hash(1), EMAIL_VERIFICATION, NOW);
                aConsumed.countDown();
                await(releaseA);
                status.setRollbackOnly();
            }));
            assertTrue(aConsumed.await(10, TimeUnit.SECONDS));

            Future<Integer> b = pool.submit(() -> tx.execute(status ->
                    accountTokenRepository.consume(hash(1), EMAIL_VERIFICATION, NOW.plusSeconds(1))));
            awaitBackendWaitingForLock();

            releaseA.countDown();
            a.get(10, TimeUnit.SECONDS);
            assertEquals(1, b.get(10, TimeUnit.SECONDS));
            assertEquals(NOW.plusSeconds(1), usedAt(hash(1)));
        } finally {
            releaseA.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * Varios hilos que arrancan a la vez con el mismo token, cada uno con la
     * lógica del servicio (consumir y, si gana, verificar la cuenta):
     * exactamente uno gana, y la fecha del token y la de la cuenta son las suyas.
     */
    @Test
    void variosConsumosSimultaneosDelMismoTokenSoloGanaUno() throws Exception {
        save(alice, hash(1), NOW, NOW.plus(TTL));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier start = new CyclicBarrier(threads);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Instant now = NOW.plusSeconds(i + 1);
                results.add(pool.submit(() -> verify(start, hash(1), now)));
            }

            List<Integer> winners = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                if (results.get(i).get(20, TimeUnit.SECONDS) == 1) {
                    winners.add(i);
                }
            }

            assertEquals(1, winners.size(), "solo una petición puede consumir el token: " + winners);
            Instant winnerNow = NOW.plusSeconds(winners.get(0) + 1);
            assertEquals(winnerNow, usedAt(hash(1)));
            assertEquals(winnerNow, userRepository.findById(alice.getId()).orElseThrow().getEmailVerifiedAt());
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Lo que hará el servicio al confirmar el email: devuelve 1 si ha ganado. */
    private int verify(CyclicBarrier start, String hash, Instant now) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        return tx.execute(status -> {
            if (accountTokenRepository.consume(hash, EMAIL_VERIFICATION, now) != 1) {
                return 0;
            }
            Long userId = accountTokenRepository.findUserIdByTokenHashAndPurpose(hash, EMAIL_VERIFICATION).orElseThrow();
            return userRepository.markEmailVerified(userId, now);
        });
    }

    private int consume(String hash, Instant now) {
        return consume(hash, EMAIL_VERIFICATION, now);
    }

    private int consume(String hash, AccountTokenPurpose purpose, Instant now) {
        return tx.execute(status -> accountTokenRepository.consume(hash, purpose, now));
    }

    /** Espera (máx. 10 s) a que alguna conexión de esta base esté bloqueada esperando una fila de account_tokens. */
    private void awaitBackendWaitingForLock() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity"
                    + " WHERE datname = current_database() AND wait_event_type = 'Lock'"
                    + " AND query ILIKE '%account_tokens%'", Integer.class);
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

    private void assertCheck(String constraint, Runnable statement) {
        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class, statement::run);
        assertEquals(CHECK_VIOLATION, sqlState(error), constraint);
        assertTrue(error.getMessage().contains(constraint), error.getMessage());
    }

    /** Hash de ejemplo válido (64 caracteres hexadecimales) distinto para cada número. */
    private static String hash(int n) {
        return String.format("%064x", n);
    }

    private void insert(long userId, String purpose, String hash, Instant createdAt, Instant expiresAt, Instant usedAt) {
        jdbc.update("INSERT INTO account_tokens (user_id, purpose, token_hash, created_at, expires_at, used_at)"
                + " VALUES (?, ?, ?, ?, ?, ?)", userId, purpose, hash, utc(createdAt), utc(expiresAt), utc(usedAt));
    }

    private AccountToken save(User user, String hash, Instant createdAt, Instant expiresAt) {
        return save(user, EMAIL_VERIFICATION, hash, createdAt, expiresAt);
    }

    private AccountToken save(User user, AccountTokenPurpose purpose, String hash, Instant createdAt,
            Instant expiresAt) {
        AccountToken token = new AccountToken();
        token.setUser(user);
        token.setPurpose(purpose);
        token.setTokenHash(hash);
        token.setCreatedAt(createdAt);
        token.setExpiresAt(expiresAt);
        return accountTokenRepository.save(token);
    }

    private Instant usedAt(String hash) {
        OffsetDateTime value = jdbc.queryForObject(
                "SELECT used_at FROM account_tokens WHERE token_hash = ?", OffsetDateTime.class, hash);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM account_tokens", Integer.class);
    }
}
