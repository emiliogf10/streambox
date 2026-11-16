package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
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

import com.emilio.streambox.repository.UsernameReservationQueryContract;
import com.emilio.streambox.repository.UsernameReservationRepository;

/**
 * Reservas de nombre de usuario ({@code V13}) contra <strong>PostgreSQL
 * real</strong> (Testcontainers). Ejecuta los mismos casos que la versión H2
 * ({@code UsernameReservationRepositoryIntegrationTest}), definidos en
 * {@link UsernameReservationQueryContract}.
 *
 * <p>
 * Qué puede cambiar el motor y por eso se repite: {@code ON CONFLICT DO
 * NOTHING}, los parámetros {@link OffsetDateTime} hacia {@code timestamptz}
 * (el driver de PostgreSQL no acepta {@link Instant}), la cascada y el
 * {@code COUNT(*)} como {@code bigint}.
 * </p>
 *
 * <p>
 * Además, lo que H2 no puede demostrar: que {@code reserve} es segura con
 * <b>concurrencia real</b>. Dos registros simultáneos con el mismo nombre no
 * dan un error (que sería un 500) y el nombre queda con <b>una sola</b>
 * caducidad, la del que llegó primero, con todos los emails como dueños.
 * </p>
 */
class PostgresUsernameReservationIntegrationTest extends PostgresIntegrationTestSupport
        implements UsernameReservationQueryContract {

    /** Tiempo máximo de espera de cada hilo (evita colgar la suite si algo se bloquea). */
    private static final long TIMEOUT_SECONDS = 30;

    @Autowired private UsernameReservationRepository usernameReservationRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM username_reservations");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM username_reservations");
    }

    @Override
    public UsernameReservationRepository reservations() {
        return usernameReservationRepository;
    }

    @Override
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    @Override
    public TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------
    // Concurrencia (solo PostgreSQL)
    // ------------------------------------------------------------------

    /**
     * Carrera forzada: el primer registro crea la reserva y <b>no confirma</b>
     * todavía; el segundo (otro email, con un «ahora» posterior) llega
     * mientras tanto. Su {@code INSERT ... ON CONFLICT DO NOTHING} tiene que
     * esperar al primero y, cuando este confirma, no insertar nada: devuelve
     * {@code false} (lo que solo es posible si esperó: sin la reserva
     * confirmada del primero habría creado la suya), no falla y se suma como
     * dueño. Queda una sola reserva con la caducidad del primero.
     */
    @Test
    void unSegundoRegistroSimultaneoEsperaAlPrimeroYSeSumaSinCambiarLaCaducidad() throws Exception {
        CountDownLatch firstReserved = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> tx().execute(status -> {
                boolean created = reservations().reserve("carrera", "ana@test.com", T0, TTL);
                firstReserved.countDown();
                sleep(Duration.ofMillis(500)); // el segundo queda esperando en el INSERT
                return created;
            }));
            assertTrue(firstReserved.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Future<Boolean> second = pool.submit(() -> tx().execute(status ->
                    reservations().reserve("carrera", "bea@test.com", T0.plusSeconds(1), TTL)));

            assertTrue(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertFalse(second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), "debió esperar al primero y no crear otra");
        } finally {
            pool.shutdownNow();
        }

        assertSingleReservation("carrera", T0, 2);
        assertFalse(reservations().isReservedForOthers("carrera", "ana@test.com", T0.plusSeconds(1)));
        assertFalse(reservations().isReservedForOthers("carrera", "bea@test.com", T0.plusSeconds(1)));
    }

    /**
     * Muchos registros a la vez con el mismo nombre y emails distintos (cada
     * uno con su «ahora»): ninguno falla, exactamente uno crea la reserva y
     * su caducidad es la de todos; los demás son dueños.
     */
    @Test
    void muchosRegistrosSimultaneosDejanUnaSolaReservaConTodosLosDuenos() throws Exception {
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                Instant now = T0.plusSeconds(i);
                String email = "email" + i + "@test.com";
                results.add(pool.submit(() -> {
                    start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    return tx().execute(status -> reservations().reserve("multitud", email, now, TTL));
                }));
            }
            int created = 0;
            for (Future<Boolean> result : results) {
                if (result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    created++;
                }
            }
            assertEquals(1, created, "solo uno crea la reserva");
        } finally {
            pool.shutdownNow();
        }

        OffsetDateTime createdAt = jdbc.queryForObject(
                "SELECT created_at FROM username_reservations WHERE username = 'multitud'", OffsetDateTime.class);
        assertSingleReservation("multitud", createdAt.toInstant(), threads);
    }

    /**
     * Dos registros simultáneos sobre un nombre cuya reserva <b>caducó</b>:
     * los dos quieren borrarla y crear la nueva. El segundo espera al
     * {@code DELETE} del primero, no falla, y queda una sola reserva nueva con
     * los dos emails nuevos como dueños (el dueño antiguo ya no lo es).
     */
    @Test
    void dosRegistrosSimultaneosSobreUnaReservaCaducadaDejanUnaSolaNueva() throws Exception {
        reservations().reserve("caducada", "vieja@test.com", T0, TTL);
        Instant later = T0.plus(TTL).plus(Duration.ofHours(1));
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(() -> {
                start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return tx().execute(status -> reservations().reserve("caducada", "ana@test.com", later, TTL));
            });
            Future<Boolean> b = pool.submit(() -> {
                start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return tx().execute(status -> reservations().reserve("caducada", "bea@test.com", later.plusSeconds(1), TTL));
            });

            assertTrue(a.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) ^ b.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "exactamente uno crea la reserva nueva");
        } finally {
            pool.shutdownNow();
        }

        OffsetDateTime createdAt = jdbc.queryForObject(
                "SELECT created_at FROM username_reservations WHERE username = 'caducada'", OffsetDateTime.class);
        assertTrue(!createdAt.toInstant().isBefore(later), "es la reserva nueva, no la caducada");
        assertSingleReservation("caducada", createdAt.toInstant(), 2);
        assertTrue(reservations().isReservedForOthers("caducada", "vieja@test.com", later.plusSeconds(1)));
    }

    /** El mismo email dos veces a la vez: ningún error y un solo dueño. */
    @Test
    void elMismoEmailDosVecesALaVezNoFallaYDejaUnSoloDueno() throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit(() -> {
                    start.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    return tx().execute(status -> reservations().reserve("doble", "ana@test.com", T0, TTL));
                }));
            }
            assertTrue(results.get(0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    ^ results.get(1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertSingleReservation("doble", T0, 1);
    }

    /**
     * <b>Hallazgo BAJA</b> (revisión del arreglo de NV-2): la reserva vigente
     * de un nombre caduca y otra transacción la borra <b>entre</b> el
     * {@code INSERT} de la reserva (que choca con ella y no hace nada) y el del
     * dueño. Antes, ese segundo {@code INSERT} violaba la clave foránea y el
     * registro respondía 500. Ahora {@code reserve} bloquea la fila del nombre
     * antes de añadir el dueño, así que el borrado espera a que confirme.
     *
     * <p>
     * <b>Cómo se fuerza el orden:</b> un disparador de prueba
     * ({@code BEFORE INSERT} sobre los dueños) detiene el {@code INSERT} del
     * dueño hasta que el test suelta un cerrojo consultivo
     * ({@code pg_advisory_xact_lock}). Mientras está detenido, la limpieza
     * ({@code deleteExpired}, con un «ahora» en el que la reserva ya caducó)
     * intenta borrar la reserva: sin el arreglo lo consigue y confirma; con
     * él, se queda esperando. El disparador se borra al terminar.
     * </p>
     */
    @Test
    void unaReservaQueCaducaMientrasSeAnadeElDuenoNoAcabaEnUnErrorDeClaveForanea() throws Exception {
        reservations().reserve("frontera", "vieja@test.com", T0, TTL);
        Instant stillValid = T0.plus(TTL).minusSeconds(1);
        Instant expired = T0.plus(TTL);
        long key = 4_242_001L;
        jdbc.execute("CREATE OR REPLACE FUNCTION test_pause_owner_insert() RETURNS trigger AS $$"
                + " BEGIN PERFORM pg_advisory_xact_lock_shared(" + key + "); RETURN NEW; END $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER test_pause_owner_insert BEFORE INSERT ON username_reservation_owners"
                + " FOR EACH ROW EXECUTE FUNCTION test_pause_owner_insert()");
        CountDownLatch gateClosed = new CountDownLatch(1);
        CountDownLatch openGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> gate = pool.submit(() -> tx().executeWithoutResult(status -> {
                jdbc.execute("SELECT pg_advisory_xact_lock(" + key + ")");
                gateClosed.countDown();
                await(openGate);
            }));
            assertTrue(gateClosed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

            Future<Boolean> reserving = pool.submit(() -> tx().execute(status ->
                    reservations().reserve("frontera", "ana@test.com", stillValid, TTL)));
            awaitWaiting("advisory");

            Future<Integer> cleanup = pool.submit(() -> tx().execute(status ->
                    reservations().deleteExpired(expired)));
            awaitDoneOrWaiting(cleanup);
            openGate.countDown();
            gate.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            assertFalse(resultOf(reserving), "la reserva estaba vigente: solo se añade el dueño");
            assertEquals(1, resultOf(cleanup), "la limpieza la borra después, cuando ya caducó");
        } finally {
            openGate.countDown();
            pool.shutdownNow();
            jdbc.execute("DROP TRIGGER IF EXISTS test_pause_owner_insert ON username_reservation_owners");
            jdbc.execute("DROP FUNCTION IF EXISTS test_pause_owner_insert()");
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM username_reservation_owners", Integer.class),
                "ningún dueño huérfano");
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Espera a que alguna conexión espere un bloqueo de ese tipo ({@code pg_stat_activity.wait_event}). */
    private void awaitWaiting(String waitEvent) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity"
                    + " WHERE datname = current_database() AND wait_event_type = 'Lock' AND wait_event = ?",
                    Integer.class, waitEvent);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("nadie llegó a esperar un bloqueo " + waitEvent);
    }

    /**
     * Espera a que la tarea termine (sin el arreglo) o a que haya dos
     * conexiones esperando un bloqueo (con él: la que está en el disparador y
     * la limpieza, que espera la fila).
     */
    private void awaitDoneOrWaiting(Future<?> future) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (future.isDone()) {
                return;
            }
            Integer waiting = jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity"
                    + " WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting >= 2) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("la limpieza ni terminó ni se quedó esperando");
    }

    /** Resultado de un hilo; si lanzó una excepción (un 500 en la vida real), el test falla con ella. */
    private static <T> T resultOf(Future<T> future) throws Exception {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("La operación terminó con un error: " + e.getCause(), e.getCause());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("tiempo de espera agotado");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Comprueba que el nombre tiene exactamente una reserva, creada en
     * {@code createdAt} y que caduca 24 h después, con {@code owners} dueños.
     */
    private void assertSingleReservation(String username, Instant createdAt, int owners) {
        List<OffsetDateTime[]> rows = jdbc.query(
                "SELECT created_at, expires_at FROM username_reservations WHERE username = ?",
                (rs, row) -> new OffsetDateTime[] {
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("expires_at", OffsetDateTime.class) },
                username);
        assertEquals(1, rows.size(), "una sola reserva por nombre");
        assertEquals(createdAt, rows.get(0)[0].toInstant());
        assertEquals(createdAt.plus(TTL), rows.get(0)[1].toInstant(), "una sola caducidad: la del primero");
        assertEquals(owners, jdbc.queryForObject(
                "SELECT COUNT(*) FROM username_reservation_owners WHERE username = ?", Integer.class, username));
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
