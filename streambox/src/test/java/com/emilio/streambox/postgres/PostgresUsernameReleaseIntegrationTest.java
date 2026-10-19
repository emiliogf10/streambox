package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.dto.AcceptedResponse;
import com.emilio.streambox.dto.CreateUserRequest;
import com.emilio.streambox.dto.UpdateProfileRequest;
import com.emilio.streambox.dto.UserResponse;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.UserAlreadyExistsException;
import com.emilio.streambox.service.RegistrationService;
import com.emilio.streambox.service.UserService;

/**
 * «Editar perfil» libera el nombre de una cuenta pendiente <b>sustituible</b>
 * (incidencia QA-2) contra <b>PostgreSQL real</b>.
 *
 * <p>
 * <b>Por qué en PostgreSQL.</b> El camino nuevo de
 * {@code UserService.updateProfile} bloquea dos filas con
 * {@code SELECT ... FOR UPDATE}, borra la pendiente con SQL nativo (que vacía
 * la sesión de Hibernate) y guarda la propia cuenta con {@code merge}. Cómo
 * espera un {@code FOR UPDATE}, qué devuelve cuando la fila se borró mientras
 * esperaba y si dos transacciones que bloquean las mismas filas en distinto
 * orden acaban en un interbloqueo ({@code 40P01}) depende del motor; H2 no
 * bloquea igual. El caso funcional sin concurrencia está en
 * {@code EmailVerificationQaIntegrationTest} (H2).
 * </p>
 *
 * <p>
 * Datos con commit real, limpiados antes y después. Sin Docker la clase se
 * omite.
 * </p>
 */
class PostgresUsernameReleaseIntegrationTest extends PostgresIntegrationTestSupport {

    private static final String PASSWORD = "Paseo-Por-El-Retiro-2026";

    @Autowired private UserService userService;
    @Autowired private RegistrationService registrationService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private ExecutorService pool;
    private CountDownLatch releaseArbiter;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        pool = Executors.newFixedThreadPool(3);
        releaseArbiter = new CountDownLatch(1);
    }

    @AfterEach
    void tearDown() {
        releaseArbiter.countDown();
        pool.shutdownNow();
        cleanDatabase();
    }

    /**
     * Cambiarse el nombre al de una pendiente sustituible: se borra (con sus
     * tokens, en cascada) y el cambio se guarda sin pisar el resto de la
     * cuenta (la contraseña y la verificación siguen como estaban).
     */
    @Test
    void elNombreDeUnaPendienteSustituibleSeLiberaYLaCuentaConservaSusDatos() {
        User stale = saveReplaceablePending("caducada_pg", "caducada@pg.test");
        User owner = saveUser("titular_pg", Role.USER);
        String passwordHash = owner.getPassword();

        UserResponse response = userService.updateProfile(owner.getId(), rename("caducada_pg"));

        assertEquals("caducada_pg", response.username());
        assertFalse(userRepository.existsById(stale.getId()), "La pendiente sustituible se borra");
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM account_tokens WHERE user_id = ?",
                Integer.class, stale.getId()));
        User after = userRepository.findById(owner.getId()).orElseThrow();
        assertEquals("caducada_pg", after.getUsername());
        assertEquals(passwordHash, after.getPassword());
        assertTrue(after.isEmailVerified());
    }

    /** Una pendiente con su enlace vigente no se borra: 409 y todo queda igual. */
    @Test
    void elNombreDeUnaPendienteVigenteDa409YNoSeBorra() {
        register("vigente_pg", "vigente@pg.test");
        User pending = userRepository.findByEmail("vigente@pg.test").orElseThrow();
        User owner = saveUser("otro_pg", Role.USER);

        assertThrows(UserAlreadyExistsException.class,
                () -> userService.updateProfile(owner.getId(), rename("vigente_pg")));

        assertTrue(userRepository.existsById(pending.getId()));
        assertEquals("otro_pg", userRepository.findById(owner.getId()).orElseThrow().getUsername());
    }

    /**
     * <b>Orden de los bloqueos.</b> Un registro con el email de la cuenta y el
     * nombre de la pendiente, y el cambio de nombre a ese nombre, a la vez.
     * Los dos bloquean las mismas dos filas. Un árbitro retiene la pendiente
     * (id menor); el registro llega primero a esperarla y después el cambio de
     * nombre. Si el cambio de nombre bloqueara su propia fila antes (orden
     * contrario al del registro), el registro obtendría la pendiente y
     * esperaría la propia, que el cambio de nombre ya tiene, mientras este
     * espera la pendiente: interbloqueo, y PostgreSQL abortaría a uno con
     * {@code 40P01} (un 500). Con el mismo orden (de menor a mayor id) el
     * cambio de nombre simplemente espera: el registro borra la pendiente y
     * avisa al titular (202), y después el cambio de nombre encuentra la
     * pendiente borrada, da el nombre por libre y se guarda.
     */
    @Test
    void unRegistroYUnCambioDeNombreSobreLasMismasCuentasNoSeInterbloquean() throws Exception {
        User stale = saveReplaceablePending("disputado_pg", "disputado@pg.test");
        User owner = saveUser("titular_dos_pg", Role.USER);
        assertTrue(stale.getId() < owner.getId(), "La pendiente debe tener el id menor");
        Future<?> arbiter = holdRowLock(stale.getId());

        Future<AcceptedResponse> registration = pool.submit(() -> register("disputado_pg", owner.getEmail()));
        awaitWaitingForLock(1);
        Future<UserResponse> renaming = pool.submit(
                () -> userService.updateProfile(owner.getId(), rename("disputado_pg")));
        awaitWaitingForLock(2);
        releaseArbiter.countDown();
        arbiter.get(10, TimeUnit.SECONDS);

        assertEquals(AcceptedResponse.REGISTRATION, result(registration));
        assertEquals("disputado_pg", result(renaming).username());
        assertFalse(userRepository.existsById(stale.getId()));
        assertEquals("disputado_pg", userRepository.findById(owner.getId()).orElseThrow().getUsername());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static UpdateProfileRequest rename(String username) {
        return new UpdateProfileRequest(username, null, null, null, null);
    }

    private AcceptedResponse register(String username, String email) {
        CreateUserRequest request = new CreateUserRequest();
        request.setUsername(username);
        request.setEmail(email);
        request.setPassword(PASSWORD);
        return registrationService.register(request);
    }

    /**
     * Cuenta pendiente <b>sustituible</b>: creada hace dos días por el registro
     * (su único token es de su misma fecha de alta) y con ese token caducado
     * hace un día sin usar.
     */
    private User saveReplaceablePending(String username, String email) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        User saved = userRepository.saveAndFlush(user);
        Instant createdAt = Instant.now().minus(Duration.ofDays(2));
        jdbc.update("UPDATE users SET created_at = ?, email_verified_at = NULL WHERE id = ?",
                Timestamp.from(createdAt), saved.getId());
        jdbc.update("INSERT INTO account_tokens (user_id, purpose, token_hash, created_at, expires_at)"
                        + " VALUES (?, 'EMAIL_VERIFICATION', ?, ?, ?)",
                saved.getId(), String.format("%064x", saved.getId()), Timestamp.from(createdAt),
                Timestamp.from(createdAt.plus(Duration.ofDays(1))));
        return saved;
    }

    /**
     * Transacción «árbitro»: bloquea la fila del usuario
     * ({@code SELECT ... FOR UPDATE}) hasta {@link #releaseArbiter} y confirma
     * sin cambios.
     */
    private Future<?> holdRowLock(Long id) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        Future<?> arbiter = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, id);
            locked.countDown();
            await(releaseArbiter);
        }));
        assertTrue(locked.await(10, TimeUnit.SECONDS), "El árbitro no llegó a bloquear la fila");
        return arbiter;
    }

    /** Espera (máx. 10 s) a ver {@code expected} conexiones esperando un bloqueo sobre {@code users}. */
    private void awaitWaitingForLock(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int waiting = 0;
        while (System.nanoTime() < deadline) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity"
                    + " WHERE datname = current_database() AND wait_event_type = 'Lock'"
                    + " AND query ILIKE ?", Integer.class, "%users%");
            waiting = count == null ? 0 : count;
            if (waiting >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Se esperaban " + expected + " conexiones esperando el bloqueo de users y hay "
                + waiting);
    }

    /** Resultado de un hilo; si lanzó una excepción, el test falla con ella (p. ej. un 500 en la vida real). */
    private static <T> T result(Future<T> future) throws Exception {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("La petición concurrente terminó con un error: " + e.getCause(), e.getCause());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("tiempo de espera agotado");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
