package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.emilio.streambox.dto.WatchProgressRequest;
import com.emilio.streambox.entity.Episode;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.EpisodeNotFoundException;
import com.emilio.streambox.exception.MovieNotFoundException;
import com.emilio.streambox.exception.UserNotFoundException;
import com.emilio.streambox.service.WatchProgressService;

/**
 * <p>
 * La API del progreso de visionado y de «Seguir viendo» contra
 * <strong>PostgreSQL real</strong> (Testcontainers). Las consultas en sí ya las
 * repite {@link PostgresWatchProgressIntegrationTest}; aquí, lo que depende del
 * motor en el <b>servicio</b>:
 * </p>
 * <ul>
 *   <li>Distinguir la clave foránea del usuario de la del título. Con
 *       {@code JdbcClient} no hay nombre de restricción de Hibernate y se lee
 *       del mensaje de PostgreSQL ({@code violates foreign key constraint
 *       "fk_..."}); además, tras el error la transacción queda abortada y el
 *       servicio no debe intentar ninguna consulta más.</li>
 *   <li>Varios guardados HTTP simultáneos del mismo título (dos pestañas, o
 *       el guardado periódico y el de la pausa): todos 204 y una sola fila.</li>
 * </ul>
 * <p>
 * Los casos del contrato HTTP están en {@code WatchProgressIntegrationTest}
 * (H2).
 * </p>
 */
class PostgresWatchProgressApiIntegrationTest extends PostgresIntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WatchProgressService watchProgressService;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    /**
     * La traducción de la clave foránea con el mensaje <b>de PostgreSQL</b>:
     * usuario inexistente → «Usuario no encontrado»; título inexistente → su
     * 404 (por la comprobación previa).
     */
    @Test
    void laClaveForaneaDistingueUsuarioYTituloEnPostgres() {
        User ana = saveUser("progana", Role.USER);
        Genre drama = saveGenre("Drama");
        Movie movie = saveMovie("Pelicula", 2000, drama);
        Series series = saveSeries("Serie", 2010, drama);
        Episode episode = saveEpisode(series, 1, 1);
        long missingUser = ana.getId() + 1_000_000;
        WatchProgressRequest request = new WatchProgressRequest(10, 100);

        assertThrows(UserNotFoundException.class,
                () -> watchProgressService.saveMovieProgress(missingUser, movie.getId(), request));
        assertThrows(UserNotFoundException.class,
                () -> watchProgressService.saveEpisodeProgress(missingUser, episode.getId(), request));
        assertThrows(MovieNotFoundException.class,
                () -> watchProgressService.saveMovieProgress(ana.getId(), Long.MAX_VALUE, request));
        assertThrows(EpisodeNotFoundException.class,
                () -> watchProgressService.saveEpisodeProgress(ana.getId(), Long.MAX_VALUE, request));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_movie_progress", Integer.class)
                + jdbc.queryForObject("SELECT COUNT(*) FROM user_episode_progress", Integer.class));
    }

    /**
     * Ocho {@code PUT} simultáneos del primer guardado de la misma película:
     * todos 204 (ninguno pierde la carrera del {@code INSERT} con un 500), una
     * sola fila, y «Seguir viendo» la muestra una vez con uno de los valores
     * enviados.
     */
    @Test
    void guardadosSimultaneosDelMismoTituloSonTodos204() throws Exception {
        User ana = saveUser("progconc", Role.USER);
        Movie movie = saveMovie("Pelicula", 2000, saveGenre("Drama"));
        String token = tokenFor(ana);
        int threads = 8;
        CyclicBarrier start = new CyclicBarrier(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int position = 10 + i;
                Callable<Integer> save = () -> {
                    start.await(10, TimeUnit.SECONDS);
                    return mockMvc.perform(put("/api/users/me/progress/movies/" + movie.getId())
                            .header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"positionSeconds\":" + position + ",\"durationSeconds\":100}"))
                            .andReturn().getResponse().getStatus();
                };
                results.add(pool.submit(save));
            }
            for (Future<Integer> result : results) {
                assertEquals(204, result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_movie_progress WHERE user_id = ?",
                Integer.class, ana.getId()));
        mockMvc.perform(get("/api/users/me/continue-watching").header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("MOVIE"))
                .andExpect(jsonPath("$[0].id").value(movie.getId()));
    }
}
