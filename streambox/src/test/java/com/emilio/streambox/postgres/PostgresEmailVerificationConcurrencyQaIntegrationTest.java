package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.dto.AcceptedResponse;
import com.emilio.streambox.dto.CreateUserRequest;
import com.emilio.streambox.email.MemoryEmailSender;
import com.emilio.streambox.email.MemoryEmailSender.SentEmail;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.InvalidVerificationTokenException;
import com.emilio.streambox.exception.UserAlreadyExistsException;
import com.emilio.streambox.service.EmailVerificationService;
import com.emilio.streambox.service.RegistrationService;

/**
 * Revisión de QA (2026-10-18): <b>concurrencia real</b> del registro, el
 * reenvío y la confirmación del correo sobre PostgreSQL (petición de
 * {@code backend} y {@code security}).
 *
 * <p>
 * <b>Por qué en PostgreSQL.</b> El registro y el reenvío bloquean la fila del
 * usuario ({@code SELECT ... FOR UPDATE}, {@code UserRepository.findForUpdateById})
 * y deciden con su estado releído; si la fila desaparece mientras esperan, el
 * registro se repite una vez y el reenvío no envía nada. Cómo espera un
 * {@code FOR UPDATE} detrás de otra transacción, qué ve cuando esta confirma
 * un {@code DELETE} y qué filas nuevas ve un {@code UPDATE} masivo en
 * {@code READ COMMITTED} depende del motor; los tests unitarios
 * ({@code RegistrationServiceTest}, {@code EmailVerificationServiceTest}) lo
 * simulan con dobles, y H2 no bloquea igual.
 * </p>
 *
 * <p>
 * <b>Cómo se fuerza la carrera sin depender de la suerte.</b> Una transacción
 * «árbitro» bloquea antes la fila implicada; las peticiones se lanzan en
 * hilos, el test espera a verlas <b>esperando ese bloqueo</b> en
 * {@code pg_stat_activity} y solo entonces el árbitro confirma (sin cambios, o
 * borrando la fila). Así las dos peticiones leyeron el estado de antes y se
 * cruzan exactamente en el punto que se quiere probar.
 * </p>
 *
 * <p>
 * Se llama a los servicios directamente (no a MockMvc) desde varios hilos; en
 * el perfil {@code test} el reenvío corre en el mismo hilo
 * ({@code streambox.email.async=false}) y los correos llegan a
 * {@link MemoryEmailSender}. Sin el límite por cuenta del perfil {@code test}
 * ({@code min-interval=0}), cada camino emite su enlace: lo que se comprueba
 * es que nunca quedan <b>dos enlaces vigentes</b> ni dos cuentas, y que nada
 * acaba en un error. Datos con commit real, limpiados antes y después. Sin
 * Docker la clase se omite.
 * </p>
 */
class PostgresEmailVerificationConcurrencyQaIntegrationTest extends PostgresIntegrationTestSupport {

    private static final String PASSWORD = "Paseo-Por-El-Retiro-2026";
    private static final Pattern LINK = Pattern.compile("#token=([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");

    @Autowired private RegistrationService registrationService;
    @Autowired private EmailVerificationService emailVerificationService;
    @Autowired private MemoryEmailSender inbox;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private ExecutorService pool;
    private CountDownLatch releaseArbiter;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        inbox.clear();
        pool = Executors.newFixedThreadPool(3);
        releaseArbiter = new CountDownLatch(1);
    }

    @AfterEach
    void tearDown() {
        releaseArbiter.countDown();
        pool.shutdownNow();
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Registro contra registro
    // ------------------------------------------------------------------

    /**
     * Dos registros simultáneos con el email de una pendiente <b>sustituible</b>
     * (creada por el registro, con su único enlace caducado) y nombres
     * distintos. Los dos leen la pendiente y esperan su bloqueo; el primero la
     * borra y crea la suya; el segundo encuentra la fila borrada, repite y ve
     * la cuenta nueva (con su enlace vigente), así que solo le reenvía el
     * enlace y reserva su nombre. Resultado: los dos responden el 202 de
     * siempre, queda <b>una</b> cuenta con ese email (pendiente, con el nombre
     * del ganador y un único enlace vigente) y el nombre del perdedor da 409.
     * Antes de B2 ({@code refresh} con bloqueo), el perdedor respondía 500.
     */
    @Test
    void dosRegistrosSimultaneosSobreUnaPendienteSustituibleDejanUnaSolaCuentaYNingunError() throws Exception {
        User stale = saveReplaceablePending("vieja_pg", "disputado@pg.test");
        Future<?> arbiter = holdRowLock("users", stale.getId(), false);

        Future<AcceptedResponse> first = pool.submit(() -> register("nuevo_uno", "disputado@pg.test"));
        Future<AcceptedResponse> second = pool.submit(() -> register("nuevo_dos", "disputado@pg.test"));
        awaitWaitingForLock("users", 2);
        releaseArbiter.countDown();
        arbiter.get(10, TimeUnit.SECONDS);

        assertEquals(AcceptedResponse.REGISTRATION, result(first));
        assertEquals(AcceptedResponse.REGISTRATION, result(second));

        assertFalse(userRepository.existsById(stale.getId()), "La pendiente sustituible se borra");
        User winner = userRepository.findByEmail("disputado@pg.test").orElseThrow();
        assertTrue(Set.of("nuevo_uno", "nuevo_dos").contains(winner.getUsername()), winner.getUsername());
        assertFalse(winner.isEmailVerified());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class,
                "disputado@pg.test"));
        assertEquals(1, activeTokens(winner.getId()), "Nunca dos enlaces vigentes");

        String loserName = winner.getUsername().equals("nuevo_uno") ? "nuevo_dos" : "nuevo_uno";
        assertTrue(userRepository.findByUsername(loserName).isEmpty());
        assertThrows(UserAlreadyExistsException.class, () -> register(loserName, "tercero@pg.test"),
                "El nombre del perdedor queda reservado, como en cualquier registro con un email que ya tiene cuenta");

        // Solo el último enlace enviado confirma la cuenta.
        List<String> tokens = tokensSentTo("disputado@pg.test");
        assertEquals(2, tokens.size(), tokens::toString);
        assertThrows(InvalidVerificationTokenException.class, () -> emailVerificationService.confirm(tokens.get(1)));
        emailVerificationService.confirm(tokens.get(0));
        assertTrue(userRepository.findById(winner.getId()).orElseThrow().isEmailVerified());
    }

    // ------------------------------------------------------------------
    // Registro contra reenvío
    // ------------------------------------------------------------------

    /**
     * Un registro con el email de una pendiente con su enlace vigente y un
     * reenvío a ese email, a la vez. Los dos emiten un enlace nuevo
     * invalidando los anteriores, pero con la fila del usuario bloqueada uno
     * espera al otro: al final hay <b>un único enlace vigente</b> (el del
     * último correo). Sin el bloqueo, el {@code UPDATE} que invalida los
     * enlaces de cada transacción no ve el {@code INSERT} aún sin confirmar de
     * la otra y quedarían dos vigentes.
     */
    @Test
    void unRegistroYUnReenvioALaVezDejanUnSoloEnlaceVigente() throws Exception {
        register("pendiente_pg", "pendiente@pg.test");
        User pending = userRepository.findByEmail("pendiente@pg.test").orElseThrow();
        Future<?> arbiter = holdRowLock("users", pending.getId(), false);

        Future<AcceptedResponse> registration = pool.submit(() -> register("otro_nombre_pg", "pendiente@pg.test"));
        Future<AcceptedResponse> resend = pool.submit(() -> emailVerificationService.resend("pendiente@pg.test"));
        awaitWaitingForLock("users", 2);
        releaseArbiter.countDown();
        arbiter.get(10, TimeUnit.SECONDS);

        assertEquals(AcceptedResponse.REGISTRATION, result(registration));
        assertEquals(AcceptedResponse.RESEND, result(resend));

        assertEquals(1, activeTokens(pending.getId()), "Nunca dos enlaces vigentes");
        User after = userRepository.findById(pending.getId()).orElseThrow();
        assertEquals("pendiente_pg", after.getUsername(), "La pendiente vigente no se toca");
        List<String> tokens = tokensSentTo("pendiente@pg.test");
        assertEquals(3, tokens.size(), tokens::toString);
        assertThrows(InvalidVerificationTokenException.class, () -> emailVerificationService.confirm(tokens.get(2)));
        assertThrows(InvalidVerificationTokenException.class, () -> emailVerificationService.confirm(tokens.get(1)));
        emailVerificationService.confirm(tokens.get(0));
    }

    // ------------------------------------------------------------------
    // La fila desaparece mientras se espera su bloqueo (B2)
    // ------------------------------------------------------------------

    /**
     * Otra conexión borra la cuenta (como haría un registro que la sustituye)
     * mientras el registro espera su bloqueo. El {@code FOR UPDATE} devuelve
     * vacío, el registro se repite con el estado nuevo (ya no hay cuenta con
     * ese email) y crea la suya: 202, una cuenta pendiente con el nombre
     * nuevo, un enlace y un correo. Antes, el {@code refresh} con bloqueo
     * lanzaba {@code EntityNotFoundException} (500).
     */
    @Test
    void siOtraConexionBorraLaCuentaMientrasElRegistroEsperaElRegistroReintentaYResponde202() throws Exception {
        User stale = saveReplaceablePending("borrada_pg", "borrada@pg.test");
        Future<?> arbiter = holdRowLock("users", stale.getId(), true);

        Future<AcceptedResponse> registration = pool.submit(() -> register("tras_borrado_pg", "borrada@pg.test"));
        awaitWaitingForLock("users", 1);
        releaseArbiter.countDown();
        arbiter.get(10, TimeUnit.SECONDS);

        assertEquals(AcceptedResponse.REGISTRATION, result(registration));
        User created = userRepository.findByEmail("borrada@pg.test").orElseThrow();
        assertEquals("tras_borrado_pg", created.getUsername());
        assertFalse(created.isEmailVerified());
        assertEquals(1, activeTokens(created.getId()));
        assertEquals(1, tokensSentTo("borrada@pg.test").size());
    }

    /**
     * Otra conexión borra la cuenta pendiente mientras el reenvío espera su
     * bloqueo: el reenvío lo trata como «sin cuenta». Responde el 202 de
     * siempre, no envía ningún correo y no deja ninguna fila (ni un token
     * huérfano, que la clave foránea rechazaría con un error).
     */
    @Test
    void siOtraConexionBorraLaCuentaMientrasElReenvioEsperaNoSeEnviaNada() throws Exception {
        register("reenvio_pg", "reenvio@pg.test");
        User pending = userRepository.findByEmail("reenvio@pg.test").orElseThrow();
        inbox.clear();
        Future<?> arbiter = holdRowLock("users", pending.getId(), true);

        Future<AcceptedResponse> resend = pool.submit(() -> emailVerificationService.resend("reenvio@pg.test"));
        awaitWaitingForLock("users", 1);
        releaseArbiter.countDown();
        arbiter.get(10, TimeUnit.SECONDS);

        assertEquals(AcceptedResponse.RESEND, result(resend));
        assertTrue(inbox.sentTo("reenvio@pg.test").isEmpty(), "No se envía nada a una cuenta que ya no existe");
        assertTrue(userRepository.findByEmail("reenvio@pg.test").isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM account_tokens", Integer.class));
    }

    // ------------------------------------------------------------------
    // Doble clic en el enlace
    // ------------------------------------------------------------------

    /**
     * Doble clic en el enlace (dos confirmaciones del mismo token a la vez, con
     * el servicio completo: consumir, verificar, invalidar y revocar sesiones):
     * exactamente una termina bien y la otra recibe el error de siempre
     * ({@code INVALID_VERIFICATION_TOKEN}, un 400), nunca un 500; la cuenta
     * queda verificada una sola vez.
     */
    @Test
    void dosConfirmacionesSimultaneasDelMismoEnlaceSoloGanaUnaYLaOtraEsEl400DeSiempre() throws Exception {
        register("doble_clic_pg", "doble@pg.test");
        String token = tokensSentTo("doble@pg.test").get(0);
        Long tokenId = jdbc.queryForObject("SELECT id FROM account_tokens", Long.class);
        Future<?> arbiter = holdRowLock("account_tokens", tokenId, false);

        Callable<Boolean> click = () -> {
            try {
                emailVerificationService.confirm(token);
                return true;
            } catch (InvalidVerificationTokenException e) {
                return false;
            }
        };
        Future<Boolean> a = pool.submit(click);
        Future<Boolean> b = pool.submit(click);
        awaitWaitingForLock("account_tokens", 2);
        releaseArbiter.countDown();
        arbiter.get(10, TimeUnit.SECONDS);

        List<Boolean> outcomes = new ArrayList<>(List.of(result(a), result(b)));
        assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count(), outcomes::toString);
        assertTrue(userRepository.findByEmail("doble@pg.test").orElseThrow().isEmailVerified());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM account_tokens WHERE used_at IS NULL",
                Integer.class));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

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
     * Transacción «árbitro»: bloquea la fila ({@code SELECT ... FOR UPDATE}) y
     * la mantiene hasta {@link #releaseArbiter}; entonces, si se pide, la borra,
     * y confirma.
     */
    private Future<?> holdRowLock(String table, Long id, boolean deleteBeforeCommit) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        Future<?> arbiter = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT id FROM " + table + " WHERE id = ? FOR UPDATE", Long.class, id);
            locked.countDown();
            await(releaseArbiter);
            if (deleteBeforeCommit) {
                jdbc.update("DELETE FROM " + table + " WHERE id = ?", id);
            }
        }));
        assertTrue(locked.await(10, TimeUnit.SECONDS), "El árbitro no llegó a bloquear la fila");
        return arbiter;
    }

    /** Espera (máx. 10 s) a ver {@code expected} conexiones esperando un bloqueo sobre {@code table}. */
    private void awaitWaitingForLock(String table, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int waiting = 0;
        while (System.nanoTime() < deadline) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity"
                    + " WHERE datname = current_database() AND wait_event_type = 'Lock'"
                    + " AND query ILIKE ?", Integer.class, "%" + table + "%");
            waiting = count == null ? 0 : count;
            if (waiting >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Se esperaban " + expected + " conexiones esperando el bloqueo de " + table
                + " y hay " + waiting);
    }

    /** Resultado de un hilo; si lanzó una excepción, el test falla con ella (p. ej. un 500 en la vida real). */
    private static <T> T result(Future<T> future) throws Exception {
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("La petición concurrente terminó con un error: " + e.getCause(), e.getCause());
        }
    }

    private int activeTokens(Long userId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM account_tokens WHERE user_id = ? AND used_at IS NULL"
                + " AND expires_at > CURRENT_TIMESTAMP", Integer.class, userId);
    }

    /** Tokens de los correos enviados a una dirección, del más reciente al más antiguo. */
    private List<String> tokensSentTo(String address) {
        List<String> tokens = new ArrayList<>();
        for (SentEmail email : inbox.sentTo(address)) {
            Matcher matcher = LINK.matcher(email.body());
            assertTrue(matcher.find(), email.body());
            tokens.add(matcher.group(1));
        }
        return tokens;
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
