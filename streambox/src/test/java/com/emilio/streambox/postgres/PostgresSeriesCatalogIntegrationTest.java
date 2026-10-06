package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.service.SeriesFavoriteService;
import com.emilio.streambox.service.SeriesService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManagerFactory;

/**
 * Catálogo y "Mi lista" de series sobre un <b>PostgreSQL real</b> (revisión de
 * QA, fase 5): lo que H2 podría ocultar.
 *
 * <ul>
 * <li><b>Búsqueda:</b> {@code lower(...) LIKE ... ESCAPE} con letras cuya
 * minúscula difiere entre Java y PostgreSQL ({@code İ}, sigma final) y con los
 * comodines escapados; es el código compartido {@code LikePatterns}, que ya
 * ocultó un bug en películas.</li>
 * <li><b>Visibilidad:</b> el {@code EXISTS} correlacionado que oculta las
 * series sin episodios en el listado, en la búsqueda y en el total.</li>
 * <li><b>Recuentos:</b> la consulta agrupada {@code count(distinct ...)} de
 * temporadas y episodios, y que una página o "Mi lista" no ejecuten una
 * consulta por serie.</li>
 * <li><b>Concurrencia real:</b> en PostgreSQL la segunda inserción espera al
 * bloqueo de la primera y después falla con 23505; el cliente debe ver un 409,
 * nunca un 500 ni un duplicado.</li>
 * <li><b>Géneros:</b> {@code countByGenres_Id} de series en el mensaje del
 * 409.</li>
 * </ul>
 *
 * <p>
 * Sin Docker la clase se omite (ver {@link PostgresIntegrationTestSupport}).
 * Datos confirmados de verdad y limpieza antes y después de cada test.
 * </p>
 */
class PostgresSeriesCatalogIntegrationTest extends PostgresIntegrationTestSupport {

    private static final String SERIES = "/api/series";
    private static final String SEARCH = SERIES + "/search";
    private static final String SERIES_FAVORITES = "/api/users/me/favorites/series";

    @Autowired private MockMvc mockMvc;
    @Autowired private SeriesService seriesService;
    @Autowired private SeriesFavoriteService seriesFavoriteService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User user;
    private String userToken;
    private String adminToken;
    private Genre drama;
    private Genre comedy;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        user = saveUser("pgseriesuser", Role.USER);
        userToken = tokenFor(user);
        adminToken = tokenFor(saveUser("pgseriesadmin", Role.ADMIN));
        drama = saveGenre("Drama");
        comedy = saveGenre("Comedia");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Búsqueda
    // ------------------------------------------------------------------

    /**
     * Un título con letras de minusculización especial se encuentra buscándolo
     * entero: los dos lados del {@code LIKE} se minusculizan con el
     * {@code lower()} de PostgreSQL, no uno en Java y otro en la base de datos.
     */
    @ParameterizedTest
    @ValueSource(strings = { "İstanbul", "ΟΔΥΣΣΕΥΣ", "Straße", "ÑANDÚ salvaje" })
    void unTituloSeEncuentraBuscandoloEntero(String title) throws Exception {
        visible(title);
        visible("Otra");

        assertEquals(List.of(title), titles(search("title", title)));
    }

    /** Los comodines y la barra invertida se buscan como texto literal también en PostgreSQL. */
    @Test
    void losComodinesSeBuscanComoTextoLiteral() throws Exception {
        visible("100% real");
        visible("a_c");
        visible("abc");
        visible("Ruta C:\\series");

        assertEquals(List.of("100% real"), titles(search("title", "%")));
        assertEquals(List.of("a_c"), titles(search("title", "a_c")));
        assertEquals(List.of("Ruta C:\\series"), titles(search("title", "\\")));
        assertEquals(List.of(), titles(search("title", "%%")));
    }

    // ------------------------------------------------------------------
    // Visibilidad y recuentos
    // ------------------------------------------------------------------

    /**
     * Las series vacías no salen ni cuentan en el listado ni en la búsqueda,
     * y los recuentos agrupados de las visibles son correctos (temporadas
     * distintas, no episodios).
     */
    @Test
    void lasSeriesVaciasNoSeCuentanYLosRecuentosSonCorrectos() throws Exception {
        Series full = saveSeries("Llena", 2010, drama);
        saveEpisode(full, 1, 1);
        saveEpisode(full, 1, 2);
        saveEpisode(full, 3, 1);
        saveSeries("Vacia", 2010, drama);

        JsonNode list = json(mockMvc.perform(get(SERIES).header("Authorization", userToken))
                .andExpect(status().isOk()));
        assertEquals(1, list.get("totalElements").asInt());
        assertEquals("Llena", list.get("content").get(0).get("title").asText());
        assertEquals(2, list.get("content").get(0).get("seasonCount").asInt());
        assertEquals(3, list.get("content").get(0).get("episodeCount").asInt());
        assertEquals(1, search("genreId", String.valueOf(drama.getId())).get("totalElements").asInt());

        JsonNode admin = json(mockMvc.perform(get("/api/admin/series").header("Authorization", adminToken))
                .andExpect(status().isOk()));
        assertEquals(2, admin.get("totalElements").asInt());
    }

    /** Una página de 5 o de 25 series y "Mi lista" con 12 cuestan lo mismo en PostgreSQL. */
    @Test
    void niLaPaginaNiLaListaEjecutanUnaConsultaPorSerie() {
        for (int i = 0; i < 30; i++) {
            Series series = saveSeries(String.format("Serie %02d", i), 2000, drama, comedy);
            saveEpisode(series, 1, 1);
            saveEpisode(series, 2, 1);
            if (i < 12) {
                new TransactionTemplate(transactionManager)
                        .executeWithoutResult(s -> seriesRepository.addFavorite(user.getId(), series.getId()));
            }
        }
        Sort order = Sort.by("title").and(Sort.by("id"));

        long five = statementsFor(() -> seriesService.getSeries(PageRequest.of(0, 5, order)));
        long twentyFive = statementsFor(() -> seriesService.getSeries(PageRequest.of(0, 25, order)));
        long favorites = statementsFor(() -> seriesFavoriteService.getFavorites(user.getId()));

        assertTrue(five <= 4, "página de 5: " + five);
        assertEquals(five, twentyFive);
        assertTrue(favorites <= 3, "lista de 12: " + favorites);
    }

    // ------------------------------------------------------------------
    // Concurrencia real
    // ------------------------------------------------------------------

    /**
     * Cuatro altas simultáneas del mismo episodio: un 201 y tres 409. En
     * PostgreSQL la violación de unicidad aborta la transacción; el servicio
     * debe traducirla al 409 de dominio sin intentar nada más en ella.
     */
    @Test
    void altasSimultaneasDelMismoEpisodioDanUnExitoYElRestoConflicto() throws Exception {
        Series series = saveSeries("Concurrente", 2010, drama);
        String url = SERIES + "/" + series.getId() + "/episodes";

        for (int round = 1; round <= 3; round++) {
            String body = "{\"seasonNumber\":1,\"episodeNumber\":" + round + ",\"title\":\"t\",\"duration\":40,"
                    + "\"videoUrl\":\"https://example.com/v.mp4\"}";
            List<Integer> statuses = concurrently(4, () -> mockMvc.perform(post(url)
                            .header("Authorization", adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn().getResponse().getStatus());

            assertEquals(List.of(201, 409, 409, 409), statuses.stream().sorted().toList(), "ronda " + round);
        }
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM episodes WHERE series_id = ?",
                Integer.class, series.getId()));
    }

    /** Cuatro altas simultáneas del mismo favorito de series: un 204, tres 409 y una sola fila. */
    @Test
    void altasSimultaneasDelMismoFavoritoDanUnExitoYElRestoConflicto() throws Exception {
        for (int round = 1; round <= 3; round++) {
            Series series = visible("Favorita " + round);
            String url = SERIES_FAVORITES + "/" + series.getId();

            List<Integer> statuses = concurrently(4, () -> mockMvc.perform(post(url)
                            .header("Authorization", userToken))
                    .andReturn().getResponse().getStatus());

            assertEquals(List.of(204, 409, 409, 409), statuses.stream().sorted().toList(), "ronda " + round);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM user_favorite_series WHERE series_id = ?",
                    Integer.class, series.getId()));
        }
    }

    // ------------------------------------------------------------------
    // Géneros
    // ------------------------------------------------------------------

    /** El recuento de series que usan un género (también ocultas) llega al mensaje del 409. */
    @Test
    void borrarUnGeneroQueUsanPeliculasYSeriesDaElMensajeConAmbosRecuentos() throws Exception {
        saveMovie("Peli", 2000, comedy);
        saveSeries("Oculta", 2010, comedy);
        saveSeries("Otra oculta", 2011, comedy);
        visible("Con otro genero");

        mockMvc.perform(delete("/api/genres/" + comedy.getId()).header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENRE_IN_USE"))
                .andExpect(jsonPath("$.message").value("No se puede eliminar el género \"Comedia\": lo usan "
                        + "1 película y 2 series. Quítalo de esa película y de esas series antes de borrarlo."));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private <T> List<T> concurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Serie visible (un episodio) del año 2000 con el género Drama. */
    private Series visible(String title) {
        Series series = saveSeries(title, 2000, drama);
        saveEpisode(series, 1, 1);
        return series;
    }

    /** Busca pasando el valor con {@code .param} (sin codificar dos veces {@code %} ni {@code \}). */
    private JsonNode search(String parameter, String value) throws Exception {
        MockHttpServletRequestBuilder request = get(SEARCH).header("Authorization", userToken).param(parameter, value);
        return json(mockMvc.perform(request).andExpect(status().isOk()));
    }

    private JsonNode json(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static List<String> titles(JsonNode page) {
        List<String> titles = new ArrayList<>();
        page.get("content").forEach(node -> titles.add(node.get("title").asText()));
        return titles;
    }

    private long statementsFor(Runnable operation) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        operation.run();
        return statistics.getPrepareStatementCount();
    }
}
