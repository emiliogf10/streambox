package com.emilio.streambox.repository;

import static com.emilio.streambox.entity.AccountTokenPurpose.EMAIL_VERIFICATION;
import static com.emilio.streambox.entity.AccountTokenPurpose.PASSWORD_RESET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.AccountToken;
import com.emilio.streambox.entity.AccountTokenPurpose;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;

/**
 * Consultas de {@link AccountTokenRepository} y de verificación de
 * {@link UserRepository} ({@code markEmailVerified},
 * {@code deleteIfEmailNotVerified}) contra el esquema real de Flyway (H2).
 *
 * <p>
 * Como {@link RefreshTokenRepositoryIntegrationTest}: los datos se confirman de
 * verdad (sin {@code @Transactional} en la clase) y se limpian antes y después
 * de cada test; las escrituras van en un {@link TransactionTemplate}, como en
 * un servicio. La concurrencia real del consumo (dos hilos, el mismo token) se
 * prueba contra PostgreSQL en {@code PostgresAccountTokenIntegrationTest}.
 * </p>
 *
 * <p>
 * Desde la {@code V10} hay dos propósitos ({@code EMAIL_VERIFICATION} y
 * {@code PASSWORD_RESET}); el apartado «Propósitos» comprueba que cada
 * consulta solo ve los tokens del suyo: un enlace de verificación nunca sirve
 * para cambiar la contraseña, ni gastarlo por error con el propósito
 * equivocado lo inutiliza.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountTokenRepositoryIntegrationTest {

    /** Instante fijo (como el {@code Clock} de un test del servicio). */
    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");
    private static final Duration TTL = Duration.ofHours(24);
    /** Caducidad de un enlace de restablecimiento (la del contrato: 1 hora). */
    private static final Duration RESET_TTL = Duration.ofHours(1);

    @Autowired private AccountTokenRepository accountTokenRepository;
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

    // ------------------------------------------------------------------
    // Mapeo
    // ------------------------------------------------------------------

    /** Todos los campos se guardan y se leen igual; el propósito se guarda por su nombre. */
    @Test
    void guardaYLeeTodosLosCampos() {
        AccountToken saved = save(alice, hash(1), NOW, NOW.plus(TTL));

        AccountToken read = tx.execute(status -> {
            AccountToken token = accountTokenRepository.findByTokenHashAndPurpose(hash(1), EMAIL_VERIFICATION)
                    .orElseThrow();
            assertEquals(alice.getId(), token.getUser().getId());
            return token;
        });

        assertEquals(saved.getId(), read.getId());
        assertEquals(EMAIL_VERIFICATION, read.getPurpose());
        assertEquals(hash(1), read.getTokenHash());
        assertEquals(NOW, read.getCreatedAt());
        assertEquals(NOW.plus(TTL), read.getExpiresAt());
        assertNull(read.getUsedAt());
        assertEquals("EMAIL_VERIFICATION", jdbc.queryForObject("SELECT purpose FROM account_tokens", String.class));
        assertTrue(accountTokenRepository.findByTokenHashAndPurpose(hash(2), EMAIL_VERIFICATION).isEmpty());
    }

    @Test
    void elToStringNoMuestraElHash() {
        AccountToken token = save(alice, hash(7), NOW, NOW.plus(TTL));

        assertFalse(token.toString().contains(hash(7)), token.toString());
    }

    /** Una cuenta creada sin indicar la fecha nace pendiente; {@code isEmailVerified} lee esa fecha. */
    @Test
    void unaCuentaNuevaNacePendienteDeVerificar() {
        User read = userRepository.findById(alice.getId()).orElseThrow();

        assertNull(read.getEmailVerifiedAt());
        assertFalse(read.isEmailVerified());
        read.setEmailVerifiedAt(NOW);
        assertTrue(read.isEmailVerified());
    }

    // ------------------------------------------------------------------
    // Consumo atómico
    // ------------------------------------------------------------------

    /** El flujo completo del servicio: consumir, obtener la cuenta y marcarla verificada. */
    @Test
    void consumirUnTokenVigenteLoGastaYPermiteVerificarLaCuenta() {
        save(alice, hash(1), NOW, NOW.plus(TTL));
        Instant clickAt = NOW.plus(Duration.ofHours(2));

        Optional<Long> verified = tx.execute(status -> {
            if (accountTokenRepository.consume(hash(1), EMAIL_VERIFICATION, clickAt) != 1) {
                return Optional.<Long>empty();
            }
            Long userId = accountTokenRepository.findUserIdByTokenHashAndPurpose(hash(1), EMAIL_VERIFICATION)
                    .orElseThrow();
            assertEquals(1, userRepository.markEmailVerified(userId, clickAt));
            return Optional.of(userId);
        });

        assertEquals(Optional.of(alice.getId()), verified);
        assertEquals(clickAt, usedAt(hash(1)));
        assertEquals(clickAt, userRepository.findById(alice.getId()).orElseThrow().getEmailVerifiedAt());
        assertNull(userRepository.findById(bob.getId()).orElseThrow().getEmailVerifiedAt(), "bob sigue pendiente");
    }

    /** Un token ya consumido no vale una segunda vez y conserva la fecha del primer uso. */
    @Test
    void unTokenYaUsadoNoSePuedeVolverAConsumir() {
        save(alice, hash(1), NOW, NOW.plus(TTL));

        assertEquals(1, consume(hash(1), NOW.plusSeconds(10)));
        assertEquals(0, consume(hash(1), NOW.plusSeconds(20)));

        assertEquals(NOW.plusSeconds(10), usedAt(hash(1)));
    }

    /** Un token caducado no se consume, tampoco justo en el instante de caducar (el límite es exclusivo). */
    @Test
    void unTokenCaducadoNoSeConsume() {
        save(alice, hash(1), NOW.minus(TTL), NOW);
        save(alice, hash(2), NOW.minus(TTL).minusSeconds(1), NOW.minusSeconds(1));
        save(alice, hash(3), NOW.minus(TTL).plusSeconds(1), NOW.plusSeconds(1));

        assertEquals(0, consume(hash(1), NOW), "caduca justo ahora");
        assertEquals(0, consume(hash(2), NOW), "caducó hace un segundo");
        assertEquals(1, consume(hash(3), NOW), "le queda un segundo");
        assertNull(usedAt(hash(1)));
        assertNull(usedAt(hash(2)));
    }

    /** Un hash que no existe no consume nada ni toca los demás tokens. */
    @Test
    void unTokenInexistenteNoConsumeNada() {
        save(alice, hash(1), NOW, NOW.plus(TTL));

        assertEquals(0, consume(hash(2), NOW));
        assertTrue(tx.execute(status ->
                accountTokenRepository.findUserIdByTokenHashAndPurpose(hash(2), EMAIL_VERIFICATION)).isEmpty());
        assertNull(usedAt(hash(1)));
    }

    /** Un token invalidado (al reenviar el correo) ya no se puede consumir. */
    @Test
    void unTokenInvalidadoNoSeConsume() {
        save(alice, hash(1), NOW, NOW.plus(TTL));
        tx.executeWithoutResult(status ->
                accountTokenRepository.invalidateActiveTokens(alice.getId(), EMAIL_VERIFICATION, NOW.plusSeconds(5)));

        assertEquals(0, consume(hash(1), NOW.plusSeconds(10)));
    }

    /**
     * El consumo exige la transacción del servicio: así el token y la
     * verificación de la cuenta se confirman o se deshacen juntos.
     */
    @Test
    void elConsumoExigeUnaTransaccion() {
        save(alice, hash(1), NOW, NOW.plus(TTL));

        assertThrows(IllegalTransactionStateException.class,
                () -> accountTokenRepository.consume(hash(1), EMAIL_VERIFICATION, NOW));
        assertNull(usedAt(hash(1)));
    }

    /** Si la transacción del servicio falla tras consumir, el token vuelve a estar disponible. */
    @Test
    void siLaTransaccionSeDeshaceElTokenNoQuedaGastado() {
        save(alice, hash(1), NOW, NOW.plus(TTL));

        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            accountTokenRepository.consume(hash(1), EMAIL_VERIFICATION, NOW);
            throw new IllegalStateException("fallo después de consumir");
        }));

        assertNull(usedAt(hash(1)));
        assertEquals(1, consume(hash(1), NOW));
    }

    // ------------------------------------------------------------------
    // Reenvío: invalidar, «¿tiene uno vigente?» y contar
    // ------------------------------------------------------------------

    /**
     * Invalidar solo toca los vigentes de esa cuenta: los ya usados conservan
     * su fecha, los caducados no se tocan y los de otra cuenta tampoco.
     */
    @Test
    void invalidarSoloTocaLosTokensVigentesDeLaCuenta() {
        save(alice, hash(1), NOW, NOW.plus(TTL));
        save(alice, hash(2), NOW, NOW.plus(TTL));
        save(alice, hash(3), NOW.minus(TTL).minusSeconds(1), NOW.minusSeconds(1));
        save(alice, hash(4), NOW.minusSeconds(60), NOW.plus(TTL));
        assertEquals(1, consume(hash(4), NOW.minusSeconds(30)));
        save(bob, hash(5), NOW, NOW.plus(TTL));

        int invalidated = tx.execute(status ->
                accountTokenRepository.invalidateActiveTokens(alice.getId(), EMAIL_VERIFICATION, NOW));

        assertEquals(2, invalidated);
        assertEquals(NOW, usedAt(hash(1)));
        assertEquals(NOW, usedAt(hash(2)));
        assertNull(usedAt(hash(3)), "el caducado no se toca");
        assertEquals(NOW.minusSeconds(30), usedAt(hash(4)), "el usado conserva su fecha");
        assertNull(usedAt(hash(5)), "el de otra cuenta sigue vigente");
        assertEquals(0, (int) tx.execute(status ->
                accountTokenRepository.invalidateActiveTokens(alice.getId(), EMAIL_VERIFICATION, NOW)), "idempotente");
    }

    /** «¿Tiene un token vigente?»: no, si todos están usados, invalidados o caducados. */
    @Test
    void tieneTokenVigenteSoloSiHayUnoSinUsarNiCaducar() {
        assertFalse(hasActive(alice, NOW), "sin tokens");

        save(alice, hash(1), NOW.minus(TTL), NOW);
        assertFalse(hasActive(alice, NOW), "caducado justo ahora");

        save(alice, hash(2), NOW, NOW.plus(TTL));
        assertTrue(hasActive(alice, NOW));
        assertFalse(hasActive(bob, NOW), "los de alice no cuentan para bob");
        assertFalse(hasActive(alice, NOW.plus(TTL)), "cuando caduca, deja de estar vigente");

        assertEquals(1, consume(hash(2), NOW.plusSeconds(1)));
        assertFalse(hasActive(alice, NOW.plusSeconds(2)), "usado");
    }

    /** Se cuentan los emitidos en la ventana, usados o no, de esa cuenta. */
    @Test
    void cuentaLosTokensEmitidosDesdeUnInstante() {
        save(alice, hash(1), NOW.minus(Duration.ofHours(2)), NOW.plus(TTL));
        save(alice, hash(2), NOW.minus(Duration.ofMinutes(30)), NOW.plus(TTL));
        save(alice, hash(3), NOW.minus(Duration.ofMinutes(10)), NOW.plus(TTL));
        assertEquals(1, consume(hash(3), NOW));
        save(bob, hash(4), NOW.minus(Duration.ofMinutes(5)), NOW.plus(TTL));

        assertEquals(2, accountTokenRepository.countIssuedSince(alice.getId(), EMAIL_VERIFICATION,
                NOW.minus(Duration.ofHours(1))));
        assertEquals(3, accountTokenRepository.countIssuedSince(alice.getId(), EMAIL_VERIFICATION,
                NOW.minus(Duration.ofDays(1))));
        assertEquals(0, accountTokenRepository.countIssuedSince(alice.getId(), EMAIL_VERIFICATION, NOW));
    }

    // ------------------------------------------------------------------
    // Propósitos (V10): cada consulta solo ve los tokens del suyo
    // ------------------------------------------------------------------

    /** El propósito se guarda y se lee por su nombre también para {@code PASSWORD_RESET}. */
    @Test
    void guardaYLeeUnTokenDeRestablecimiento() {
        save(alice, PASSWORD_RESET, hash(1), NOW, NOW.plus(RESET_TTL));

        AccountToken read = accountTokenRepository.findByTokenHashAndPurpose(hash(1), PASSWORD_RESET).orElseThrow();

        assertEquals(PASSWORD_RESET, read.getPurpose());
        assertEquals("PASSWORD_RESET", jdbc.queryForObject("SELECT purpose FROM account_tokens", String.class));
        assertTrue(accountTokenRepository.findByTokenHashAndPurpose(hash(1), EMAIL_VERIFICATION).isEmpty());
    }

    /**
     * Presentar un token con el propósito equivocado no lo consume ni revela
     * su cuenta, en las dos direcciones; con el suyo, después, sí vale. Es lo
     * que impide usar un enlace de verificación para cambiar la contraseña.
     */
    @Test
    void consumirConElPropositoEquivocadoNoGastaElToken() {
        save(alice, EMAIL_VERIFICATION, hash(1), NOW, NOW.plus(TTL));
        save(alice, PASSWORD_RESET, hash(2), NOW, NOW.plus(RESET_TTL));

        assertEquals(0, consume(hash(1), PASSWORD_RESET, NOW));
        assertEquals(0, consume(hash(2), EMAIL_VERIFICATION, NOW));
        assertTrue(tx.execute(status ->
                accountTokenRepository.findUserIdByTokenHashAndPurpose(hash(1), PASSWORD_RESET)).isEmpty());
        assertTrue(tx.execute(status ->
                accountTokenRepository.findUserIdByTokenHashAndPurpose(hash(2), EMAIL_VERIFICATION)).isEmpty());
        assertNull(usedAt(hash(1)), "el de verificación sigue sin usar");
        assertNull(usedAt(hash(2)), "el de restablecimiento sigue sin usar");

        assertEquals(1, consume(hash(1), EMAIL_VERIFICATION, NOW.plusSeconds(1)));
        assertEquals(1, consume(hash(2), PASSWORD_RESET, NOW.plusSeconds(2)));
        assertEquals(Optional.of(alice.getId()), tx.execute(status ->
                accountTokenRepository.findUserIdByTokenHashAndPurpose(hash(2), PASSWORD_RESET)));
    }

    /**
     * Pedir otro enlace de un tipo invalida solo los de ese tipo: reenviar la
     * verificación no anula un restablecimiento en curso, ni al revés.
     */
    @Test
    void invalidarLosDeUnPropositoNoTocaLosDelOtro() {
        save(alice, EMAIL_VERIFICATION, hash(1), NOW, NOW.plus(TTL));
        save(alice, PASSWORD_RESET, hash(2), NOW, NOW.plus(RESET_TTL));
        save(alice, PASSWORD_RESET, hash(3), NOW, NOW.plus(RESET_TTL));

        assertEquals(2, (int) tx.execute(status ->
                accountTokenRepository.invalidateActiveTokens(alice.getId(), PASSWORD_RESET, NOW.plusSeconds(5))));
        assertNull(usedAt(hash(1)), "el de verificación sigue vigente");
        assertEquals(NOW.plusSeconds(5), usedAt(hash(2)));
        assertEquals(NOW.plusSeconds(5), usedAt(hash(3)));

        save(alice, PASSWORD_RESET, hash(4), NOW.plusSeconds(10), NOW.plusSeconds(10).plus(RESET_TTL));
        assertEquals(1, (int) tx.execute(status ->
                accountTokenRepository.invalidateActiveTokens(alice.getId(), EMAIL_VERIFICATION, NOW.plusSeconds(20))));
        assertEquals(NOW.plusSeconds(20), usedAt(hash(1)));
        assertNull(usedAt(hash(4)), "el de restablecimiento sigue vigente");
    }

    /**
     * «¿Tiene uno vigente?», el recuento de la ventana y «¿se le emitió alguno
     * hasta…?» son por propósito: un restablecimiento pedido por una cuenta
     * pendiente no la hace parecer «con su enlace de verificación vigente» ni
     * «creada por el registro» (lo que decide si el registro puede sustituirla),
     * ni gasta el cupo de reenvíos de la verificación.
     */
    @Test
    void vigenteRecuentoYEmisionSonPorProposito() {
        save(alice, PASSWORD_RESET, hash(1), NOW.minusSeconds(60), NOW.minusSeconds(60).plus(RESET_TTL));
        save(alice, PASSWORD_RESET, hash(2), NOW.minusSeconds(30), NOW.minusSeconds(30).plus(RESET_TTL));
        save(alice, EMAIL_VERIFICATION, hash(3), NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(1)));

        assertTrue(accountTokenRepository.hasActiveToken(alice.getId(), PASSWORD_RESET, NOW));
        assertFalse(accountTokenRepository.hasActiveToken(alice.getId(), EMAIL_VERIFICATION, NOW),
                "su enlace de verificación caducó; el de restablecimiento no cuenta");
        assertEquals(2, accountTokenRepository.countIssuedSince(alice.getId(), PASSWORD_RESET, NOW.minus(RESET_TTL)));
        assertEquals(0, accountTokenRepository.countIssuedSince(alice.getId(), EMAIL_VERIFICATION, NOW.minus(RESET_TTL)));

        save(bob, PASSWORD_RESET, hash(4), NOW.minusSeconds(10), NOW.minusSeconds(10).plus(RESET_TTL));
        assertFalse(accountTokenRepository.existsByUser_IdAndPurposeAndCreatedAtLessThanEqual(
                bob.getId(), EMAIL_VERIFICATION, NOW), "un restablecimiento no es un primer enlace de verificación");
        assertTrue(accountTokenRepository.existsByUser_IdAndPurposeAndCreatedAtLessThanEqual(
                bob.getId(), PASSWORD_RESET, NOW));
    }

    /**
     * {@code invalidateAllActiveTokens}: al cambiar la contraseña caen los
     * vigentes de la cuenta de <b>los dos</b> propósitos; los usados conservan
     * su fecha, los caducados no se tocan y los de otra cuenta tampoco.
     */
    @Test
    void invalidarTodosLosVigentesDeLaCuentaAbarcaLosDosPropositos() {
        save(alice, EMAIL_VERIFICATION, hash(1), NOW, NOW.plus(TTL));
        save(alice, PASSWORD_RESET, hash(2), NOW, NOW.plus(RESET_TTL));
        save(alice, PASSWORD_RESET, hash(3), NOW.minusSeconds(60), NOW.minusSeconds(60).plus(RESET_TTL));
        assertEquals(1, consume(hash(3), PASSWORD_RESET, NOW.minusSeconds(30)));
        save(alice, PASSWORD_RESET, hash(4), NOW.minus(RESET_TTL).minusSeconds(1), NOW.minusSeconds(1));
        save(bob, PASSWORD_RESET, hash(5), NOW, NOW.plus(RESET_TTL));
        save(bob, EMAIL_VERIFICATION, hash(6), NOW, NOW.plus(TTL));

        int invalidated = tx.execute(status -> accountTokenRepository.invalidateAllActiveTokens(alice.getId(), NOW));

        assertEquals(2, invalidated);
        assertEquals(NOW, usedAt(hash(1)));
        assertEquals(NOW, usedAt(hash(2)));
        assertEquals(NOW.minusSeconds(30), usedAt(hash(3)), "el usado conserva su fecha");
        assertNull(usedAt(hash(4)), "el caducado no se toca");
        assertNull(usedAt(hash(5)), "los de otra cuenta siguen vigentes");
        assertNull(usedAt(hash(6)), "los de otra cuenta siguen vigentes");
        assertFalse(accountTokenRepository.hasActiveToken(alice.getId(), EMAIL_VERIFICATION, NOW));
        assertFalse(accountTokenRepository.hasActiveToken(alice.getId(), PASSWORD_RESET, NOW));
        assertEquals(0, (int) tx.execute(status -> accountTokenRepository.invalidateAllActiveTokens(alice.getId(), NOW)),
                "idempotente");
    }

    /** La limpieza borra los caducados sea cual sea su propósito. */
    @Test
    void laLimpiezaBorraLosCaducadosDeLosDosPropositos() {
        save(alice, EMAIL_VERIFICATION, hash(1), NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofDays(2)));
        save(alice, PASSWORD_RESET, hash(2), NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofDays(3)).plus(RESET_TTL));
        save(alice, PASSWORD_RESET, hash(3), NOW, NOW.plus(RESET_TTL));

        assertEquals(2, accountTokenRepository.deleteExpired(NOW.minus(Duration.ofDays(1))));
        assertEquals(List.of(hash(3)), jdbc.queryForList("SELECT token_hash FROM account_tokens", String.class));
    }

    // ------------------------------------------------------------------
    // Limpieza y cascada
    // ------------------------------------------------------------------

    @Test
    void laLimpiezaBorraLosCaducadosAntesDelLimiteUsadosONo() {
        save(alice, hash(1), NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofDays(2)));
        AccountToken usedOld = save(alice, hash(2), NOW.minus(Duration.ofDays(3)), NOW.minus(Duration.ofDays(2)));
        jdbc.update("UPDATE account_tokens SET used_at = ? WHERE id = ?",
                java.sql.Timestamp.from(NOW.minus(Duration.ofDays(3)).plusSeconds(60)), usedOld.getId());
        save(alice, hash(3), NOW.minus(TTL).minusSeconds(60), NOW.minusSeconds(60));
        save(bob, hash(4), NOW, NOW.plus(TTL));

        int deleted = accountTokenRepository.deleteExpired(NOW.minus(Duration.ofDays(1)));

        assertEquals(2, deleted);
        assertEquals(List.of(hash(3), hash(4)),
                jdbc.queryForList("SELECT token_hash FROM account_tokens ORDER BY token_hash", String.class));
    }

    /** Borrar la cuenta con JPA (como la limpieza de los tests o un borrado de cuenta) arrastra sus tokens. */
    @Test
    void borrarLaCuentaBorraSusTokens() {
        save(alice, hash(1), NOW, NOW.plus(TTL));
        save(bob, hash(2), NOW, NOW.plus(TTL));

        userRepository.deleteById(alice.getId());

        assertEquals(List.of(hash(2)), jdbc.queryForList("SELECT token_hash FROM account_tokens", String.class));
    }

    // ------------------------------------------------------------------
    // UserRepository: marcar verificada y sustituir una cuenta pendiente
    // ------------------------------------------------------------------

    /** Marcar como verificada es idempotente y no pisa la fecha de la primera verificación. */
    @Test
    void marcarVerificadaEsIdempotenteYConservaLaPrimeraFecha() {
        assertEquals(1, (int) tx.execute(status -> userRepository.markEmailVerified(alice.getId(), NOW)));
        assertEquals(0, (int) tx.execute(status -> userRepository.markEmailVerified(alice.getId(), NOW.plusSeconds(60))));
        assertEquals(0, (int) tx.execute(status -> userRepository.markEmailVerified(987654L, NOW)), "no existe");

        assertEquals(NOW, userRepository.findById(alice.getId()).orElseThrow().getEmailVerifiedAt());
        assertNull(userRepository.findById(bob.getId()).orElseThrow().getEmailVerifiedAt());
    }

    /**
     * Por {@code @DynamicUpdate}, guardar otro cambio de una entidad cargada
     * antes de la verificación no devuelve la fecha a {@code null}.
     */
    @Test
    void guardarOtroCambioDeUnUsuarioCargadoAntesNoDeshaceLaVerificacion() {
        tx.executeWithoutResult(status -> {
            User loaded = userRepository.findById(alice.getId()).orElseThrow();
            assertEquals(1, userRepository.markEmailVerified(alice.getId(), NOW));
            loaded.setAvatar("rocket");
        });

        User read = userRepository.findById(alice.getId()).orElseThrow();
        assertEquals("rocket", read.getAvatar());
        assertEquals(NOW, read.getEmailVerifiedAt());
    }

    /** Solo se borra una cuenta pendiente (con sus tokens); una verificada nunca. */
    @Test
    void soloSeBorraUnaCuentaPendienteYSeLlevaSusTokens() {
        save(alice, hash(1), NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(1)));
        save(bob, hash(2), NOW, NOW.plus(TTL));
        tx.executeWithoutResult(status -> userRepository.markEmailVerified(bob.getId(), NOW));

        assertEquals(1, (int) tx.execute(status -> userRepository.deleteIfEmailNotVerified(alice.getId())));
        assertEquals(0, (int) tx.execute(status -> userRepository.deleteIfEmailNotVerified(bob.getId())),
                "una cuenta verificada no se borra");

        assertTrue(userRepository.findById(alice.getId()).isEmpty());
        assertTrue(userRepository.findById(bob.getId()).isPresent());
        assertEquals(List.of(hash(2)), jdbc.queryForList("SELECT token_hash FROM account_tokens", String.class));
    }

    /**
     * Sustituir una cuenta pendiente en una sola transacción, como hará el
     * registro: borrarla y crear otra con el mismo email y nombre. El borrado
     * vacía la sesión, así que la entidad antigua no estorba al guardar la nueva.
     */
    @Test
    void sePuedeSustituirUnaCuentaPendienteEnLaMismaTransaccion() {
        Long newId = tx.execute(status -> {
            User old = userRepository.findByEmail("alice@test.com").orElseThrow();
            assertEquals(1, userRepository.deleteIfEmailNotVerified(old.getId()));
            return userRepository.save(newUser("alice")).getId();
        });

        assertEquals(List.of(newId), jdbc.queryForList("SELECT id FROM users WHERE email = 'alice@test.com'", Long.class));
        assertFalse(newId.equals(alice.getId()));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Hash de ejemplo válido (64 caracteres hexadecimales) distinto para cada número. */
    private static String hash(int n) {
        return String.format("%064x", n);
    }

    private int consume(String hash, Instant now) {
        return consume(hash, EMAIL_VERIFICATION, now);
    }

    private int consume(String hash, AccountTokenPurpose purpose, Instant now) {
        return tx.execute(status -> accountTokenRepository.consume(hash, purpose, now));
    }

    private boolean hasActive(User user, Instant now) {
        return accountTokenRepository.hasActiveToken(user.getId(), EMAIL_VERIFICATION, now);
    }

    private AccountToken save(User user, String hash, Instant createdAt, Instant expiresAt) {
        return save(user, EMAIL_VERIFICATION, hash, createdAt, expiresAt);
    }

    private AccountToken save(User user, AccountTokenPurpose purpose, String hash, Instant createdAt, Instant expiresAt) {
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

    private User saveUser(String name) {
        return userRepository.save(newUser(name));
    }

    private static User newUser(String name) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword("hash-no-usado");
        user.setRole(Role.USER);
        return user;
    }

    private void cleanDatabase() {
        // Los tokens caen en cascada al borrar los usuarios (fk_account_tokens_user)
        userRepository.deleteAll();
    }
}
