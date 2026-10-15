package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
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
 * Revisión independiente (QA) de {@code GET /api/movies/{id}/similar}: los
 * casos límite que no cubre {@link SimilarMoviesIntegrationTest}.
 *
 * <ul>
 * <li><strong>Ids raros en la ruta</strong> (negativo, cero, el máximo de
 * {@code long}, texto, decimal, desbordado): responden lo mismo que el detalle
 * de esa película ({@code GET /api/movies/{id}}), nunca un 500 ni una lista
 * vacía que haga creer que la película existe.</li>
 * <li><strong>Parámetro {@code limit} raro</strong> (vacío o repetido): el valor
 * que se usa siempre pasa por la validación 1..24.</li>
 * <li><strong>Métodos</strong>: lo puede leer cualquier autenticado (también
 * ADMIN); escribir en la ruta nunca llega a un 500 (USER → 403 por la regla de
 * cierre, ADMIN → 405 porque no hay tal endpoint).</li>
 * <li><strong>Nada que el detalle no enseñe</strong>: cada elemento es
 * exactamente el mismo JSON que el detalle de esa película.</li>
 * <li><strong>Sin caché en el servidor</strong>: editar los géneros o borrar una
 * película desde el panel se refleja en la siguiente llamada.</li>
 * </ul>
 *
 * <p>
 * Datos confirmados de verdad (sin {@code @Transactional}) y limpieza antes y
 * después de cada test; mismas propiedades que {@link SimilarMoviesIntegrationTest}
 * para compartir su contexto de Spring.
 * </p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SimilarMoviesQaIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private MovieRepository movieRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String userToken;
    private String adminToken;
    private Genre drama;
    private Genre comedy;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        userToken = "Bearer " + jwtService.generateToken(saveUser("qasimilaruser", Role.USER));
        adminToken = "Bearer " + jwtService.generateToken(saveUser("qasimilaradmin", Role.ADMIN));
        drama = saveGenre("Drama");
        comedy = saveGenre("Comedia");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Ids raros en la ruta
    // ------------------------------------------------------------------

    /**
     * Ids que encajan en un {@code Long} pero no pueden existir: el mismo 404
     * (estado, código y mensaje) que el detalle, no {@code []}.
     */
    @ParameterizedTest
    @ValueSource(strings = { "-1", "0", "9223372036854775807", "-9223372036854775808" })
    void unIdImposibleDaElMismo404QueElDetalle(String id) throws Exception {
        saveMovie("Una", 2000, drama);

        JsonNode similar = errorBody(get("/api/movies/" + id + "/similar"), 404);
        JsonNode detail = errorBody(get("/api/movies/" + id), 404);

        assertEquals("RESOURCE_NOT_FOUND", similar.get("code").asText());
        assertSameError(detail, similar);
    }

    /**
     * Un id que no es un {@code Long} (texto, decimal, desbordado): el mismo 400
     * que el detalle, sin llegar a la base de datos ni acabar en 500.
     */
    @ParameterizedTest
    @ValueSource(strings = { "abc", "1.5", "99999999999999999999", "1e3" })
    void unIdNoNumericoODesbordadoDaElMismo400QueElDetalle(String id) throws Exception {
        JsonNode similar = errorBody(get("/api/movies/" + id + "/similar"), 400);
        JsonNode detail = errorBody(get("/api/movies/" + id), 400);

        assertEquals("VALIDATION_ERROR", similar.get("code").asText());
        assertSameError(detail, similar);
        assertEquals(detail.get("validationErrors"), similar.get("validationErrors"));
    }

    /** Rutas que cuelgan de la de similares pero no existen: 404 de ruta, no 500. */
    @ParameterizedTest
    @ValueSource(strings = { "/similar/", "/similar/extra", "/similares" })
    void unaRutaParecidaQueNoExisteDa404(String suffix) throws Exception {
        Movie origin = saveMovie("Origen", 2000, drama);

        mockMvc.perform(get("/api/movies/" + origin.getId() + suffix).header("Authorization", userToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ------------------------------------------------------------------
    // Parámetro limit raro
    // ------------------------------------------------------------------

    /** {@code ?limit=} (vacío) se trata como si no viniera: el valor por defecto (12). */
    @Test
    void unLimiteVacioUsaElValorPorDefecto() throws Exception {
        Movie origin = saveMovie("Origen", 2000, drama);
        for (int i = 0; i < 15; i++) {
            saveMovie("Candidata " + i, 2001, drama);
        }

        mockMvc.perform(get("/api/movies/" + origin.getId() + "/similar").param("limit", "")
                        .header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(12));
    }

    /**
     * Con {@code limit} repetido nunca se salta la validación: o se rechaza
     * (400) o se usa un valor dentro de 1..24. Ninguna combinación devuelve más
     * de 24 películas ni un 500. Con 30 candidatas, más de 24 en la respuesta
     * significaría que el tope se puede saltar.
     */
    @ParameterizedTest
    @ValueSource(strings = { "1,999", "999,1", "30,30", "2,abc" })
    void unLimiteRepetidoNuncaSaltaElTope(String values) throws Exception {
        Movie origin = saveMovie("Origen", 2000, drama);
        for (int i = 0; i < 30; i++) {
            saveMovie("Candidata " + i, 2001, drama);
        }

        MvcResult result = mockMvc.perform(get("/api/movies/" + origin.getId() + "/similar")
                        .param("limit", values.split(","))
                        .header("Authorization", userToken))
                .andReturn();

        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        assertTrue(status == 200 || status == 400, "estado inesperado " + status + ": " + body);
        if (status == 200) {
            assertTrue(objectMapper.readTree(body).size() <= 24, "más de 24 películas: " + body);
        } else {
            assertEquals("VALIDATION_ERROR", objectMapper.readTree(body).get("code").asText());
        }
    }

    // ------------------------------------------------------------------
    // Métodos y roles
    // ------------------------------------------------------------------

    /** Un ADMIN también lo lee (GET y HEAD), con el mismo resultado que un USER. */
    @Test
    void unAdministradorLoLeeIgualQueUnUsuario() throws Exception {
        Movie origin = saveMovie("Origen", 2000, drama);
        saveMovie("Candidata", 2001, drama);

        String asUser = mockMvc.perform(get("/api/movies/" + origin.getId() + "/similar")
                        .header("Authorization", userToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String asAdmin = mockMvc.perform(get("/api/movies/" + origin.getId() + "/similar")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mockMvc.perform(head("/api/movies/" + origin.getId() + "/similar").header("Authorization", adminToken))
                .andExpect(status().isOk());

        assertEquals(objectMapper.readTree(asUser), objectMapper.readTree(asAdmin));
    }

    /** {@code HEAD} sigue el mismo contrato que {@code GET}: 404 si la película no existe, 401 sin sesión. */
    @Test
    void headDevuelveLosMismosEstadosQueGet() throws Exception {
        mockMvc.perform(head("/api/movies/987654/similar").header("Authorization", userToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(head("/api/movies/987654/similar"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Escribir en la ruta: un USER se queda en la regla de cierre (403) y un
     * ADMIN, que sí pasa la seguridad, recibe 405 en el formato de la API (no
     * hay endpoint de escritura), nunca un 500.
     */
    @Test
    void escribirEnLaRutaDa403AlUsuarioY405AlAdministrador() throws Exception {
        Movie origin = saveMovie("Origen", 2000, drama);
        String url = "/api/movies/" + origin.getId() + "/similar";

        for (MockHttpServletRequestBuilder write : List.of(post(url), put(url), patch(url), delete(url))) {
            mockMvc.perform(write.header("Authorization", userToken).contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }
        for (MockHttpServletRequestBuilder write : List.of(post(url), put(url), patch(url), delete(url))) {
            mockMvc.perform(write.header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.status").value(405))
                    .andExpect(jsonPath("$.code").exists());
        }
    }

    // ------------------------------------------------------------------
    // Contenido
    // ------------------------------------------------------------------

    /**
     * Cada elemento es exactamente el JSON del detalle de esa película (mismos
     * campos y valores): la fila no expone nada que el catálogo no enseñe ya, ni
     * le falta nada para pintar la página al pulsarla (el frontend la usa como
     * anticipo del detalle).
     */
    @Test
    void cadaElementoEsIdenticoAlDetalleDeEsaPelicula() throws Exception {
        Movie origin = saveMovie("Origen", 2000, drama, comedy);
        Movie candidate = saveMovie("Candidata", 2003, comedy, drama);

        JsonNode similar = objectMapper.readTree(mockMvc.perform(get("/api/movies/" + origin.getId() + "/similar")
                        .header("Authorization", userToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode detail = objectMapper.readTree(mockMvc.perform(get("/api/movies/" + candidate.getId())
                        .header("Authorization", userToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertEquals(1, similar.size());
        assertEquals(detail, similar.get(0));
    }

    /**
     * Sin caché en el servidor: tras cambiar los géneros de una película con el
     * {@code PUT} del panel, deja de ser similar (o pasa a serlo) en la llamada
     * siguiente; tras borrar una candidata, desaparece; tras borrar la de origen,
     * 404.
     */
    @Test
    void losCambiosDelPanelSeVenEnLaLlamadaSiguiente() throws Exception {
        Movie origin = saveMovie("Origen", 2000, drama);
        Movie stays = saveMovie("Se queda", 2001, drama);
        Movie changes = saveMovie("Cambia de genero", 2001, drama);
        Movie joins = saveMovie("Se une", 2001, comedy);
        assertEquals(List.of(stays.getId(), changes.getId()).stream().sorted().toList(),
                similarIds(origin).stream().sorted().toList());

        updateGenres(changes, comedy);
        updateGenres(joins, drama);
        assertEquals(List.of(stays.getId(), joins.getId()).stream().sorted().toList(),
                similarIds(origin).stream().sorted().toList());

        mockMvc.perform(delete("/api/movies/" + stays.getId()).header("Authorization", adminToken))
                .andExpect(status().isNoContent());
        assertEquals(List.of(joins.getId()), similarIds(origin));

        mockMvc.perform(delete("/api/movies/" + origin.getId()).header("Authorization", adminToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/movies/" + origin.getId() + "/similar").header("Authorization", userToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private List<Long> similarIds(Movie origin) throws Exception {
        String json = mockMvc.perform(get("/api/movies/" + origin.getId() + "/similar")
                        .header("Authorization", userToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Long> ids = new java.util.ArrayList<>();
        objectMapper.readTree(json).forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }

    /** Cambia los géneros de una película con el {@code PUT} real del panel (como ADMIN). */
    private void updateGenres(Movie movie, Genre... genres) throws Exception {
        Map<String, Object> body = Map.of("title", movie.getTitle(), "description", movie.getDescription(),
                "duration", movie.getDuration(), "releaseYear", movie.getReleaseYear(),
                "imageUrl", movie.getImageUrl(), "videoUrl", movie.getVideoUrl(),
                "genreIds", java.util.Arrays.stream(genres).map(Genre::getId).toList());
        mockMvc.perform(put("/api/movies/" + movie.getId()).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    /** Ejecuta la petición como USER, exige el estado y devuelve el cuerpo de error. */
    private JsonNode errorBody(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        String body = mockMvc.perform(request.header("Authorization", userToken))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    /** Mismo estado, código y mensaje (la ruta, lógicamente, cambia). */
    private static void assertSameError(JsonNode expected, JsonNode actual) {
        assertEquals(expected.get("status"), actual.get("status"));
        assertEquals(expected.get("code"), actual.get("code"));
        assertEquals(expected.get("message"), actual.get("message"));
    }

    private User saveUser(String name, Role role) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(role);
        return userRepository.save(user);
    }

    private Genre saveGenre(String name) {
        Genre genre = new Genre();
        genre.setName(name);
        return genreRepository.save(genre);
    }

    private Movie saveMovie(String title, int year, Genre... genres) {
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setDescription("Descripción de " + title);
        movie.setDuration(100);
        movie.setReleaseYear(year);
        movie.setImageUrl("https://example.com/image.jpg");
        movie.setVideoUrl("https://example.com/video.mp4");
        movie.setGenres(new HashSet<>(List.of(genres)));
        return movieRepository.save(movie);
    }

    /** Vacía las tablas respetando las claves foráneas (las series antes que los géneros). */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
