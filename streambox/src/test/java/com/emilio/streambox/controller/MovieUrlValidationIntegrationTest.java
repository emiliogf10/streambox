package com.emilio.streambox.controller;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Validación de {@code imageUrl} y {@code videoUrl} en el alta
 * ({@code POST /api/movies}) y la edición ({@code PUT /api/movies/{id}}) de
 * películas, a través de la API real (como ADMIN).
 *
 * <p>
 * Antes se usaba {@code @URL}, que aceptaba {@code http:}, {@code file:} o
 * {@code ftp:}, rechazaba las portadas propias {@code /covers/...} y no
 * limitaba la longitud: una URL de más de 500 caracteres llegaba a la base de
 * datos y el cliente recibía un 409 engañoso. Cada uno de esos casos tiene
 * aquí un test que fallaba con la versión anterior. Los mensajes se comparan
 * literalmente porque el panel de administración del frontend los muestra
 * junto al campo.
 * </p>
 *
 * <p>
 * Usa {@code @Transactional} (rollback al terminar): con identificadores
 * {@code IDENTITY} el {@code INSERT} se ejecuta en el momento, así que el
 * caso de 500 caracteres sí llega a probar la columna {@code VARCHAR(500)}.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class MovieUrlValidationIntegrationTest {

    private static final String IMAGE_FORMAT =
            "La URL de la imagen debe empezar por https:// o ser una portada propia (/covers/archivo)";
    private static final String IMAGE_SIZE = "La URL de la imagen no puede superar los 500 caracteres";
    private static final String VIDEO_FORMAT = "La URL del vídeo debe empezar por https://";
    private static final String VIDEO_SIZE = "La URL del vídeo no puede superar los 500 caracteres";

    private static final String VALID_IMAGE = "https://image.tmdb.org/t/p/w500/dune.jpg";
    private static final String VALID_VIDEO = "https://videos.streambox.example/watch/dune";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String adminToken;
    private Genre genre;

    @BeforeEach
    void setUp() {
        User admin = new User();
        admin.setUsername("urladmin");
        admin.setEmail("urladmin@test.com");
        admin.setPassword(passwordEncoder.encode("adminpass1"));
        admin.setRole(Role.ADMIN);
        admin.setCreatedAt(Instant.now());
        adminToken = "Bearer " + jwtService.generateToken(userRepository.save(admin));

        Genre scifi = new Genre();
        scifi.setName("Ciencia ficción");
        genre = genreRepository.save(scifi);
    }

    // ------------------------------------------------------------------
    // Alta
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "http://cdn.example.com/dune.jpg", "javascript:alert(1)", "data:image/png;base64,AAAA",
            "file:///etc/passwd", "ftp://cdn.example.com/dune.jpg", "//cdn.example.com/dune.jpg",
            "HTTPS://cdn.example.com/dune.jpg", "https:///ruta", "https://",
            "https://u:p@cdn.example.com/dune.jpg", "https://cdn.example.com/a b.jpg",
            "/covers/../x.webp", "/covers/sub/x.webp", "/covers/x.webp?v=1", "/covers/%2e%2e.webp"
    })
    void altaConImageUrlInvalidaRetorna400ConElMensajeDelContrato(String url) throws Exception {
        long before = movieRepository.count();

        create(body(url, VALID_VIDEO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.imageUrl").value(IMAGE_FORMAT))
                .andExpect(jsonPath("$.validationErrors.videoUrl").doesNotExist());

        assertEquals(before, movieRepository.count());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://cdn.example.com/dune.mp4", "javascript:alert(1)", "ftp://cdn.example.com/dune.mp4",
            "/covers/dune-parte-dos.webp", "https://u:p@cdn.example.com/dune.mp4"
    })
    void altaConVideoUrlInvalidaRetorna400ConElMensajeDelContrato(String url) throws Exception {
        create(body(VALID_IMAGE, url))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.videoUrl").value(VIDEO_FORMAT))
                .andExpect(jsonPath("$.validationErrors.imageUrl").doesNotExist());
    }

    @Test
    void altaConAmbasUrlsInvalidasDevuelveUnErrorPorCampo() throws Exception {
        create(body("http://cdn.example.com/dune.jpg", "file:///dune.mp4"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.imageUrl").value(IMAGE_FORMAT))
                .andExpect(jsonPath("$.validationErrors.videoUrl").value(VIDEO_FORMAT));
    }

    /**
     * Antes este caso acababa en un {@code DataIntegrityViolationException}
     * (valor demasiado largo para la columna) y el cliente recibía un 409
     * {@code DATA_INTEGRITY_VIOLATION}. La segunda URL, además de larga, tiene
     * un esquema prohibido: solo se informa de la longitud.
     */
    @Test
    void altaConUrlsDe501CaracteresRetorna400YNo409() throws Exception {
        long before = movieRepository.count();

        create(body(httpsUrlOfLength(501), "http://" + "a".repeat(494)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.imageUrl").value(IMAGE_SIZE))
                .andExpect(jsonPath("$.validationErrors.videoUrl").value(VIDEO_SIZE));

        assertEquals(before, movieRepository.count());
    }

    @Test
    void altaConUrlsDeExactamente500CaracteresSeGuarda() throws Exception {
        String url = httpsUrlOfLength(500);

        create(body(url, url))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imageUrl").value(url))
                .andExpect(jsonPath("$.videoUrl").value(url));
    }

    /** Las portadas de ejemplo del frontend antes no pasaban {@code @URL}. */
    @Test
    void altaConPortadaPropiaRetorna201YLaDevuelveTalCual() throws Exception {
        String body = create(body("/covers/dune-parte-dos.webp", VALID_VIDEO))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.imageUrl").value("/covers/dune-parte-dos.webp"))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).get("id").asLong();

        assertEquals("/covers/dune-parte-dos.webp", movieRepository.findById(id).orElseThrow().getImageUrl());
    }

    // ------------------------------------------------------------------
    // Edición
    // ------------------------------------------------------------------

    @Test
    void edicionConUrlsInvalidasRetorna400YNoModificaLaPelicula() throws Exception {
        Movie movie = saveMovie();

        update(movie.getId(), body("http://cdn.example.com/otra.jpg", VALID_VIDEO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.imageUrl").value(IMAGE_FORMAT));
        update(movie.getId(), body(VALID_IMAGE, "javascript:alert(1)"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.videoUrl").value(VIDEO_FORMAT));
        update(movie.getId(), body(VALID_IMAGE, "/covers/dune-parte-dos.webp"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.videoUrl").value(VIDEO_FORMAT));

        Movie unchanged = movieRepository.findById(movie.getId()).orElseThrow();
        assertEquals("Original", unchanged.getTitle());
        assertEquals("https://cdn.example.com/original.jpg", unchanged.getImageUrl());
        assertEquals("https://cdn.example.com/original.mp4", unchanged.getVideoUrl());
    }

    @Test
    void edicionConUrlsDe501CaracteresRetorna400YNo409() throws Exception {
        Movie movie = saveMovie();
        String tooLong = httpsUrlOfLength(501);

        update(movie.getId(), body(tooLong, tooLong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.imageUrl").value(IMAGE_SIZE))
                .andExpect(jsonPath("$.validationErrors.videoUrl").value(VIDEO_SIZE));
    }

    @Test
    void edicionConPortadaPropiaRetorna200() throws Exception {
        Movie movie = saveMovie();

        update(movie.getId(), body("/covers/a_b-c.1.jpg", VALID_VIDEO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value("/covers/a_b-c.1.jpg"))
                .andExpect(jsonPath("$.videoUrl").value(VALID_VIDEO));
    }

    // ------------------------------------------------------------------
    // Documentación
    // ------------------------------------------------------------------

    /**
     * El panel de administración se construye a partir del OpenAPI: debe
     * publicar el máximo de 500 caracteres (sale de {@code @Size}) y explicar
     * qué URL se aceptan (sale de {@code @Schema}).
     */
    @Test
    void elOpenApiDescribeLasReglasDeLasUrls() throws Exception {
        String schema = "$.components.schemas.MovieRequest.properties";

        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath(schema + ".imageUrl.maxLength").value(500))
                .andExpect(jsonPath(schema + ".imageUrl.description").value(containsString("https://")))
                .andExpect(jsonPath(schema + ".imageUrl.description").value(containsString("/covers/")))
                .andExpect(jsonPath(schema + ".videoUrl.maxLength").value(500))
                .andExpect(jsonPath(schema + ".videoUrl.description").value(containsString("https://")));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private ResultActions create(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/movies").header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions update(Long id, Map<String, Object> body) throws Exception {
        return mockMvc.perform(put("/api/movies/{id}", id).header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    /** Cuerpo de alta/edición válido salvo, quizá, las URL indicadas. */
    private Map<String, Object> body(String imageUrl, String videoUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Dune");
        body.put("description", "Sinopsis de Dune");
        body.put("duration", 155);
        body.put("releaseYear", 2021);
        body.put("imageUrl", imageUrl);
        body.put("videoUrl", videoUrl);
        body.put("genreIds", List.of(genre.getId()));
        return body;
    }

    private Movie saveMovie() {
        Movie movie = new Movie();
        movie.setTitle("Original");
        movie.setDescription("Sinopsis original");
        movie.setDuration(100);
        movie.setReleaseYear(2000);
        movie.setImageUrl("https://cdn.example.com/original.jpg");
        movie.setVideoUrl("https://cdn.example.com/original.mp4");
        movie.setCreatedAt(Instant.now());
        movie.setGenres(new HashSet<>(Set.of(genre)));
        return movieRepository.save(movie);
    }

    private static String httpsUrlOfLength(int length) {
        String prefix = "https://cdn.example.com/";
        return prefix + "a".repeat(length - prefix.length());
    }
}
