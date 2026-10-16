package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.validation.YouTubeUrlSamples;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tráiler y vídeo opcional a través de la API contra <b>PostgreSQL real</b>.
 *
 * <p>
 * El riesgo que cubre: la API acepta varias formas de enlace de YouTube, pero
 * los {@code CHECK} {@code ck_movies_trailer_url} y {@code ck_series_trailer_url}
 * solo admiten la normalizada, y se escribieron con {@code TRANSLATE},
 * {@code SUBSTRING} y {@code CHAR_LENGTH}, cuyo comportamiento puede variar
 * entre H2 y PostgreSQL. Aquí se guarda <b>cada</b> forma aceptada (el mismo
 * catálogo que en H2, {@link YouTubeUrlSamples}) con confirmación real y se
 * comprueba que PostgreSQL la acepta ya normalizada; un fallo saldría como un
 * 409 o un 500 en lugar del 201. También comprueba que {@code video_url}
 * admite {@code NULL} en las dos tablas.
 * </p>
 */
class PostgresTrailerUrlApiIntegrationTest extends PostgresIntegrationTestSupport {

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String adminToken;
    private Genre genre;

    static Stream<Arguments> acceptedTrailers() {
        return YouTubeUrlSamples.ACCEPTED.entrySet().stream()
                .map(entry -> Arguments.of(entry.getKey(), "https://www.youtube.com/watch?v=" + entry.getValue()));
    }

    @BeforeEach
    void setUp() {
        cleanDatabase();
        adminToken = tokenFor(saveUser("pgtrailer", Role.ADMIN));
        genre = saveGenre("Ciencia ficción");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @ParameterizedTest
    @MethodSource("acceptedTrailers")
    void cadaFormaAceptadaSeGuardaNormalizadaEnPeliculasYSeries(String trailer, String normalized)
            throws Exception {
        long movieId = idOf(send(post("/api/movies"), movieBody(trailer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trailerUrl").value(normalized)));
        long seriesId = idOf(send(post("/api/series"), seriesBody(trailer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.trailerUrl").value(normalized)));

        assertEquals(normalized, movieRepository.findById(movieId).orElseThrow().getTrailerUrl());
        assertEquals(normalized, seriesRepository.findById(seriesId).orElseThrow().getTrailerUrl());
    }

    /** Película y episodio sin vídeo: {@code video_url} admite {@code NULL} en PostgreSQL. */
    @Test
    void peliculaYEpisodioSinVideoSeGuardanConNull() throws Exception {
        Map<String, Object> movie = movieBody(null);
        movie.put("videoUrl", "");
        long movieId = idOf(send(post("/api/movies"), movie).andExpect(status().isCreated()));

        long seriesId = idOf(send(post("/api/series"), seriesBody(null)).andExpect(status().isCreated()));
        Map<String, Object> episode = new HashMap<>();
        episode.put("seasonNumber", 1);
        episode.put("episodeNumber", 1);
        episode.put("title", "Piloto");
        episode.put("duration", 50);
        long episodeId = idOf(send(post("/api/series/" + seriesId + "/episodes"), episode)
                .andExpect(status().isCreated()));

        assertNull(movieRepository.findById(movieId).orElseThrow().getVideoUrl());
        assertNull(movieRepository.findById(movieId).orElseThrow().getTrailerUrl());
        assertNull(episodeRepository.findById(episodeId).orElseThrow().getVideoUrl());
    }

    /** Un {@code PUT} sin tráiler lo deja a {@code NULL} (el {@code CHECK} admite {@code NULL}). */
    @Test
    void unPutSinTrailerLoBorra() throws Exception {
        long id = idOf(send(post("/api/movies"), movieBody(YouTubeUrlSamples.NORMALIZED))
                .andExpect(status().isCreated()));
        Map<String, Object> body = movieBody(null);
        body.remove("trailerUrl");

        send(put("/api/movies/" + id), body).andExpect(status().isOk());

        assertNull(movieRepository.findById(id).orElseThrow().getTrailerUrl());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private ResultActions send(MockHttpServletRequestBuilder builder, Map<String, Object> body) throws Exception {
        return mockMvc.perform(builder.header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private long idOf(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    private Map<String, Object> movieBody(String trailerUrl) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Duna");
        body.put("description", "Sinopsis de Duna");
        body.put("duration", 155);
        body.put("releaseYear", 2021);
        body.put("imageUrl", "https://cdn.example.com/duna.jpg");
        body.put("videoUrl", "https://cdn.example.com/duna.mp4");
        body.put("trailerUrl", trailerUrl);
        body.put("genreIds", List.of(genre.getId()));
        return body;
    }

    private Map<String, Object> seriesBody(String trailerUrl) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Fundación");
        body.put("description", "Sinopsis de Fundación");
        body.put("releaseYear", 2021);
        body.put("imageUrl", "https://cdn.example.com/fundacion.jpg");
        body.put("trailerUrl", trailerUrl);
        body.put("genreIds", List.of(genre.getId()));
        return body;
    }
}
