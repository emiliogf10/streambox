package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Revisión de QA de la V16: <b>nada que la API acepte puede acabar en un 500
 * por los {@code CHECK} de la base de datos</b>, y nada que rechace llega a
 * ella.
 *
 * <p>
 * {@code ImageUrlParityContractTest} prueba el validador a solas y
 * {@code BackdropAndEpisodeImageIntegrationTest} unos pocos casos por la API.
 * Aquí se recorre <b>todo</b> el contrato compartido
 * ({@code frontend/src/test/imageUrlCases.json}) por la API real y con
 * <i>commit</i> real (sin {@code @Transactional}, para que los {@code CHECK}
 * {@code ck_movies_backdrop_url}, {@code ck_series_backdrop_url},
 * {@code ck_episodes_image_url} y los de la portada se evalúen de verdad):
 * </p>
 * <ul>
 * <li>cada caso «valida» se guarda (201) en las cinco columnas (portada y
 * fondo de película y de serie, imagen del episodio) <b>exactamente</b> como
 * se envió, sin recortes ni normalizaciones;</li>
 * <li>cada caso «formato» o «longitud» da 400 {@code VALIDATION_ERROR} en los
 * tres campos nuevos, nunca 500, y no deja ninguna fila.</li>
 * </ul>
 *
 * <p>
 * Es lo que se rompería si alguien relajara {@code @HttpsUrl} (p. ej. aceptar
 * {@code /covers/} en mayúsculas o {@code http:}) sin tocar la migración, o
 * endureciera el {@code CHECK} (p. ej. un mínimo de longitud mayor) sin tocar
 * la API. Contra H2; la misma comprobación en PostgreSQL necesitaría Docker
 * (ver «Huecos» del informe de QA).
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ImageUrlContractPersistenceIntegrationTest {

    /** Ruta del contrato compartido, relativa al directorio del módulo ({@code streambox/}). */
    private static final Path CONTRACT = Path.of("..", "frontend", "src", "test", "imageUrlCases.json");

    private static final String POSTER = "https://cdn.example.com/poster.jpg";
    private static final String VIDEO = "https://cdn.example.com/1x1.mp4";

    /** Títulos únicos entre casos (la unicidad de título+año no debe interferir). */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private MovieRepository movieRepository;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String adminToken;
    private Genre genre;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        User admin = new User();
        admin.setEmailVerifiedAt(Instant.now());
        admin.setUsername("qaimgadmin");
        admin.setEmail("qaimgadmin@test.com");
        admin.setPassword(passwordEncoder.encode("password123"));
        admin.setRole(Role.ADMIN);
        adminToken = "Bearer " + jwtService.generateToken(userRepository.save(admin));
        Genre newGenre = new Genre();
        newGenre.setName("Drama");
        genre = genreRepository.save(newGenre);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    /** Casos del contrato con el resultado esperado indicado. */
    static Stream<Arguments> cases(boolean valid) throws IOException {
        assertTrue(Files.exists(CONTRACT), "No se encuentra el contrato compartido: " + CONTRACT.toAbsolutePath());
        JsonNode root = new ObjectMapper().readTree(Files.readString(CONTRACT));
        List<Arguments> arguments = new ArrayList<>();
        for (JsonNode item : root.get("casos")) {
            boolean isValid = item.get("esperado").asText().equals("valida");
            if (isValid == valid) {
                arguments.add(Arguments.of(item.get("nota").asText(), item.get("url").asText()));
            }
        }
        assertTrue(arguments.size() >= 5, "El contrato debería tener casos de esta clase");
        return arguments.stream();
    }

    static Stream<Arguments> validCases() throws IOException {
        return cases(true);
    }

    static Stream<Arguments> invalidCases() throws IOException {
        return cases(false);
    }

    /**
     * Un caso «valida» se guarda en las cinco columnas tal cual: el
     * {@code CHECK} de la base de datos nunca rechaza lo que la API acepta.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("validCases")
    void loQueLaApiAceptaSeGuardaTalCualEnLasCincoColumnas(String nota, String url) throws Exception {
        int n = SEQUENCE.incrementAndGet();

        JsonNode movie = created(MOVIES, movieBody("Película " + n, url, url));
        assertEquals(url, movie.get("imageUrl").asText(), nota);
        assertEquals(url, movie.get("backdropUrl").asText(), nota);
        assertEquals(url, jdbc.queryForObject("SELECT backdrop_url FROM movies WHERE id = ?", String.class,
                movie.get("id").asLong()));
        assertEquals(url, jdbc.queryForObject("SELECT image_url FROM movies WHERE id = ?", String.class,
                movie.get("id").asLong()));

        JsonNode series = created(SERIES, seriesBody("Serie " + n, url, url));
        assertEquals(url, series.get("backdropUrl").asText(), nota);
        assertEquals(url, jdbc.queryForObject("SELECT backdrop_url FROM series WHERE id = ?", String.class,
                series.get("id").asLong()));

        JsonNode episode = created(SERIES + "/" + series.get("id").asLong() + "/episodes", episodeBody(url));
        assertEquals(url, episode.get("imageUrl").asText(), nota);
        assertEquals(url, jdbc.queryForObject("SELECT image_url FROM episodes WHERE id = ?", String.class,
                episode.get("id").asLong()));
    }

    /**
     * Un caso «formato» o «longitud» da 400 en los tres campos nuevos (nunca
     * 500 por el {@code CHECK}) y no guarda nada.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCases")
    void loQueLaApiRechazaDa400YNoLlegaALaBase(String nota, String url) throws Exception {
        int n = SEQUENCE.incrementAndGet();
        Series series = new Series();
        series.setTitle("Base " + n);
        series.setDescription("Sinopsis");
        series.setReleaseYear(2021);
        series.setImageUrl(POSTER);
        series = seriesRepository.save(series);

        assertBadRequest(MOVIES, movieBody("Película " + n, POSTER, url), "backdropUrl", nota);
        assertBadRequest(SERIES, seriesBody("Serie " + n, POSTER, url), "backdropUrl", nota);
        assertBadRequest(SERIES + "/" + series.getId() + "/episodes", episodeBody(url), "imageUrl", nota);

        assertEquals(0, movieRepository.count());
        assertEquals(1, seriesRepository.count());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM episodes", Integer.class));
        seriesRepository.deleteAll();
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static final String MOVIES = "/api/movies";
    private static final String SERIES = "/api/series";

    private JsonNode created(String url, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post(url)
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        String content = result.getResponse().getContentAsString();
        assertEquals(201, result.getResponse().getStatus(), url + " → " + content);
        return objectMapper.readTree(content);
    }

    private void assertBadRequest(String url, Map<String, Object> body, String field, String nota) throws Exception {
        MvcResult result = mockMvc.perform(post(url)
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
                .andReturn();
        String content = result.getResponse().getContentAsString();
        assertEquals(400, result.getResponse().getStatus(), nota + ": " + url + " → " + content);
        JsonNode json = objectMapper.readTree(content);
        assertEquals("VALIDATION_ERROR", json.get("code").asText(), nota);
        assertTrue(json.get("validationErrors").has(field), nota + ": " + content);
    }

    private Map<String, Object> movieBody(String title, String imageUrl, String backdropUrl) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("description", "Sinopsis");
        body.put("duration", 100);
        body.put("releaseYear", 2021);
        body.put("imageUrl", imageUrl);
        body.put("backdropUrl", backdropUrl);
        body.put("genreIds", List.of(genre.getId()));
        return body;
    }

    private Map<String, Object> seriesBody(String title, String imageUrl, String backdropUrl) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("description", "Sinopsis");
        body.put("releaseYear", 2021);
        body.put("imageUrl", imageUrl);
        body.put("backdropUrl", backdropUrl);
        body.put("genreIds", List.of(genre.getId()));
        return body;
    }

    private static Map<String, Object> episodeBody(String imageUrl) {
        Map<String, Object> body = new HashMap<>();
        body.put("seasonNumber", 1);
        body.put("episodeNumber", 1);
        body.put("title", "Episodio 1x1");
        body.put("duration", 50);
        body.put("videoUrl", VIDEO);
        body.put("imageUrl", imageUrl);
        return body;
    }

    /** Series primero (sus géneros y episodios caen con ellas), luego el resto. */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
