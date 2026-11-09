package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
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

import com.emilio.streambox.dto.FavoriteGenresRequest;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.FavoriteGenresQueryContract;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.service.FavoriteGenreService;

/**
 * Géneros favoritos y su peso en las recomendaciones ({@code V11}) contra
 * <strong>PostgreSQL real</strong> (Testcontainers). Ejecuta los mismos casos
 * que la versión H2 ({@code FavoriteGenresRepositoryIntegrationTest}),
 * definidos en {@link FavoriteGenresQueryContract}.
 *
 * <p>
 * Qué puede cambiar el motor y por eso se repite: el {@code INSERT ... SELECT}
 * con parámetros en la lista de columnas (tipos de {@code :userId} y de un
 * {@link java.time.Instant} hacia {@code timestamptz}), el {@code IN} con una
 * colección, el {@code COALESCE} del {@code UPDATE} de la pregunta, la suma de
 * literales enteros en el {@code UNION ALL} de los pesos, {@code SELECT
 * EXISTS(...)} como booleano y el orden por nombre.
 * </p>
 *
 * <p>
 * Además, lo que H2 no puede demostrar: que el bloqueo de la fila del usuario
 * ({@code findForUpdateById}, {@code SELECT ... FOR UPDATE}) <b>serializa</b>
 * dos sustituciones simultáneas de la lista del mismo usuario, de modo que gana
 * entera la última y nunca queda una mezcla de las dos (que podría superar el
 * máximo de 5 géneros). Antes el cerrojo era el {@code UPDATE} de
 * {@code markGenresOnboarded}, que se hacía en cada {@code PUT}; ahora ese
 * {@code UPDATE} solo se hace si la pregunta estaba pendiente (BAJA-1).
 * </p>
 */
class PostgresFavoriteGenresIntegrationTest extends PostgresIntegrationTestSupport
        implements FavoriteGenresQueryContract {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private FavoriteGenreService favoriteGenreService;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Override
    public GenreRepository genres() {
        return genreRepository;
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
    public TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    // ------------------------------------------------------------------
    // Concurrencia real (solo PostgreSQL)
    // ------------------------------------------------------------------

    /**
     * El mecanismo, con los mismos pasos que {@code FavoriteGenreService}: la
     * primera sustitución bloquea la fila del usuario ({@code findForUpdateById}),
     * cierra la pregunta, borra e inserta {@code (a, b, c)} y mantiene la
     * transacción abierta. La segunda, con {@code (d, e, f)}, <b>espera</b> en
     * su {@code findForUpdateById} hasta que la primera confirma; entonces lee
     * la fila ya con la fecha puesta (no vuelve a cerrar la pregunta) y su
     * {@code DELETE} (una sentencia nueva, que ya ve lo confirmado) borra
     * {@code (a, b, c)} e inserta lo suyo. Resultado: exactamente
     * {@code (d, e, f)}. Sin el cerrojo, el {@code DELETE} de la segunda no
     * vería las filas aún sin confirmar de la primera y la lista acabaría con
     * los seis géneros. Se mide la espera para demostrar que el solapamiento
     * existió de verdad.
     */
    @Test
    void laSegundaSustitucionEsperaALaPrimeraYGanaEntera() throws Exception {
        List<Genre> six = new ArrayList<>();
        for (String name : List.of("a", "b", "c", "d", "e", "f")) {
            six.add(genre(name));
        }
        List<Long> first = six.subList(0, 3).stream().map(Genre::getId).toList();
        List<Long> second = six.subList(3, 6).stream().map(Genre::getId).toList();
        User ana = user("ana");
        TransactionTemplate tx = tx();
        CountDownLatch firstInserted = new CountDownLatch(1);
        long holdMillis = 600;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> one = pool.submit(() -> tx.executeWithoutResult(status -> {
                userRepository.findForUpdateById(ana.getId()).orElseThrow();
                userRepository.markGenresOnboarded(ana.getId(), BASE);
                genreRepository.clearFavorites(ana.getId());
                genreRepository.addFavorites(ana.getId(), first, BASE);
                firstInserted.countDown();
                sleep(holdMillis); // mantiene la transacción abierta sin confirmar
            }));
            Future<Long> two = pool.submit(() -> {
                firstInserted.await();
                long start = System.nanoTime();
                tx.executeWithoutResult(status -> {
                    User locked = userRepository.findForUpdateById(ana.getId()).orElseThrow();
                    assertEquals(BASE, locked.getGenresOnboardedAt(),
                            "tras esperar al cerrojo, lee la fecha que confirmó la primera");
                    genreRepository.clearFavorites(ana.getId());
                    genreRepository.addFavorites(ana.getId(), second, BASE.plusSeconds(60));
                });
                return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            });

            one.get(30, TimeUnit.SECONDS);
            long waited = two.get(30, TimeUnit.SECONDS);

            assertTrue(waited >= holdMillis / 2, "la segunda sustitución no esperó a la primera: " + waited + " ms");
            assertEquals(second, favoriteIds(ana));
            assertEquals(BASE, onboardedAt(ana), "conserva la fecha de la primera vez");
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Varias sustituciones simultáneas del mismo usuario (dos pestañas, doble
     * clic con listas distintas), en varias rondas con una barrera, por el
     * <b>servicio real</b> (cerrojo, pregunta, comparación con lo guardado,
     * borrado e inserción): ninguna falla (ni por la clave primaria) y al final
     * la lista es <b>exactamente una</b> de las pedidas, nunca una mezcla. A
     * partir de la segunda ronda, alguna petición encuentra ya guardada su
     * propia lista y no escribe nada: también ese camino va en fila.
     */
    @Test
    void variasSustitucionesSimultaneasDejanExactamenteUnaDeLasListas() throws Exception {
        List<Long> genreIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Genre genre = genre("genero " + i);
            movie("Película " + i, genre); // el servicio solo admite géneros con títulos visibles
            genreIds.add(genre.getId());
        }
        int threads = 6;
        List<List<Long>> lists = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            lists.add(List.of(genreIds.get(i), genreIds.get(i + 2), genreIds.get(i + 4)).stream().sorted().toList());
        }
        User ana = user("ana");
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 6; round++) {
                CyclicBarrier barrier = new CyclicBarrier(threads);
                List<Callable<Integer>> calls = new ArrayList<>();
                for (List<Long> list : lists) {
                    calls.add(() -> {
                        barrier.await(30, TimeUnit.SECONDS);
                        return favoriteGenreService.replaceFavoriteGenres(ana.getId(),
                                new FavoriteGenresRequest(list)).size();
                    });
                }
                for (Future<Integer> future : pool.invokeAll(calls)) {
                    assertEquals(3, future.get(60, TimeUnit.SECONDS), "ronda " + round);
                }
                List<Long> result = jdbc.queryForList("SELECT genre_id FROM user_favorite_genres WHERE user_id = ?"
                        + " ORDER BY genre_id", Long.class, ana.getId());
                assertTrue(lists.contains(result), "ronda " + round + ": mezcla de listas " + result);
            }
        } finally {
            pool.shutdownNow();
        }
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
