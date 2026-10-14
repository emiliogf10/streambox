package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Revisión de QA de la búsqueda por título sin acentos (ADR 0013) con
 * <b>H2</b>: textos que <em>desaparecen</em> al normalizarlos.
 *
 * <p>
 * El servicio solo omite el filtro de título si el texto está en blanco
 * ({@code isBlank()}), pero la normalización ({@code streambox_unaccent}) se
 * hace después, en la base de datos, y quita las marcas diacríticas. Un texto
 * formado solo por marcas combinantes (p. ej. {@code U+0301}, el acento agudo
 * suelto) no está en blanco, pero su clave de búsqueda es la cadena vacía, y
 * {@code LIKE '%' || '' || '%'} es {@code LIKE '%%'}: encuentra <b>todos</b>
 * los títulos. Antes de la V5 ({@code LikePatterns}) no encontraba ninguno.
 * La misma comprobación contra PostgreSQL real está en
 * {@code postgres/PostgresTitleSearchQaIntegrationTest}.
 * </p>
 *
 * <p>
 * Sin {@code @Transactional}: datos confirmados y limpieza antes y después.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TitleSearchQaIntegrationTest {

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
        userToken = "Bearer " + jwtService.generateToken(saveUser("qatitleuser", Role.USER));
        adminToken = "Bearer " + jwtService.generateToken(saveUser("qatitleadmin", Role.ADMIN));
        saveMovie("Mañana");
        saveMovie("Alien");
        saveEpisode(saveSeries("Pokémon"));
        saveSeries("Oculta sin episodios");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    /**
     * Textos que no están en blanco pero cuya clave de búsqueda es vacía (solo
     * marcas combinantes, con o sin espacios). Deben comportarse como un texto
     * que ningún título contiene: cero resultados, nunca el catálogo entero.
     */
    static Stream<Arguments> textsThatVanish() {
        List<Arguments> arguments = new ArrayList<>();
        for (String endpoint : List.of("/api/movies/search", "/api/series/search", "/api/admin/series")) {
            for (String text : List.of("\u0301", "\u0301\u0308\u0303", " \u0301 ", "\u0303\u0303\u0303\u0303\u0303\u0303")) {
                arguments.add(Arguments.of(endpoint, text));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "[{index}] {0} con solo marcas combinantes")
    @MethodSource("textsThatVanish")
    void unTextoDeSoloMarcasCombinantesNoDevuelveTodoElCatalogo(String endpoint, String text) throws Exception {
        JsonNode page = search(endpoint, text);

        assertEquals(List.of(), titles(page), "«" + text + "» (" + text.codePoints().count()
                + " marcas) no está contenido en ningún título");
        assertFalse(page.get("approximate").asBoolean());
    }

    /**
     * Contraste: una letra con su marca combinante <em>sí</em> es una búsqueda
     * válida. "man" + U+0303 + "ana" (la ñ descompuesta, como la escriben
     * algunos teclados y la pega macOS) encuentra "Mañana", y solo ese.
     */
    @ParameterizedTest
    @ValueSource(strings = { "man\u0303ana", "MAN\u0303ANA", "an\u0303a" })
    void unaLetraConSuMarcaCombinanteSiEsUnaBusquedaValida(String text) throws Exception {
        assertEquals(List.of("Mañana"), titles(search("/api/movies/search", text)));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private JsonNode search(String endpoint, String text) throws Exception {
        String token = endpoint.startsWith("/api/admin") ? adminToken : userToken;
        MockHttpServletResponse response = mockMvc.perform(get(endpoint).header("Authorization", token)
                .param("title", text).param("size", "100")).andReturn().getResponse();
        assertEquals(200, response.getStatus(), response.getContentAsString());
        return objectMapper.readTree(response.getContentAsString());
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

    private Series saveEpisode(Series series) {
        Episode episode = new Episode();
        episode.setSeries(series);
        episode.setSeasonNumber(1);
        episode.setEpisodeNumber(1);
        episode.setTitle("Piloto");
        episode.setDuration(45);
        episode.setVideoUrl("https://example.com/episodio.mp4");
        episodeRepository.save(episode);
        return series;
    }

    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
