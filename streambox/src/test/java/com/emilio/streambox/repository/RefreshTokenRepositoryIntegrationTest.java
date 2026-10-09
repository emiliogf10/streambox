package com.emilio.streambox.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.RefreshToken;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;

/**
 * Consultas de {@link RefreshTokenRepository} y mapeo de
 * {@link RefreshToken} contra el esquema real de Flyway (H2).
 *
 * <p>
 * Como {@link SeriesRepositoryIntegrationTest}, los datos se confirman de
 * verdad (sin {@code @Transactional} en la clase) y se limpian antes y después
 * de cada test; las escrituras van en un {@link TransactionTemplate}, como en
 * un servicio. El bloqueo pesimista y su concurrencia real se prueban contra
 * PostgreSQL en {@code PostgresRefreshTokenIntegrationTest}: H2 no reproduce
 * fielmente los bloqueos de fila de PostgreSQL.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
class RefreshTokenRepositoryIntegrationTest {

    /** Instante fijo (como el {@code Clock} de un test del servicio), truncado a microsegundos. */
    private static final Instant NOW = Instant.parse("2026-10-08T10:00:00Z");

    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        cleanDatabase();
        alice = saveUser("alice");
        bob = saveUser("bob");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    /** Todos los campos se guardan y se leen igual (UUID nativo, instantes con zona). */
    @Test
    void guardaYLeeTodosLosCampos() {
        UUID family = UUID.randomUUID();
        RefreshToken saved = save(alice, hash(1), family, NOW, NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)));

        RefreshToken read = tx.execute(status -> {
            RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(hash(1)).orElseThrow();
            assertEquals(alice.getId(), token.getUser().getId());
            return token;
        });

        assertEquals(saved.getId(), read.getId());
        assertEquals(hash(1), read.getTokenHash());
        assertEquals(family, read.getFamilyId());
        assertEquals(NOW, read.getCreatedAt());
        assertEquals(NOW.plus(Duration.ofDays(1)), read.getExpiresAt());
        assertEquals(NOW.plus(Duration.ofDays(30)), read.getFamilyExpiresAt());
        assertNull(read.getRevokedAt());
        assertNull(read.getReplacedBy());
        assertEquals(family, jdbc.queryForObject("SELECT family_id FROM refresh_tokens", UUID.class));
    }

    @Test
    void buscarUnHashInexistenteDevuelveVacio() {
        save(alice, hash(1), UUID.randomUUID());

        assertTrue(tx.execute(status -> refreshTokenRepository.findByTokenHashForUpdate(hash(2))).isEmpty());
    }

    /** El bloqueo exige una transacción: sin ella el error es inmediato, no un bloqueo que no bloquea. */
    @Test
    void laBusquedaConBloqueoExigeUnaTransaccion() {
        save(alice, hash(1), UUID.randomUUID());

        assertThrows(IllegalTransactionStateException.class,
                () -> refreshTokenRepository.findByTokenHashForUpdate(hash(1)));
    }

    /** Rotación tal como la hará el servicio: revocar el viejo y enlazarlo con el nuevo. */
    @Test
    void laRotacionEnlazaElTokenViejoConElNuevo() {
        UUID family = UUID.randomUUID();
        save(alice, hash(1), family);

        tx.executeWithoutResult(status -> {
            RefreshToken old = refreshTokenRepository.findByTokenHashForUpdate(hash(1)).orElseThrow();
            RefreshToken next = refreshTokenRepository.save(newToken(old.getUser(), hash(2), family, NOW.plusSeconds(60),
                    NOW.plus(Duration.ofDays(1)).plusSeconds(60), old.getFamilyExpiresAt()));
            old.setRevokedAt(NOW.plusSeconds(60));
            old.setReplacedBy(next);
        });

        Long nextId = jdbc.queryForObject("SELECT id FROM refresh_tokens WHERE token_hash = ?", Long.class, hash(2));
        assertEquals(nextId, jdbc.queryForObject(
                "SELECT replaced_by_id FROM refresh_tokens WHERE token_hash = ?", Long.class, hash(1)));
    }

    /** Un enlace con sucesor pero sin revocar lo rechaza la base de datos (CHECK), no pasa en silencio. */
    @Test
    void enlazarSinRevocarFallaEnLaBaseDeDatos() {
        UUID family = UUID.randomUUID();
        save(alice, hash(1), family);
        RefreshToken next = save(alice, hash(2), family);

        assertThrows(DataIntegrityViolationException.class, () -> tx.executeWithoutResult(status -> {
            RefreshToken old = refreshTokenRepository.findByTokenHashForUpdate(hash(1)).orElseThrow();
            old.setReplacedBy(refreshTokenRepository.getReferenceById(next.getId()));
        }));
    }

    @Test
    void revocarUnaFamiliaSoloTocaSusTokensActivos() {
        UUID family = UUID.randomUUID();
        UUID otherFamily = UUID.randomUUID();
        Instant earlier = NOW.minusSeconds(3600);
        save(alice, hash(1), family);
        save(alice, hash(2), family);
        RefreshToken alreadyRevoked = save(alice, hash(3), family);
        jdbc.update("UPDATE refresh_tokens SET revoked_at = ? WHERE id = ?",
                java.sql.Timestamp.from(earlier), alreadyRevoked.getId());
        save(alice, hash(4), otherFamily);
        save(bob, hash(5), UUID.randomUUID());

        int revoked = tx.execute(status -> refreshTokenRepository.revokeFamily(family, NOW));

        assertEquals(2, revoked);
        assertEquals(List.of(hash(1), hash(2), hash(3)), revokedHashes());
        assertEquals(earlier, revokedAt(hash(3)), "un token ya revocado conserva su fecha original");
        assertEquals(NOW, revokedAt(hash(1)));
        assertEquals(0, (int) tx.execute(status -> refreshTokenRepository.revokeFamily(family, NOW)), "idempotente");
    }

    @Test
    void revocarTodasLasDeUnUsuarioNoTocaLasDeOtros() {
        save(alice, hash(1), UUID.randomUUID());
        save(alice, hash(2), UUID.randomUUID());
        save(bob, hash(3), UUID.randomUUID());

        int revoked = tx.execute(status -> refreshTokenRepository.revokeAllByUserId(alice.getId(), NOW));

        assertEquals(2, revoked);
        assertEquals(List.of(hash(1), hash(2)), revokedHashes());
    }

    /**
     * Las revocaciones masivas escriben antes los cambios pendientes
     * ({@code flushAutomatically}) y vacían la sesión después
     * ({@code clearAutomatically}): una lectura posterior en la misma
     * transacción ve el {@code revokedAt} real, no el de la caché de Hibernate.
     */
    @Test
    void trasRevocarLaMismaTransaccionLeeElValorReal() {
        UUID family = UUID.randomUUID();
        save(alice, hash(1), family);

        Instant seen = tx.execute(status -> {
            RefreshToken token = refreshTokenRepository.findByTokenHashForUpdate(hash(1)).orElseThrow();
            assertNull(token.getRevokedAt());
            refreshTokenRepository.revokeFamily(family, NOW);
            return refreshTokenRepository.findByTokenHashForUpdate(hash(1)).orElseThrow().getRevokedAt();
        });

        assertEquals(NOW, seen);
    }

    /**
     * La limpieza borra los de familias caducadas y los caducados antes del
     * límite; conserva los rotados recientes de familias vivas. Si borra un
     * sucesor, el predecesor que sobrevive queda sin enlace pero revocado.
     */
    @Test
    void laLimpiezaBorraLosCaducadosYConservaLosRecientes() {
        Instant longAgo = NOW.minus(Duration.ofDays(40));
        // Familia muerta: tope ya pasado
        save(alice, hash(1), UUID.randomUUID(), longAgo, longAgo.plus(Duration.ofDays(1)), NOW.minusSeconds(1));
        // Familia viva, token caducado hace mucho (antes del límite)
        UUID live = UUID.randomUUID();
        RefreshToken oldExpired = save(alice, hash(2), live, longAgo, longAgo.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)));
        // Familia viva, token caducado hace poco (después del límite): se conserva para detectar reutilización
        Instant recent = NOW.minus(Duration.ofDays(2));
        RefreshToken recentExpired = save(alice, hash(3), live, recent, recent.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)));
        // Activo
        save(bob, hash(4), UUID.randomUUID(), NOW, NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)));
        // El reciente es predecesor del caducado hace mucho (caso artificial: se borra el sucesor, no el predecesor)
        jdbc.update("UPDATE refresh_tokens SET revoked_at = ?, replaced_by_id = ? WHERE id = ?",
                java.sql.Timestamp.from(recent), oldExpired.getId(), recentExpired.getId());

        int deleted = tx.execute(status -> refreshTokenRepository.deleteExpired(NOW, NOW.minus(Duration.ofDays(7))));

        assertEquals(2, deleted);
        assertEquals(List.of(hash(3), hash(4)),
                jdbc.queryForList("SELECT token_hash FROM refresh_tokens ORDER BY token_hash", String.class));
        assertNull(jdbc.queryForObject("SELECT replaced_by_id FROM refresh_tokens WHERE token_hash = ?",
                Long.class, hash(3)));
        assertNotNull(revokedAt(hash(3)));
    }

    /** Borrar un usuario con JPA (como hacen los tests y un futuro borrado de cuenta) arrastra sus tokens. */
    @Test
    void borrarElUsuarioBorraSusTokens() {
        save(alice, hash(1), UUID.randomUUID());
        save(bob, hash(2), UUID.randomUUID());

        userRepository.deleteById(alice.getId());

        assertEquals(List.of(hash(2)), jdbc.queryForList("SELECT token_hash FROM refresh_tokens", String.class));
    }

    /** Las escrituras masivas abren su propia transacción si se llaman sueltas (tarea programada). */
    @Test
    void lasEscriturasMasivasFuncionanSinTransaccionDelLlamador() {
        UUID family = UUID.randomUUID();
        save(alice, hash(1), family);

        assertEquals(1, refreshTokenRepository.revokeFamily(family, NOW));
        assertEquals(0, refreshTokenRepository.revokeAllByUserId(alice.getId(), NOW));
        assertEquals(1, refreshTokenRepository.deleteExpired(NOW.plus(Duration.ofDays(365)), NOW));
    }

    @Test
    void elToStringNoMuestraElHash() {
        RefreshToken token = save(alice, hash(7), UUID.randomUUID());

        assertTrue(!token.toString().contains(hash(7)), token.toString());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Hash de ejemplo válido (64 caracteres hexadecimales) distinto para cada número. */
    private static String hash(int n) {
        return String.format("%064x", n);
    }

    private RefreshToken save(User user, String hash, UUID family) {
        return save(user, hash, family, NOW, NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)));
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
        token.setCreatedAt(createdAt.truncatedTo(ChronoUnit.MICROS));
        token.setExpiresAt(expiresAt.truncatedTo(ChronoUnit.MICROS));
        token.setFamilyExpiresAt(familyExpiresAt.truncatedTo(ChronoUnit.MICROS));
        return token;
    }

    private List<String> revokedHashes() {
        return jdbc.queryForList(
                "SELECT token_hash FROM refresh_tokens WHERE revoked_at IS NOT NULL ORDER BY token_hash", String.class);
    }

    private Instant revokedAt(String hash) {
        java.sql.Timestamp value = jdbc.queryForObject(
                "SELECT revoked_at FROM refresh_tokens WHERE token_hash = ?", java.sql.Timestamp.class, hash);
        return value == null ? null : value.toInstant();
    }

    private User saveUser(String name) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword("hash-no-usado");
        user.setRole(Role.USER);
        return userRepository.save(user);
    }

    private void cleanDatabase() {
        // Los tokens caen en cascada al borrar los usuarios (fk_refresh_tokens_user)
        userRepository.deleteAll();
    }
}
