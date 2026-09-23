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
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tests de integracion para MovieController. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MovieControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private String adminToken;
    private String userToken;
    private Genre savedGenre;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        User admin = new User();
        admin.setUsername("adminmovie");
        admin.setEmail("adminmovie@test.com");
        admin.setPassword(passwordEncoder.encode("adminpass1"));
        admin.setRole(Role.ADMIN);
        admin.setCreatedAt(LocalDateTime.now());
        adminToken = "Bearer " + jwtService.generateToken(userRepository.save(admin));

        User user = new User();
        user.setUsername("usermovie");
        user.setEmail("usermovie@test.com");
        user.setPassword(passwordEncoder.encode("userpass1"));
        user.setRole(Role.USER);
        user.setCreatedAt(LocalDateTime.now());
        userToken = "Bearer " + jwtService.generateToken(userRepository.save(user));

        Genre genre = new Genre();
        genre.setName("Action");
        savedGenre = genreRepository.save(genre);
    }

    @Test
    void getMoviesSinTokenRetorna401() throws Exception {
        mockMvc.perform(get("/api/movies")).andExpect(status().isUnauthorized());
    }

    @Test
    void getMoviesConTokenRetorna200() throws Exception {
        mockMvc.perform(get("/api/movies").header("Authorization", userToken)
                        .param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").exists());
    }

    @Test
    void getMovieByIdInexistenteRetorna404() throws Exception {
        mockMvc.perform(get("/api/movies/999999").header("Authorization", userToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void getMovieByIdValidoRetorna200() throws Exception {
        Movie m = buildMovie("Matrix", savedGenre);
        Long id = movieRepository.save(m).getId();
        mockMvc.perform(get("/api/movies/" + id).header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Matrix"));
    }

    @Test
    void createMovieSinTokenRetorna401() throws Exception {
        mockMvc.perform(post("/api/movies").contentType(MediaType.APPLICATION_JSON)
                        .content(validMovieJson(savedGenre.getId())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createMovieConTokenUserRetorna403() throws Exception {
        mockMvc.perform(post("/api/movies").header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validMovieJson(savedGenre.getId())))
                .andExpect(status().isForbidden());
    }

    @Test
    void createMovieConTokenAdminRetorna201() throws Exception {
        mockMvc.perform(post("/api/movies").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validMovieJson(savedGenre.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value("Inception"));
    }

    @Test
    void createMovieCamposAusentesRetorna400() throws Exception {
        mockMvc.perform(post("/api/movies").header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors").isMap());
    }

    @Test
    void updateMovieSinTokenRetorna401() throws Exception {
        mockMvc.perform(put("/api/movies/1").contentType(MediaType.APPLICATION_JSON)
                        .content(validMovieJson(savedGenre.getId())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void updateMovieConTokenAdminRetorna200() throws Exception {
        Movie m = buildMovie("Avatar", savedGenre);
        Long id = movieRepository.save(m).getId();
        var body = Map.of("title", "Avatar2", "description", "desc", "duration", 162,
                "releaseYear", 2022, "imageUrl", "https://cdn.example.com/av.jpg",
                "videoUrl", "https://cdn.example.com/av.mp4",
                "genreIds", Set.of(savedGenre.getId()));
        mockMvc.perform(put("/api/movies/" + id).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Avatar2"));
    }

    @Test
    void deleteMovieSinTokenRetorna401() throws Exception {
        mockMvc.perform(delete("/api/movies/1")).andExpect(status().isUnauthorized());
    }

    @Test
    void deleteMovieConTokenUserRetorna403() throws Exception {
        Movie m = buildMovie("Forbid", savedGenre);
        Long id = movieRepository.save(m).getId();
        mockMvc.perform(delete("/api/movies/" + id).header("Authorization", userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteMovieConTokenAdminRetorna204() throws Exception {
        Movie m = buildMovie("Borrar", savedGenre);
        Long id = movieRepository.save(m).getId();
        mockMvc.perform(delete("/api/movies/" + id).header("Authorization", adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteMovieIdInexistenteRetorna404() throws Exception {
        mockMvc.perform(delete("/api/movies/999999").header("Authorization", adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void searchMoviesPaginaValidaRetorna200() throws Exception {
        mockMvc.perform(get("/api/movies/search").header("Authorization", userToken)
                        .param("page", "0").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void searchMoviesSizeCeroRetorna400() throws Exception {
        mockMvc.perform(get("/api/movies/search").header("Authorization", userToken)
                        .param("page", "0").param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchMoviesSizeMayorLimiteRetorna400() throws Exception {
        mockMvc.perform(get("/api/movies/search").header("Authorization", userToken)
                        .param("page", "0").param("size", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void searchMoviesPaginaNegativaRetorna400() throws Exception {
        mockMvc.perform(get("/api/movies/search").header("Authorization", userToken)
                        .param("page", "-1").param("size", "10"))
                .andExpect(status().isBadRequest());
    }

    private Movie buildMovie(String title, Genre genre) {
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setDescription("Descripcion de " + title);
        movie.setDuration(120);
        movie.setReleaseYear(2000);
        movie.setImageUrl("https://cdn.example.com/" + title.toLowerCase() + ".jpg");
        movie.setVideoUrl("https://cdn.example.com/" + title.toLowerCase() + ".mp4");
        movie.setCreatedAt(LocalDateTime.now());
        movie.setGenres(Set.of(genre));
        return movie;
    }

    private String validMovieJson(Long genreId) throws Exception {
        var body = Map.of("title", "Inception", "description", "desc", "duration", 148,
                "releaseYear", 2010, "imageUrl", "https://cdn.example.com/inception.jpg",
                "videoUrl", "https://cdn.example.com/inception.mp4",
                "genreIds", Set.of(genreId));
        return objectMapper.writeValueAsString(body);
    }
}