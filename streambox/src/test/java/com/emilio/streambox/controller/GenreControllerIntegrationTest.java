package com.emilio.streambox.controller;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tests de integración para {@link GenreController}. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class GenreControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private String adminToken;
    private String userToken;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        User admin = new User();
        admin.setUsername("admingenre");
        admin.setEmail("admingenre@test.com");
        admin.setPassword(passwordEncoder.encode("adminpass1"));
        admin.setRole(Role.ADMIN);
        admin.setCreatedAt(Instant.now());
        adminToken = "Bearer " + jwtService.generateToken(userRepository.save(admin));

        User user = new User();
        user.setUsername("usergenre");
        user.setEmail("usergenre@test.com");
        user.setPassword(passwordEncoder.encode("userpass1"));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        userToken = "Bearer " + jwtService.generateToken(userRepository.save(user));
    }


    @Test
    void getGenresSinTokenRetorna401() throws Exception {
        mockMvc.perform(get("/api/genres")).andExpect(status().isUnauthorized());
    }

    @Test
    void getGenresConTokenRetorna200() throws Exception {
        mockMvc.perform(get("/api/genres").header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void createGenreSinTokenRetorna401() throws Exception {
        mockMvc.perform(post("/api/genres").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Terror"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createGenreConTokenUserRetorna403() throws Exception {
        mockMvc.perform(post("/api/genres").header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Terror"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void createGenreConTokenAdminRetorna201() throws Exception {
        mockMvc.perform(post("/api/genres").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Terror"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Terror"));
    }

    @Test
    void createGenreNombreNormalizadoMayuscula() throws Exception {
        mockMvc.perform(post("/api/genres").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "  cOMeDiA  "))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Comedia"));
    }

    @Test
    void createGenreNombreVacioRetorna400() throws Exception {
        mockMvc.perform(post("/api/genres").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.name").exists());
    }

    @Test
    void createGenreNombreCortoRetorna400() throws Exception {
        mockMvc.perform(post("/api/genres").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "A"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.name").exists());
    }

    @Test
    void createGenreNombreLargoRetorna400() throws Exception {
        mockMvc.perform(post("/api/genres").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "A".repeat(51)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.name").exists());
    }

    // ------------------------------------------------------------------
    // Alta: nombre repetido
    // ------------------------------------------------------------------

    @Test
    void createGenreDuplicadoRetorna409ConCodigoEspecifico() throws Exception {
        saveGenre("Drama");

        mockMvc.perform(post("/api/genres").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("  dRAMA ")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENRE_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value("Ya existe un género con el nombre \"Drama\""));
    }

    /**
     * Una fila anterior a la normalización ({@code "Ciencia Ficción"}) no choca
     * con la restricción {@code UNIQUE}, que distingue mayúsculas, al crear
     * {@code "Ciencia ficción"}: la comprobación del servicio debe detectarla.
     */
    @Test
    void createGenreDuplicadoDeUnaFilaConOtrasMayusculasRetorna409() throws Exception {
        saveGenre("Ciencia Ficción");

        mockMvc.perform(post("/api/genres").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("ciencia ficción")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENRE_ALREADY_EXISTS"));
    }

    // ------------------------------------------------------------------
    // Edición (PUT /api/genres/{id})
    // ------------------------------------------------------------------

    @Test
    void updateGenreNormalizaElNombreYRetorna200() throws Exception {
        Genre genre = saveGenre("Scifi");

        mockMvc.perform(put("/api/genres/{id}", genre.getId()).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("  ciencia FICCIÓN ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(genre.getId()))
                .andExpect(jsonPath("$.name").value("Ciencia ficción"));

        assertEquals("Ciencia ficción", genreRepository.findById(genre.getId()).orElseThrow().getName());
    }

    @Test
    void updateGenreASuMismoNombreRetorna200() throws Exception {
        Genre genre = saveGenre("Drama");

        mockMvc.perform(put("/api/genres/{id}", genre.getId()).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("drama")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(genre.getId()))
                .andExpect(jsonPath("$.name").value("Drama"));
    }

    @Test
    void updateGenreANombreDeOtroGeneroRetorna409() throws Exception {
        saveGenre("Drama");
        Genre comedy = saveGenre("Comedia");

        mockMvc.perform(put("/api/genres/{id}", comedy.getId()).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("DRAMA")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENRE_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value("Ya existe un género con el nombre \"Drama\""));

        assertEquals("Comedia", genreRepository.findById(comedy.getId()).orElseThrow().getName());
    }

    @Test
    void updateGenreInexistenteRetorna404() throws Exception {
        mockMvc.perform(put("/api/genres/{id}", 999_999L).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("Terror")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void updateGenreNombreVacioRetorna400() throws Exception {
        Genre genre = saveGenre("Drama");

        mockMvc.perform(put("/api/genres/{id}", genre.getId()).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("   ")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.name").exists());
    }

    @Test
    void updateGenreNombreLargoRetorna400() throws Exception {
        Genre genre = saveGenre("Drama");

        mockMvc.perform(put("/api/genres/{id}", genre.getId()).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json("A".repeat(51))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.name").exists());

        assertEquals("Drama", genreRepository.findById(genre.getId()).orElseThrow().getName());
    }

    // ------------------------------------------------------------------
    // Borrado (DELETE /api/genres/{id})
    // ------------------------------------------------------------------

    @Test
    void deleteGenreRetorna204YDesapareceDelListado() throws Exception {
        Genre genre = saveGenre("Western");

        mockMvc.perform(delete("/api/genres/{id}", genre.getId()).header("Authorization", adminToken))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        mockMvc.perform(get("/api/genres").header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'Western')]").isEmpty());

        assertFalse(genreRepository.existsById(genre.getId()));
    }

    @Test
    void deleteGenreInexistenteRetorna404() throws Exception {
        mockMvc.perform(delete("/api/genres/{id}", 999_999L).header("Authorization", adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /**
     * El recuento solo debe incluir las películas que tienen <em>este</em>
     * género: la película de otro género no cuenta.
     */
    @Test
    void deleteGenreUsadoPorPeliculasRetorna409ConElRecuento() throws Exception {
        Genre drama = saveGenre("Drama");
        Genre comedy = saveGenre("Comedia");
        saveMovie("Uno", drama);
        saveMovie("Dos", drama, comedy);
        saveMovie("Tres", drama);
        saveMovie("Cuatro", comedy);

        mockMvc.perform(delete("/api/genres/{id}", drama.getId()).header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENRE_IN_USE"))
                .andExpect(jsonPath("$.message").value(
                        "No se puede eliminar el género \"Drama\": lo usan 3 películas. "
                                + "Quítalo de esas películas antes de borrarlo."));

        assertTrue(genreRepository.existsById(drama.getId()));
    }

    @Test
    void deleteGenreUsadoPorUnaPeliculaUsaElSingular() throws Exception {
        Genre drama = saveGenre("Drama");
        saveMovie("Uno", drama);

        mockMvc.perform(delete("/api/genres/{id}", drama.getId()).header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENRE_IN_USE"))
                .andExpect(jsonPath("$.message", containsString("lo usa 1 película. Quítalo de esa película")));

        assertTrue(genreRepository.existsById(drama.getId()));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private String json(String name) throws Exception {
        return objectMapper.writeValueAsString(Map.of("name", name));
    }

    private Genre saveGenre(String name) {
        Genre genre = new Genre();
        genre.setName(name);
        return genreRepository.save(genre);
    }

    private void saveMovie(String title, Genre... genres) {
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setDescription("Descripcion de " + title);
        movie.setDuration(120);
        movie.setReleaseYear(2000);
        movie.setImageUrl("https://cdn.example.com/" + title.toLowerCase() + ".jpg");
        movie.setVideoUrl("https://cdn.example.com/" + title.toLowerCase() + ".mp4");
        movie.setCreatedAt(Instant.now());
        movie.setGenres(Set.of(genres));
        movieRepository.save(movie);
    }
}
