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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.emilio.streambox.dto.EpisodeRequest;
import com.emilio.streambox.dto.MovieRequest;
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
 * Imágenes opcionales de la migración {@code V16} a través de la API real: la
 * imagen horizontal de cabecera ({@code backdropUrl}) de películas y series y
 * la imagen propia del episodio ({@code imageUrl} de {@code EpisodeRequest}).
 *
 * <p>
 * Comprueba el contrato con el frontend: se guardan y se devuelven en las
 * lecturas de lista y de detalle (el episodio, dentro del detalle de su serie);
 * en blanco o ausentes son {@code null} (presente en el JSON) y un {@code PUT}
 * que las omite las borra; {@code /covers/<archivo>} vale; y {@code http:},
 * {@code javascript:} y más de 500 caracteres dan 400
 * {@code VALIDATION_ERROR} con <b>un</b> mensaje exacto en el campo correcto y
 * sin guardar nada.
 * </p>
 *
 * <p>
 * <b>Por qué importa el 400.</b> Las tres columnas tienen un {@code CHECK}
 * ({@code https://} o {@code /covers/}). Si la validación dejara pasar un
 * {@code http:} o un texto en blanco, el {@code INSERT} violaría el
 * {@code CHECK} y el cliente recibiría un 500. Sin {@code @Transactional}: cada
 * petición se confirma de verdad, así que los {@code CHECK} se evalúan igual
 * que en producción.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class BackdropAndEpisodeImageIntegrationTest {

    /*
     * Los mensajes van escritos a mano, no con las constantes: son el contrato
     * con el frontend y el test debe fallar si alguien cambia el texto.
     */
    private static final String BACKDROP_FORMAT =
            "La URL de la imagen de fondo debe empezar por https:// o ser una portada propia (/covers/archivo)";
    private static final String BACKDROP_SIZE = "La URL de la imagen de fondo no puede superar los 500 caracteres";
    private static final String EPISODE_IMAGE_FORMAT =
            "La URL de la imagen del episodio debe empezar por https:// o ser una portada propia (/covers/archivo)";
    private static final String EPISODE_IMAGE_SIZE =
            "La URL de la imagen del episodio no puede superar los 500 caracteres";

    private static final String MOVIES = "/api/movies";
    private static final String SERIES = "/api/series";
    private static final String POSTER = "https://cdn.example.com/poster.jpg";
    private static final String BACKDROP = "https://cdn.example.com/fondo-1280.jpg?v=2";
    private static final String LOCAL_COVER = "/covers/x.webp";
    private static final String VIDEO = "https://cdn.example.com/1x1.mp4";

    /** URL {@code https://} válida de exactamente 500 caracteres. */
    private static final String URL_500 = "https://cdn.example.com/" + "a".repeat(500 - 24 - 5) + ".webp";

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
        adminToken = "Bearer " + jwtService.generateToken(saveUser("imgadmin", Role.ADMIN));
        userToken = "Bearer " + jwtService.generateToken(saveUser("imguser", Role.USER));
        genre = saveGenre("Drama");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    /** URLs inválidas y el mensaje que debe dar cada una (longitud antes que formato). */
    static Stream<Arguments> invalidUrls() {
        return Stream.of(
                Arguments.of("http://cdn.example.com/fondo.jpg", false),
                Arguments.of("javascript:alert(1)", false),
                Arguments.of("/covers/../secreto.webp", false),
                Arguments.of(URL_500 + "a", true),
                Arguments.of("http://" + "a".repeat(494), true));
    }

    // ------------------------------------------------------------------
    // Películas
    // ------------------------------------------------------------------

    /** Alta con imagen de fondo: guardada tal cual y presente en detalle, listado y búsqueda. */
    @Test
    void altaDePeliculaConImagenDeFondoLaGuardaYLaDevuelveEnLasLecturas() throws Exception {
        long id = idOf(send(post(MOVIES), adminToken, movieBody(BACKDROP))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.backdropUrl").value(BACKDROP))
                .andExpect(jsonPath("$.imageUrl").value(POSTER)));

        assertEquals(BACKDROP, movieRepository.findById(id).orElseThrow().getBackdropUrl());
        get(MOVIES + "/" + id, userToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backdropUrl").value(BACKDROP));
        get(MOVIES, userToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].backdropUrl").value(BACKDROP));
    }

    /** Una portada propia del frontend también vale como imagen de fondo. */
    @Test
    void unaPortadaPropiaValeComoImagenDeFondoDePelicula() throws Exception {
        send(post(MOVIES), adminToken, movieBody(LOCAL_COVER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.backdropUrl").value(LOCAL_COVER));
        send(post(MOVIES), adminToken, withTitle(movieBody(URL_500), "Duna 500"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.backdropUrl").value(URL_500));
    }

    /**
     * Vacía o en blanco = sin imagen de fondo: {@code null}, presente en el
     * JSON. Sin el paso a {@code null} del mapper, el texto en blanco violaría
     * {@code ck_movies_backdrop_url} (500).
     */
    @ParameterizedTest
    @ValueSource(strings = { "", "   ", "\t" })
    void unaImagenDeFondoEnBlancoSeGuardaComoNull(String blank) throws Exception {
        long id = idOf(send(post(MOVIES), adminToken, movieBody(blank))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasKey("backdropUrl")))
                .andExpect(jsonPath("$.backdropUrl").value(nullValue())));

        assertNull(movieRepository.findById(id).orElseThrow().getBackdropUrl());
    }

    /** Sin el campo, la película se crea y la respuesta lo lleva a {@code null}. */
    @Test
    void unaPeliculaSinImagenDeFondoSeCreaConNull() throws Exception {
        Map<String, Object> body = movieBody(null);
        body.remove("backdropUrl");

        send(post(MOVIES), adminToken, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasKey("backdropUrl")))
                .andExpect(jsonPath("$.backdropUrl").value(nullValue()));
    }

    /** {@code http:}, otros esquemas, una portada mal formada o más de 500 caracteres: 400 en el campo. */
    @ParameterizedTest
    @MethodSource("invalidUrls")
    void unaImagenDeFondoDePeliculaInvalidaDa400ConElMensajeExacto(String url, boolean tooLong) throws Exception {
        send(post(MOVIES), adminToken, movieBody(url))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.backdropUrl").value(tooLong ? BACKDROP_SIZE : BACKDROP_FORMAT))
                .andExpect(jsonPath("$.validationErrors.imageUrl").doesNotExist());

        assertEquals(0, movieRepository.count());
    }

    /** Un {@code PUT} cambia la imagen de fondo y otro que la omite la borra. */
    @Test
    void unPutDePeliculaCambiaLaImagenDeFondoYOmitirlaLaBorra() throws Exception {
        Movie movie = saveMovie("Duna", BACKDROP);

        send(put(MOVIES + "/" + movie.getId()), adminToken, movieBody(LOCAL_COVER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backdropUrl").value(LOCAL_COVER));
        assertEquals(LOCAL_COVER, movieRepository.findById(movie.getId()).orElseThrow().getBackdropUrl());

        Map<String, Object> withoutBackdrop = movieBody(null);
        withoutBackdrop.remove("backdropUrl");
        send(put(MOVIES + "/" + movie.getId()), adminToken, withoutBackdrop)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasKey("backdropUrl")))
                .andExpect(jsonPath("$.backdropUrl").value(nullValue()));
        assertNull(movieRepository.findById(movie.getId()).orElseThrow().getBackdropUrl());
    }

    /** Un {@code PUT} inválido no toca la imagen de fondo guardada. */
    @Test
    void unPutDePeliculaConImagenDeFondoInvalidaDa400YNoCambiaNada() throws Exception {
        Movie movie = saveMovie("Intacta", BACKDROP);

        send(put(MOVIES + "/" + movie.getId()), adminToken, movieBody("http://cdn.example.com/fondo.jpg"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.backdropUrl").value(BACKDROP_FORMAT));

        assertEquals(BACKDROP, movieRepository.findById(movie.getId()).orElseThrow().getBackdropUrl());
    }

    // ------------------------------------------------------------------
    // Series
    // ------------------------------------------------------------------

    /**
     * Alta con imagen de fondo: en la respuesta, en el detalle y en los
     * listados (público y del panel). El público necesita un episodio: una
     * serie sin episodios es invisible para los usuarios.
     */
    @Test
    void altaDeSerieConImagenDeFondoLaDevuelveEnListadosYDetalle() throws Exception {
        long id = idOf(send(post(SERIES), adminToken, seriesBody(BACKDROP))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.backdropUrl").value(BACKDROP))
                .andExpect(jsonPath("$.imageUrl").value(POSTER)));
        assertEquals(BACKDROP, seriesRepository.findById(id).orElseThrow().getBackdropUrl());
        saveEpisode(seriesRepository.findById(id).orElseThrow(), null);

        get(SERIES + "/" + id, userToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backdropUrl").value(BACKDROP));
        get(SERIES, userToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].backdropUrl").value(BACKDROP));
        get("/api/admin/series/" + id, adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backdropUrl").value(BACKDROP));
        get("/api/admin/series", adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].backdropUrl").value(BACKDROP));
    }

    @Test
    void unaPortadaPropiaValeComoImagenDeFondoDeSerie() throws Exception {
        send(post(SERIES), adminToken, seriesBody(LOCAL_COVER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.backdropUrl").value(LOCAL_COVER));
    }

    @ParameterizedTest
    @ValueSource(strings = { "", "   ", "\t" })
    void unaImagenDeFondoDeSerieEnBlancoSeGuardaComoNull(String blank) throws Exception {
        long id = idOf(send(post(SERIES), adminToken, seriesBody(blank))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasKey("backdropUrl")))
                .andExpect(jsonPath("$.backdropUrl").value(nullValue())));

        assertNull(seriesRepository.findById(id).orElseThrow().getBackdropUrl());
    }

    @ParameterizedTest
    @MethodSource("invalidUrls")
    void unaImagenDeFondoDeSerieInvalidaDa400ConElMensajeExacto(String url, boolean tooLong) throws Exception {
        send(post(SERIES), adminToken, seriesBody(url))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.backdropUrl").value(tooLong ? BACKDROP_SIZE : BACKDROP_FORMAT))
                .andExpect(jsonPath("$.validationErrors.imageUrl").doesNotExist());

        assertEquals(0, seriesRepository.count());
    }

    @Test
    void unPutDeSerieCambiaLaImagenDeFondoYOmitirlaLaBorra() throws Exception {
        Series series = saveSeries("Fundación", BACKDROP);

        send(put(SERIES + "/" + series.getId()), adminToken, seriesBody(LOCAL_COVER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backdropUrl").value(LOCAL_COVER));
        assertEquals(LOCAL_COVER, seriesRepository.findById(series.getId()).orElseThrow().getBackdropUrl());

        Map<String, Object> withoutBackdrop = seriesBody(null);
        withoutBackdrop.remove("backdropUrl");
        send(put(SERIES + "/" + series.getId()), adminToken, withoutBackdrop)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasKey("backdropUrl")))
                .andExpect(jsonPath("$.backdropUrl").value(nullValue()));
        assertNull(seriesRepository.findById(series.getId()).orElseThrow().getBackdropUrl());
    }

    // ------------------------------------------------------------------
    // Episodios
    // ------------------------------------------------------------------

    /** Alta con imagen propia: en la respuesta y dentro del detalle de la serie (público y del panel). */
    @Test
    void altaDeEpisodioConImagenLaDevuelveTambienEnElDetalleDeLaSerie() throws Exception {
        Series series = saveSeries("Fundación", null);
        String image = "https://cdn.example.com/1x1.jpg";

        long id = idOf(send(post(episodes(series)), adminToken, episodeBody(image))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imageUrl").value(image)));

        assertEquals(image, episodeRepository.findById(id).orElseThrow().getImageUrl());
        get(SERIES + "/" + series.getId(), userToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seasons[0].episodes[0].imageUrl").value(image))
                .andExpect(jsonPath("$.imageUrl").value(POSTER));
        get("/api/admin/series/" + series.getId(), adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seasons[0].episodes[0].imageUrl").value(image));
    }

    @Test
    void unaPortadaPropiaValeComoImagenDelEpisodio() throws Exception {
        Series series = saveSeries("Portada", null);

        send(post(episodes(series)), adminToken, episodeBody(LOCAL_COVER))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imageUrl").value(LOCAL_COVER));
    }

    /** En blanco o ausente = sin imagen propia ({@code null}, y el cliente usa la portada de la serie). */
    @ParameterizedTest
    @ValueSource(strings = { "", "   ", "\t", "ausente" })
    void unaImagenDelEpisodioEnBlancoOAusenteSeGuardaComoNull(String blank) throws Exception {
        Series series = saveSeries("En blanco", null);
        Map<String, Object> body = episodeBody(blank);
        if ("ausente".equals(blank)) {
            body.remove("imageUrl");
        }

        long id = idOf(send(post(episodes(series)), adminToken, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$", hasKey("imageUrl")))
                .andExpect(jsonPath("$.imageUrl").value(nullValue())));

        assertNull(episodeRepository.findById(id).orElseThrow().getImageUrl());
    }

    @ParameterizedTest
    @MethodSource("invalidUrls")
    void unaImagenDelEpisodioInvalidaDa400ConElMensajeExacto(String url, boolean tooLong) throws Exception {
        Series series = saveSeries("Inválida", null);

        send(post(episodes(series)), adminToken, episodeBody(url))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.imageUrl")
                        .value(tooLong ? EPISODE_IMAGE_SIZE : EPISODE_IMAGE_FORMAT))
                .andExpect(jsonPath("$.validationErrors.videoUrl").doesNotExist());

        assertEquals(0, episodeRepository.count());
    }

    @Test
    void unPutDeEpisodioCambiaLaImagenYOmitirlaLaBorra() throws Exception {
        Series series = saveSeries("Edición", null);
        Episode episode = saveEpisode(series, "https://cdn.example.com/antes.jpg");
        String url = episodes(series) + "/" + episode.getId();

        send(put(url), adminToken, episodeBody(LOCAL_COVER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value(LOCAL_COVER));
        assertEquals(LOCAL_COVER, episodeRepository.findById(episode.getId()).orElseThrow().getImageUrl());

        send(put(url), adminToken, episodeBody("javascript:alert(1)"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.imageUrl").value(EPISODE_IMAGE_FORMAT));
        assertEquals(LOCAL_COVER, episodeRepository.findById(episode.getId()).orElseThrow().getImageUrl());

        Map<String, Object> withoutImage = episodeBody(null);
        withoutImage.remove("imageUrl");
        send(put(url), adminToken, withoutImage)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasKey("imageUrl")))
                .andExpect(jsonPath("$.imageUrl").value(nullValue()));
        assertNull(episodeRepository.findById(episode.getId()).orElseThrow().getImageUrl());
    }

    // ------------------------------------------------------------------
    // Documentación y constantes
    // ------------------------------------------------------------------

    /**
     * Las constantes públicas, que usa la anotación, dicen exactamente lo
     * acordado con el frontend.
     */
    @Test
    void lasConstantesDeLosMensajesSonLasDelContrato() {
        assertEquals(BACKDROP_FORMAT, MovieRequest.BACKDROP_URL_FORMAT_MESSAGE);
        assertEquals(BACKDROP_SIZE, MovieRequest.BACKDROP_URL_SIZE_MESSAGE);
        assertEquals(EPISODE_IMAGE_FORMAT, EpisodeRequest.IMAGE_URL_FORMAT_MESSAGE);
        assertEquals(EPISODE_IMAGE_SIZE, EpisodeRequest.IMAGE_URL_SIZE_MESSAGE);
    }

    /**
     * El OpenAPI publica los campos nuevos: tope de 500 y 16:9 en las
     * peticiones, {@code ["string", "null"]} en las respuestas.
     */
    @Test
    void elOpenApiDocumentaLasImagenesNuevas() throws Exception {
        String schemas = "$.components.schemas";

        ResultActions docs = get("/v3/api-docs", null).andExpect(status().isOk());
        for (String request : List.of("MovieRequest", "SeriesRequest")) {
            docs.andExpect(jsonPath(schemas + "." + request + ".properties.backdropUrl.maxLength").value(500))
                    .andExpect(jsonPath(schemas + "." + request + ".properties.backdropUrl.description")
                            .value(containsString("16:9")));
        }
        docs.andExpect(jsonPath(schemas + ".EpisodeRequest.properties.imageUrl.maxLength").value(500))
                .andExpect(jsonPath(schemas + ".EpisodeRequest.properties.imageUrl.description")
                        .value(containsString("portada de la serie")));
        for (String response : List.of("MovieResponse", "SeriesResponse", "SeriesDetailResponse")) {
            docs.andExpect(jsonPath(schemas + "." + response + ".properties.backdropUrl.type")
                    .value(containsInAnyOrder("string", "null")));
        }
        docs.andExpect(jsonPath(schemas + ".EpisodeResponse.properties.imageUrl.type")
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

    private static String episodes(Series series) {
        return SERIES + "/" + series.getId() + "/episodes";
    }

    private static Map<String, Object> withTitle(Map<String, Object> body, String title) {
        body.put("title", title);
        return body;
    }

    /** Película válida con la imagen de fondo indicada ({@code null} se manda como {@code null}). */
    private Map<String, Object> movieBody(String backdropUrl) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Duna");
        body.put("description", "Sinopsis de Duna");
        body.put("duration", 155);
        body.put("releaseYear", 2021);
        body.put("imageUrl", POSTER);
        body.put("backdropUrl", backdropUrl);
        body.put("genreIds", List.of(genre.getId()));
        return body;
    }

    private Map<String, Object> seriesBody(String backdropUrl) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Fundación");
        body.put("description", "Sinopsis de Fundación");
        body.put("releaseYear", 2021);
        body.put("imageUrl", POSTER);
        body.put("backdropUrl", backdropUrl);
        body.put("genreIds", List.of(genre.getId()));
        return body;
    }

    private Map<String, Object> episodeBody(String imageUrl) {
        Map<String, Object> body = new HashMap<>();
        body.put("seasonNumber", 1);
        body.put("episodeNumber", 1);
        body.put("title", "Episodio 1x1");
        body.put("duration", 50);
        body.put("videoUrl", VIDEO);
        body.put("imageUrl", imageUrl);
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

    private Movie saveMovie(String title, String backdropUrl) {
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setDescription("Sinopsis");
        movie.setDuration(100);
        movie.setReleaseYear(2021);
        movie.setImageUrl(POSTER);
        movie.setBackdropUrl(backdropUrl);
        movie.setGenres(new HashSet<>(Set.of(genre)));
        return movieRepository.save(movie);
    }

    private Series saveSeries(String title, String backdropUrl) {
        Series series = new Series();
        series.setTitle(title);
        series.setDescription("Sinopsis de " + title);
        series.setReleaseYear(2021);
        series.setImageUrl(POSTER);
        series.setBackdropUrl(backdropUrl);
        series.setGenres(new HashSet<>(Set.of(genre)));
        return seriesRepository.save(series);
    }

    private Episode saveEpisode(Series series, String imageUrl) {
        Episode episode = new Episode();
        episode.setSeries(series);
        episode.setSeasonNumber(1);
        episode.setEpisodeNumber(1);
        episode.setTitle("Episodio 1x1");
        episode.setDuration(45);
        episode.setVideoUrl(VIDEO);
        episode.setImageUrl(imageUrl);
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
