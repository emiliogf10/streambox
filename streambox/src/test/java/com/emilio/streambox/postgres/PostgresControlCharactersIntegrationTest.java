package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * El NUL ({@code U+0000}) en los textos del catálogo y en {@code by-title},
 * contra <b>PostgreSQL real</b>.
 *
 * <p>
 * <b>El fallo.</b> PostgreSQL no admite el NUL en un texto: el {@code INSERT}
 * falla con {@code SQLSTATE 22021} y Spring lo convierte en una
 * {@link DataIntegrityViolationException}. Sin validación, esa excepción
 * llegaba a {@code GlobalExceptionHandler}, que respondía un 409 «conflicto con
 * datos existentes» (falso); desde que allí responde un 500, sería un error del
 * servidor. H2 sí guarda el NUL, así que solo aquí se ve lo que hace el motor.
 * </p>
 *
 * <p>
 * El primer test demuestra la causa (lo que llegaba al manejador); el resto,
 * que con el arreglo la API responde 400 {@code VALIDATION_ERROR} con el campo
 * y la petición no llega a la base de datos. Sin el arreglo, esos tests
 * reciben el 409 (o el 500) en lugar del 400. También se comprueba que los
 * saltos de línea y el tabulador que se siguen admitiendo en las sinopsis los
 * guarda PostgreSQL tal cual.
 * </p>
 */
class PostgresControlCharactersIntegrationTest extends PostgresIntegrationTestSupport {

    private static final String TITLE_MESSAGE = "El título no puede contener caracteres de control";

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String adminToken;
    private String userToken;
    private Genre genre;
    private Movie movie;
    private Series series;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        adminToken = tokenFor(saveUser("pgcontrol", Role.ADMIN));
        User user = saveUser("pgcontroluser", Role.USER);
        userToken = tokenFor(user);
        genre = saveGenre("Drama");
        movie = saveMovie("Película existente", 2020, genre);
        series = saveSeries("Serie existente", 2020, genre);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    /**
     * La causa: PostgreSQL rechaza el NUL con un error de datos ({@code 22021})
     * que Spring entrega como {@link DataIntegrityViolationException}, la
     * excepción que antes acababa en el 409 falso.
     */
    @Test
    void postgresqlRechazaElNulConUnaViolacionDeIntegridad() {
        Movie withNul = new Movie();
        withNul.setTitle("Dune\u0000");
        withNul.setDescription("Sinopsis");
        withNul.setDuration(100);
        withNul.setReleaseYear(2021);
        withNul.setImageUrl("https://example.com/image.jpg");
        withNul.setGenres(new HashSet<>(List.of(genre)));

        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> movieRepository.saveAndFlush(withNul));

        assertEquals("22021", sqlState(error));
    }

    static Stream<Arguments> textsWithNul() {
        return Stream.of(
                Arguments.of("película: título", "movie", "title", "Dune\u0000", TITLE_MESSAGE),
                Arguments.of("película: sinopsis", "movie", "description", "Sinopsis\u0000",
                        "La sinopsis contiene caracteres de control no permitidos"),
                Arguments.of("serie: título", "series", "title", "Fundación\u0000", TITLE_MESSAGE),
                Arguments.of("serie: descripción", "series", "description", "Sinopsis\u0000",
                        "La descripción contiene caracteres de control no permitidos"),
                Arguments.of("episodio: título", "episode", "title", "Piloto\u0000", TITLE_MESSAGE),
                Arguments.of("episodio: descripción", "episode", "description", "Sinopsis\u0000",
                        "La descripción contiene caracteres de control no permitidos"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("textsWithNul")
    void unNulEnElCatalogoDa400ConElCampoYNoLlegaALaBase(
            String name, String kind, String field, String value, String message) throws Exception {
        long movies = movieRepository.count();
        long seriesCount = seriesRepository.count();
        long episodes = episodeRepository.count();
        Map<String, Object> body = switch (kind) {
            case "movie" -> movieBody();
            case "series" -> seriesBody();
            default -> episodeBody();
        };
        body.put(field, value);
        String path = switch (kind) {
            case "movie" -> "/api/movies";
            case "series" -> "/api/series";
            default -> "/api/series/" + series.getId() + "/episodes";
        };

        send(post(path), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors." + field).value(message));

        assertEquals(movies, movieRepository.count());
        assertEquals(seriesCount, seriesRepository.count());
        assertEquals(episodes, episodeRepository.count());
    }

    @Test
    void unNulAlEditarUnaPeliculaDa400YNoLaCambia() throws Exception {
        Map<String, Object> body = movieBody();
        body.put("title", "Dune\u0000");

        send(put("/api/movies/" + movie.getId()), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.title").value(TITLE_MESSAGE));

        assertEquals("Película existente", movieRepository.findById(movie.getId()).orElseThrow().getTitle());
    }

    /**
     * En {@code by-title} el NUL llegaba a la consulta
     * {@code findAllByTitleIgnoreCase}, que PostgreSQL rechaza con el mismo
     * {@code 22021}.
     */
    @Test
    void unNulEnByTitleDa400AlAnadirYAlQuitar() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(
                post("/api/users/me/favorites/by-title"), delete("/api/users/me/favorites/by-title"))) {
            mockMvc.perform(request.param("title", "Película existente\u0000").header("Authorization", userToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.validationErrors.title").value(TITLE_MESSAGE));
        }
    }

    /** Lo que sí se admite en una sinopsis, PostgreSQL lo guarda sin cambios. */
    @Test
    void losSaltosDeLineaYElTabuladorDeUnaSinopsisSeGuardanTalCual() throws Exception {
        String description = "Primera línea\r\nsegunda\ty tercera\nlínea";
        Map<String, Object> body = movieBody();
        body.put("description", description);

        String json = send(post("/api/movies"), body)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long id = objectMapper.readTree(json).get("id").asLong();
        assertEquals(description, movieRepository.findById(id).orElseThrow().getDescription());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private ResultActions send(
            MockHttpServletRequestBuilder builder, Map<String, Object> body) throws Exception {
        return mockMvc.perform(builder.header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> movieBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Duna");
        body.put("description", "Sinopsis de Duna");
        body.put("duration", 155);
        body.put("releaseYear", 2021);
        body.put("imageUrl", "https://cdn.example.com/duna.jpg");
        body.put("genreIds", List.of(genre.getId()));
        return body;
    }

    private Map<String, Object> seriesBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Fundación");
        body.put("description", "Sinopsis de Fundación");
        body.put("releaseYear", 2021);
        body.put("imageUrl", "https://cdn.example.com/fundacion.jpg");
        body.put("genreIds", List.of(genre.getId()));
        return body;
    }

    private static Map<String, Object> episodeBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("seasonNumber", 1);
        body.put("episodeNumber", 1);
        body.put("title", "Piloto");
        body.put("description", "Sinopsis del piloto");
        body.put("duration", 50);
        return body;
    }
}
