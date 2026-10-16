package com.emilio.streambox.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

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
import com.emilio.streambox.validation.YouTubeUrlSamples;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tráiler ({@code trailerUrl}) y vídeo opcional ({@code videoUrl}) de
 * películas, series y episodios a través de la API real (migración
 * {@code V6}).
 *
 * <p>
 * Comprueba el contrato con el frontend:
 * </p>
 * <ul>
 * <li>cada forma de enlace de YouTube aceptada se guarda y se devuelve
 * <b>normalizada</b> ({@code https://www.youtube.com/watch?v=<id>}) y, al
 * confirmarse de verdad, prueba que no viola el {@code CHECK} de la base de
 * datos (si el servicio guardara el enlace tal cual, un {@code youtu.be}
 * acabaría en error de integridad);</li>
 * <li>lo que no es una forma aceptada da 400 con el mensaje exacto y no se
 * guarda nada;</li>
 * <li>vídeo y tráiler son opcionales: ausentes, {@code null}, vacíos o en
 * blanco se guardan y se devuelven como {@code null} (presentes en el JSON);</li>
 * <li>un {@code PUT} sin {@code trailerUrl}/{@code videoUrl} los borra;</li>
 * <li>todas las respuestas de lectura (detalle, listados, búsqueda, panel,
 * "Mi lista") llevan {@code trailerUrl}.</li>
 * </ul>
 *
 * <p>
 * Sin {@code @Transactional}: cada petición se confirma de verdad (como en
 * {@code SeriesAdminIntegrationTest}), así que los {@code CHECK} se evalúan
 * igual que en producción. La misma comprobación contra PostgreSQL real está en
 * {@code PostgresTrailerUrlApiIntegrationTest}.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TrailerUrlIntegrationTest {

    private static final String TRAILER_FORMAT = "El tráiler debe ser un enlace de un vídeo de YouTube";
    private static final String TRAILER_SIZE = "La URL del tráiler no puede superar los 500 caracteres";
    private static final String VIDEO_FORMAT = "La URL del vídeo debe empezar por https://";

    private static final String MOVIES = "/api/movies";
    private static final String SERIES = "/api/series";

    @Autowired private MockMvc mockMvc;
    @Autowired private MovieRepository movieRepository;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String adminToken;
    private String userToken;
    private Genre genre;

    static Stream<Arguments> acceptedTrailers() {
        return YouTubeUrlSamples.ACCEPTED.entrySet().stream()
                .map(entry -> Arguments.of(entry.getKey(), "https://www.youtube.com/watch?v=" + entry.getValue()));
    }

    static Stream<String> rejectedTrailers() {
        return YouTubeUrlSamples.REJECTED.stream();
    }

    @BeforeEach
    void setUp() {
        cleanDatabase();
        adminToken = "Bearer " + jwtService.generateToken(saveUser("traileradmin", Role.ADMIN));
        userToken = "Bearer " + jwtService.generateToken(saveUser("traileruser", Role.USER));
        genre = saveGenre("Ciencia ficción");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Películas: alta
    // ------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("acceptedTrailers")
    void altaDePeliculaConCadaFormaAceptadaGuardaElTrailerNormalizado(String trailer, String normalized)
            throws Exception {
        long id = idOf(send(post(MOVIES), movieBody(trailer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trailerUrl").value(normalized)));

        assertEquals(normalized, movieRepository.findById(id).orElseThrow().getTrailerUrl());
    }

    @ParameterizedTest
    @MethodSource("rejectedTrailers")
    void altaDePeliculaConUnTrailerNoAceptadoDa400ConElMensajeExacto(String trailer) throws Exception {
        send(post(MOVIES), movieBody(trailer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.trailerUrl").value(TRAILER_FORMAT))
                .andExpect(jsonPath("$.validationErrors.videoUrl").doesNotExist());

        assertEquals(0, movieRepository.count());
    }

    @Test
    void altaDePeliculaConUnTrailerDe501CaracteresDaSoloElMensajeDeLongitud() throws Exception {
        String prefix = "https://www.youtube.com/watch?v=" + YouTubeUrlSamples.ID + "&x=";
        String trailer500 = prefix + "a".repeat(500 - prefix.length());

        send(post(MOVIES), movieBody(trailer500 + "a"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.trailerUrl").value(TRAILER_SIZE));
        send(post(MOVIES), movieBody("javascript:" + "a".repeat(490)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.trailerUrl").value(TRAILER_SIZE));
        send(post(MOVIES), movieBody(trailer500))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trailerUrl").value(YouTubeUrlSamples.NORMALIZED));
    }

    /** Sin vídeo ni tráiler: 201 y los dos campos presentes en el JSON con {@code null}. */
    @Test
    void altaDePeliculaSinVideoNiTrailerDevuelveAmbosANull() throws Exception {
        Map<String, Object> body = movieBody(null);
        body.remove("videoUrl");
        body.remove("trailerUrl");

        long id = idOf(send(post(MOVIES), body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasKey("videoUrl")))
                .andExpect(jsonPath("$", hasKey("trailerUrl")))
                .andExpect(jsonPath("$.videoUrl").value(nullValue()))
                .andExpect(jsonPath("$.trailerUrl").value(nullValue())));

        Movie saved = movieRepository.findById(id).orElseThrow();
        assertNull(saved.getVideoUrl());
        assertNull(saved.getTrailerUrl());
    }

    /** Vacío o en blanco = "no tiene": se guarda {@code null}, nunca la cadena vacía. */
    @ParameterizedTest
    @ValueSource(strings = { "", "   ", "\t" })
    void unVideoOUnTrailerEnBlancoSeGuardaComoNull(String blank) throws Exception {
        Map<String, Object> body = movieBody(blank);
        body.put("videoUrl", blank);

        long id = idOf(send(post(MOVIES), body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.videoUrl").value(nullValue()))
                .andExpect(jsonPath("$.trailerUrl").value(nullValue())));

        assertEquals(1, count("SELECT COUNT(*) FROM movies WHERE id = ? AND video_url IS NULL"
                + " AND trailer_url IS NULL", id));
    }

    /** El vídeo sigue validándose si viene: opcional no es "cualquier cosa". */
    @Test
    void unVideoInvalidoSigueDando400() throws Exception {
        Map<String, Object> body = movieBody(null);
        body.put("videoUrl", "http://cdn.example.com/dune.mp4");

        send(post(MOVIES), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.videoUrl").value(VIDEO_FORMAT));
    }

    // ------------------------------------------------------------------
    // Películas: edición
    // ------------------------------------------------------------------

    /**
     * {@code PUT} sustituye la película completa: sin {@code trailerUrl} ni
     * {@code videoUrl}, los dos quedan a {@code null} (igual que cualquier otro
     * campo del {@code PUT}).
     */
    @Test
    void unPutSinTrailerNiVideoLosBorra() throws Exception {
        Movie movie = saveMovie("Con tráiler", YouTubeUrlSamples.NORMALIZED);
        Map<String, Object> body = movieBody(null);
        body.remove("videoUrl");
        body.remove("trailerUrl");

        send(put(MOVIES + "/" + movie.getId()), body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoUrl").value(nullValue()))
                .andExpect(jsonPath("$.trailerUrl").value(nullValue()));

        Movie saved = movieRepository.findById(movie.getId()).orElseThrow();
        assertNull(saved.getVideoUrl());
        assertNull(saved.getTrailerUrl());
    }

    @Test
    void unPutConOtraFormaDeEnlaceGuardaElTrailerNormalizado() throws Exception {
        Movie movie = saveMovie("Sin tráiler", null);

        send(put(MOVIES + "/" + movie.getId()), movieBody("https://www.youtube-nocookie.com/embed/zZ9-_yY8xX7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trailerUrl").value("https://www.youtube.com/watch?v=zZ9-_yY8xX7"));

        assertEquals("https://www.youtube.com/watch?v=zZ9-_yY8xX7",
                movieRepository.findById(movie.getId()).orElseThrow().getTrailerUrl());
    }

    @Test
    void unPutConUnTrailerNoAceptadoDa400YNoCambiaNada() throws Exception {
        Movie movie = saveMovie("Intacta", YouTubeUrlSamples.NORMALIZED);

        send(put(MOVIES + "/" + movie.getId()), movieBody("https://youtube.com.evil.com/watch?v=aB3_-eFgHiJ"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.trailerUrl").value(TRAILER_FORMAT));

        Movie unchanged = movieRepository.findById(movie.getId()).orElseThrow();
        assertEquals("Intacta", unchanged.getTitle());
        assertEquals(YouTubeUrlSamples.NORMALIZED, unchanged.getTrailerUrl());
    }

    // ------------------------------------------------------------------
    // Películas: lecturas
    // ------------------------------------------------------------------

    @Test
    void lasLecturasDePeliculasDevuelvenElTrailer() throws Exception {
        Movie movie = saveMovie("Duna", YouTubeUrlSamples.NORMALIZED);
        String trailer = YouTubeUrlSamples.NORMALIZED;

        get(MOVIES + "/" + movie.getId(), userToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trailerUrl").value(trailer))
                .andExpect(jsonPath("$.videoUrl").value("https://cdn.example.com/duna.mp4"));
        get(MOVIES, userToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].trailerUrl").value(trailer));
        get(MOVIES + "/search?title=Duna", userToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].trailerUrl").value(trailer));

        send(post("/api/users/me/favorites/" + movie.getId()), userToken, null).andExpect(status().isNoContent());
        get("/api/users/me/favorites", userToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].trailerUrl").value(trailer));
    }

    // ------------------------------------------------------------------
    // Series
    // ------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("acceptedTrailers")
    void altaDeSerieConCadaFormaAceptadaGuardaElTrailerNormalizado(String trailer, String normalized)
            throws Exception {
        long id = idOf(send(post(SERIES), seriesBody(trailer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trailerUrl").value(normalized)));

        assertEquals(normalized, seriesRepository.findById(id).orElseThrow().getTrailerUrl());
    }

    @ParameterizedTest
    @MethodSource("rejectedTrailers")
    void altaDeSerieConUnTrailerNoAceptadoDa400ConElMensajeExacto(String trailer) throws Exception {
        send(post(SERIES), seriesBody(trailer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.trailerUrl").value(TRAILER_FORMAT));

        assertEquals(0, seriesRepository.count());
    }

    @Test
    void unaSerieSinTrailerLoDevuelveANullYUnPutSinTrailerLoBorra() throws Exception {
        long id = idOf(send(post(SERIES), seriesBody("   "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasKey("trailerUrl")))
                .andExpect(jsonPath("$.trailerUrl").value(nullValue())));

        send(put(SERIES + "/" + id), seriesBody("https://youtu.be/" + YouTubeUrlSamples.ID + "?si=x"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trailerUrl").value(YouTubeUrlSamples.NORMALIZED));
        assertEquals(YouTubeUrlSamples.NORMALIZED, seriesRepository.findById(id).orElseThrow().getTrailerUrl());

        Map<String, Object> withoutTrailer = seriesBody(null);
        withoutTrailer.remove("trailerUrl");
        send(put(SERIES + "/" + id), withoutTrailer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trailerUrl").value(nullValue()));
        assertNull(seriesRepository.findById(id).orElseThrow().getTrailerUrl());
    }

    /** Resumen y detalle, públicos y del panel, y "Mi lista": todos llevan el tráiler. */
    @Test
    void lasLecturasDeSeriesDevuelvenElTrailer() throws Exception {
        String trailer = YouTubeUrlSamples.NORMALIZED;
        Series series = saveSeries("Fundación", trailer);
        saveEpisode(series, 1, 1, null);

        get(SERIES + "/" + series.getId(), userToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trailerUrl").value(trailer))
                .andExpect(jsonPath("$.seasons[0].episodes[0].videoUrl").value(nullValue()));
        get(SERIES, userToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].trailerUrl").value(trailer));
        get(SERIES + "/search?title=Fundación", userToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].trailerUrl").value(trailer));
        get("/api/admin/series", adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].trailerUrl").value(trailer));
        get("/api/admin/series/" + series.getId(), adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trailerUrl").value(trailer));

        send(post("/api/users/me/favorites/series/" + series.getId()), userToken, null)
                .andExpect(status().isNoContent());
        get("/api/users/me/favorites/series", userToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].trailerUrl").value(trailer));
    }

    // ------------------------------------------------------------------
    // Episodios: vídeo opcional
    // ------------------------------------------------------------------

    @Test
    void unEpisodioSinVideoSeCreaYSeDevuelveConVideoNull() throws Exception {
        Series series = saveSeries("Sin vídeo", null);
        Map<String, Object> body = episodeBody(1, 1);
        body.remove("videoUrl");

        long id = idOf(send(post(SERIES + "/" + series.getId() + "/episodes"), body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasKey("videoUrl")))
                .andExpect(jsonPath("$.videoUrl").value(nullValue())));

        assertNull(episodeRepository.findById(id).orElseThrow().getVideoUrl());
    }

    @Test
    void unPutDeEpisodioConVideoEnBlancoOSinVideoLoBorra() throws Exception {
        Series series = saveSeries("Episodios", null);
        Episode episode = saveEpisode(series, 1, 1, "https://cdn.example.com/1x1.mp4");
        String url = SERIES + "/" + series.getId() + "/episodes/" + episode.getId();

        Map<String, Object> blank = episodeBody(1, 1);
        blank.put("videoUrl", "  ");
        send(put(url), blank)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoUrl").value(nullValue()));
        assertNull(episodeRepository.findById(episode.getId()).orElseThrow().getVideoUrl());

        send(put(url), episodeBody(1, 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoUrl").value("https://cdn.example.com/ep.mp4"));

        Map<String, Object> missing = episodeBody(1, 1);
        missing.remove("videoUrl");
        send(put(url), missing)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoUrl").value(nullValue()));
        assertNull(episodeRepository.findById(episode.getId()).orElseThrow().getVideoUrl());
    }

    // ------------------------------------------------------------------
    // Documentación
    // ------------------------------------------------------------------

    /**
     * El OpenAPI publica los campos nuevos: tope de 500 en las peticiones, las
     * formas aceptadas en la descripción y el tipo {@code ["string", "null"]}
     * (OpenAPI 3.1) en las respuestas.
     */
    @Test
    void elOpenApiDocumentaElTrailerYLosNulos() throws Exception {
        String schemas = "$.components.schemas";

        get("/v3/api-docs", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath(schemas + ".MovieRequest.properties.trailerUrl.maxLength").value(500))
                .andExpect(jsonPath(schemas + ".SeriesRequest.properties.trailerUrl.maxLength").value(500))
                .andExpect(jsonPath(schemas + ".MovieRequest.properties.trailerUrl.description")
                        .value(containsString("youtu.be")))
                .andExpect(jsonPath(schemas + ".SeriesRequest.required")
                        .value(not(hasItem("trailerUrl"))))
                .andExpect(jsonPath(schemas + ".EpisodeRequest.required")
                        .value(not(hasItem("videoUrl"))))
                .andExpect(jsonPath(schemas + ".MovieResponse.properties.trailerUrl.type")
                        .value(containsInAnyOrder("string", "null")))
                .andExpect(jsonPath(schemas + ".MovieResponse.properties.videoUrl.type")
                        .value(containsInAnyOrder("string", "null")))
                .andExpect(jsonPath(schemas + ".SeriesResponse.properties.trailerUrl.type")
                        .value(containsInAnyOrder("string", "null")))
                .andExpect(jsonPath(schemas + ".SeriesDetailResponse.properties.trailerUrl.type")
                        .value(containsInAnyOrder("string", "null")))
                .andExpect(jsonPath(schemas + ".EpisodeResponse.properties.videoUrl.type")
                        .value(containsInAnyOrder("string", "null")));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private ResultActions send(MockHttpServletRequestBuilder builder, Map<String, Object> body) throws Exception {
        return send(builder, adminToken, body);
    }

    private ResultActions send(MockHttpServletRequestBuilder builder, String token, Map<String, Object> body)
            throws Exception {
        builder.header("Authorization", token);
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(builder);
    }

    private ResultActions get(String url, String token) throws Exception {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.get(url);
        if (token != null) {
            builder.header("Authorization", token);
        }
        return mockMvc.perform(builder);
    }

    private long idOf(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    /** Película válida con vídeo y el tráiler indicado ({@code null} se manda como {@code null}). */
    private Map<String, Object> movieBody(String trailerUrl) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Duna");
        body.put("description", "Sinopsis de Duna");
        body.put("duration", 155);
        body.put("releaseYear", 2021);
        body.put("imageUrl", "https://cdn.example.com/duna.jpg");
        body.put("videoUrl", "https://cdn.example.com/duna.mp4");
        body.put("trailerUrl", trailerUrl);
        body.put("genreIds", List.of(genre.getId()));
        return body;
    }

    private Map<String, Object> seriesBody(String trailerUrl) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Fundación");
        body.put("description", "Sinopsis de Fundación");
        body.put("releaseYear", 2021);
        body.put("imageUrl", "https://cdn.example.com/fundacion.jpg");
        body.put("trailerUrl", trailerUrl);
        body.put("genreIds", List.of(genre.getId()));
        return body;
    }

    private Map<String, Object> episodeBody(int season, int number) {
        Map<String, Object> body = new HashMap<>();
        body.put("seasonNumber", season);
        body.put("episodeNumber", number);
        body.put("title", "Episodio " + season + "x" + number);
        body.put("duration", 50);
        body.put("videoUrl", "https://cdn.example.com/ep.mp4");
        return body;
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
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
        Genre newGenre = new Genre();
        newGenre.setName(name);
        return genreRepository.save(newGenre);
    }

    private Movie saveMovie(String title, String trailerUrl) {
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setDescription("Sinopsis");
        movie.setDuration(100);
        movie.setReleaseYear(2021);
        movie.setImageUrl("https://cdn.example.com/duna.jpg");
        movie.setVideoUrl("https://cdn.example.com/duna.mp4");
        movie.setTrailerUrl(trailerUrl);
        movie.setGenres(new HashSet<>(Set.of(genre)));
        return movieRepository.save(movie);
    }

    private Series saveSeries(String title, String trailerUrl) {
        Series series = new Series();
        series.setTitle(title);
        series.setDescription("Sinopsis de " + title);
        series.setReleaseYear(2021);
        series.setImageUrl("https://cdn.example.com/serie.jpg");
        series.setTrailerUrl(trailerUrl);
        series.setGenres(new HashSet<>(Set.of(genre)));
        return seriesRepository.save(series);
    }

    private Episode saveEpisode(Series series, int season, int number, String videoUrl) {
        Episode episode = new Episode();
        episode.setSeries(series);
        episode.setSeasonNumber(season);
        episode.setEpisodeNumber(number);
        episode.setTitle("Episodio " + season + "x" + number);
        episode.setDuration(45);
        episode.setVideoUrl(videoUrl);
        return episodeRepository.save(episode);
    }

    /** Series primero (sus géneros, episodios y favoritos caen con ellas), luego el resto. */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
