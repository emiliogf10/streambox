package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

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
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Un número con decimales en un campo entero del JSON se rechaza con
 * {@code 400 MALFORMED_REQUEST} en lugar de truncarse en silencio (incidencia
 * QA-1).
 *
 * <p>
 * Por defecto Jackson tiene activado {@code ACCEPT_FLOAT_AS_INT}: convertía
 * {@code "duration": 100.5} en {@code 100}, respondía 201 y guardaba algo
 * distinto de lo que el administrador envió. Se desactiva con
 * {@code spring.jackson.deserialization.accept-float-as-int=false}
 * ({@code application.properties}). Sin esa línea fallan todos los casos de
 * decimales de esta clase (dan 201) y el del {@link JsonMapper}.
 * </p>
 *
 * <p>
 * Complementa a {@code SeriesQaEdgeCasesIntegrationTest}, que solo comprueba
 * el 400: aquí se fija además el código y el mensaje genérico (sin detalles
 * del parser) y que lo que ya funcionaba (enteros normales, parámetros de
 * consulta) sigue igual. Los cuerpos van como texto literal para controlar
 * exactamente cómo se escribe cada número ({@code 100} frente a {@code 100.0}).
 * </p>
 *
 * <p>
 * Usa {@code @Transactional} (rollback al terminar): no depende de
 * restricciones de la base de datos que necesiten un commit real.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class JsonIntegerCoercionIntegrationTest {

    private static final String MALFORMED_MESSAGE =
            "La petición no se puede interpretar. Revisa el formato de los datos";

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
    private Genre drama;

    @BeforeEach
    void setUp() {
        User admin = new User();
        admin.setUsername("floatintadmin");
        admin.setEmail("floatintadmin@test.com");
        admin.setPassword(passwordEncoder.encode("adminpass1"));
        admin.setRole(Role.ADMIN);
        admin.setCreatedAt(Instant.now());
        adminToken = "Bearer " + jwtService.generateToken(userRepository.save(admin));

        Genre genre = new Genre();
        genre.setName("Drama decimales");
        drama = genreRepository.save(genre);
    }

    /**
     * El {@link JsonMapper} de Jackson 3 que Spring Boot autoconfigura (el que
     * usan los controladores para leer los cuerpos) tiene la característica
     * desactivada. Si alguien sustituye el mapper o borra la propiedad, este
     * test señala la causa antes que los de la API.
     */
    @Test
    void elJsonMapperDeLaApiNoAceptaDecimalesEnEnteros() {
        assertFalse(jsonMapper.isEnabled(DeserializationFeature.ACCEPT_FLOAT_AS_INT));
    }

    // ------------------------------------------------------------------
    // Películas
    // ------------------------------------------------------------------

    /** {@code duration: 100.5} → 400 con el mensaje genérico y sin guardar nada. */
    @Test
    void unaDuracionDecimalEnUnaPeliculaDa400MalformedRequest() throws Exception {
        long before = movieRepository.count();

        String response = postJson("/api/movies", movieJson("100.5", "2000"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value(MALFORMED_MESSAGE))
                .andExpect(jsonPath("$.validationErrors").doesNotExist())
                .andExpect(jsonPath("$.path").value("/api/movies"))
                .andReturn().getResponse().getContentAsString();

        assertNoInternalDetails(response);
        assertEquals(before, movieRepository.count());
    }

    /**
     * {@code 100.0} también se rechaza aunque no tenga parte decimal: en JSON
     * es un número con decimales y Jackson no distingue su valor. El frontend
     * envía {@code 100} ({@code JSON.stringify} de un entero nunca escribe
     * {@code .0}), así que no le afecta.
     */
    @Test
    void unEnteroEscritoConPuntoCeroTambienSeRechaza() throws Exception {
        long before = movieRepository.count();

        postJson("/api/movies", movieJson("100", "2000.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        assertEquals(before, movieRepository.count());
    }

    /** Los enteros normales siguen funcionando y se guardan tal cual. */
    @Test
    void unaPeliculaConEnterosNormalesSeCrea() throws Exception {
        postJson("/api/movies", movieJson("100", "2000"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.duration").value(100))
                .andExpect(jsonPath("$.releaseYear").value(2000));
    }

    /** Los ids de género ({@code Long}) tampoco aceptan decimales: {@code 1.5} no es el género 1. */
    @Test
    void unIdDeGeneroDecimalSeRechaza() throws Exception {
        String body = """
                {"title":"Genero decimal","description":"d","duration":100,"releaseYear":2000,
                 "imageUrl":"https://example.com/i.jpg","videoUrl":"https://example.com/v.mp4",
                 "genreIds":[%s.5]}
                """.formatted(drama.getId());

        postJson("/api/movies", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value(MALFORMED_MESSAGE));
    }

    // ------------------------------------------------------------------
    // Series y episodios
    // ------------------------------------------------------------------

    /** Año de estreno o de fin decimal en una serie → 400 {@code MALFORMED_REQUEST}. */
    @ParameterizedTest
    @ValueSource(strings = { "releaseYear", "endYear" })
    void unAnioDecimalEnUnaSerieDa400MalformedRequest(String field) throws Exception {
        String releaseYear = "releaseYear".equals(field) ? "2014.5" : "2014";
        String endYear = "endYear".equals(field) ? "2016.5" : "2016";
        String body = """
                {"title":"Serie decimal","description":"d","releaseYear":%s,"endYear":%s,
                 "imageUrl":"https://example.com/s.jpg","genreIds":[%d]}
                """.formatted(releaseYear, endYear, drama.getId());

        postJson("/api/series", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value(MALFORMED_MESSAGE));

        assertEquals(0, seriesRepository.count());
    }

    /** Temporada, número o duración decimal en un episodio → 400 {@code MALFORMED_REQUEST}. */
    @ParameterizedTest
    @ValueSource(strings = { "seasonNumber", "episodeNumber", "duration" })
    void unEnteroDecimalEnUnEpisodioDa400MalformedRequest(String field) throws Exception {
        Series series = saveSeries();

        String response = postJson("/api/series/" + series.getId() + "/episodes", episodeJson(field, "1.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value(MALFORMED_MESSAGE))
                .andReturn().getResponse().getContentAsString();

        assertNoInternalDetails(response);
        assertEquals(0, episodeRepository.count());
    }

    /** El mismo episodio con enteros normales se crea con los valores exactos. */
    @Test
    void unEpisodioConEnterosNormalesSeCrea() throws Exception {
        Series series = saveSeries();

        postJson("/api/series/" + series.getId() + "/episodes", episodeJson("duration", "45"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seasonNumber").value(1))
                .andExpect(jsonPath("$.episodeNumber").value(1))
                .andExpect(jsonPath("$.duration").value(45));
    }

    // ------------------------------------------------------------------
    // Parámetros de consulta
    // ------------------------------------------------------------------

    /**
     * Los parámetros de consulta no pasan por Jackson (los convierte Spring
     * MVC), así que la propiedad no les afecta: {@code ?page=1} sigue
     * funcionando igual que antes.
     */
    @Test
    void losParametrosDeConsultaEnterosSiguenFuncionando() throws Exception {
        mockMvc.perform(get("/api/movies").param("page", "1").param("size", "5")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private ResultActions postJson(String url, String json) throws Exception {
        return mockMvc.perform(post(url)
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    /** Película válida salvo, quizá, los números que se pasan tal cual se escriben en el JSON. */
    private String movieJson(String duration, String releaseYear) {
        return """
                {"title":"Pelicula numeros","description":"d","duration":%s,"releaseYear":%s,
                 "imageUrl":"https://example.com/i.jpg","videoUrl":"https://example.com/v.mp4",
                 "genreIds":[%d]}
                """.formatted(duration, releaseYear, drama.getId());
    }

    /** Episodio 1x1 de 50 minutos con {@code field} sustituido por {@code value} literal. */
    private String episodeJson(String field, String value) {
        String season = "seasonNumber".equals(field) ? value : "1";
        String number = "episodeNumber".equals(field) ? value : "1";
        String duration = "duration".equals(field) ? value : "50";
        return """
                {"seasonNumber":%s,"episodeNumber":%s,"title":"Piloto","duration":%s,
                 "videoUrl":"https://example.com/1x1.mp4"}
                """.formatted(season, number, duration);
    }

    private Series saveSeries() {
        Series series = new Series();
        series.setTitle("Serie con episodios");
        series.setDescription("Sinopsis");
        series.setReleaseYear(2010);
        series.setImageUrl("https://example.com/serie.jpg");
        series.setGenres(new HashSet<>(Set.of(drama)));
        return seriesRepository.save(series);
    }

    /** El cuerpo de error no debe revelar el parser, clases ni trazas. */
    private static void assertNoInternalDetails(String response) {
        assertFalse(response.contains("Exception") || response.contains("jackson")
                || response.contains("Integer") || response.contains("at com."),
                "el error no debe exponer detalles internos: " + response);
    }
}
