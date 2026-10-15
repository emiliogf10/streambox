package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.emilio.streambox.dto.MovieResponse;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.emilio.streambox.service.MovieService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import jakarta.persistence.EntityManagerFactory;

/**
 * Tests de punta a punta de {@code GET /api/movies/{id}/similar} («Películas
 * similares»), con datos confirmados de verdad (sin {@code @Transactional}) y
 * limpieza antes y después de cada test.
 *
 * <p>
 * Los casos finos del criterio de orden (cada desempate por separado, en H2 y
 * en PostgreSQL) están en {@code repository.SimilarMoviesQueryContract}; aquí
 * se comprueba que el endpoint los respeta de punta a punta, el contrato HTTP
 * (lista, límite por defecto y rango, 404 idéntico al del detalle, 401/403) y
 * el coste en sentencias SQL. Usa las mismas propiedades que
 * {@link CatalogIntegrationTest} para compartir su contexto de Spring.
 * </p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SimilarMoviesIntegrationTest {

    /** Instante base para fijar {@code created_at} a mano. */
    private static final Instant BASE = Instant.parse("2024-01-01T00:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private MovieService movieService;
    @Autowired private MovieRepository movieRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private SeriesRepository seriesRepository;
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

        userToken = "Bearer " + jwtService.generateToken(saveUser("similaruser", Role.USER));

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
    // Orden y contenido
    // ------------------------------------------------------------------

    /**
     * Los cuatro criterios a la vez, en una sola respuesta. Los datos van en
     * contra de los criterios siguientes para que ninguno acierte por
     * casualidad: la de más géneros en común es la más lejana en años, la de año
     * más cercano es la creada antes, y las películas se insertan (ids
     * crecientes) de la última a la primera del resultado esperado, salvo las
     * dos gemelas, que solo puede ordenar el id. La propia película y la que no
     * comparte géneros no aparecen.
     */
    @Test
    void devuelveLasSimilaresEnElOrdenDelCriterioYExcluyeLaPropia() throws Exception {
        Movie origin = saveMovie("Origen", 2000, action, drama, scifi);
        Movie twinInsertedFirst = saveMovie("Gemela 1", 2001, action);
        Movie twinInsertedSecond = saveMovie("Gemela 2", 2001, action);
        Movie olderCreated = saveMovie("Creada antes", 2001, action);
        Movie newerCreated = saveMovie("Creada despues", 2001, action);
        Movie closerYear = saveMovie("Anio mas cercano", 2000, action);
        Movie twoGenres = saveMovie("Dos generos", 1950, action, drama, comedy);
        Movie threeGenres = saveMovie("Tres generos", 1900, action, drama, scifi);
        saveMovie("Sin nada en comun", 2000, comedy);

        setCreatedAt(twinInsertedFirst, BASE);
        setCreatedAt(twinInsertedSecond, BASE);
        setCreatedAt(olderCreated, BASE.plusSeconds(10));
        setCreatedAt(newerCreated, BASE.plusSeconds(20));
        setCreatedAt(closerYear, BASE);
        setCreatedAt(twoGenres, BASE);
        setCreatedAt(threeGenres, BASE);

        List<MovieResponse> similar = similar(origin.getId(), "");

        // 3 géneros > 2 > 1; con 1: año a distancia 0 > distancia 1; con
        // distancia 1: creada después > creada antes > gemelas (misma fecha) por id.
        assertEquals(List.of(threeGenres.getId(), twoGenres.getId(), closerYear.getId(), newerCreated.getId(),
                olderCreated.getId(), twinInsertedFirst.getId(), twinInsertedSecond.getId()), ids(similar));
    }

    /** Cada elemento es el {@code MovieResponse} completo, con sus géneros ordenados por nombre. */
    @Test
    void cadaPeliculaLlegaConSusDatosYSusGenerosOrdenados() throws Exception {
        Movie origin = saveMovie("Origen", 2000, drama);
        Movie candidate = saveMovie("Candidata", 2005, scifi, drama, action);

        mockMvc.perform(get("/api/movies/" + origin.getId() + "/similar").header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(candidate.getId()))
                .andExpect(jsonPath("$[0].title").value("Candidata"))
                .andExpect(jsonPath("$[0].releaseYear").value(2005))
                .andExpect(jsonPath("$[0].videoUrl").value("https://example.com/video.mp4"))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].genres.length()").value(3))
                .andExpect(jsonPath("$[0].genres[0].name").value("Accion"))
                .andExpect(jsonPath("$[0].genres[1].name").value("Ciencia ficcion"))
                .andExpect(jsonPath("$[0].genres[2].name").value("Drama"));
    }

    /** Una película que existe pero no comparte géneros (o no tiene) da {@code []}, no 404. */
    @Test
    void sinGenerosEnComunDevuelveUnaListaVacia() throws Exception {
        Movie lonely = saveMovie("Solitaria", 2000, drama);
        Movie withoutGenres = saveMovie("Sin generos", 2000);
        saveMovie("Otra", 2000, comedy);

        mockMvc.perform(get("/api/movies/" + lonely.getId() + "/similar").header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
        assertEquals(List.of(), similar(withoutGenres.getId(), ""));
    }

    // ------------------------------------------------------------------
    // Límite
    // ------------------------------------------------------------------

    /**
     * Sin {@code limit} devuelve 12; con uno explícito, el principio exacto del
     * orden completo; con el máximo (24), todas las que hay (15).
     */
    @Test
    void elLimitePorDefectoEs12YElExplicitoCortaElOrdenCompleto() throws Exception {
        Movie origin = saveMovie("Origen", 2000, drama);
        for (int i = 1; i <= 15; i++) {
            saveMovie("Candidata " + i, 2000 + i, drama);
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
        Movie origin = saveMovie("Origen", 2000, drama);

        mockMvc.perform(get("/api/movies/" + origin.getId() + "/similar")
                        .param("limit", limit)
                        .header("Authorization", userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.limit").value(message));
    }

    // ------------------------------------------------------------------
    // 404, 401 y 403
    // ------------------------------------------------------------------

    /**
     * Una película inexistente da el mismo 404 que su detalle (mismo estado,
     * código y mensaje), y no una lista vacía.
     */
    @Test
    void unaPeliculaInexistenteDaElMismo404QueSuDetalle() throws Exception {
        String similarBody = mockMvc.perform(get("/api/movies/987654/similar").header("Authorization", userToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();
        String detailBody = mockMvc.perform(get("/api/movies/987654").header("Authorization", userToken))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        JsonNode similarJson = objectMapper.readTree(similarBody);
        JsonNode detailJson = objectMapper.readTree(detailBody);
        assertEquals(detailJson.get("status"), similarJson.get("status"));
        assertEquals(detailJson.get("code"), similarJson.get("code"));
        assertEquals(detailJson.get("message"), similarJson.get("message"));
    }

    @Test
    void sinSesionDa401() throws Exception {
        Movie origin = saveMovie("Origen", 2000, drama);

        mockMvc.perform(get("/api/movies/" + origin.getId() + "/similar"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * La ruta cae en la regla de lectura del catálogo de {@code SecurityConfig}
     * ({@code GET}/{@code HEAD} sobre {@code /api/movies/**} para cualquier
     * autenticado) y cualquier escritura en la de cierre (solo ADMIN): un USER
     * puede leerla, también con {@code HEAD}, pero un {@code POST} suyo es 403
     * antes de llegar a Spring MVC.
     */
    @Test
    void unUsuarioNormalPuedeLeerlaPeroNoEscribirEnElla() throws Exception {
        Movie origin = saveMovie("Origen", 2000, drama);
        saveMovie("Candidata", 2001, drama);

        mockMvc.perform(get("/api/movies/" + origin.getId() + "/similar").header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(head("/api/movies/" + origin.getId() + "/similar").header("Authorization", userToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/movies/" + origin.getId() + "/similar").header("Authorization", userToken))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    // Coste en sentencias SQL
    // ------------------------------------------------------------------

    /**
     * El servicio cuesta exactamente tres sentencias (existencia + ids + películas
     * con géneros) y dos si no hay ninguna similar; nunca una por película ni
     * por género.
     */
    @Test
    void elServicioEjecutaTresSentenciasSeaCualSeaElNumeroDePeliculas() {
        Movie origin = saveMovie("Origen", 2000, drama, comedy);
        for (int i = 0; i < 20; i++) {
            saveMovie("Similar " + i, 1990 + i, drama, comedy, i % 2 == 0 ? scifi : action);
        }

        long twelve = statementsFor(() -> assertEquals(12, movieService.getSimilarMovies(origin.getId(), 12).size()));
        long two = statementsFor(() -> assertEquals(2, movieService.getSimilarMovies(origin.getId(), 2).size()));

        assertEquals(3, twelve, "existencia + ids + películas con géneros");
        assertEquals(3, two, "el coste no depende del límite");
    }

    /**
     * Sin candidatas, la segunda consulta (la carga por ids) no se lanza: dos
     * sentencias.
     */
    @Test
    void sinSimilaresElServicioSoloEjecutaDosSentencias() {
        Movie lonely = saveMovie("Solitaria", 2000, drama);
        saveMovie("Otra", 2000, comedy);

        long statements = statementsFor(() -> assertEquals(0, movieService.getSimilarMovies(lonely.getId(), 12).size()));

        assertEquals(2, statements, "existencia + ids");
    }

    /**
     * De punta a punta (con la autenticación, que lee el usuario de la base de
     * datos en cada petición): el endpoint con resultados cuesta exactamente
     * dos sentencias más que el 404 de una película inexistente (que se queda
     * en la comprobación de existencia), y lo mismo con 2 que con 12 películas.
     */
    @Test
    void elEndpointCuestaDosSentenciasMasQueEl404() {
        Movie origin = saveMovie("Origen", 2000, drama, comedy);
        for (int i = 0; i < 20; i++) {
            saveMovie("Similar " + i, 1990 + i, drama, comedy, scifi);
        }

        long notFound = statementsFor(() -> callQuietly(() ->
                mockMvc.perform(get("/api/movies/987654/similar").header("Authorization", userToken))
                        .andExpect(status().isNotFound())));
        long twelve = statementsFor(() -> callQuietly(() -> similar(origin.getId(), "limit=12")));
        long two = statementsFor(() -> callQuietly(() -> similar(origin.getId(), "limit=2")));

        assertEquals(notFound + 2, twelve,"autenticación + existencia + ids + películas con géneros");
        assertEquals(twelve, two, "el coste no depende del límite");
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Llama al endpoint como USER con la consulta dada ({@code clave=valor} o vacía) y exige 200. */
    private List<MovieResponse> similar(Long movieId, String query) throws Exception {
        String url = "/api/movies/" + movieId + "/similar" + (query.isEmpty() ? "" : "?" + query);
        String json = mockMvc.perform(get(url).header("Authorization", userToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(json, new TypeReference<List<MovieResponse>>() { });
    }

    private static List<Long> ids(List<MovieResponse> movies) {
        return movies.stream().map(MovieResponse::id).toList();
    }

    /** Ejecuta una llamada que lanza excepciones comprobadas dentro de un {@link Runnable}. */
    private static void callQuietly(ThrowingCall call) {
        try {
            call.run();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    private long statementsFor(Runnable operation) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        operation.run();
        return statistics.getPrepareStatementCount();
    }

    /** Sobrescribe {@code created_at} con SQL directo: la entidad no deja cambiarlo. */
    private void setCreatedAt(Movie movie, Instant createdAt) {
        jdbc.update("UPDATE movies SET created_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC), movie.getId());
    }

    private User saveUser(String name, Role role) {
        User user = new User();
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

    private Movie saveMovie(String title, int year, Genre... genres) {
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setDescription("Descripción de " + title);
        movie.setDuration(100);
        movie.setReleaseYear(year);
        movie.setImageUrl("https://example.com/image.jpg");
        movie.setVideoUrl("https://example.com/video.mp4");
        movie.setGenres(new HashSet<>(List.of(genres)));
        return movieRepository.save(movie);
    }

    /** Vacía las tablas respetando las claves foráneas (las series antes que los géneros). */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
