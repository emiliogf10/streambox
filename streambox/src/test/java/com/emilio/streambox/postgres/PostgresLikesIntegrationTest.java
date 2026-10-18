package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.MovieNotFoundException;
import com.emilio.streambox.exception.SeriesNotFoundException;
import com.emilio.streambox.exception.UserNotFoundException;
import com.emilio.streambox.repository.LikesQueryContract;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.service.LikeService;

import jakarta.persistence.EntityManagerFactory;

/**
 * «Me gusta» y recomendaciones ({@code V8}) contra <strong>PostgreSQL
 * real</strong> (Testcontainers). Ejecuta los mismos casos que la versión H2
 * ({@code LikesRepositoryIntegrationTest}), definidos en
 * {@link LikesQueryContract}.
 *
 * <p>
 * Qué puede cambiar el motor y por eso se repite: {@code INSERT ... ON
 * CONFLICT DO NOTHING} (no es SQL estándar; H2 lo imita en modo PostgreSQL),
 * el {@code SQLSTATE} de la violación de clave foránea, el enlace de un
 * {@link java.time.Instant} a {@code timestamptz} en una consulta nativa, la
 * validación del {@code GROUP BY} con {@code SUM} en el {@code ORDER BY}, la
 * tabla derivada con {@code UNION ALL}, {@code FETCH FIRST :limit ROWS ONLY}
 * con parámetro, {@code SELECT EXISTS(...) OR EXISTS(...)} como booleano y el
 * borrado en cascada real.
 * </p>
 *
 * <p>
 * Además, lo que H2 no puede demostrar: la <b>concurrencia real</b> de las
 * peticiones HTTP (varios {@code PUT} del mismo «me gusta» a la vez,
 * {@code PUT} y {@code DELETE} cruzados, y un «me gusta» mientras el
 * administrador borra la película). {@code ON CONFLICT DO NOTHING} y la clave
 * foránea son los que garantizan que nunca hay un 500 ni una fila huérfana;
 * aquí se comprueba con transacciones de verdad que se solapan.
 * </p>
 */
class PostgresLikesIntegrationTest extends PostgresIntegrationTestSupport
        implements LikesQueryContract {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MockMvc mockMvc;
    @Autowired private LikeService likeService;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Override
    public MovieRepository movies() {
        return movieRepository;
    }

    @Override
    public SeriesRepository series() {
        return seriesRepository;
    }

    @Override
    public UserRepository users() {
        return userRepository;
    }

    @Override
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    @Override
    public Genre genre(String name) {
        return saveGenre(name);
    }

    @Override
    public Movie movie(String title, Genre... genres) {
        return saveMovie(title, 2000, genres);
    }

    @Override
    public Series serie(String title, Genre... genres) {
        return saveSeries(title, 2010, genres);
    }

    @Override
    public void episode(Series series, int number) {
        saveEpisode(series, 1, number);
    }

    @Override
    public User user(String name) {
        return saveUser(name, Role.USER);
    }

    @Override
    public long statementsFor(Runnable operation) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        operation.run();
        return statistics.getPrepareStatementCount();
    }

    @Override
    public TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------
    // Concurrencia real (solo PostgreSQL)
    // ------------------------------------------------------------------

    /**
     * El mecanismo en el que se apoya la idempotencia: dos transacciones
     * insertan el mismo «me gusta»; la segunda <b>espera</b> a la primera y,
     * cuando esta confirma, {@code ON CONFLICT DO NOTHING} la deja en 0 filas
     * <b>sin</b> lanzar la violación de la clave primaria (que abortaría la
     * transacción y acabaría en 500). Se mide la espera para demostrar que el
     * solapamiento existió de verdad, y se comprueba que se conserva la fecha
     * de la primera.
     */
    @Test
    void elSegundoInsertDelMismoMeGustaEsperaALaPrimeraYNoInsertaNada() throws Exception {
        User ana = user("ana");
        Movie movie = movie("Pelicula");
        TransactionTemplate tx = tx();
        CountDownLatch firstInserted = new CountDownLatch(1);
        long holdMillis = 600;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> tx.execute(status -> {
                int rows = movies().addLike(ana.getId(), movie.getId(), BASE);
                firstInserted.countDown();
                sleep(holdMillis); // mantiene la transacción abierta sin confirmar
                return rows;
            }));
            Future<long[]> second = pool.submit(() -> {
                firstInserted.await();
                long start = System.nanoTime();
                int rows = tx.execute(status -> movies().addLike(ana.getId(), movie.getId(), BASE.plusSeconds(60)));
                return new long[] { rows, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) };
            });

            assertEquals(1, first.get(30, TimeUnit.SECONDS));
            long[] result = second.get(30, TimeUnit.SECONDS);

            assertEquals(0, result[0], "el segundo INSERT no debe insertar nada");
            assertTrue(result[1] >= holdMillis / 2, "el segundo INSERT no esperó a la primera: " + result[1] + " ms");
            assertEquals(1, likeRows("user_liked_movies", "user_id", ana.getId()));
            assertEquals(BASE, jdbc.queryForObject("SELECT created_at FROM user_liked_movies WHERE user_id = ?",
                    OffsetDateTime.class, ana.getId()).toInstant(), "conserva la fecha de la primera");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Varios {@code PUT} simultáneos del mismo «me gusta» (doble clic, dos
     * pestañas): todos responden 204, ninguno 409 ni 500, y queda una sola
     * fila. Varias rondas con una barrera para que algunas peticiones pasen la
     * comprobación de existencia a la vez. Películas y series.
     */
    @Test
    void variosPutSimultaneosDelMismoMeGustaDanTodos204YUnaSolaFila() throws Exception {
        User ana = user("ana");
        String token = tokenFor(ana);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 6; round++) {
                Movie movie = movie("Ronda " + round);
                Series series = visibleSerie("Serie ronda " + round);
                List<String> urls = List.of("/api/users/me/likes/movies/" + movie.getId(),
                        "/api/users/me/likes/series/" + series.getId());
                for (String url : urls) {
                    CyclicBarrier barrier = new CyclicBarrier(threads);
                    List<Callable<Integer>> calls = new ArrayList<>();
                    for (int i = 0; i < threads; i++) {
                        calls.add(() -> {
                            barrier.await(30, TimeUnit.SECONDS);
                            return mockMvc.perform(put(url).header("Authorization", token))
                                    .andReturn().getResponse().getStatus();
                        });
                    }
                    for (Future<Integer> future : pool.invokeAll(calls)) {
                        assertEquals(204, future.get(60, TimeUnit.SECONDS), "ronda " + round + ", " + url);
                    }
                }
                assertEquals(1, likeRows("user_liked_movies", "movie_id", movie.getId()), "ronda " + round);
                assertEquals(1, likeRows("user_liked_series", "series_id", series.getId()), "ronda " + round);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * {@code PUT} y {@code DELETE} del mismo «me gusta» cruzados a la vez
     * (pulsar el corazón muy rápido en dos pestañas): todas las peticiones
     * responden 204, nunca un 4xx ni un 500, y al final el «me gusta» existe 0
     * o 1 veces (gana la última en confirmar). Después, un {@code PUT} solo lo
     * deja dado: el estado no queda corrupto.
     */
    @Test
    void putYDeleteCruzadosNuncaFallanNiDejanEstadosIncoherentes() throws Exception {
        User ana = user("ana");
        String token = tokenFor(ana);
        Movie movie = movie("Pelicula");
        String url = "/api/users/me/likes/movies/" + movie.getId();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 8; round++) {
                CyclicBarrier barrier = new CyclicBarrier(threads);
                List<Callable<Integer>> calls = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    boolean like = i % 2 == 0;
                    calls.add(() -> {
                        barrier.await(30, TimeUnit.SECONDS);
                        return mockMvc.perform((like ? put(url) : delete(url)).header("Authorization", token))
                                .andReturn().getResponse().getStatus();
                    });
                }
                for (Future<Integer> future : pool.invokeAll(calls)) {
                    assertEquals(204, future.get(60, TimeUnit.SECONDS), "ronda " + round);
                }
                int rows = likeRows("user_liked_movies", "user_id", ana.getId());
                assertTrue(rows == 0 || rows == 1, "ronda " + round + ": " + rows + " filas");
            }

            assertEquals(204, mockMvc.perform(put(url).header("Authorization", token))
                    .andReturn().getResponse().getStatus());
            assertEquals(1, likeRows("user_liked_movies", "user_id", ana.getId()));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Un usuario da «me gusta» a una película mientras el administrador la
     * borra desde el panel. Según quién confirme antes: el «me gusta» entra y
     * la cascada lo borra con la película, o llega tarde y es un 404 (por la
     * comprobación previa o por la clave foránea, traducida). Nunca un 500 y
     * nunca queda una fila que apunte a una película inexistente. Varias
     * rondas para recorrer los distintos órdenes.
     */
    @Test
    void meGustaMientrasElAdministradorBorraLaPeliculaNuncaDa500NiDejaHuerfanos() throws Exception {
        User ana = user("ana");
        String userToken = tokenFor(ana);
        String adminToken = tokenFor(saveUser("admin", Role.ADMIN));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 12; round++) {
                Movie movie = movie("Ronda " + round);
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Integer> like = pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return mockMvc.perform(put("/api/users/me/likes/movies/" + movie.getId())
                            .header("Authorization", userToken)).andReturn().getResponse().getStatus();
                });
                Future<Integer> remove = pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return mockMvc.perform(delete("/api/movies/" + movie.getId())
                            .header("Authorization", adminToken)).andReturn().getResponse().getStatus();
                });

                int likeStatus = like.get(60, TimeUnit.SECONDS);
                assertEquals(204, remove.get(60, TimeUnit.SECONDS), "ronda " + round + ": borrado");
                assertTrue(likeStatus == 204 || likeStatus == 404, "ronda " + round + ": «me gusta» " + likeStatus);
                assertEquals(0, likeRows("user_liked_movies", "movie_id", movie.getId()), "ronda " + round);
            }
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_liked_movies l"
                    + " WHERE NOT EXISTS (SELECT 1 FROM movies m WHERE m.id = l.movie_id)", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * La versión de series de la carrera anterior: un «me gusta» mientras el
     * administrador borra la serie desde el panel ({@code DELETE
     * /api/series/{id}}, que borra también sus episodios). Según el orden, 204
     * (y la cascada se lleva el «me gusta») o 404; nunca 500 ni filas
     * huérfanas.
     */
    @Test
    void meGustaMientrasElAdministradorBorraLaSerieNuncaDa500NiDejaHuerfanos() throws Exception {
        User ana = user("ana");
        String userToken = tokenFor(ana);
        String adminToken = tokenFor(saveUser("admin", Role.ADMIN));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 12; round++) {
                Series series = visibleSerie("Serie ronda " + round);
                episode(series, 2);
                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<Integer> like = pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return mockMvc.perform(put("/api/users/me/likes/series/" + series.getId())
                            .header("Authorization", userToken)).andReturn().getResponse().getStatus();
                });
                Future<Integer> remove = pool.submit(() -> {
                    barrier.await(30, TimeUnit.SECONDS);
                    return mockMvc.perform(delete("/api/series/" + series.getId())
                            .header("Authorization", adminToken)).andReturn().getResponse().getStatus();
                });

                int likeStatus = like.get(60, TimeUnit.SECONDS);
                assertEquals(204, remove.get(60, TimeUnit.SECONDS), "ronda " + round + ": borrado");
                assertTrue(likeStatus == 204 || likeStatus == 404, "ronda " + round + ": «me gusta» " + likeStatus);
                assertEquals(0, likeRows("user_liked_series", "series_id", series.getId()), "ronda " + round);
            }
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_liked_series l"
                    + " WHERE NOT EXISTS (SELECT 1 FROM series s WHERE s.id = l.series_id)", Integer.class));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * La traducción de la clave foránea con el {@code SQLSTATE} y el nombre de
     * restricción <b>de PostgreSQL</b> (en H2 lo cubre
     * {@code LikesIntegrationTest}): si el usuario ya no existe (se borró la
     * cuenta durante la petición) es «Usuario no encontrado» y no un falso
     * «Película no encontrada»: distinguirlos depende del nombre de la
     * restricción que trae el error, que cada motor informa a su manera. Un
     * título inexistente sigue siendo el 404 del título (la comprobación
     * previa; la carrera con la clave foránea la cubren los tests de borrado
     * concurrente).
     */
    @Test
    void laClaveForaneaDistingueUsuarioYTituloEnPostgres() {
        User ana = user("ana");
        Movie movie = movie("Pelicula");
        Series series = visibleSerie("Serie");
        long missingUser = ana.getId() + 1_000_000;

        assertThrows(UserNotFoundException.class, () -> likeService.likeMovie(missingUser, movie.getId()));
        assertThrows(UserNotFoundException.class, () -> likeService.likeSeries(missingUser, series.getId()));
        assertThrows(MovieNotFoundException.class, () -> likeService.likeMovie(ana.getId(), Long.MAX_VALUE));
        assertThrows(SeriesNotFoundException.class, () -> likeService.likeSeries(ana.getId(), Long.MAX_VALUE));
        assertEquals(0, likeRows("user_liked_movies", "movie_id", movie.getId())
                + likeRows("user_liked_series", "series_id", series.getId()));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
