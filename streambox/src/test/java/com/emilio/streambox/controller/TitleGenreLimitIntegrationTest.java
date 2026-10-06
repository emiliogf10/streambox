package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tope de 20 géneros por título en el alta y la edición de películas
 * ({@code /api/movies}) y series ({@code /api/series}), a través de la API
 * real (como ADMIN).
 *
 * <p>
 * Antes {@code genreIds} no tenía límite de tamaño: cualquier número de ids
 * llegaba al {@code WHERE id IN (...)} del servicio. Con 21 géneros
 * <em>existentes</em> (para que el 400 no pueda confundirse con el 404 de
 * "género no encontrado") estos tests fallaban con la versión anterior, que
 * creaba el título con 21 géneros. Los mensajes se comparan literalmente
 * porque son parte del contrato con el panel de administración.
 * </p>
 *
 * <p>
 * Usa {@code @Transactional} (rollback al terminar): solo se comprueba la
 * validación y el alta normal, sin restricciones ni cascadas de la base de
 * datos que necesiten un commit real.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class TitleGenreLimitIntegrationTest {

    private static final String MOVIE_LIMIT = "Una película puede tener como máximo 20 géneros";
    private static final String SERIES_LIMIT = "Una serie puede tener como máximo 20 géneros";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String adminToken;

    /** Ids de 21 géneros que existen de verdad en la base de datos. */
    private List<Long> genreIds;

    @BeforeEach
    void setUp() {
        User admin = new User();
        admin.setUsername("genrelimitadmin");
        admin.setEmail("genrelimitadmin@test.com");
        admin.setPassword(passwordEncoder.encode("adminpass1"));
        admin.setRole(Role.ADMIN);
        admin.setCreatedAt(Instant.now());
        adminToken = "Bearer " + jwtService.generateToken(userRepository.save(admin));

        genreIds = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            Genre genre = new Genre();
            genre.setName("Género tope " + i);
            genreIds.add(genreRepository.save(genre).getId());
        }
    }

    // ------------------------------------------------------------------
    // Películas
    // ------------------------------------------------------------------

    @Test
    void unaPeliculaConVeinteGenerosSeCrea() throws Exception {
        send(post("/api/movies"), movieBody(20))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.genres.length()").value(20));
    }

    @Test
    void unaPeliculaConVeintiunGenerosDa400ConElMensajeYNoSeCrea() throws Exception {
        long before = movieRepository.count();

        send(post("/api/movies"), movieBody(21))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.genreIds").value(MOVIE_LIMIT));

        assertEquals(before, movieRepository.count());
    }

    @Test
    void editarUnaPeliculaConVeintiunGenerosDa400() throws Exception {
        String json = send(post("/api/movies"), movieBody(1))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(json).get("id").asLong();

        send(put("/api/movies/{id}", id), movieBody(21))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.genreIds").value(MOVIE_LIMIT));
    }

    // ------------------------------------------------------------------
    // Series
    // ------------------------------------------------------------------

    @Test
    void unaSerieConVeinteGenerosSeCrea() throws Exception {
        send(post("/api/series"), seriesBody(20))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.genres.length()").value(20));
    }

    @Test
    void unaSerieConVeintiunGenerosDa400ConElMensajeYNoSeCrea() throws Exception {
        long before = seriesRepository.count();

        send(post("/api/series"), seriesBody(21))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.genreIds").value(SERIES_LIMIT));

        assertEquals(before, seriesRepository.count());
    }

    @Test
    void editarUnaSerieConVeintiunGenerosDa400() throws Exception {
        String json = send(post("/api/series"), seriesBody(1))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(json).get("id").asLong();

        send(put("/api/series/{id}", id), seriesBody(21))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.genreIds").value(SERIES_LIMIT));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private ResultActions send(MockHttpServletRequestBuilder builder, Map<String, Object> body) throws Exception {
        return mockMvc.perform(builder.header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    /** Película válida con los {@code genreCount} primeros géneros creados. */
    private Map<String, Object> movieBody(int genreCount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Dune");
        body.put("description", "Sinopsis de Dune");
        body.put("duration", 155);
        body.put("releaseYear", 2021);
        body.put("imageUrl", "https://image.tmdb.org/t/p/w500/dune.jpg");
        body.put("videoUrl", "https://videos.streambox.example/watch/dune");
        body.put("genreIds", genreIds.subList(0, genreCount));
        return body;
    }

    /** Serie válida con los {@code genreCount} primeros géneros creados. */
    private Map<String, Object> seriesBody(int genreCount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Fargo");
        body.put("description", "Sinopsis de Fargo");
        body.put("releaseYear", 2014);
        body.put("imageUrl", "https://example.com/fargo.jpg");
        body.put("genreIds", genreIds.subList(0, genreCount));
        return body;
    }
}
