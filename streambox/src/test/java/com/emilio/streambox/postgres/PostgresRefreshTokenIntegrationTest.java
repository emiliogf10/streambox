package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

import com.emilio.streambox.entity.RefreshToken;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.RefreshTokenRepository;

/**
 * Tabla {@code refresh_tokens} de {@code V4} y {@link RefreshTokenRepository}
 * sobre un <b>PostgreSQL real</b>.
 *
 * <p>
 * Repite lo que depende del motor: tipos reales ({@code uuid},
 * {@code timestamptz}), índices, políticas de borrado, cada restricción con su
 * {@code SQLSTATE} y, sobre todo, el <b>bloqueo pesimista</b> de
 * {@link RefreshTokenRepository#findByTokenHashForUpdate(String)} con dos
 * transacciones concurrentes de verdad: H2 no reproduce los bloqueos de fila
 * de PostgreSQL.
 * </p>
 *
 * <p>
 * Los datos se confirman de verdad (sin {@code @Transactional}) y se limpian
 * antes y después de cada test (los tokens caen en cascada al borrar los
 * usuarios). Sin Docker la clase se omite (ver {@link PostgresIntegrationTestSupport}).
 * </p>
 */
class PostgresRefreshTokenIntegrationTest extends PostgresIntegrationTestSupport {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String CHECK_VIOLATION = "23514";

    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private User alice;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        cleanDatabase();
        alice = saveUser("alice", Role.USER);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Migración: tipos, claves, índices y restricciones
    // ------------------------------------------------------------------

    /** V4 se aplica (junto a las anteriores) y los tipos son los esperados. */
    @Test
    void losTiposDeColumnaSonLosEsperados() {
        Map<String, String> types = new HashMap<>();
        Set<String> nullable = new java.util.HashSet<>();
        jdbc.queryForList("SELECT column_name, data_type, character_maximum_length, is_nullable"
                        + " FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'refresh_tokens'")
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
                "token_hash", "character varying(64)",
                "family_id", "uuid",
                "created_at", "timestamp with time zone",
                "expires_at", "timestamp with time zone",
                "family_expires_at", "timestamp with time zone",
                "revoked_at", "timestamp with time zone",
                "replaced_by_id", "bigint"), types);
        assertEquals(Set.of("revoked_at", "replaced_by_id"), nullable);
        assertEquals("YES", jdbc.queryForObject("SELECT is_identity FROM information_schema.columns"
                + " WHERE table_name = 'refresh_tokens' AND column_name = 'id'", String.class));
    }

    @Test
    void existenLosIndicesYLaUnicidadSobreLasColumnasCorrectas() {
        Map<String, String> definitions = new HashMap<>();
        jdbc.queryForList("SELECT indexname, indexdef FROM pg_indexes WHERE tablename = 'refresh_tokens'")
                .forEach(r -> definitions.put((String) r.get("indexname"), (String) r.get("indexdef")));

        assertEquals(Set.of("pk_refresh_tokens", "uk_refresh_tokens_token_hash", "idx_refresh_tokens_user_id",
                "idx_refresh_tokens_family_id", "idx_refresh_tokens_replaced_by_id"), definitions.keySet());
        assertTrue(definitions.get("uk_refresh_tokens_token_hash")
                .contains("UNIQUE INDEX uk_refresh_tokens_token_hash ON public.refresh_tokens USING btree (token_hash)"));
        assertTrue(definitions.get("idx_refresh_tokens_user_id").contains("USING btree (user_id)"));
        assertTrue(definitions.get("idx_refresh_tokens_family_id").contains("USING btree (family_id)"));
        assertTrue(definitions.get("idx_refresh_tokens_replaced_by_id").contains("USING btree (replaced_by_id)"));
    }

    @Test
    void lasClavesForaneasYLosCheckTienenNombreYPolitica() {
        Map<String, String> rules = new HashMap<>();
        jdbc.queryForList("SELECT constraint_name, delete_rule FROM information_schema.referential_constraints"
                        + " WHERE constraint_schema = 'public'")
                .forEach(r -> rules.put((String) r.get("constraint_name"), (String) r.get("delete_rule")));
        assertEquals("CASCADE", rules.get("fk_refresh_tokens_user"));
        assertEquals("SET NULL", rules.get("fk_refresh_tokens_replaced_by"));

        Set<String> checks = Set.copyOf(jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE contype = 'c' AND conrelid = 'public.refresh_tokens'::regclass",
                String.class));
        assertEquals(Set.of("ck_refresh_tokens_token_hash_format", "ck_refresh_tokens_expires_at",
                "ck_refresh_tokens_family_expires_at", "ck_refresh_tokens_replaced_revoked",
                "ck_refresh_tokens_not_self_replaced"), checks);
    }

    /** Cada CHECK rechaza lo suyo con 23514 y su nombre (el de formato, también el token "en claro"). */
    @Test
    void cadaRestriccionCheckRechazaLoSuyo() {
        UUID family = UUID.randomUUID();
        assertCheck("ck_refresh_tokens_token_hash_format", () -> insert(alice.getId(), "a".repeat(63), family, NOW, NOW.plusSeconds(60), NOW.plusSeconds(60)));
        assertCheck("ck_refresh_tokens_token_hash_format", () -> insert(alice.getId(), "A".repeat(64), family, NOW, NOW.plusSeconds(60), NOW.plusSeconds(60)));
        assertCheck("ck_refresh_tokens_token_hash_format", () -> insert(alice.getId(), "Zq3-_x" + "a".repeat(58), family, NOW, NOW.plusSeconds(60), NOW.plusSeconds(60)));
        assertCheck("ck_refresh_tokens_expires_at", () -> insert(alice.getId(), hash(1), family, NOW, NOW, NOW.plusSeconds(60)));
        assertCheck("ck_refresh_tokens_family_expires_at", () -> insert(alice.getId(), hash(1), family, NOW, NOW.plusSeconds(120), NOW.plusSeconds(60)));
        assertEquals(0, count());

        long next = insert(alice.getId(), hash(2), family, NOW, NOW.plusSeconds(60), NOW.plusSeconds(60));
        long previous = insert(alice.getId(), hash(1), family, NOW, NOW.plusSeconds(60), NOW.plusSeconds(60));
        assertCheck("ck_refresh_tokens_replaced_revoked",
                () -> jdbc.update("UPDATE refresh_tokens SET replaced_by_id = ? WHERE id = ?", next, previous));
        assertCheck("ck_refresh_tokens_not_self_replaced",
                () -> jdbc.update("UPDATE refresh_tokens SET revoked_at = now(), replaced_by_id = id WHERE id = ?", previous));
    }

    @Test
    void elHashEsUnicoYElUsuarioDebeExistir() {
        insert(alice.getId(), hash(1), UUID.randomUUID(), NOW, NOW.plusSeconds(60), NOW.plusSeconds(60));

        DataIntegrityViolationException duplicate = assertThrows(DataIntegrityViolationException.class,
                () -> insert(alice.getId(), hash(1), UUID.randomUUID(), NOW, NOW.plusSeconds(60), NOW.plusSeconds(60)));
        assertEquals(UNIQUE_VIOLATION, sqlState(duplicate));
        assertTrue(duplicate.getMessage().contains("uk_refresh_tokens_token_hash"), duplicate.getMessage());

        DataIntegrityViolationException orphan = assertThrows(DataIntegrityViolationException.class,
                () -> insert(987654L, hash(2), UUID.randomUUID(), NOW, NOW.plusSeconds(60), NOW.plusSeconds(60)));
        assertEquals(FOREIGN_KEY_VIOLATION, sqlState(orphan));
        assertTrue(orphan.getMessage().contains("fk_refresh_tokens_user"), orphan.getMessage());
    }

    /** Borrar un usuario borra sus tokens (no los de otros); borrar un sucesor deja el enlace a NULL. */
    @Test
    void lasCascadasFuncionanEnPostgres() {
        User bob = saveUser("bob", Role.USER);
        UUID family = UUID.randomUUID();
        long next = insert(alice.getId(), hash(2), family, NOW, NOW.plusSeconds(60), NOW.plusSeconds(60));
        long previous = insert(alice.getId(), hash(1), family, NOW, NOW.plusSeconds(60), NOW.plusSeconds(60));
        jdbc.update("UPDATE refresh_tokens SET revoked_at = now(), replaced_by_id = ? WHERE id = ?", next, previous);
        insert(bob.getId(), hash(3), UUID.randomUUID(), NOW, NOW.plusSeconds(60), NOW.plusSeconds(60));

        jdbc.update("DELETE FROM refresh_tokens WHERE id = ?", next);
        assertNull(jdbc.queryForObject("SELECT replaced_by_id FROM refresh_tokens WHERE id = ?", Long.class, previous));

        jdbc.update("DELETE FROM users WHERE id = ?", alice.getId());
        assertEquals(List.of(hash(3)), jdbc.queryForList("SELECT token_hash FROM refresh_tokens", String.class));
    }

    // ------------------------------------------------------------------
    // Consultas del repositorio
    // ------------------------------------------------------------------

    @Test
    void lasRevocacionesMasivasFuncionanEnPostgres() {
        User bob = saveUser("bob", Role.USER);
        UUID family = UUID.randomUUID();
        save(alice, hash(1), family, NOW, NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)));
        save(alice, hash(2), family, NOW, NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)));
        save(alice, hash(3), UUID.randomUUID(), NOW, NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)));
        save(bob, hash(4), UUID.randomUUID(), NOW, NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)));

        assertEquals(2, refreshTokenRepository.revokeFamily(family, NOW));
        assertEquals(0, refreshTokenRepository.revokeFamily(family, NOW.plusSeconds(5)));
        assertEquals(1, refreshTokenRepository.revokeAllByUserId(alice.getId(), NOW.plusSeconds(10)));

        assertEquals(List.of(hash(1), hash(2), hash(3)), jdbc.queryForList(
                "SELECT token_hash FROM refresh_tokens WHERE revoked_at IS NOT NULL ORDER BY token_hash", String.class));
        assertEquals(NOW, jdbc.queryForObject("SELECT revoked_at FROM refresh_tokens WHERE token_hash = ?",
                OffsetDateTime.class, hash(1)).toInstant(), "la segunda revocación no pisa la fecha");
    }

    /**
     * La limpieza borra en una sola sentencia una familia caducada entera,
     * con su cadena de sucesores ({@code replaced_by_id} apuntando a filas que
     * se borran en la misma sentencia), y conserva lo reciente.
     */
    @Test
    void laLimpiezaBorraUnaCadenaEnteraEnUnaSolaSentencia() {
        Instant start = NOW.minus(Duration.ofDays(40));
        UUID dead = UUID.randomUUID();
        long third = insert(alice.getId(), hash(3), dead, start, start.plus(Duration.ofDays(1)), NOW.minusSeconds(1));
        long second = insert(alice.getId(), hash(2), dead, start, start.plus(Duration.ofDays(1)), NOW.minusSeconds(1));
        long first = insert(alice.getId(), hash(1), dead, start, start.plus(Duration.ofDays(1)), NOW.minusSeconds(1));
        jdbc.update("UPDATE refresh_tokens SET revoked_at = now(), replaced_by_id = ? WHERE id = ?", third, second);
        jdbc.update("UPDATE refresh_tokens SET revoked_at = now(), replaced_by_id = ? WHERE id = ?", second, first);
        Instant recent = NOW.minus(Duration.ofDays(2));
        insert(alice.getId(), hash(4), UUID.randomUUID(), recent, recent.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)));
        insert(alice.getId(), hash(5), UUID.randomUUID(), NOW, NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)));

        assertEquals(3, refreshTokenRepository.deleteExpired(NOW, NOW.minus(Duration.ofDays(7))));

        assertEquals(List.of(hash(4), hash(5)),
                jdbc.queryForList("SELECT token_hash FROM refresh_tokens ORDER BY token_hash", String.class));
    }

    // ------------------------------------------------------------------
    // Bloqueo pesimista: concurrencia real
    // ------------------------------------------------------------------

    /**
     * El caso que justifica el bloqueo: mientras la transacción A tiene el token
     * bloqueado (y aún no ha confirmado la rotación), la B que pide el mismo
     * token <b>espera</b> (se comprueba en {@code pg_stat_activity}: está
     * esperando un bloqueo) y, cuando A confirma, lee el token <b>ya
     * revocado</b>. Así el servicio nunca rota dos veces el mismo token.
     */
    @Test
    void unSegundoRefreshDelMismoTokenEsperaAlPrimeroYLoVeRevocado() throws Exception {
        save(alice, hash(1), UUID.randomUUID(), NOW, NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch aHasLock = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        try {
            Future<?> a = pool.submit(() -> tx.executeWithoutResult(status -> {
                RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(hash(1)).orElseThrow();
                aHasLock.countDown();
                await(releaseA);
                token.setRevokedAt(NOW);
            }));
            assertTrue(aHasLock.await(10, TimeUnit.SECONDS), "A no llegó a bloquear el token");

            Future<Instant> b = pool.submit(() -> tx.execute(status ->
                    refreshTokenRepository.findByTokenHashForUpdate(hash(1)).orElseThrow().getRevokedAt()));

            awaitBackendWaitingForLock();
            assertFalse(b.isDone(), "B no debe avanzar mientras A tiene el bloqueo");

            releaseA.countDown();
            a.get(10, TimeUnit.SECONDS);
            assertEquals(NOW, b.get(10, TimeUnit.SECONDS), "B debe leer el token ya revocado por A");
        } finally {
            releaseA.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * Control: una lectura <b>sin</b> bloqueo no espera y ve el token todavía
     * activo mientras A no confirma. Es exactamente la carrera que tendría el
     * servicio sin {@code findByTokenHashForUpdate}: las dos peticiones creerían
     * que el token sigue sin usar.
     */
    @Test
    void sinBloqueoLaSegundaLecturaNoEsperaYVeElTokenActivo() throws Exception {
        save(alice, hash(1), UUID.randomUUID(), NOW, NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch aHasLock = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        try {
            Future<?> a = pool.submit(() -> tx.executeWithoutResult(status -> {
                RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(hash(1)).orElseThrow();
                token.setRevokedAt(NOW);
                refreshTokenRepository.flush(); // la fila ya está modificada, pero sin confirmar
                aHasLock.countDown();
                await(releaseA);
            }));
            assertTrue(aHasLock.await(10, TimeUnit.SECONDS));

            Future<Object> plainRead = pool.submit(() -> tx.execute(status -> jdbc.queryForObject(
                    "SELECT revoked_at FROM refresh_tokens WHERE token_hash = ?", OffsetDateTime.class, hash(1))));

            assertNull(plainRead.get(10, TimeUnit.SECONDS), "la lectura normal no espera y ve el token activo");
            releaseA.countDown();
            a.get(10, TimeUnit.SECONDS);
        } finally {
            releaseA.countDown();
            pool.shutdownNow();
        }
    }

    /**
     * Rotación completa en dos hilos que arrancan a la vez con el mismo token,
     * con la lógica que tendrá el servicio (bloquear, comprobar, revocar y crear
     * el sucesor): exactamente uno rota y el otro ve el token revocado. La
     * familia acaba con un solo token activo.
     */
    @Test
    void dosRotacionesSimultaneasDelMismoTokenSoloRotanUnaVez() throws Exception {
        UUID family = UUID.randomUUID();
        save(alice, hash(1), family, NOW, NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        try {
            List<Future<Boolean>> results = List.of(
                    pool.submit(() -> rotate(start, hash(1), hash(2))),
                    pool.submit(() -> rotate(start, hash(1), hash(3))));

            int rotated = 0;
            for (Future<Boolean> result : results) {
                rotated += result.get(20, TimeUnit.SECONDS) ? 1 : 0;
            }

            assertEquals(1, rotated, "solo una de las dos peticiones puede rotar");
            assertEquals(2, count());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens"
                    + " WHERE family_id = ? AND revoked_at IS NULL", Integer.class, family));
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Lo que hará el servicio en un refresh: {@code true} si ha rotado, {@code false} si ya estaba revocado. */
    private boolean rotate(CyclicBarrier start, String presented, String successorHash) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        return tx.execute(status -> {
            RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(presented).orElseThrow();
            if (token.getRevokedAt() != null) {
                return false;
            }
            RefreshToken next = refreshTokenRepository.save(newToken(token.getUser(), successorHash,
                    token.getFamilyId(), NOW.plusSeconds(1), NOW.plus(Duration.ofDays(1)), token.getFamilyExpiresAt()));
            token.setRevokedAt(NOW.plusSeconds(1));
            token.setReplacedBy(next);
            return true;
        });
    }

    /** Espera (máx. 10 s) a que alguna conexión de esta base esté bloqueada esperando una fila de refresh_tokens. */
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

    private void assertCheck(String constraint, Runnable statement) {
        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class, statement::run);
        assertEquals(CHECK_VIOLATION, sqlState(error), constraint);
        assertTrue(error.getMessage().contains(constraint), error.getMessage());
    }

    /** Hash de ejemplo válido (64 caracteres hexadecimales) distinto para cada número. */
    private static String hash(int n) {
        return String.format("%064x", n);
    }

    private long insert(long userId, String hash, UUID family, Instant createdAt, Instant expiresAt,
            Instant familyExpiresAt) {
        return jdbc.queryForObject("INSERT INTO refresh_tokens (user_id, token_hash, family_id, created_at,"
                        + " expires_at, family_expires_at) VALUES (?, ?, ?, ?, ?, ?) RETURNING id", Long.class,
                userId, hash, family, utc(createdAt), utc(expiresAt), utc(familyExpiresAt));
    }

    private RefreshToken save(User user, String hash, UUID family, Instant createdAt, Instant expiresAt,
            Instant familyExpiresAt) {
        return refreshTokenRepository.save(newToken(user, hash, family, createdAt, expiresAt, familyExpiresAt));
    }

    private static RefreshToken newToken(User user, String hash, UUID family, Instant createdAt, Instant expiresAt,
            Instant familyExpiresAt) {
        RefreshToken token = new RefreshToken();
        token.setUser(user);
        token.setTokenHash(hash);
        token.setFamilyId(family);
        token.setCreatedAt(createdAt);
        token.setExpiresAt(expiresAt);
        token.setFamilyExpiresAt(familyExpiresAt);
        return token;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private int count() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens", Integer.class);
    }
}
