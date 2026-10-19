package com.emilio.streambox.controller;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

import com.emilio.streambox.dto.MovieRequest;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Revisión de QA del tráiler: casos de la API que no cubre
 * {@link TrailerUrlIntegrationTest}.
 *
 * <ul>
 * <li><b>Tipos JSON incorrectos</b> en {@code trailerUrl} y {@code videoUrl}
 * (número, booleano, lista, objeto): deben ser un 400 con el formato de
 * error, nunca un 500 ni una coerción silenciosa a texto (un {@code 123} que
 * se guardara como {@code "123"}).</li>
 * <li><b>Las dos diferencias cliente/servidor</b> encontradas en la revisión
 * (ver {@code YouTubeUrlParityContractTest}), comprobadas de punta a punta:
 * lo que el servidor acepta con corchetes en los parámetros se guarda
 * normalizado sin violar el {@code CHECK} (commit real), y un {@code %} mal
 * formado es un 400 con el mensaje del contrato. Fijan el comportamiento del
 * servidor, que es la referencia que debe imitar el frontend.</li>
 * </ul>
 *
 * <p>
 * Sin {@code @Transactional}: el {@code CHECK} {@code ck_movies_trailer_url}
 * solo se evalúa si la fila se confirma de verdad.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TrailerUrlQaIntegrationTest {

    private static final String ID = "aB3_-eFgHiJ";
    private static final String NORMALIZED = "https://www.youtube.com/watch?v=" + ID;
    private static final String TRAILER_FORMAT = "El tráiler debe ser un enlace de un vídeo de YouTube";
    /** Contrato compartido con el frontend (ver {@code YouTubeUrlParityContractTest}), relativo a {@code streambox/}. */
    private static final Path CONTRACT = Path.of("..", "frontend", "src", "test", "youtubeTrailerUrlCases.json");

    @Autowired private MockMvc mockMvc;
    @Autowired private MovieRepository movieRepository;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String adminToken;
    private long genreId;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        User admin = new User();
        admin.setEmailVerifiedAt(Instant.now()); // cuenta verificada (V9)
        admin.setUsername("qatraileradmin");
        admin.setEmail("qatraileradmin@test.com");
        admin.setPassword(passwordEncoder.encode("password123"));
        admin.setRole(Role.ADMIN);
        adminToken = "Bearer " + jwtService.generateToken(userRepository.save(admin));
        Genre genre = new Genre();
        genre.setName("Drama QA");
        genreId = genreRepository.save(genre).getId();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    /** Valores JSON que no son texto, escritos tal cual en el cuerpo. */
    static Stream<String> nonTextValues() {
        return Stream.of("123", "1.5", "true", "[\"" + NORMALIZED + "\"]", "{\"url\":\"" + NORMALIZED + "\"}");
    }

    /** Película y serie: un {@code trailerUrl} que no es texto es un 400, no un 500 ni una coerción. */
    @ParameterizedTest(name = "trailerUrl = {0}")
    @MethodSource("nonTextValues")
    void unTrailerQueNoEsTextoEsUn400(String rawValue) throws Exception {
        postJson("/api/movies", movieJson("\"videoUrl\":null,\"trailerUrl\":" + rawValue))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/api/movies"));
        postJson("/api/series", seriesJson("\"trailerUrl\":" + rawValue))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    /** El vídeo, ahora opcional, sigue sin admitir otros tipos JSON. */
    @ParameterizedTest(name = "videoUrl = {0}")
    @MethodSource("nonTextValues")
    void unVideoQueNoEsTextoEsUn400(String rawValue) throws Exception {
        postJson("/api/movies", movieJson("\"videoUrl\":" + rawValue + ",\"trailerUrl\":null"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    /**
     * Corchetes en los parámetros: {@code java.net.URI} los admite (RFC 2732),
     * así que la API acepta el enlace y lo guarda normalizado, con commit real
     * y sin violar el {@code CHECK}. Hoy el formulario del panel lo rechaza
     * (incidencia de QA trailer-1).
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "https://www.youtube.com/watch?v=" + ID + "&a[]=1",
        "https://youtu.be/" + ID + "?x=[]",
        "https://www.youtube.com/watch?[=1&v=" + ID })
    void corchetesEnLosParametrosSeAceptanYSeGuardanNormalizados(String trailer) throws Exception {
        postJson("/api/movies", movieJson("\"videoUrl\":null,\"trailerUrl\":" + objectMapper.writeValueAsString(trailer)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trailerUrl").value(NORMALIZED));
        postJson("/api/series", seriesJson("\"trailerUrl\":" + objectMapper.writeValueAsString(trailer)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trailerUrl").value(NORMALIZED));
    }

    /**
     * Un {@code %} sin dos dígitos hexadecimales en los parámetros: la API
     * responde 400 con el mensaje del contrato en {@code validationErrors}.
     * Hoy el formulario del panel lo da por bueno (incidencia de QA trailer-1).
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "https://www.youtube.com/watch?v=" + ID + "&x=%",
        "https://youtu.be/" + ID + "?x=%zz",
        "https://youtu.be/" + ID + "?x=%4" })
    void unPorcentajeMalFormadoEsUn400ConElMensajeDelContrato(String trailer) throws Exception {
        postJson("/api/movies", movieJson("\"videoUrl\":null,\"trailerUrl\":" + objectMapper.writeValueAsString(trailer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.trailerUrl").value(TRAILER_FORMAT));
    }

    /** Casos del contrato compartido con el frontend: nota, url y resultado esperado. */
    static Stream<Arguments> contractCases() throws IOException {
        assertTrue(Files.isRegularFile(CONTRACT), "No se encuentra el contrato compartido " + CONTRACT.toAbsolutePath());
        JsonNode root = new ObjectMapper().readTree(Files.readString(CONTRACT));
        List<Arguments> cases = new ArrayList<>();
        for (JsonNode item : root.get("casos")) {
            cases.add(Arguments.of(item.get("nota").asText(), item.get("url").asText(), item.get("esperado").asText()));
        }
        return cases.stream();
    }

    /**
     * El contrato compartido con el frontend ({@code youtubeTrailerUrlCases.json}),
     * de punta a punta por la API real y con commit real, en películas y en
     * series.
     *
     * <p>
     * {@code YouTubeUrlParityContractTest} lo comprueba con el validador de
     * Bean Validation a mano; aquí pasa además por Jackson (los caracteres de
     * control, el BOM o U+2028 viajan escapados en el JSON), por el mapper
     * que normaliza y por el {@code CHECK} de la base de datos: lo aceptado da
     * 201 con la forma normalizada (si violara el {@code CHECK}, sería un 409
     * o un 500), lo rechazado da 400 con el mensaje exacto del contrato en
     * {@code validationErrors.trailerUrl} y nunca un 500.
     * </p>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("contractCases")
    void cadaCasoDelContratoCompartidoDaLoMismoEnLaApi(String note, String url, String expected) throws Exception {
        String trailer = objectMapper.writeValueAsString(url);
        assertContract(postJson("/api/movies", movieJson("\"videoUrl\":null,\"trailerUrl\":" + trailer)), expected);
        assertContract(postJson("/api/series", seriesJson("\"trailerUrl\":" + trailer)), expected);
    }

    /** Comprueba la respuesta de un alta según el resultado esperado del contrato. */
    private static void assertContract(ResultActions result, String expected) throws Exception {
        switch (expected) {
            case "formato" -> result.andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.validationErrors.trailerUrl").value(TRAILER_FORMAT));
            case "longitud" -> result.andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.validationErrors.trailerUrl").value(MovieRequest.TRAILER_URL_SIZE_MESSAGE));
            default -> result.andExpect(status().isCreated())
                    .andExpect(jsonPath("$.trailerUrl").value(expected));
        }
    }

    /**
     * Un {@code PUT} con {@code "trailerUrl": ""} (lo que mandaría un cliente
     * que no convierte el vacío en {@code null}) borra el tráiler igual que
     * omitirlo.
     */
    @Test
    void unPutConTrailerVacioLoBorra() throws Exception {
        String location = postJson("/api/movies", movieJson("\"videoUrl\":null,\"trailerUrl\":\"https://youtu.be/" + ID + "\""))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(location).get("id").asLong();

        mockMvc.perform(put("/api/movies/" + id)
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(movieJson("\"videoUrl\":\"\",\"trailerUrl\":\"\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trailerUrl").isEmpty())
                .andExpect(jsonPath("$.videoUrl").isEmpty());
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

    /** Película válida en JSON crudo, con los campos de vídeo y tráiler que se indiquen. */
    private String movieJson(String videoAndTrailer) {
        return "{\"title\":\"Duna QA\",\"description\":\"Sinopsis\",\"duration\":155,\"releaseYear\":2021,"
                + "\"imageUrl\":\"https://cdn.example.com/duna.jpg\"," + videoAndTrailer
                + ",\"genreIds\":[" + genreId + "]}";
    }

    /** Serie válida en JSON crudo, con el campo de tráiler que se indique. */
    private String seriesJson(String trailer) {
        return "{\"title\":\"Fundación QA\",\"description\":\"Sinopsis\",\"releaseYear\":2021,"
                + "\"imageUrl\":\"https://cdn.example.com/f.jpg\"," + trailer
                + ",\"genreIds\":[" + genreId + "]}";
    }

    /** Series primero (sus géneros y episodios caen con ellas), luego el resto. */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
