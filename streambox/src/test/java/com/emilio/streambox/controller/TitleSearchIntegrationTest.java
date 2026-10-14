package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.emilio.streambox.entity.Episode;
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
import com.emilio.streambox.specification.MovieSpecification;
import com.emilio.streambox.specification.SeriesSpecification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Búsqueda por título sin acentos (migración V5) en todos los endpoints que la
 * usan, con <b>H2</b>: {@code /api/movies/search}, {@code /api/series/search} y
 * {@code /api/admin/series}.
 *
 * <p>
 * Lo que depende de PostgreSQL ({@code unaccent} real, el índice GIN, la
 * tolerancia a erratas con {@code pg_trgm}) está en
 * {@code postgres/PostgresTitleSearchIntegrationTest}. En H2 las funciones de
 * la V5 son Java ({@code h2/H2SearchFunctions}), pero la consulta es la misma:
 * estos tests prueban la parte común y que el SQL de la búsqueda tolerante es
 * válido también en H2 (el E2E y el navegador de prueba lo ejecutan).
 * </p>
 *
 * <p>
 * Sin {@code @Transactional}: datos confirmados y limpieza antes y después.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TitleSearchIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private MovieRepository movieRepository;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String userToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        userToken = "Bearer " + jwtService.generateToken(saveUser("titlesearchuser", Role.USER));
        adminToken = "Bearer " + jwtService.generateToken(saveUser("titlesearchadmin", Role.ADMIN));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Sin acentos, en los dos sentidos
    // ------------------------------------------------------------------

    /**
     * Los acentos no cuentan ni en el título ni en lo que se escribe: cada
     * búsqueda encuentra su título y solo ese. Antes de la V5 fallaban todas
     * (incluida {@code istanbul}: {@code lower('İ')} de H2/Java es {@code i}
     * más un punto combinante, que ahora se quita como cualquier acento).
     */
    @ParameterizedTest(name = "\"{0}\" encuentra \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "manana     | Mañana",
            "pokemon    | Pokémon: la película",
            "POKEMON    | Pokémon: la película",
            "elite      | ÉLITE",
            "camion     | El camión",
            "pinguino   | Pingüino",
            "francois   | François",
            "strasse    | Straße",
            "aeon       | Æon",
            "istanbul   | İstanbul" })
    void laBusquedaDePeliculasIgnoraLosAcentos(String text, String expected) throws Exception {
        for (String title : List.of("Mañana", "Pokémon: la película", "ÉLITE", "El camión",
                "Pingüino", "François", "Straße", "Æon", "İstanbul", "Otra cosa")) {
            saveMovie(title);
        }

        assertEquals(List.of(expected), titles(call("/api/movies/search", userToken, "title", text)));
    }

    /** {@code élite} con tilde encuentra los dos títulos, con y sin ella. */
    @Test
    void unaBusquedaConAcentoEncuentraTambienElTituloSinAcento() throws Exception {
        saveMovie("ÉLITE");
        saveMovie("Elite de barrio");

        assertEquals(List.of("Elite de barrio", "ÉLITE"),
                titles(call("/api/movies/search", userToken, "title", "élite", "sort", "id", "direction", "desc")));
    }

    /**
     * Las series se buscan igual que las películas (código compartido), y la
     * búsqueda pública sigue sin ver las series vacías.
     */
    @Test
    void laBusquedaPublicaDeSeriesIgnoraLosAcentosYSigueOcultandoLasVacias() throws Exception {
        saveEpisode(saveSeries("Pokémon: la serie"));
        saveSeries("Pokémon vacía");
        saveEpisode(saveSeries("Otra"));

        assertEquals(List.of("Pokémon: la serie"), titles(call("/api/series/search", userToken, "title", "pokemon")));
    }

    /** El panel ve también las series vacías y busca sin acentos. */
    @Test
    void laBusquedaDelPanelDeSeriesIgnoraLosAcentos() throws Exception {
        saveSeries("Élite");
        saveEpisode(saveSeries("Elite: historias breves"));
        saveSeries("Otra");

        assertEquals(List.of("Elite: historias breves", "Élite"),
                titles(call("/api/admin/series", adminToken, "title", "ELITE")));
    }

    // ------------------------------------------------------------------
    // Comodines: primero normalizar, después escapar
    // ------------------------------------------------------------------

    /**
     * {@code unaccent} (y NFKD en H2) convierte {@code ％ ＿ ＼} de ancho
     * completo en {@code % _ \}. Si los comodines se escapasen antes de
     * normalizar (en Java), {@code ＿} llegaría al {@code LIKE} como el comodín
     * {@code _} y encontraría <b>todos</b> los títulos; {@code ％}, también. Con
     * el escape hecho en la base de datos después de normalizar, se buscan como
     * texto: encuentran el título con el carácter, en su forma normal o de
     * ancho completo.
     */
    @ParameterizedTest(name = "\"{0}\" es texto literal")
    @CsvSource(delimiter = '|', value = {
            "＿ | a_b",
            "％ | 100%",
            "＼ | C:\\pelis" })
    void losComodinesDeAnchoCompletoSeBuscanComoTextoLiteral(String text, String expected) throws Exception {
        saveMovie("a_b");
        saveMovie("100%");
        saveMovie("C:\\pelis");
        saveMovie("Normal");

        assertEquals(List.of(expected), titles(call("/api/movies/search", userToken, "title", text)));
    }

    /** Y en series, por el mismo código: el comodín de ancho completo no lo encuentra todo. */
    @Test
    void enSeriesElComodinDeAnchoCompletoTampocoLoEncuentraTodo() throws Exception {
        saveSeries("a_b");
        saveSeries("Normal");

        assertEquals(List.of("a_b"), titles(call("/api/admin/series", adminToken, "title", "＿")));
    }

    // ------------------------------------------------------------------
    // Búsqueda tolerante a erratas: en H2 no encuentra nada, pero es SQL válido
    // ------------------------------------------------------------------

    /**
     * {@code hasSimilarTitle} solo encuentra algo en PostgreSQL; en H2 la
     * función devuelve 0. Lo que se protege aquí es que su SQL (con la función
     * en el {@code WHERE} y en el {@code ORDER BY}, con página ordenada o sin
     * ordenar) funcione también en H2: el E2E y el navegador de prueba usan H2
     * y un error de SQL ahí rompería la búsqueda entera.
     */
    @Test
    void laBusquedaToleranteEsSqlValidoEnH2YNoEncuentraNada() {
        saveMovie("Interstellar");
        saveEpisode(saveSeries("Interstellar: la serie"));

        for (PageRequest page : List.of(PageRequest.of(0, 10), PageRequest.of(0, 10, Sort.by("title", "id")))) {
            assertEquals(0, movieRepository.findAll(MovieSpecification.hasSimilarTitle("interstelar"), page)
                    .getTotalElements());
            assertEquals(0, seriesRepository.findAll(SeriesSpecification.hasSimilarTitle("interstelar")
                    .and(SeriesSpecification.hasEpisodes()), page).getTotalElements());
            assertEquals(0, movieRepository.findAll(MovieSpecification.hasSimilarTitle("in"), page)
                    .getTotalElements());
        }
    }

    // ------------------------------------------------------------------
    // Campo approximate (segundo intento de service.TypoTolerantTitleSearch)
    // ------------------------------------------------------------------

    /**
     * En H2 no hay tolerancia a erratas: una búsqueda larga sin coincidencias
     * hace el segundo intento, que no encuentra nada, y la respuesta es "sin
     * resultados" con {@code approximate = false} en los tres endpoints de
     * búsqueda. Comprueba también que el segundo intento (función en el
     * {@code WHERE} y en el {@code ORDER BY}) no rompe la petición en H2, que
     * es lo que usan el E2E y el navegador de prueba.
     */
    @Test
    void enH2UnaErrataNoEncuentraNadaYLaRespuestaNoEsAproximada() throws Exception {
        saveMovie("Interstellar");
        saveEpisode(saveSeries("Interstellar: la serie"));

        for (JsonNode page : List.of(
                call("/api/movies/search", userToken, "title", "interstelar"),
                call("/api/series/search", userToken, "title", "interstelar"),
                call("/api/admin/series", adminToken, "title", "interstelar"))) {
            assertEquals(List.of(), titles(page));
            assertEquals(0, page.get("totalElements").asLong());
            assertFalse(page.get("approximate").asBoolean());
        }
    }

    /**
     * El campo existe (booleano) en todas las respuestas paginadas del
     * catálogo, también en las que no buscan por título: el frontend comparte
     * el tipo de página y no debe encontrarse el campo ausente.
     */
    @Test
    void elCampoApproximateExisteEnTodasLasPaginasDelCatalogoYEsFalseConCoincidencias() throws Exception {
        saveMovie("Interstellar");
        saveEpisode(saveSeries("Interstellar: la serie"));

        for (JsonNode page : List.of(
                call("/api/movies", userToken),
                call("/api/series", userToken),
                call("/api/movies/search", userToken),
                call("/api/movies/search", userToken, "title", "interstellar"),
                call("/api/series/search", userToken, "title", "interstellar"),
                call("/api/admin/series", adminToken),
                call("/api/admin/series", adminToken, "title", "interstellar"))) {
            assertTrue(page.get("approximate").isBoolean(), page.toString());
            assertFalse(page.get("approximate").asBoolean());
            assertEquals(1, page.get("totalElements").asLong());
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** GET autenticado con pares nombre/valor, sin codificar (como {@code CatalogEdgeCasesIntegrationTest}). */
    private JsonNode call(String endpoint, String token, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(endpoint).header("Authorization", token).param("size", "100");
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
        }
        String json = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        return objectMapper.readTree(json);
    }

    private static List<String> titles(JsonNode page) {
        List<String> titles = new ArrayList<>();
        page.get("content").forEach(item -> titles.add(item.get("title").asText()));
        return titles;
    }

    private User saveUser(String name, Role role) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(role);
        return userRepository.save(user);
    }

    private Movie saveMovie(String title) {
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setDescription("Descripción de " + title);
        movie.setDuration(100);
        movie.setReleaseYear(2000);
        movie.setImageUrl("https://example.com/image.jpg");
        movie.setVideoUrl("https://example.com/video.mp4");
        movie.setGenres(new HashSet<>());
        return movieRepository.save(movie);
    }

    private Series saveSeries(String title) {
        Series series = new Series();
        series.setTitle(title);
        series.setDescription("Sinopsis de " + title);
        series.setReleaseYear(2010);
        series.setImageUrl("https://example.com/serie.jpg");
        series.setGenres(new HashSet<>());
        return seriesRepository.save(series);
    }

    private void saveEpisode(Series series) {
        Episode episode = new Episode();
        episode.setSeries(series);
        episode.setSeasonNumber(1);
        episode.setEpisodeNumber(1);
        episode.setTitle("Piloto");
        episode.setDuration(45);
        episode.setVideoUrl("https://example.com/episodio.mp4");
        episodeRepository.save(episode);
    }

    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
