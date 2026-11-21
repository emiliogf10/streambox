package com.emilio.streambox.controller;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests del filtro {@code withTitles} de {@code GET /api/genres}: los géneros
 * que ofrecen los filtros de {@code /peliculas} y {@code /series}.
 *
 * <p>
 * Qué protege: que un género solo aparece si tiene algún título
 * <strong>visible</strong> de ese tipo (una serie sin episodios no cuenta: si
 * contara, el filtro delataría que existe), que sin el parámetro la respuesta
 * no cambia para los clientes que ya la usan, y la lista blanca del
 * parámetro (400 en cualquier otro valor).
 * </p>
 *
 * <p>
 * Sin {@code @Transactional}, con datos confirmados y limpieza antes y después
 * de cada test, para que las consultas nativas lean lo mismo que leerían en
 * producción (filas ya escritas en las tablas de unión).
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class GenreWithTitlesIntegrationTest {

    private static final String GENRES = "/api/genres";
    private static final String WITH_TITLES_MESSAGE =
            "Filtro de títulos no permitido. Valores válidos: movies, series";

    @Autowired private MockMvc mockMvc;
    @Autowired private GenreRepository genreRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String userToken;
    private String adminToken;

    /** Solo una película. */
    private Genre aventura;

    /** Una película y una serie sin episodios: sale en películas, no en series. */
    private Genre comedia;

    /** Una película y una serie con episodios: sale en los dos. */
    private Genre drama;

    /** Solo una serie sin episodios: no sale en ninguno (igual que si no tuviera nada). */
    private Genre documental;

    /** Solo una serie con episodios. */
    private Genre western;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        userToken = bearer(saveUser("withtitlesuser", Role.USER));
        adminToken = bearer(saveUser("withtitlesadmin", Role.ADMIN));

        // Creados en un orden distinto del alfabético, para comprobar el orden.
        western = saveGenre("Western");
        saveGenre("Terror"); // sin ningún título
        documental = saveGenre("Documental");
        drama = saveGenre("Drama");
        comedia = saveGenre("Comedia");
        aventura = saveGenre("Aventura");

        saveMovie("Película de aventuras", aventura);
        saveMovie("Película de comedia", comedia);
        saveMovie("Película de drama", drama);
        saveSeries("Serie de comedia vacía", comedia);
        saveEpisode(saveSeries("Serie de drama", drama));
        saveSeries("Documental vacío", documental);
        saveEpisode(saveSeries("Serie del oeste", western));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Valores válidos
    // ------------------------------------------------------------------

    /** Géneros con alguna película, por nombre; solo id y nombre. */
    @Test
    void conMoviesDevuelveLosGenerosConAlgunaPeliculaPorNombre() throws Exception {
        getGenres(userToken, "movies")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].id").value(aventura.getId()))
                .andExpect(jsonPath("$[0].name").value("Aventura"))
                .andExpect(jsonPath("$[1].id").value(comedia.getId()))
                .andExpect(jsonPath("$[2].id").value(drama.getId()))
                .andExpect(jsonPath("$[0].movieCount").doesNotExist())
                .andExpect(jsonPath("$[0].seriesCount").doesNotExist());
    }

    /**
     * Géneros con alguna serie <strong>con episodios</strong>: Comedia
     * (cuya serie está vacía) y Documental (solo una serie vacía) no salen.
     */
    @Test
    void conSeriesSoloCuentanLasSeriesConEpisodios() throws Exception {
        getGenres(userToken, "series")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(drama.getId()))
                .andExpect(jsonPath("$[0].name").value("Drama"))
                .andExpect(jsonPath("$[1].id").value(western.getId()))
                .andExpect(jsonPath("$[1].name").value("Western"));
    }

    /**
     * La respuesta no depende del rol: un ADMIN tampoco ve el género de una
     * serie vacía (un endpoint, un contrato; la vista con todo es
     * {@code /api/admin/genres}).
     */
    @Test
    void unAdminRecibeLaMismaLista() throws Exception {
        getGenres(adminToken, "series")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].name", containsInAnyOrder("Drama", "Western")));
    }

    /** En cuanto la serie vacía recibe un episodio, su género aparece. */
    @Test
    void unGeneroApareceCuandoSuSerieRecibeUnEpisodio() throws Exception {
        Series empty = seriesRepository.findAll().stream()
                .filter(s -> s.getTitle().equals("Documental vacío")).findFirst().orElseThrow();
        saveEpisode(empty);

        getGenres(userToken, "series")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", containsInAnyOrder("Documental", "Drama", "Western")));
    }

    /**
     * Crear series vacías no cambia ni un byte de la respuesta a un usuario:
     * ni en un género sin títulos (Terror) ni en uno que ya tenía series
     * visibles (Drama) ni en uno que solo tenía películas (Aventura). Si el
     * cuerpo cambiara, quien consultara el filtro antes y después sabría que
     * el panel ha creado una serie oculta con ese género.
     */
    @Test
    void crearSeriesVaciasNoCambiaLaRespuesta() throws Exception {
        String seriesBefore = body(getGenres(userToken, "series"));
        String moviesBefore = body(getGenres(userToken, "movies"));

        Genre terror = genreRepository.findAll().stream()
                .filter(g -> g.getName().equals("Terror")).findFirst().orElseThrow();
        saveSeries("Serie vacía de terror", terror);
        saveSeries("Otra serie vacía de drama", drama);
        saveSeries("Serie vacía de aventuras", aventura);

        assertEquals(seriesBefore, body(getGenres(userToken, "series")));
        assertEquals(moviesBefore, body(getGenres(userToken, "movies")));
    }

    /**
     * Si las series pierden todos sus episodios, la respuesta es idéntica a la
     * de un catálogo sin ninguna serie: una serie vaciada vuelve a ser
     * indistinguible de una inexistente.
     */
    @Test
    void sinEpisodiosLaRespuestaEsLaDeUnCatalogoSinSeries() throws Exception {
        episodeRepository.deleteAll();
        String withEmptySeries = body(getGenres(userToken, "series"));

        seriesRepository.deleteAll();
        String withoutSeries = body(getGenres(userToken, "series"));

        assertEquals("[]", withoutSeries);
        assertEquals(withoutSeries, withEmptySeries);
    }

    /** Sin el parámetro, todos los géneros, como siempre (también los que no tienen títulos). */
    @Test
    void sinElParametroDevuelveTodosLosGeneros() throws Exception {
        mockMvc.perform(get(GENRES).header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].name", containsInAnyOrder(
                        "Aventura", "Comedia", "Documental", "Drama", "Terror", "Western")));
    }

    /**
     * Las dos listas juntas son exactamente las opciones de «Tus gustos»
     * ({@code /api/users/me/genres/options}): las tres usan la misma
     * definición de «título visible».
     */
    @Test
    void lasDosListasJuntasSonLasOpcionesDeTusGustos() throws Exception {
        TreeSet<Long> union = new TreeSet<>(ids(getGenres(userToken, "movies")));
        union.addAll(ids(getGenres(userToken, "series")));

        List<Long> options = ids(mockMvc.perform(get("/api/users/me/genres/options")
                .header("Authorization", userToken)));

        assertEquals(new ArrayList<>(union), new ArrayList<>(new TreeSet<>(options)));
        assertEquals(4, options.size());
    }

    @Test
    void sinTokenDa401() throws Exception {
        mockMvc.perform(get(GENRES).param("withTitles", "movies"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Lista blanca
    // ------------------------------------------------------------------

    /**
     * Cualquier valor fuera de la lista blanca exacta es un 400 con el motivo
     * en {@code withTitles}: también el vacío, las mayúsculas, el nombre en
     * español o los dos a la vez.
     */
    @ParameterizedTest
    @ValueSource(strings = { "", " ", "MOVIES", "Series", "peliculas", "movies,series", "all", "movies " })
    void unValorFueraDeLaListaBlancaDa400(String value) throws Exception {
        getGenres(userToken, value)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.withTitles").value(WITH_TITLES_MESSAGE));
    }

    /** Repetir el parámetro tampoco vale (Spring une los valores con una coma). */
    @Test
    void elParametroRepetidoDa400() throws Exception {
        mockMvc.perform(get(GENRES).param("withTitles", "movies", "series").header("Authorization", userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.withTitles").value(WITH_TITLES_MESSAGE));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private ResultActions getGenres(String token, String withTitles) throws Exception {
        return mockMvc.perform(get(GENRES).param("withTitles", withTitles).header("Authorization", token));
    }

    /** Cuerpo de una respuesta 200, tal cual llega al cliente. */
    private static String body(ResultActions result) throws Exception {
        return result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private List<Long> ids(ResultActions result) throws Exception {
        JsonNode body = objectMapper.readTree(result.andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        List<Long> ids = new ArrayList<>();
        body.forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
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

    private String bearer(User user) {
        return "Bearer " + jwtService.generateToken(user);
    }

    private Genre saveGenre(String name) {
        Genre genre = new Genre();
        genre.setName(name);
        return genreRepository.save(genre);
    }

    private void saveMovie(String title, Genre... genres) {
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setDescription("Descripción de " + title);
        movie.setDuration(100);
        movie.setReleaseYear(2000);
        movie.setImageUrl("https://example.com/image.jpg");
        movie.setVideoUrl("https://example.com/video.mp4");
        movie.setGenres(new HashSet<>(List.of(genres)));
        movieRepository.save(movie);
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

    /** Vacía las tablas respetando las claves foráneas (los episodios caen con su serie). */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
