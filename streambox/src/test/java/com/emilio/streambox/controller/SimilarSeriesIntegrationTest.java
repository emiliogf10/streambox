package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.emilio.streambox.dto.SeriesResponse;
import com.emilio.streambox.entity.Episode;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.emilio.streambox.service.SeriesService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import jakarta.persistence.EntityManagerFactory;

/**
 * Tests de punta a punta de {@code GET /api/series/{id}/similar} («Series
 * similares»), gemelos de {@link SimilarMoviesIntegrationTest}: datos
 * confirmados de verdad (sin {@code @Transactional}) y limpieza antes y después
 * de cada test.
 *
 * <p>
 * Los desempates finos del criterio (en H2 y en PostgreSQL) están en
 * {@code repository.SimilarSeriesQueryContract}; aquí se comprueba lo que añade
 * el endpoint: el orden completo de punta a punta, la <strong>visibilidad</strong>
 * (una serie sin episodios nunca aparece y, como origen, da el mismo 404 que una
 * inexistente, con el mismo cuerpo), los recuentos de temporadas y episodios,
 * el contrato HTTP (límite, id no numérico, 401, {@code HEAD}) y el coste en
 * sentencias SQL. Mismas propiedades que {@link SimilarMoviesIntegrationTest}
 * para compartir su contexto de Spring.
 * </p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SimilarSeriesIntegrationTest {

    /** Instante base para fijar {@code created_at} a mano. */
    private static final Instant BASE = Instant.parse("2024-01-01T00:00:00Z");

    /** Id que no corresponde a ninguna serie. */
    private static final long MISSING_ID = 987654L;

    /**
     * Campos del {@code ErrorResponse} que cambian de una petición a otra sin
     * revelar nada: la hora y la ruta pedida. Todos los demás deben coincidir.
     */
    private static final Set<String> VOLATILE_ERROR_FIELDS = Set.of("timestamp", "path");

    @Autowired private MockMvc mockMvc;
    @Autowired private SeriesService seriesService;
    @Autowired private MovieRepository movieRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private String userToken;
    private Genre action;
    private Genre drama;
    private Genre scifi;
    private Genre comedy;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        userToken = "Bearer " + jwtService.generateToken(saveUser("similarseriesuser", Role.USER));

        action = saveGenre("Accion");
        drama = saveGenre("Drama");
        scifi = saveGenre("Ciencia ficcion");
        comedy = saveGenre("Comedia");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Orden, contenido y visibilidad
    // ------------------------------------------------------------------

    /**
     * Los cuatro criterios a la vez, en una sola respuesta, con datos que van
     * en contra del criterio siguiente (como en películas): la de más géneros
     * en común es la más lejana en años, la de año más cercano es la creada
     * antes, y las series se insertan de la última a la primera del resultado
     * esperado, salvo las dos gemelas, que solo puede ordenar el id. No
     * aparecen la propia serie, la que no comparte géneros ni la que los
     * comparte todos pero no tiene episodios.
     */
    @Test
    void devuelveLasSimilaresEnElOrdenDelCriterioYExcluyeLaPropiaYLasOcultas() throws Exception {
        Series origin = saveVisibleSeries("Origen", 2000, action, drama, scifi);
        Series twinInsertedFirst = saveVisibleSeries("Gemela 1", 2001, action);
        Series twinInsertedSecond = saveVisibleSeries("Gemela 2", 2001, action);
        Series olderCreated = saveVisibleSeries("Creada antes", 2001, action);
        Series newerCreated = saveVisibleSeries("Creada despues", 2001, action);
        Series closerYear = saveVisibleSeries("Anio mas cercano", 2000, action);
        Series twoGenres = saveVisibleSeries("Dos generos", 1950, action, drama, comedy);
        Series threeGenres = saveVisibleSeries("Tres generos", 1900, action, drama, scifi);
        saveVisibleSeries("Sin nada en comun", 2000, comedy);
        saveEmptySeries("Oculta identica", 2000, action, drama, scifi);

        setCreatedAt(twinInsertedFirst, BASE);
        setCreatedAt(twinInsertedSecond, BASE);
        setCreatedAt(olderCreated, BASE.plusSeconds(10));
        setCreatedAt(newerCreated, BASE.plusSeconds(20));
        setCreatedAt(closerYear, BASE);
        setCreatedAt(twoGenres, BASE);
        setCreatedAt(threeGenres, BASE);

        List<SeriesResponse> similar = similar(origin.getId(), "");

        assertEquals(List.of(threeGenres.getId(), twoGenres.getId(), closerYear.getId(), newerCreated.getId(),
                olderCreated.getId(), twinInsertedFirst.getId(), twinInsertedSecond.getId()), ids(similar));
    }

    /**
     * Una serie sin episodios nunca sale, ni siquiera cuando es la única que
     * comparte géneros (la lista queda vacía) ni con el límite máximo; en
     * cuanto recibe su primer episodio, aparece.
     */
    @Test
    void unaSerieSinEpisodiosNoApareceHastaQueTieneUno() throws Exception {
        Series origin = saveVisibleSeries("Origen", 2000, drama);
        Series hidden = saveEmptySeries("Oculta", 2000, drama);

        assertEquals(List.of(), similar(origin.getId(), ""));
        assertEquals(List.of(), similar(origin.getId(), "limit=24"));

        addEpisode(hidden, 1, 1);

        assertEquals(List.of(hidden.getId()), ids(similar(origin.getId(), "")));
    }

    /**
     * Cada elemento es el {@code SeriesResponse} de los listados: sus datos,
     * los géneros ordenados por nombre y los recuentos reales de temporadas y
     * episodios de <em>cada</em> serie (no los de la de origen ni los de otra).
     */
    @Test
    void cadaSerieLlegaConSusDatosSusGenerosOrdenadosYSusRecuentos() throws Exception {
        Series origin = saveVisibleSeries("Origen", 2000, drama);
        Series big = saveEmptySeries("Grande", 2005, scifi, drama, action);
        addEpisode(big, 1, 1);
        addEpisode(big, 1, 2);
        addEpisode(big, 2, 1);
        addEpisode(big, 3, 1);
        addEpisode(big, 3, 2);
        // Más lejana en años que la grande: así el orden no depende de created_at.
        Series small = saveVisibleSeries("Pequena", 2010, drama);

        mockMvc.perform(get("/api/series/" + origin.getId() + "/similar").header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(big.getId()))
                .andExpect(jsonPath("$[0].title").value("Grande"))
                .andExpect(jsonPath("$[0].releaseYear").value(2005))
                .andExpect(jsonPath("$[0].imageUrl").value("https://example.com/serie.jpg"))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].genres.length()").value(3))
                .andExpect(jsonPath("$[0].genres[0].name").value("Accion"))
                .andExpect(jsonPath("$[0].genres[1].name").value("Ciencia ficcion"))
                .andExpect(jsonPath("$[0].genres[2].name").value("Drama"))
                .andExpect(jsonPath("$[0].seasonCount").value(3))
                .andExpect(jsonPath("$[0].episodeCount").value(5))
                .andExpect(jsonPath("$[1].id").value(small.getId()))
                .andExpect(jsonPath("$[1].seasonCount").value(1))
                .andExpect(jsonPath("$[1].episodeCount").value(1));
    }

    /** Una serie visible que no comparte géneros (o no tiene) da {@code []}, no 404. */
    @Test
    void sinGenerosEnComunDevuelveUnaListaVacia() throws Exception {
        Series lonely = saveVisibleSeries("Solitaria", 2000, drama);
        Series withoutGenres = saveVisibleSeries("Sin generos", 2000);
        saveVisibleSeries("Otra", 2000, comedy);

        mockMvc.perform(get("/api/series/" + lonely.getId() + "/similar").header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
        assertEquals(List.of(), similar(withoutGenres.getId(), ""));
    }

    // ------------------------------------------------------------------
    // Límite e id
    // ------------------------------------------------------------------

    /**
     * Sin {@code limit} devuelve 12; con uno explícito, el principio exacto del
     * orden completo; con el máximo (24), todas las visibles que hay (15).
     */
    @Test
    void elLimitePorDefectoEs12YElExplicitoCortaElOrdenCompleto() throws Exception {
        Series origin = saveVisibleSeries("Origen", 2000, drama);
        for (int i = 1; i <= 15; i++) {
            saveVisibleSeries("Candidata " + i, 2000 + i, drama);
        }

        List<Long> all = ids(similar(origin.getId(), "limit=24"));
        List<Long> byDefault = ids(similar(origin.getId(), ""));
        List<Long> firstThree = ids(similar(origin.getId(), "limit=3"));
        List<Long> justOne = ids(similar(origin.getId(), "limit=1"));

        assertEquals(15, all.size());
        assertEquals(all.subList(0, 12), byDefault);
        assertEquals(all.subList(0, 3), firstThree);
        assertEquals(all.subList(0, 1), justOne);
    }

    @ParameterizedTest
    @CsvSource({
            "0, El límite mínimo es 1",
            "-5, El límite mínimo es 1",
            "25, El límite máximo es 24",
            "abc, El valor no tiene el formato esperado",
            "1.5, El valor no tiene el formato esperado",
            "99999999999, El valor no tiene el formato esperado"
    })
    void unLimiteFueraDeRangoONoNumericoDa400(String limit, String message) throws Exception {
        Series origin = saveVisibleSeries("Origen", 2000, drama);

        mockMvc.perform(get("/api/series/" + origin.getId() + "/similar")
                        .param("limit", limit)
                        .header("Authorization", userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.limit").value(message));
    }

    /**
     * Un id que no es un {@code Long} da el mismo 400 que el detalle de la
     * serie y que la ruta gemela de películas, sin llegar a la base de datos.
     */
    @ParameterizedTest
    @ValueSource(strings = { "abc", "1.5", "99999999999999999999", "1e3" })
    void unIdNoNumericoDaElMismo400QueElDetalleYQuePeliculas(String id) throws Exception {
        JsonNode similar = errorBody(get("/api/series/" + id + "/similar").header("Authorization", userToken), 400);
        JsonNode detail = errorBody(get("/api/series/" + id).header("Authorization", userToken), 400);
        JsonNode movies = errorBody(get("/api/movies/" + id + "/similar").header("Authorization", userToken), 400);

        assertEquals("VALIDATION_ERROR", similar.get("code").asText());
        assertSameError(detail, similar);
        assertSameError(movies, similar);
    }

    // ------------------------------------------------------------------
    // 404, 401 y métodos
    // ------------------------------------------------------------------

    /**
     * Una serie inexistente da el mismo 404 que su detalle, y no una lista
     * vacía.
     */
    @Test
    void unaSerieInexistenteDaElMismo404QueSuDetalle() throws Exception {
        JsonNode similar = errorBody(get("/api/series/" + MISSING_ID + "/similar")
                .header("Authorization", userToken), 404);
        JsonNode detail = errorBody(get("/api/series/" + MISSING_ID).header("Authorization", userToken), 404);

        assertEquals("RESOURCE_NOT_FOUND", similar.get("code").asText());
        assertSameError(detail, similar);
    }

    /**
     * Una serie de origen sin episodios responde exactamente igual (estado y
     * cuerpo entero, salvo hora y ruta) que una inexistente, aunque comparta
     * géneros con series visibles: la consulta de ids no mira el origen, así
     * que sin la comprobación del servicio devolvería 200 con esas series y
     * revelaría que la serie oculta existe.
     */
    @Test
    void unOrigenSinEpisodiosDaUn404IdenticoAlDeUnoInexistente() throws Exception {
        Series hidden = saveEmptySeries("Oculta", 2000, drama);
        saveVisibleSeries("Visible parecida", 2000, drama);

        JsonNode emptyOrigin = errorBody(get("/api/series/" + hidden.getId() + "/similar")
                .header("Authorization", userToken), 404);
        JsonNode missingOrigin = errorBody(get("/api/series/" + MISSING_ID + "/similar")
                .header("Authorization", userToken), 404);

        assertSameError(missingOrigin, emptyOrigin);
        assertEquals("/api/series/" + hidden.getId() + "/similar", emptyOrigin.get("path").asText());
    }

    @Test
    void sinSesionDa401() throws Exception {
        Series origin = saveVisibleSeries("Origen", 2000, drama);

        mockMvc.perform(get("/api/series/" + origin.getId() + "/similar"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * La ruta cae en la regla de lectura del catálogo de {@code SecurityConfig}
     * ({@code GET}/{@code HEAD} sobre {@code /api/series/**} para cualquier
     * autenticado) y cualquier escritura en la de cierre (solo ADMIN): un USER
     * puede leerla, también con {@code HEAD}, pero un {@code POST} suyo es 403.
     */
    @Test
    void unUsuarioNormalPuedeLeerlaTambienConHeadPeroNoEscribirEnElla() throws Exception {
        Series origin = saveVisibleSeries("Origen", 2000, drama);
        saveVisibleSeries("Candidata", 2001, drama);

        mockMvc.perform(get("/api/series/" + origin.getId() + "/similar").header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(head("/api/series/" + origin.getId() + "/similar").header("Authorization", userToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/series/" + origin.getId() + "/similar").header("Authorization", userToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Coste en sentencias SQL
    // ------------------------------------------------------------------

    /**
     * El servicio cuesta exactamente cuatro sentencias (visibilidad + ids +
     * series con géneros + recuentos) con 12 o con 2 resultados; nunca una por
     * serie, por género ni por episodio.
     */
    @Test
    void elServicioEjecutaCuatroSentenciasSeaCualSeaElNumeroDeSeries() {
        Series origin = saveVisibleSeries("Origen", 2000, drama, comedy);
        for (int i = 0; i < 20; i++) {
            Series candidate = saveVisibleSeries("Similar " + i, 1990 + i, drama, comedy,
                    i % 2 == 0 ? scifi : action);
            addEpisode(candidate, 2, 1);
        }

        long twelve = statementsFor(() -> assertEquals(12, seriesService.getSimilarSeries(origin.getId(), 12).size()));
        long two = statementsFor(() -> assertEquals(2, seriesService.getSimilarSeries(origin.getId(), 2).size()));

        assertEquals(4, twelve, "visibilidad + ids + series con géneros + recuentos");
        assertEquals(4, two, "el coste no depende del límite");
    }

    /** Sin candidatas no se lanzan ni la carga ni los recuentos: dos sentencias. */
    @Test
    void sinSimilaresElServicioSoloEjecutaDosSentencias() {
        Series lonely = saveVisibleSeries("Solitaria", 2000, drama);
        saveVisibleSeries("Otra", 2000, comedy);

        long statements = statementsFor(() ->
                assertEquals(0, seriesService.getSimilarSeries(lonely.getId(), 12).size()));

        assertEquals(2, statements, "visibilidad + ids");
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Llama al endpoint como USER con la consulta dada ({@code clave=valor} o vacía) y exige 200. */
    private List<SeriesResponse> similar(Long seriesId, String query) throws Exception {
        String url = "/api/series/" + seriesId + "/similar" + (query.isEmpty() ? "" : "?" + query);
        String json = mockMvc.perform(get(url).header("Authorization", userToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(json, new TypeReference<List<SeriesResponse>>() { });
    }

    /** Ejecuta la petición, exige el estado indicado y devuelve el cuerpo JSON del error. */
    private JsonNode errorBody(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            int expectedStatus) throws Exception {

        String body = mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /**
     * Comprueba que dos errores son iguales campo a campo salvo
     * {@link #VOLATILE_ERROR_FIELDS} (mismos campos, mismos valores; incluido
     * {@code validationErrors} si lo hay).
     */
    private static void assertSameError(JsonNode expected, JsonNode actual) {
        assertEquals(fieldNames(expected), fieldNames(actual), "los dos errores deben tener los mismos campos");
        for (String field : fieldNames(expected)) {
            if (!VOLATILE_ERROR_FIELDS.contains(field)) {
                assertEquals(expected.get(field), actual.get(field), "campo " + field);
            }
        }
        assertTrue(actual.has("message"));
        assertFalse(actual.get("message").asText().isBlank());
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext();) {
            names.add(it.next());
        }
        return names;
    }

    private static List<Long> ids(List<SeriesResponse> series) {
        return series.stream().map(SeriesResponse::id).toList();
    }

    private long statementsFor(Runnable operation) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        operation.run();
        return statistics.getPrepareStatementCount();
    }

    /** Sobrescribe {@code created_at} con SQL directo: la entidad no deja cambiarlo. */
    private void setCreatedAt(Series series, Instant createdAt) {
        jdbc.update("UPDATE series SET created_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC), series.getId());
    }

    private User saveUser(String name, Role role) {
        User user = new User();
        user.setEmailVerifiedAt(Instant.now()); // cuenta verificada (V9)
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(role);
        return userRepository.save(user);
    }

    private Genre saveGenre(String name) {
        Genre genre = new Genre();
        genre.setName(name);
        return genreRepository.save(genre);
    }

    /** Serie sin episodios: existe, pero para los usuarios es como si no existiera. */
    private Series saveEmptySeries(String title, int year, Genre... genres) {
        Series series = new Series();
        series.setTitle(title);
        series.setDescription("Sinopsis de " + title);
        series.setReleaseYear(year);
        series.setImageUrl("https://example.com/serie.jpg");
        series.setGenres(new HashSet<>(List.of(genres)));
        return seriesRepository.save(series);
    }

    /** Serie visible: con un episodio (1x1). */
    private Series saveVisibleSeries(String title, int year, Genre... genres) {
        Series series = saveEmptySeries(title, year, genres);
        addEpisode(series, 1, 1);
        return series;
    }

    private void addEpisode(Series series, int season, int number) {
        Episode episode = new Episode();
        episode.setSeries(series);
        episode.setSeasonNumber(season);
        episode.setEpisodeNumber(number);
        episode.setTitle("Episodio " + season + "x" + number);
        episode.setDuration(45);
        episode.setVideoUrl("https://example.com/episodio.mp4");
        episodeRepository.save(episode);
    }

    /**
     * Vacía las tablas respetando las claves foráneas: las series arrastran sus
     * episodios y {@code series_genres} ({@code ON DELETE CASCADE}).
     */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
