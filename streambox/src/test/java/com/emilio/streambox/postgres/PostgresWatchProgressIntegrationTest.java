package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.repository.WatchProgress;
import com.emilio.streambox.repository.WatchProgressQueryContract;
import com.emilio.streambox.repository.WatchProgressRepository;

/**
 * Progreso de visionado ({@code V14}) contra <strong>PostgreSQL real</strong>
 * (Testcontainers). Ejecuta los mismos casos que la versión H2
 * ({@code WatchProgressRepositoryIntegrationTest}), definidos en
 * {@link WatchProgressQueryContract}.
 *
 * <p>
 * Qué puede cambiar el motor y por eso se repite: {@code INSERT ... ON
 * CONFLICT DO NOTHING} (no es SQL estándar), los {@code SQLSTATE} de
 * {@code CHECK} ({@code 23514}) y de clave foránea ({@code 23503}) con el
 * nombre de cada restricción, la resolución de tipos del {@code UNION ALL} con
 * {@code CAST(NULL AS ...)}, {@code ROW_NUMBER() OVER (PARTITION BY ...)},
 * {@code FETCH FIRST :limit ROWS ONLY} con parámetro, el enlace de
 * {@code OffsetDateTime} a {@code timestamptz} y las cascadas reales.
 * </p>
 *
 * <p>
 * Además, lo que H2 no puede demostrar: la <b>carrera de dos primeros
 * guardados</b> de la misma fila. Es la razón por la que el <i>upsert</i> es
 * «{@code UPDATE} y, si no había fila, {@code INSERT ... ON CONFLICT DO
 * NOTHING}, y otra vuelta si se pierde» y no un {@code MERGE} (que aquí
 * fallaría con {@code 23505}).
 * </p>
 */
class PostgresWatchProgressIntegrationTest extends PostgresIntegrationTestSupport
        implements WatchProgressQueryContract {

    /** PostgreSQL: violación de {@code CHECK}. */
    private static final String CHECK_VIOLATION = "23514";

    /** PostgreSQL: violación de clave foránea. */
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    @Autowired private WatchProgressRepository watchProgressRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Override
    public WatchProgressRepository progress() {
        return watchProgressRepository;
    }

    @Override
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    @Override
    public TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @Override
    public String checkViolationState() {
        return CHECK_VIOLATION;
    }

    @Override
    public String foreignKeyViolationState() {
        return FOREIGN_KEY_VIOLATION;
    }

    /**
     * La carrera exacta, paso a paso: la transacción A inserta la fila y,
     * <b>sin confirmar</b>, espera a que B quede bloqueada en su
     * {@code INSERT ... ON CONFLICT DO NOTHING} (B no ve la fila sin confirmar:
     * su {@code UPDATE} no actualizó nada). Al confirmar A, el {@code INSERT}
     * de B no inserta ni falla, y B vuelve al {@code UPDATE}, que ahora sí ve
     * la fila: B devuelve {@code false} («sobrescrita») y gana su valor, el
     * último en escribirse. Con un {@code INSERT} normal, o con {@code MERGE},
     * B acabaría en {@code 23505}.
     */
    @Test
    void dosPrimerosGuardadosCruzadosNoFallanYGanaElUltimo() throws Exception {
        long ana = user("ana");
        long movie = movie("Pelicula");
        CountDownLatch aInserted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(() -> tx().execute(status -> {
                boolean created = progress().saveMovieProgress(ana, movie, 10, 100, BASE);
                aInserted.countDown();
                waitUntilSomeoneWaitsForALock();
                return created;
            }));
            assertTrue(aInserted.await(10, TimeUnit.SECONDS), "A no llegó a insertar");
            Future<Boolean> b = pool.submit(() -> tx().execute(
                    status -> progress().saveMovieProgress(ana, movie, 20, 100, BASE.plusSeconds(1))));

            assertTrue(a.get(20, TimeUnit.SECONDS), "A crea la fila");
            assertFalse(b.get(20, TimeUnit.SECONDS), "B la encuentra hecha y la sobrescribe");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(Optional.of(new WatchProgress(20, 100, BASE.plusSeconds(1), null)),
                progress().findMovieProgress(ana, movie));
        assertEquals(1, rows("user_movie_progress", "user_id", ana));
    }

    /**
     * La misma carrera con {@code finished_at} ({@code V15}), en los dos
     * sentidos. Primero: A crea la fila a medias y B, que llega terminada,
     * pierde el {@code INSERT}; al volver al {@code UPDATE} marca el título
     * como visto con su fecha. Después, con otra película: A crea la fila ya
     * terminada con una fecha <b>posterior</b> a la de B, que llega a medias y
     * confirma la última (el caso de dos pestañas): el guardado de B no falla
     * por {@code ck_movie_progress_finished_at_not_after_updated_at}, la fecha
     * de «visto» se recorta a la suya y el título sigue visto.
     */
    @Test
    void laCarreraDelPrimerGuardadoConservaLaFechaDeVisto() throws Exception {
        long ana = user("ana");
        long first = movie("Primera");
        long second = movie("Segunda");

        race(ana, first, 10, BASE, 100, BASE.plusSeconds(1));
        assertEquals(Optional.of(new WatchProgress(100, 100, BASE.plusSeconds(1), BASE.plusSeconds(1))),
                progress().findMovieProgress(ana, first));

        race(ana, second, 100, BASE.plusSeconds(10), 30, BASE.plusSeconds(5));
        assertEquals(Optional.of(new WatchProgress(30, 100, BASE.plusSeconds(5), BASE.plusSeconds(5))),
                progress().findMovieProgress(ana, second));
    }

    /**
     * Cruza dos primeros guardados de la misma película como
     * {@link #dosPrimerosGuardadosCruzadosNoFallanYGanaElUltimo}: A inserta
     * (duración 100) y, sin confirmar, espera a que B quede bloqueada en su
     * {@code INSERT}; B acaba sobrescribiendo la fila.
     */
    private void race(long userId, long movieId, int positionA, Instant atA, int positionB, Instant atB)
            throws Exception {
        CountDownLatch aInserted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(() -> tx().execute(status -> {
                boolean created = progress().saveMovieProgress(userId, movieId, positionA, 100, atA);
                aInserted.countDown();
                waitUntilSomeoneWaitsForALock();
                return created;
            }));
            assertTrue(aInserted.await(10, TimeUnit.SECONDS), "A no llegó a insertar");
            Future<Boolean> b = pool.submit(() -> tx().execute(
                    status -> progress().saveMovieProgress(userId, movieId, positionB, 100, atB)));

            assertTrue(a.get(20, TimeUnit.SECONDS), "A crea la fila");
            assertFalse(b.get(20, TimeUnit.SECONDS), "B la encuentra hecha y la sobrescribe");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Carga real: en cada ronda, ocho transacciones guardan a la vez el
     * <b>primer</b> progreso del mismo usuario en el mismo episodio. Ninguna
     * falla, exactamente una dice haberlo creado y queda una sola fila, con
     * el valor de una de ellas.
     */
    @Test
    void ochoPrimerosGuardadosSimultaneosDejanUnaSolaFila() throws Exception {
        int threads = 8;
        long ana = user("ana");
        long serie = serie("Serie");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 1; round <= 10; round++) {
                long episode = episode(serie, 1, round);
                CyclicBarrier start = new CyclicBarrier(threads);
                List<Future<Boolean>> results = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    int position = t;
                    Callable<Boolean> save = () -> {
                        start.await(10, TimeUnit.SECONDS);
                        return tx().execute(status -> progress().saveEpisodeProgress(ana, episode, position, 100,
                                BASE.plusSeconds(position)));
                    };
                    results.add(pool.submit(save));
                }
                int created = 0;
                for (Future<Boolean> result : results) {
                    if (result.get(30, TimeUnit.SECONDS)) {
                        created++;
                    }
                }

                assertEquals(1, created, "ronda " + round);
                assertEquals(1, rows("user_episode_progress", "episode_id", episode), "ronda " + round);
                int position = progress().findEpisodeProgress(ana, episode).orElseThrow().positionSeconds();
                assertTrue(position >= 0 && position < threads, "ronda " + round + ": " + position);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Espera (como mucho 10 s) a que alguna sesión de esta base de datos esté
     * bloqueada esperando un cerrojo: la señal de que B ya está detenida en su
     * {@code INSERT}. Se ejecuta en el hilo de A, así que consulta por la
     * conexión de A (que no espera a nadie); como PostgreSQL congela la vista
     * de {@code pg_stat_activity} durante la transacción, cada vuelta la
     * refresca con {@code pg_stat_clear_snapshot()}.
     */
    private void waitUntilSomeoneWaitsForALock() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            jdbc.execute("SELECT pg_stat_clear_snapshot()");
            Integer waiting = jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity"
                    + " WHERE datname = current_database() AND wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("B no llegó a bloquearse esperando a A");
    }
}
