package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

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
import org.springframework.transaction.annotation.Transactional;

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

import tools.jackson.databind.json.JsonMapper;

/**
 * Caracteres de control en los títulos y las sinopsis de películas, series y
 * episodios, y en el {@code title} de {@code /api/users/me/favorites/by-title}.
 *
 * <p>
 * <b>El fallo que cubre.</b> Nada impedía un {@code U+0000} en esos textos.
 * PostgreSQL lo rechaza ({@code SQLSTATE 22021}) y el cliente recibía un 409
 * «conflicto con datos existentes», que es falso; H2 en cambio lo guarda, así
 * que aquí, sin el arreglo, estos tests reciben un 201 (o un 204/404 en
 * {@code by-title}) en lugar del 400. Lo que hace PostgreSQL lo comprueba
 * {@code PostgresControlCharactersIntegrationTest}.
 * </p>
 *
 * <p>
 * <b>La regla.</b> Los títulos (campo de una línea) no admiten ningún carácter
 * de control (categoría {@code Cc}); las sinopsis admiten el tabulador y los
 * saltos de línea, porque se escriben en un {@code <textarea>}, y rechazan el
 * resto. Los mensajes se escriben literalmente porque son el contrato con el
 * frontend.
 * </p>
 *
 * <p>
 * Usa {@code @Transactional} (rollback al terminar): nada depende de un commit
 * real.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class ControlCharactersValidationIntegrationTest {

    private static final String TITLE_MESSAGE = "El título no puede contener caracteres de control";
    private static final String MOVIE_DESCRIPTION_MESSAGE = "La sinopsis contiene caracteres de control no permitidos";
    private static final String DESCRIPTION_MESSAGE = "La descripción contiene caracteres de control no permitidos";

    @Autowired private MockMvc mockMvc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private String adminToken;
    private String userToken;
    private Genre genre;
    private Movie movie;
    private Series series;
    private Episode episode;

    @BeforeEach
    void setUp() {
        adminToken = tokenFor(saveUser("ctrladmin", Role.ADMIN));
        userToken = tokenFor(saveUser("ctrluser", Role.USER));

        Genre drama = new Genre();
        drama.setName("Drama controles");
        genre = genreRepository.save(drama);

        Movie existing = new Movie();
        existing.setTitle("Película de controles");
        existing.setDescription("Sinopsis");
        existing.setDuration(100);
        existing.setReleaseYear(2020);
        existing.setImageUrl("https://cdn.example.com/p.jpg");
        existing.setGenres(new HashSet<>(List.of(genre)));
        movie = movieRepository.save(existing);

        Series existingSeries = new Series();
        existingSeries.setTitle("Serie de controles");
        existingSeries.setDescription("Sinopsis");
        existingSeries.setReleaseYear(2020);
        existingSeries.setImageUrl("https://cdn.example.com/s.jpg");
        existingSeries.setGenres(new HashSet<>(List.of(genre)));
        series = seriesRepository.save(existingSeries);

        Episode existingEpisode = new Episode();
        existingEpisode.setSeries(series);
        existingEpisode.setSeasonNumber(1);
        existingEpisode.setEpisodeNumber(1);
        existingEpisode.setTitle("Piloto");
        existingEpisode.setDuration(45);
        episode = episodeRepository.save(existingEpisode);
    }

    // ------------------------------------------------------------------
    // Títulos: ningún carácter de control
    // ------------------------------------------------------------------

    /**
     * Cada carácter de control en el título de una película da 400 con el
     * campo {@code title} y no guarda nada. Además del NUL (el caso real) se
     * prueban el tabulador y los saltos de línea (de control, aunque PostgreSQL
     * los admita), el {@code ESC}, el {@code DEL} y dos del bloque
     * {@code U+0080}–{@code U+009F} ({@code NEL} y el último).
     */
    @ParameterizedTest
    @ValueSource(strings = { "\u0000", "\t", "\n", "\r", "\u001B", "\u007F", "\u0085", "\u009F" })
    void unTituloDePeliculaConUnCaracterDeControlDa400(String control) throws Exception {
        long before = movieRepository.count();

        send(post("/api/movies"), adminToken, movieBody("Dune" + control + "Parte dos", "Sinopsis"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.title").value(TITLE_MESSAGE));

        assertEquals(before, movieRepository.count());
    }

    @Test
    void elTituloSeValidaTambienAlEditarLaPelicula() throws Exception {
        send(put("/api/movies/" + movie.getId()), adminToken, movieBody("Dune\u0000", "Sinopsis"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.title").value(TITLE_MESSAGE));
    }

    @Test
    void unTituloDeSerieConNulDa400() throws Exception {
        send(post("/api/series"), adminToken, seriesBody("Fundación\u0000", "Sinopsis"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.title").value(TITLE_MESSAGE));
        send(put("/api/series/" + series.getId()), adminToken, seriesBody("Fundación\u0000", "Sinopsis"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.title").value(TITLE_MESSAGE));
    }

    @Test
    void unTituloDeEpisodioConNulDa400() throws Exception {
        send(post(episodesPath()), adminToken, episodeBody(2, "Piloto\u0000", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.title").value(TITLE_MESSAGE));
        send(put(episodesPath() + "/" + episode.getId()), adminToken, episodeBody(1, "Piloto\u0000", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.title").value(TITLE_MESSAGE));
    }

    /**
     * Los caracteres de formato invisibles ({@code Cf}, como {@code U+200B}) no
     * son de control: siguen permitidos, igual que en la búsqueda.
     */
    @Test
    void unCaracterDeFormatoInvisibleNoEsDeControlYSeAcepta() throws Exception {
        send(post("/api/movies"), adminToken, movieBody("Dune​Parte dos", "Sinopsis"))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------
    // Sinopsis: tabulador y saltos de línea sí; el resto de controles, no
    // ------------------------------------------------------------------

    /** El NUL, el tabulador vertical, el salto de página, el ESC, el DEL y el NEL. */
    @ParameterizedTest
    @ValueSource(strings = { "\u0000", "\u000B", "\u000C", "\u001B", "\u007F", "\u0085" })
    void unaSinopsisConUnControlQueNoEsSaltoNiTabuladorDa400(String control) throws Exception {
        String description = "Primera línea" + control + "segunda";

        send(post("/api/movies"), adminToken, movieBody("Dune", description))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.description").value(MOVIE_DESCRIPTION_MESSAGE));
        send(post("/api/series"), adminToken, seriesBody("Fundación", description))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.description").value(DESCRIPTION_MESSAGE));
        send(post(episodesPath()), adminToken, episodeBody(2, "Segundo", description))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.description").value(DESCRIPTION_MESSAGE));
    }

    /**
     * Una sinopsis escrita en el {@code <textarea>} del panel puede tener
     * saltos de línea ({@code \n}, o {@code \r\n} si se pega desde otro
     * programa) y tabuladores: se aceptan y se guardan tal cual.
     */
    @ParameterizedTest
    @ValueSource(strings = { "Primera línea\nsegunda", "Primera línea\r\nsegunda", "Columna\tcolumna" })
    void unaSinopsisConSaltosDeLineaOTabuladoresSeAceptaYSeGuardaTalCual(String description) throws Exception {
        send(post("/api/movies"), adminToken, movieBody("Dune", description))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value(description));
        send(post("/api/series"), adminToken, seriesBody("Fundación", description))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value(description));
        send(put(episodesPath() + "/" + episode.getId()), adminToken, episodeBody(1, "Piloto", description))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(description));
    }

    // ------------------------------------------------------------------
    // /api/users/me/favorites/by-title
    // ------------------------------------------------------------------

    /**
     * Antes el parámetro no tenía ninguna validación (la clase no llevaba
     * {@code @Validated}): en H2 respondía 404 («no existe ninguna película con
     * ese título») y en PostgreSQL, el 409 falso.
     */
    @ParameterizedTest
    @ValueSource(strings = { "\u0000", "Película de controles\u0000", "Película\tde controles" })
    void elTituloDeByTitleConCaracteresDeControlDa400AlAnadirYAlQuitar(String title) throws Exception {
        mockMvc.perform(post("/api/users/me/favorites/by-title").param("title", title)
                        .header("Authorization", userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.title").value(TITLE_MESSAGE));
        mockMvc.perform(delete("/api/users/me/favorites/by-title").param("title", title)
                        .header("Authorization", userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.title").value(TITLE_MESSAGE));
    }

    /** La validación no estorba al caso normal. */
    @Test
    void byTitleSigueFuncionandoConUnTituloNormal() throws Exception {
        mockMvc.perform(post("/api/users/me/favorites/by-title").param("title", "película de controles")
                        .header("Authorization", userToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/users/me/favorites/by-title").param("title", "Película de controles")
                        .header("Authorization", userToken))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private ResultActions send(MockHttpServletRequestBuilder builder, String token, Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(builder.header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)));
    }

    private String episodesPath() {
        return "/api/series/" + series.getId() + "/episodes";
    }

    private Map<String, Object> movieBody(String title, String description) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("description", description);
        body.put("duration", 155);
        body.put("releaseYear", 2021);
        body.put("imageUrl", "https://cdn.example.com/duna.jpg");
        body.put("genreIds", List.of(genre.getId()));
        return body;
    }

    private Map<String, Object> seriesBody(String title, String description) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("description", description);
        body.put("releaseYear", 2021);
        body.put("imageUrl", "https://cdn.example.com/fundacion.jpg");
        body.put("genreIds", List.of(genre.getId()));
        return body;
    }

    private static Map<String, Object> episodeBody(int number, String title, String description) {
        Map<String, Object> body = new HashMap<>();
        body.put("seasonNumber", 1);
        body.put("episodeNumber", number);
        body.put("title", title);
        body.put("description", description);
        body.put("duration", 50);
        return body;
    }

    private User saveUser(String name, Role role) {
        User user = new User();
        user.setEmailVerifiedAt(Instant.now()); // cuenta verificada (V9)
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(role);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user);
    }

    private String tokenFor(User user) {
        return "Bearer " + jwtService.generateToken(user);
    }
}
