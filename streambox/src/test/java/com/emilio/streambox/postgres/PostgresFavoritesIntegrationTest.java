package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.exception.MovieNotFoundException;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.service.FavoriteService;

/**
 * "Mi lista" ({@code /api/users/me/favorites}) contra <b>PostgreSQL real</b>: el
 * {@code INSERT}/{@code DELETE} nativos sobre {@code user_favorite_movies}, el
 * error real de unicidad y, sobre todo, la <b>concurrencia</b>.
 *
 * <p>
 * Qué protege frente a H2: la traducción de la violación de clave primaria de
 * PostgreSQL ({@code SQLSTATE 23505}) a {@code 409 MOVIE_ALREADY_IN_FAVORITES},
 * el bloqueo real entre transacciones que insertan la misma pareja (H2 lo
 * resuelve con otro modelo de bloqueos) y los borrados en cascada.
 * </p>
 *
 * <p>
 * Los datos se confirman de verdad y se limpian antes y después de cada test.
 * </p>
 */
class PostgresFavoritesIntegrationTest extends PostgresIntegrationTestSupport {

    private static final String FAVORITES = "/api/users/me/favorites";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private Long aliceId;
    private Long bobId;
    private String aliceToken;
    private String bobToken;
    private String adminToken;
    private Movie dune;
    private Movie alien;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        var alice = saveUser("alice", Role.USER);
        var bob = saveUser("bob", Role.USER);
        aliceId = alice.getId();
        bobId = bob.getId();
        aliceToken = tokenFor(alice);
        bobToken = tokenFor(bob);
        adminToken = tokenFor(saveUser("admin", Role.ADMIN));
        Genre scifi = saveGenre("Scifi");
        dune = saveMovie("Dune", 2021, scifi);
        alien = saveMovie("Alien", 1979, scifi);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Comportamiento HTTP
    // ------------------------------------------------------------------

    /** Añadir, listar y quitar: las sentencias nativas funcionan en PostgreSQL. */
    @Test
    void anadirListarYQuitarUnaPelicula() throws Exception {
        add(aliceToken, dune.getId());
        add(aliceToken, alien.getId());

        mockMvc.perform(get(FAVORITES).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("Alien"))   // ordenadas por título
                .andExpect(jsonPath("$[0].genres[0].name").value("Scifi"))
                .andExpect(jsonPath("$[1].title").value("Dune"));

        mockMvc.perform(delete(FAVORITES + "/" + dune.getId()).header("Authorization", aliceToken))
                .andExpect(status().isNoContent());

        assertEquals(List.of(alien.getId()), favoriteMovieIds(aliceId));
    }

    /** Duplicado en dos peticiones consecutivas: 409 con su código y una sola fila. */
    @Test
    void anadirDosVecesLaMismaPeliculaDa409() throws Exception {
        add(aliceToken, dune.getId());

        mockMvc.perform(post(FAVORITES + "/" + dune.getId()).header("Authorization", aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MOVIE_ALREADY_IN_FAVORITES"));

        assertEquals(1, favoriteMovieIds(aliceId).size());
    }

    /** Quitar una película que no estaba en la lista: 404 con código específico. */
    @Test
    void quitarUnaPeliculaQueNoEstabaDa404() throws Exception {
        mockMvc.perform(delete(FAVORITES + "/" + dune.getId()).header("Authorization", aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MOVIE_NOT_IN_FAVORITES"));
    }

    /** Película inexistente: 404 genérico (no llega a ejecutar el INSERT). */
    @Test
    void anadirUnaPeliculaInexistenteDa404() throws Exception {
        mockMvc.perform(post(FAVORITES + "/987654").header("Authorization", aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /** Por título, sin distinguir mayúsculas, y duplicado -> 409. */
    @Test
    void anadirPorTituloIgnoraMayusculasYDetectaDuplicados() throws Exception {
        mockMvc.perform(post(FAVORITES + "/by-title").param("title", "dUnE").header("Authorization", aliceToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(FAVORITES + "/by-title").param("title", "DUNE").header("Authorization", aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MOVIE_ALREADY_IN_FAVORITES"));
    }

    /** Vaciar la lista de un usuario no toca la de otro y es idempotente. */
    @Test
    void vaciarLaListaSoloAfectaAlUsuarioAutenticado() throws Exception {
        add(aliceToken, dune.getId());
        add(aliceToken, alien.getId());
        add(bobToken, dune.getId());

        mockMvc.perform(delete(FAVORITES).header("Authorization", aliceToken)).andExpect(status().isNoContent());
        mockMvc.perform(delete(FAVORITES).header("Authorization", aliceToken)).andExpect(status().isNoContent());

        assertEquals(List.of(), favoriteMovieIds(aliceId));
        assertEquals(List.of(dune.getId()), favoriteMovieIds(bobId));
    }

    /**
     * Borrar una película desde la API retira sus favoritos de todos los usuarios.
     * En una base nueva lo garantiza además el {@code ON DELETE CASCADE}.
     */
    @Test
    void borrarUnaPeliculaConFavoritosLaRetiraDeTodasLasListas() throws Exception {
        add(aliceToken, dune.getId());
        add(bobToken, dune.getId());
        add(bobToken, alien.getId());

        mockMvc.perform(delete("/api/movies/" + dune.getId()).header("Authorization", adminToken))
                .andExpect(status().isNoContent());

        assertEquals(List.of(), favoriteMovieIds(aliceId));
        assertEquals(List.of(alien.getId()), favoriteMovieIds(bobId));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_favorite_movies WHERE movie_id = ?", Integer.class, dune.getId()));
    }

    /**
     * Cascada pura de la base de datos (sin pasar por el servicio): borrar la
     * película con SQL directo, como haría un administrador desde psql, no
     * deja filas huérfanas ni falla.
     */
    @Test
    void laCascadaDeLaBaseDeDatosLimpiaLosFavoritosAunSinPasarPorElServicio() throws Exception {
        add(aliceToken, dune.getId());

        jdbc.update("DELETE FROM movies WHERE id = ?", dune.getId());

        assertEquals(List.of(), favoriteMovieIds(aliceId));
    }

    // ------------------------------------------------------------------
    // Errores reales de PostgreSQL en las consultas nativas
    // ------------------------------------------------------------------

    /**
     * El {@code INSERT} nativo repetido lanza la violación de unicidad real de
     * PostgreSQL (23505, sobre {@code user_favorite_movies_pkey}), traducida a
     * {@link DataIntegrityViolationException}: es lo que captura
     * {@code FavoriteService.insertFavorite} para convertirlo en 409.
     */
    @Test
    void elInsertNativoDuplicadoLanzaLaViolacionDeUnicidadDePostgreSql() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(s -> userRepository.addFavorite(aliceId, dune.getId()));

        DataIntegrityViolationException error = org.junit.jupiter.api.Assertions.assertThrows(
                DataIntegrityViolationException.class,
                () -> tx.executeWithoutResult(s -> userRepository.addFavorite(aliceId, dune.getId())));

        assertEquals("23505", sqlState(error));
        assertTrue(error.getMostSpecificCause().getMessage().contains("user_favorite_movies_pkey"),
                error.getMostSpecificCause().getMessage());
    }

    /**
     * El {@code INSERT} nativo con una película que ya no existe lanza una
     * violación de CLAVE FORÁNEA (23503), no de unicidad.
     * {@code FavoriteService} distingue ambos SQLSTATE (ver el test siguiente).
     */
    @Test
    void elInsertNativoDeUnaPeliculaInexistenteEsUnaViolacionDeClaveForanea() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        DataIntegrityViolationException error = org.junit.jupiter.api.Assertions.assertThrows(
                DataIntegrityViolationException.class,
                () -> tx.executeWithoutResult(s -> userRepository.addFavorite(aliceId, 987654L)));

        assertEquals("23503", sqlState(error));
    }

    /**
     * La carrera real "la película se borra entre {@code existsById} y el
     * {@code INSERT}": el repositorio de películas simulado responde que existe,
     * pero el {@code INSERT} real contra PostgreSQL falla por clave foránea
     * (23503). {@code FavoriteService} debe responder con 404 de dominio
     * ({@code MovieNotFoundException}) y no con "ya está en favoritos" (409). La
     * película queda sin fila en la tabla de unión.
     */
    @Test
    void siLaPeliculaDesapareceEntreLaComprobacionYElInsertSeTraduceA404() {
        MovieRepository staleMovies = mock(MovieRepository.class);
        when(staleMovies.existsById(987654L)).thenReturn(true); // dato "obsoleto"
        FavoriteService service = new FavoriteService(userRepository, staleMovies);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        org.junit.jupiter.api.Assertions.assertThrows(MovieNotFoundException.class,
                () -> tx.executeWithoutResult(s -> service.addFavorite(aliceId, 987654L)));

        assertEquals(List.of(), favoriteMovieIds(aliceId));
    }

    /** {@code DELETE} nativo: devuelve el número real de filas (0 si no existía, n al vaciar). */
    @Test
    void losDeleteNativosDevuelvenElNumeroRealDeFilas() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(s -> {
            userRepository.addFavorite(aliceId, dune.getId());
            userRepository.addFavorite(aliceId, alien.getId());
        });

        assertEquals(0, (int) tx.execute(s -> userRepository.removeFavorite(bobId, dune.getId())));
        assertEquals(1, (int) tx.execute(s -> userRepository.removeFavorite(aliceId, dune.getId())));
        assertEquals(1, (int) tx.execute(s -> userRepository.clearFavorites(aliceId)));
        assertEquals(0, (int) tx.execute(s -> userRepository.clearFavorites(aliceId)));
    }

    // ------------------------------------------------------------------
    // Concurrencia real
    // ------------------------------------------------------------------

    /**
     * Dos transacciones insertan la misma pareja: en PostgreSQL la segunda
     * <b>se bloquea</b> esperando a la primera y, cuando esta confirma, falla con
     * 23505. Es el mecanismo exacto en el que se apoya el
     * {@code catch (DataIntegrityViolationException)} de
     * {@code FavoriteService}. Se mide el tiempo para demostrar que el bloqueo
     * existió (la segunda no falló al instante).
     */
    @Test
    void laSegundaTransaccionQueInsertaLaMismaParejaSeBloqueaYFallaAlConfirmarLaPrimera() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch firstInserted = new CountDownLatch(1);
        long holdMillis = 600;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> tx.executeWithoutResult(s -> {
                userRepository.addFavorite(aliceId, dune.getId());
                firstInserted.countDown();
                sleep(holdMillis); // mantiene la transacción abierta sin confirmar
            }));
            Future<Long> second = pool.submit(() -> {
                firstInserted.await();
                long start = System.nanoTime();
                try {
                    tx.executeWithoutResult(s -> userRepository.addFavorite(aliceId, dune.getId()));
                    throw new AssertionError("el segundo INSERT debía fallar");
                } catch (DataIntegrityViolationException e) {
                    assertEquals("23505", sqlState(e));
                    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                }
            });

            first.get(30, TimeUnit.SECONDS);
            long waited = second.get(30, TimeUnit.SECONDS);

            assertTrue(waited >= holdMillis / 2, "el segundo INSERT no esperó a la primera transacción: " + waited + " ms");
            assertEquals(List.of(dune.getId()), favoriteMovieIds(aliceId));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Varias peticiones HTTP simultáneas añaden la MISMA película al mismo
     * usuario: exactamente una tiene éxito (204), todas las demás reciben 409
     * {@code MOVIE_ALREADY_IN_FAVORITES} y ninguna acaba en 500. Varias rondas
     * con una barrera para maximizar el solapamiento y que algunas peticiones
     * pasen la comprobación previa {@code isFavorite} antes de que se inserte la
     * primera, de modo que el duplicado lo detecte la clave primaria.
     */
    @Test
    void variasPeticionesSimultaneasConLaMismaPeliculaDanUnExitoYElRestoConflicto() throws Exception {
        int threads = 8;
        int rounds = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < rounds; round++) {
                Movie movie = saveMovie("Ronda " + round, 2000);
                CyclicBarrier barrier = new CyclicBarrier(threads);
                List<Callable<int[]>> calls = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    calls.add(() -> {
                        barrier.await(30, TimeUnit.SECONDS);
                        var result = mockMvc.perform(post(FAVORITES + "/" + movie.getId())
                                .header("Authorization", aliceToken)).andReturn().getResponse();
                        boolean hasCode = result.getContentAsString().contains("MOVIE_ALREADY_IN_FAVORITES");
                        return new int[] { result.getStatus(), hasCode ? 1 : 0 };
                    });
                }

                int created = 0;
                int conflicts = 0;
                for (Future<int[]> future : pool.invokeAll(calls)) {
                    int[] response = future.get(60, TimeUnit.SECONDS);
                    switch (response[0]) {
                        case 204 -> created++;
                        case 409 -> {
                            conflicts++;
                            assertEquals(1, response[1], "409 sin el código MOVIE_ALREADY_IN_FAVORITES");
                        }
                        default -> throw new AssertionError("ronda " + round + ": estado inesperado " + response[0]);
                    }
                }

                assertEquals(1, created, "ronda " + round + ": debe haber exactamente un éxito");
                assertEquals(threads - 1, conflicts, "ronda " + round);
                assertEquals(1, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM user_favorite_movies WHERE user_id = ? AND movie_id = ?",
                        Integer.class, aliceId, movie.getId()));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Peticiones simultáneas de DISTINTAS películas para el mismo usuario: todas
     * tienen éxito (no hay bloqueos cruzados ni interbloqueos entre filas
     * distintas de la misma tabla).
     */
    @Test
    void variasPeticionesSimultaneasConPeliculasDistintasTienenExitoTodas() throws Exception {
        int threads = 8;
        List<Movie> movies = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            movies.add(saveMovie("Distinta " + i, 2000));
        }
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Integer>> calls = movies.stream().<Callable<Integer>>map(movie -> () -> {
                barrier.await(30, TimeUnit.SECONDS);
                return mockMvc.perform(post(FAVORITES + "/" + movie.getId()).header("Authorization", aliceToken))
                        .andReturn().getResponse().getStatus();
            }).toList();

            for (Future<Integer> future : pool.invokeAll(calls)) {
                assertEquals(204, future.get(60, TimeUnit.SECONDS));
            }
            assertEquals(threads, favoriteMovieIds(aliceId).size());
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Añadir y quitar la misma película a la vez nunca deja un estado
     * incoherente ni un 500: cada petición responde 204 o su error de negocio
     * (409/404) y al final la pareja existe 0 o 1 veces.
     */
    @Test
    void anadirYQuitarLaMismaPeliculaALaVezNoDaErroresDelServidor() throws Exception {
        int threads = 8;
        for (int round = 0; round < 5; round++) {
            Movie movie = saveMovie("Carrera " + round, 2000);
            CyclicBarrier barrier = new CyclicBarrier(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            try {
                List<Callable<Integer>> calls = new ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    boolean adding = i % 2 == 0;
                    calls.add(() -> {
                        barrier.await(30, TimeUnit.SECONDS);
                        var request = adding ? post(FAVORITES + "/" + movie.getId())
                                : delete(FAVORITES + "/" + movie.getId());
                        return mockMvc.perform(request.header("Authorization", aliceToken))
                                .andReturn().getResponse().getStatus();
                    });
                }
                for (Future<Integer> future : pool.invokeAll(calls)) {
                    int statusCode = future.get(60, TimeUnit.SECONDS);
                    assertTrue(statusCode == 204 || statusCode == 409 || statusCode == 404,
                            "ronda " + round + ": estado inesperado " + statusCode);
                }
            } finally {
                pool.shutdownNow();
            }
            int rows = jdbc.queryForObject("SELECT COUNT(*) FROM user_favorite_movies WHERE movie_id = ?",
                    Integer.class, movie.getId());
            assertTrue(rows == 0 || rows == 1, "filas: " + rows);
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private void add(String token, Long movieId) throws Exception {
        mockMvc.perform(post(FAVORITES + "/" + movieId).header("Authorization", token))
                .andExpect(status().isNoContent());
    }

    private List<Long> favoriteMovieIds(Long userId) {
        return jdbc.queryForList(
                "SELECT movie_id FROM user_favorite_movies WHERE user_id = ? ORDER BY movie_id", Long.class, userId);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
