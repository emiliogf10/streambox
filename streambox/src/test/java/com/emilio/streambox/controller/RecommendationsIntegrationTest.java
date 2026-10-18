package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.emilio.streambox.dto.RecommendationsResponse;
import com.emilio.streambox.entity.Episode;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.emilio.streambox.service.LikeService;
import com.emilio.streambox.service.RecommendationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import jakarta.persistence.EntityManagerFactory;

/**
 * Tests de punta a punta de {@code GET /api/users/me/recommendations} («Recomendadas
 * para ti»), con datos confirmados de verdad y limpieza antes y después de
 * cada test.
 *
 * <p>
 * Los casos finos del criterio (cada desempate, los pesos, H2 frente a
 * PostgreSQL) están en {@code repository.LikesQueryContract}; aquí se comprueba
 * que el endpoint los respeta de punta a punta (orden, exclusión de lo que ya
 * gusta y de las series ocultas, {@code basedOnLikes}), el contrato HTTP
 * (límite por defecto y rango, 401) y el coste fijo en sentencias SQL. Usa las
 * mismas propiedades que {@link CatalogIntegrationTest} para compartir su
 * contexto de Spring.
 * </p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class RecommendationsIntegrationTest {

    private static final String RECOMMENDATIONS = "/api/users/me/recommendations";

    /** Instante base para fijar {@code created_at} a mano. */
    private static final Instant BASE = Instant.parse("2024-01-01T00:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private LikeService likeService;
    @Autowired private RecommendationService recommendationService;
    @Autowired private MovieRepository movieRepository;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private User alice;
    private String aliceToken;
    private Genre drama;
    private Genre scifi;
    private Genre comedy;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        alice = saveUser("recoalice");
        aliceToken = "Bearer " + jwtService.generateToken(alice);
        drama = saveGenre("Drama");
        scifi = saveGenre("Ciencia ficcion");
        comedy = saveGenre("Comedia");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Sin «me gusta»
    // ------------------------------------------------------------------

    /** Un usuario sin «me gusta» recibe {@code basedOnLikes: false} y dos listas vacías (no {@code null}). */
    @Test
    void sinMeGustaNoHayRecomendaciones() throws Exception {
        saveMovie("Una película cualquiera", drama);

        mockMvc.perform(get(RECOMMENDATIONS).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basedOnLikes").value(false))
                .andExpect(jsonPath("$.movies").isArray())
                .andExpect(jsonPath("$.movies.length()").value(0))
                .andExpect(jsonPath("$.series").isArray())
                .andExpect(jsonPath("$.series.length()").value(0));
    }

    /**
     * Un «me gusta» de una serie que se ha quedado sin episodios no cuenta: el
     * usuario no la ve en su perfil, así que tampoco puede basar nada.
     */
    @Test
    void unMeGustaDeUnaSerieOcultaNoCuenta() throws Exception {
        Series hidden = saveSeries("Oculta", drama);
        saveMovie("Candidata", drama);
        jdbc.update("INSERT INTO user_liked_series (user_id, series_id, created_at) VALUES (?, ?, ?)",
                alice.getId(), hidden.getId(), OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));

        RecommendationsResponse response = recommendations("");

        assertFalse(response.basedOnLikes());
        assertEquals(List.of(), response.movies());
    }

    // ------------------------------------------------------------------
    // Orden y exclusiones
    // ------------------------------------------------------------------

    /**
     * Pesos: le gustan una película (Drama + Ciencia ficción) y una serie
     * (Drama), así que Drama pesa 2 y Ciencia ficción 1. Las candidatas puntúan
     * la suma de sus géneros; a igual puntuación, la creada después y luego el
     * id mayor. No salen lo que ya le gusta, lo que no comparte géneros ni las
     * series sin episodios. Los ids crecen al revés del orden esperado para que
     * el id no acierte por casualidad.
     */
    @Test
    void ordenaPorPesoDeGenerosYExcluyeLoQueYaGustaYLasSeriesOcultas() throws Exception {
        Movie scifiOnly = saveMovie("Solo ciencia ficción", scifi, comedy);   // 1
        Movie dramaTwinA = saveMovie("Drama gemela A", drama);                // 2
        Movie dramaTwinB = saveMovie("Drama gemela B", drama);                // 2
        Movie dramaNewer = saveMovie("Drama más nueva", drama);               // 2
        Movie both = saveMovie("Drama y ciencia ficción", drama, scifi);      // 3
        saveMovie("Comedia", comedy);                                          // no candidata
        Movie liked = saveMovie("La que me gusta", drama, scifi);
        setCreatedAt("movies", dramaTwinA.getId(), BASE);
        setCreatedAt("movies", dramaTwinB.getId(), BASE);
        setCreatedAt("movies", dramaNewer.getId(), BASE.plusSeconds(60));

        Series dramaSeries = saveSeries("Serie drama", drama);                 // 2
        saveEpisode(dramaSeries);
        Series bothSeries = saveSeries("Serie drama y ciencia ficción", drama, scifi);  // 3
        saveEpisode(bothSeries);
        saveSeries("Serie oculta", drama, scifi);                              // sin episodios
        Series likedSeries = saveSeries("La serie que me gusta", drama);
        saveEpisode(likedSeries);

        likeViaApi("movies", liked.getId());
        likeViaApi("series", likedSeries.getId());

        RecommendationsResponse response = recommendations("");

        assertTrue(response.basedOnLikes());
        assertEquals(List.of(both.getId(), dramaNewer.getId(), dramaTwinB.getId(), dramaTwinA.getId(),
                scifiOnly.getId()), response.movies().stream().map(m -> m.id()).toList());
        assertEquals(List.of(bothSeries.getId(), dramaSeries.getId()),
                response.series().stream().map(s -> s.id()).toList());
        assertEquals(1, response.series().get(0).episodeCount());
        assertEquals("Drama y ciencia ficción", response.movies().get(0).title());
    }

    /** Lo que empieza a gustar sale de las recomendaciones; al quitar el «me gusta», vuelve. */
    @Test
    void darYQuitarUnMeGustaCambiaLasRecomendaciones() throws Exception {
        Movie liked = saveMovie("La que me gusta", drama);
        Movie candidate = saveMovie("Candidata", drama);
        likeService.likeMovie(alice.getId(), liked.getId());

        assertEquals(List.of(candidate.getId()), movieIds(recommendations("")));

        likeService.likeMovie(alice.getId(), candidate.getId());
        RecommendationsResponse allLiked = recommendations("");
        assertTrue(allLiked.basedOnLikes(), "tiene «me gusta», solo que no queda nada nuevo");
        assertEquals(List.of(), allLiked.movies());

        likeService.unlikeMovie(alice.getId(), candidate.getId());
        assertEquals(List.of(candidate.getId()), movieIds(recommendations("")));
    }

    /** Los «me gusta» de otro usuario no influyen en los míos. */
    @Test
    void losMeGustaDeOtroUsuarioNoInfluyen() throws Exception {
        User bob = saveUser("recobob");
        Movie bobsMovie = saveMovie("La de Bob", comedy);
        saveMovie("Otra comedia", comedy);
        likeService.likeMovie(bob.getId(), bobsMovie.getId());

        assertFalse(recommendations("").basedOnLikes());
    }

    // ------------------------------------------------------------------
    // Límite
    // ------------------------------------------------------------------

    /** Sin {@code limit}, 12 por tipo; con uno explícito, el principio exacto del orden completo. */
    @Test
    void elLimitePorDefectoEs12YElExplicitoCortaElOrdenCompleto() throws Exception {
        Movie liked = saveMovie("La que me gusta", drama);
        likeService.likeMovie(alice.getId(), liked.getId());
        for (int i = 1; i <= 15; i++) {
            saveMovie("Candidata " + i, drama);
            Series series = saveSeries("Serie " + i, drama);
            saveEpisode(series);
        }

        RecommendationsResponse all = recommendations("limit=24");
        RecommendationsResponse byDefault = recommendations("");
        RecommendationsResponse three = recommendations("limit=3");

        assertEquals(15, all.movies().size());
        assertEquals(15, all.series().size());
        assertEquals(movieIds(all).subList(0, 12), movieIds(byDefault));
        assertEquals(seriesIds(all).subList(0, 12), seriesIds(byDefault));
        assertEquals(movieIds(all).subList(0, 3), movieIds(three));
        assertEquals(seriesIds(all).subList(0, 3), seriesIds(three));
    }

    @ParameterizedTest
    @CsvSource({
            "0, El límite mínimo es 1",
            "-1, El límite mínimo es 1",
            "25, El límite máximo es 24",
            "abc, El valor no tiene el formato esperado",
            "99999999999, El valor no tiene el formato esperado"
    })
    void unLimiteFueraDeRangoONoNumericoDa400(String limit, String message) throws Exception {
        mockMvc.perform(get(RECOMMENDATIONS).param("limit", limit).header("Authorization", aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.limit").value(message));
    }

    @Test
    void sinSesionDa401() throws Exception {
        mockMvc.perform(get(RECOMMENDATIONS))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Petición de {@code security} (paso 2.4): {@code HEAD} y {@code OPTIONS}
     * sin sesión también dan 401. Las recomendaciones dependen de lo que te
     * gusta, así que una regla futura atada solo a {@code GET} no debe
     * dejarlas abiertas por otro método. Con sesión, {@code HEAD} es 200.
     */
    @Test
    void headYOptionsSinSesionDan401() throws Exception {
        mockMvc.perform(head(RECOMMENDATIONS))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(options(RECOMMENDATIONS))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        mockMvc.perform(head(RECOMMENDATIONS).param("limit", "3"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(head(RECOMMENDATIONS).header("Authorization", aliceToken))
                .andExpect(status().isOk());
    }

    /** Los extremos del rango (1 y 24) son válidos: 200 con como mucho ese número por tipo. */
    @Test
    void losExtremosDelLimiteSonValidos() throws Exception {
        Movie liked = saveMovie("La que me gusta", drama);
        likeService.likeMovie(alice.getId(), liked.getId());
        saveMovie("Candidata 1", drama);
        saveMovie("Candidata 2", drama);

        assertEquals(1, recommendations("limit=1").movies().size());
        assertEquals(2, recommendations("limit=24").movies().size());
    }

    // ------------------------------------------------------------------
    // Coste en sentencias SQL
    // ------------------------------------------------------------------

    /** Sin «me gusta», una sola sentencia: abrir el inicio no le cuesta las agregaciones a un usuario nuevo. */
    @Test
    void sinMeGustaElServicioEjecutaUnaSolaSentencia() {
        saveMovie("Candidata", drama);

        assertEquals(1, statementsFor(() -> recommendationService.getRecommendations(alice.getId(), 12)));
    }

    /**
     * Con resultados, seis sentencias (¿tiene «me gusta»? + ids de películas +
     * ids de series + películas con géneros + series con géneros + recuentos),
     * sea cual sea el número de títulos o el límite; sin candidatas, tres.
     */
    @Test
    void conMeGustaElCosteEsFijo() {
        Movie liked = saveMovie("La que me gusta", drama, scifi);
        likeService.likeMovie(alice.getId(), liked.getId());
        assertEquals(3, statementsFor(() -> recommendationService.getRecommendations(alice.getId(), 12)),
                "sin candidatas: comprobación + dos consultas de ids");

        for (int i = 0; i < 15; i++) {
            saveMovie("Película " + i, drama, i % 2 == 0 ? scifi : comedy);
            Series series = saveSeries("Serie " + i, drama, scifi, comedy);
            saveEpisode(series);
        }

        long twelve = statementsFor(() -> {
            RecommendationsResponse response = recommendationService.getRecommendations(alice.getId(), 12);
            assertEquals(12, response.movies().size());
            assertEquals(12, response.series().size());
        });
        long two = statementsFor(() -> recommendationService.getRecommendations(alice.getId(), 2));

        assertEquals(6, twelve);
        assertEquals(6, two, "el coste no depende del límite");
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private RecommendationsResponse recommendations(String query) throws Exception {
        String url = RECOMMENDATIONS + (query.isEmpty() ? "" : "?" + query);
        String json = mockMvc.perform(get(url).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(json, RecommendationsResponse.class);
    }

    private void likeViaApi(String type, Long id) throws Exception {
        mockMvc.perform(put("/api/users/me/likes/" + type + "/" + id).header("Authorization", aliceToken))
                .andExpect(status().isNoContent());
    }

    private static List<Long> movieIds(RecommendationsResponse response) {
        return response.movies().stream().map(m -> m.id()).toList();
    }

    private static List<Long> seriesIds(RecommendationsResponse response) {
        return response.series().stream().map(s -> s.id()).toList();
    }

    private long statementsFor(Runnable operation) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        operation.run();
        return statistics.getPrepareStatementCount();
    }

    /** Sobrescribe {@code created_at} con SQL directo: la entidad no deja cambiarlo. */
    private void setCreatedAt(String table, Long id, Instant createdAt) {
        jdbc.update("UPDATE " + table + " SET created_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC), id);
    }

    private User saveUser(String name) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(Role.USER);
        return userRepository.save(user);
    }

    private Genre saveGenre(String name) {
        Genre genre = new Genre();
        genre.setName(name);
        return genreRepository.save(genre);
    }

    private Movie saveMovie(String title, Genre... genres) {
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setDescription("Descripción de " + title);
        movie.setDuration(100);
        movie.setReleaseYear(2000);
        movie.setImageUrl("https://example.com/image.jpg");
        movie.setVideoUrl("https://example.com/video.mp4");
        movie.setGenres(new HashSet<>(List.of(genres)));
        return movieRepository.save(movie);
    }

    private Series saveSeries(String title, Genre... genres) {
        Series series = new Series();
        series.setTitle(title);
        series.setDescription("Sinopsis de " + title);
        series.setReleaseYear(2010);
        series.setImageUrl("https://example.com/serie.jpg");
        series.setGenres(new HashSet<>(List.of(genres)));
        return seriesRepository.save(series);
    }

    private void saveEpisode(Series series) {
        Episode episode = new Episode();
        episode.setSeries(series);
        episode.setSeasonNumber(1);
        episode.setEpisodeNumber(1);
        episode.setTitle("Episodio 1x1");
        episode.setDuration(45);
        episode.setVideoUrl("https://example.com/episodio.mp4");
        episodeRepository.save(episode);
    }

    /** Vacía las tablas respetando las claves foráneas (los «me gusta» caen en cascada). */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
