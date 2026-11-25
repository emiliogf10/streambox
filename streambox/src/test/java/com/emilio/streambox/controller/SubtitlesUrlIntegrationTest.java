package com.emilio.streambox.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Subtítulos ({@code subtitlesUrl}, migración {@code V15}) de películas y
 * episodios a través de la API real.
 *
 * <p>
 * Comprueba el contrato con el frontend: se guardan y se devuelven en todas
 * las lecturas (también dentro del detalle de una serie, vía
 * {@code EpisodeResponse}); en blanco o ausentes son {@code null} y un
 * {@code PUT} que los omite los borra; y cada caso inválido da 400
 * {@code VALIDATION_ERROR} con <b>un</b> mensaje en el campo correcto.
 * </p>
 *
 * <p>
 * <b>Defensa en profundidad.</b> La {@code V15} tiene dos {@code CHECK}
 * (subtítulos por {@code https://} y subtítulos solo con vídeo). Si una petición
 * llegara a violarlos, el cliente recibiría un 500 (la violación no se traduce:
 * ver {@code GlobalExceptionHandler#handleDataIntegrityViolation}). Por eso
 * los casos que los violarían ({@code http:}, otros esquemas, subtítulos sin
 * vídeo, quitar el vídeo en un {@code PUT} y dejar los subtítulos) se prueban
 * aquí: la validación debe pararlos antes con un 400 y sin guardar nada. Sin
 * {@code @Transactional}: cada petición se confirma de verdad, así que los
 * {@code CHECK} se evalúan igual que en producción.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SubtitlesUrlIntegrationTest {

    private static final String SIZE = "La URL de los subtítulos no puede superar los 500 caracteres";
    private static final String FORMAT =
            "Los subtítulos deben ser un archivo .vtt (WebVTT) con una URL que empiece por https://";
    private static final String REQUIRE_VIDEO = "Los subtítulos necesitan un vídeo: indica también la URL del vídeo";
    private static final String VIDEO_FORMAT = "La URL del vídeo debe empezar por https://";

    private static final String MOVIES = "/api/movies";
    private static final String SERIES = "/api/series";
    private static final String VIDEO = "https://cdn.example.com/duna.mp4";
    private static final String SUBTITLES = "https://cdn.example.com/subs/duna.es.vtt?token=abc#t=1";

    @Autowired private MockMvc mockMvc;
    @Autowired private MovieRepository movieRepository;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String adminToken;
    private String userToken;
    private Genre genre;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        adminToken = "Bearer " + jwtService.generateToken(saveUser("subsadmin", Role.ADMIN));
        userToken = "Bearer " + jwtService.generateToken(saveUser("subsuser", Role.USER));
        genre = saveGenre("Drama");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Películas
    // ------------------------------------------------------------------

    /** Alta con subtítulos: se guardan tal cual y salen en el detalle, el listado y la búsqueda. */
    @Test
    void altaDePeliculaConSubtitulosLosGuardaYLosDevuelveEnLasLecturas() throws Exception {
        long id = idOf(send(post(MOVIES), adminToken, movieBody(VIDEO, SUBTITLES))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtitlesUrl").value(SUBTITLES)));

        assertEquals(SUBTITLES, movieRepository.findById(id).orElseThrow().getSubtitlesUrl());
        get(MOVIES + "/" + id, userToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtitlesUrl").value(SUBTITLES));
        get(MOVIES, userToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].subtitlesUrl").value(SUBTITLES));
        get(MOVIES + "/search?title=Duna", userToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].subtitlesUrl").value(SUBTITLES));
    }

    /** Ausentes, vacíos o en blanco = sin subtítulos: {@code null}, presente en el JSON. */
    @ParameterizedTest
    @ValueSource(strings = { "", "   ", "\t" })
    void unosSubtitulosEnBlancoSeGuardanComoNull(String blank) throws Exception {
        long id = idOf(send(post(MOVIES), adminToken, movieBody(VIDEO, blank))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasKey("subtitlesUrl")))
                .andExpect(jsonPath("$.subtitlesUrl").value(nullValue())));

        assertNull(movieRepository.findById(id).orElseThrow().getSubtitlesUrl());
    }

    /** Sin vídeo ni subtítulos también vale: los subtítulos solo exigen vídeo si los hay. */
    @Test
    void unaPeliculaSinVideoNiSubtitulosSeCrea() throws Exception {
        Map<String, Object> body = movieBody(null, null);
        body.remove("subtitlesUrl");

        send(post(MOVIES), adminToken, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.videoUrl").value(nullValue()))
                .andExpect(jsonPath("$.subtitlesUrl").value(nullValue()));
    }

    /**
     * Formatos que no son un {@code .vtt} por {@code https://}: 400 con el
     * mensaje de formato y nada guardado. {@code http:} y {@code javascript:}
     * violarían además {@code ck_movies_subtitles_url}: sin la validación
     * serían un 500.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "https://cdn.example.com/duna.srt",
            "https://cdn.example.com/descargar?f=duna.vtt",
            "http://cdn.example.com/duna.vtt",
            "javascript:alert(1)//x.vtt",
            "https://usuario:clave@cdn.example.com/duna.vtt" })
    void unosSubtitulosQueNoSonUnVttPorHttpsDan400(String subtitles) throws Exception {
        send(post(MOVIES), adminToken, movieBody(VIDEO, subtitles))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.subtitlesUrl").value(FORMAT))
                .andExpect(jsonPath("$.validationErrors.videoUrl").doesNotExist());

        assertEquals(0, movieRepository.count());
    }

    /** Mayúsculas en la extensión y {@code .vtt} con consulta o fragmento: válidos. */
    @ParameterizedTest
    @ValueSource(strings = {
            "https://cdn.example.com/DUNA.VTT",
            "https://cdn.example.com/duna.vtt?firma=x",
            "https://cdn.example.com/duna.vtt#t=10" })
    void lasVariantesAdmitidasDelVttSeGuardanTalCual(String subtitles) throws Exception {
        send(post(MOVIES), adminToken, movieBody(VIDEO, subtitles))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtitlesUrl").value(subtitles));
    }

    @Test
    void masDe500CaracteresDaSoloElErrorDeLongitud() throws Exception {
        String prefix = "https://cdn.example.com/";
        String url500 = prefix + "a".repeat(500 - prefix.length() - ".vtt".length()) + ".vtt";

        send(post(MOVIES), adminToken, movieBody(VIDEO, prefix + "a" + url500.substring(prefix.length())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.subtitlesUrl").value(SIZE));
        send(post(MOVIES), adminToken, movieBody(VIDEO, "http://" + "a".repeat(494)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.subtitlesUrl").value(SIZE));
        send(post(MOVIES), adminToken, movieBody(VIDEO, url500))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtitlesUrl").value(url500));
    }

    /**
     * Subtítulos válidos sin vídeo (ausente, {@code null} o en blanco): 400 en
     * {@code subtitlesUrl}, nada en {@code videoUrl} y nada guardado. Sin
     * {@code @SubtitlesRequireVideo} el {@code INSERT} violaría
     * {@code ck_movies_subtitles_require_video} y sería un 500.
     */
    @ParameterizedTest
    @ValueSource(strings = { "ausente", "", "   " })
    void unosSubtitulosValidosSinVideoDan400EnElCampoDeLosSubtitulos(String video) throws Exception {
        Map<String, Object> body = movieBody("ausente".equals(video) ? null : video, SUBTITLES);
        if ("ausente".equals(video)) {
            body.remove("videoUrl");
        }

        send(post(MOVIES), adminToken, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.subtitlesUrl").value(REQUIRE_VIDEO))
                .andExpect(jsonPath("$.validationErrors.videoUrl").doesNotExist());

        assertEquals(0, movieRepository.count());
    }

    /** Un vídeo inválido con subtítulos válidos: un solo error, el del vídeo. */
    @Test
    void unVideoInvalidoConSubtitulosValidosDaUnSoloErrorEnElVideo() throws Exception {
        send(post(MOVIES), adminToken, movieBody("http://cdn.example.com/duna.mp4", SUBTITLES))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.videoUrl").value(VIDEO_FORMAT))
                .andExpect(jsonPath("$.validationErrors.subtitlesUrl").doesNotExist());
    }

    /** Como todo {@code PUT}: lo que no llega se borra, también los subtítulos. */
    @Test
    void unPutSinSubtitulosLosBorra() throws Exception {
        Movie movie = saveMovie("Duna", VIDEO, SUBTITLES);
        Map<String, Object> body = movieBody(VIDEO, null);
        body.remove("subtitlesUrl");

        send(put(MOVIES + "/" + movie.getId()), adminToken, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.videoUrl").value(VIDEO))
                .andExpect(jsonPath("$.subtitlesUrl").value(nullValue()));

        assertNull(movieRepository.findById(movie.getId()).orElseThrow().getSubtitlesUrl());
    }

    /**
     * Quitar el vídeo y dejar los subtítulos: 400 y la película sigue igual. Es
     * el {@code UPDATE} que violaría {@code ck_movies_subtitles_require_video}.
     */
    @Test
    void unPutQueQuitaElVideoYDejaLosSubtitulosDa400YNoCambiaNada() throws Exception {
        Movie movie = saveMovie("Intacta", VIDEO, SUBTITLES);

        send(put(MOVIES + "/" + movie.getId()), adminToken, movieBody(" ", SUBTITLES))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.subtitlesUrl").value(REQUIRE_VIDEO));

        Movie unchanged = movieRepository.findById(movie.getId()).orElseThrow();
        assertEquals(VIDEO, unchanged.getVideoUrl());
        assertEquals(SUBTITLES, unchanged.getSubtitlesUrl());
    }

    // ------------------------------------------------------------------
    // Episodios
    // ------------------------------------------------------------------

    /** Alta de un episodio con subtítulos: en la respuesta y en el detalle de la serie (público y del panel). */
    @Test
    void altaDeEpisodioConSubtitulosLosDevuelveTambienEnElDetalleDeLaSerie() throws Exception {
        Series series = saveSeries("Fundación");

        long id = idOf(send(post(SERIES + "/" + series.getId() + "/episodes"), adminToken,
                episodeBody(VIDEO, SUBTITLES))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.subtitlesUrl").value(SUBTITLES)));

        assertEquals(SUBTITLES, episodeRepository.findById(id).orElseThrow().getSubtitlesUrl());
        get(SERIES + "/" + series.getId(), userToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seasons[0].episodes[0].subtitlesUrl").value(SUBTITLES));
        get("/api/admin/series/" + series.getId(), adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seasons[0].episodes[0].subtitlesUrl").value(SUBTITLES));
    }

    @Test
    void unEpisodioConSubtitulosSinVideoDa400YNoSeGuarda() throws Exception {
        Series series = saveSeries("Sin vídeo");
        Map<String, Object> body = episodeBody(null, SUBTITLES);
        body.remove("videoUrl");

        send(post(SERIES + "/" + series.getId() + "/episodes"), adminToken, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.subtitlesUrl").value(REQUIRE_VIDEO))
                .andExpect(jsonPath("$.validationErrors.videoUrl").doesNotExist());

        assertEquals(0, episodeRepository.count());
    }

    @Test
    void unEpisodioConSubtitulosQueNoSonVttDa400() throws Exception {
        Series series = saveSeries("Formato");

        send(post(SERIES + "/" + series.getId() + "/episodes"), adminToken,
                episodeBody(VIDEO, "http://cdn.example.com/1x1.vtt"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.subtitlesUrl").value(FORMAT));
        send(post(SERIES + "/" + series.getId() + "/episodes"), adminToken,
                episodeBody("http://cdn.example.com/1x1.mp4", SUBTITLES))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.videoUrl").value(VIDEO_FORMAT))
                .andExpect(jsonPath("$.validationErrors.subtitlesUrl").doesNotExist());

        assertEquals(0, episodeRepository.count());
    }

    /** {@code PUT} de un episodio: omitirlos los borra; quitar solo el vídeo, 400 sin cambios. */
    @Test
    void unPutDeEpisodioSinSubtitulosLosBorraYSinVideoDa400() throws Exception {
        Series series = saveSeries("Edición");
        Episode episode = saveEpisode(series, VIDEO, SUBTITLES);
        String url = SERIES + "/" + series.getId() + "/episodes/" + episode.getId();

        send(put(url), adminToken, episodeBody(null, SUBTITLES))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.subtitlesUrl").value(REQUIRE_VIDEO));
        assertEquals(SUBTITLES, episodeRepository.findById(episode.getId()).orElseThrow().getSubtitlesUrl());

        Map<String, Object> withoutSubtitles = episodeBody(VIDEO, null);
        withoutSubtitles.remove("subtitlesUrl");
        send(put(url), adminToken, withoutSubtitles)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasKey("subtitlesUrl")))
                .andExpect(jsonPath("$.subtitlesUrl").value(nullValue()));
        assertNull(episodeRepository.findById(episode.getId()).orElseThrow().getSubtitlesUrl());
    }

    // ------------------------------------------------------------------
    // Documentación
    // ------------------------------------------------------------------

    /**
     * El OpenAPI publica el campo nuevo: tope de 500 y las reglas en las
     * peticiones, opcional, y {@code ["string", "null"]} en las respuestas.
     */
    @Test
    void elOpenApiDocumentaLosSubtitulos() throws Exception {
        String schemas = "$.components.schemas";

        get("/v3/api-docs", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath(schemas + ".MovieRequest.properties.subtitlesUrl.maxLength").value(500))
                .andExpect(jsonPath(schemas + ".EpisodeRequest.properties.subtitlesUrl.maxLength").value(500))
                .andExpect(jsonPath(schemas + ".MovieRequest.properties.subtitlesUrl.description")
                        .value(containsString(REQUIRE_VIDEO)))
                .andExpect(jsonPath(schemas + ".EpisodeRequest.properties.subtitlesUrl.description")
                        .value(containsString(FORMAT)))
                .andExpect(jsonPath(schemas + ".MovieResponse.properties.subtitlesUrl.type")
                        .value(containsInAnyOrder("string", "null")))
                .andExpect(jsonPath(schemas + ".EpisodeResponse.properties.subtitlesUrl.type")
                        .value(containsInAnyOrder("string", "null")));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

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

    /** Película válida con el vídeo y los subtítulos indicados ({@code null} se manda como {@code null}). */
    private Map<String, Object> movieBody(String videoUrl, String subtitlesUrl) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Duna");
        body.put("description", "Sinopsis de Duna");
        body.put("duration", 155);
        body.put("releaseYear", 2021);
        body.put("imageUrl", "https://cdn.example.com/duna.jpg");
        body.put("videoUrl", videoUrl);
        body.put("subtitlesUrl", subtitlesUrl);
        body.put("genreIds", List.of(genre.getId()));
        return body;
    }

    private Map<String, Object> episodeBody(String videoUrl, String subtitlesUrl) {
        Map<String, Object> body = new HashMap<>();
        body.put("seasonNumber", 1);
        body.put("episodeNumber", 1);
        body.put("title", "Episodio 1x1");
        body.put("duration", 50);
        body.put("videoUrl", videoUrl);
        body.put("subtitlesUrl", subtitlesUrl);
        return body;
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
        Genre newGenre = new Genre();
        newGenre.setName(name);
        return genreRepository.save(newGenre);
    }

    private Movie saveMovie(String title, String videoUrl, String subtitlesUrl) {
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setDescription("Sinopsis");
        movie.setDuration(100);
        movie.setReleaseYear(2021);
        movie.setImageUrl("https://cdn.example.com/duna.jpg");
        movie.setVideoUrl(videoUrl);
        movie.setSubtitlesUrl(subtitlesUrl);
        movie.setGenres(new HashSet<>(Set.of(genre)));
        return movieRepository.save(movie);
    }

    private Series saveSeries(String title) {
        Series series = new Series();
        series.setTitle(title);
        series.setDescription("Sinopsis de " + title);
        series.setReleaseYear(2021);
        series.setImageUrl("https://cdn.example.com/serie.jpg");
        series.setGenres(new HashSet<>(Set.of(genre)));
        return seriesRepository.save(series);
    }

    private Episode saveEpisode(Series series, String videoUrl, String subtitlesUrl) {
        Episode episode = new Episode();
        episode.setSeries(series);
        episode.setSeasonNumber(1);
        episode.setEpisodeNumber(1);
        episode.setTitle("Episodio 1x1");
        episode.setDuration(45);
        episode.setVideoUrl(videoUrl);
        episode.setSubtitlesUrl(subtitlesUrl);
        return episodeRepository.save(episode);
    }

    /** Series primero (sus géneros y episodios caen con ellas), luego el resto. */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
