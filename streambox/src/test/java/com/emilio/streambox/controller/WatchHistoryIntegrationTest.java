package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests de punta a punta del <b>historial de visionado</b>
 * ({@code GET /api/users/me/watch-history}) y de las <b>cifras</b>
 * ({@code GET /api/users/me/watch-stats}) del perfil, con datos confirmados de
 * verdad (sin {@code @Transactional}) y limpieza antes y después de cada test.
 *
 * <p>
 * Cubren el contrato HTTP que consume el frontend: la forma exacta del JSON
 * (película y episodio, nulos presentes en la película, {@code finished}),
 * vacío y ceros, el orden y el {@code limit} (por defecto 12, de 1 a 24; fuera
 * de rango o no numérico, 400 {@code VALIDATION_ERROR}), una entrada por
 * serie, que lo terminado y lo que no tiene vídeo sí salen, el aislamiento
 * entre usuarios (también para un ADMIN), la sesión (401) y que «Quitar de
 * Seguir viendo» lo quita también de aquí (es la misma fila). Los casos finos
 * de las consultas (empates, cascadas, desbordamiento) están en
 * {@code repository.WatchProgressQueryContract} (H2 y PostgreSQL).
 * </p>
 *
 * <p>
 * Usa las mismas propiedades que {@link WatchProgressIntegrationTest} para
 * compartir su contexto de Spring.
 * </p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class WatchHistoryIntegrationTest {

    private static final String HISTORY = "/api/users/me/watch-history";
    private static final String STATS = "/api/users/me/watch-stats";
    private static final String MOVIE_PROGRESS = "/api/users/me/progress/movies";
    private static final String EPISODE_PROGRESS = "/api/users/me/progress/episodes";
    private static final String CONTINUE = "/api/users/me/continue-watching";

    /** Las claves exactas de cada entrada del historial (el contrato del frontend). */
    private static final Set<String> ENTRY_FIELDS = Set.of("type", "id", "title", "imageUrl", "seasonNumber",
            "episodeNumber", "seriesId", "seriesTitle", "positionSeconds", "durationSeconds", "finished",
            "updatedAt");

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private MovieRepository movieRepository;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User alice;
    private String aliceToken;
    private String bobToken;
    private Genre drama;
    private Movie dune;
    private Movie alien;
    private Series dark;
    private Episode dark11;
    private Episode dark12;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        alice = saveUser("histalice", Role.USER);
        aliceToken = token(alice);
        bobToken = token(saveUser("histbob", Role.USER));
        drama = saveGenre("Drama");
        dune = saveMovie("Dune", "https://example.com/dune.mp4");
        alien = saveMovie("Alien", "https://example.com/alien.mp4");
        dark = saveSeries("Dark");
        dark11 = saveEpisode(dark, 1, 1, "https://example.com/1x01.mp4");
        dark12 = saveEpisode(dark, 1, 2, "https://example.com/1x02.mp4");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Vacío y forma del JSON
    // ------------------------------------------------------------------

    /** Sin progreso: historial vacío (200, no 204 ni 404) y las tres cifras a 0. */
    @Test
    void sinProgresoElHistorialEstaVacioYLasCifrasSonCeros() throws Exception {
        mockMvc.perform(get(HISTORY).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(content().json("[]", true));
        mockMvc.perform(get(STATS).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(content().json(
                        "{\"finishedMovies\":0,\"finishedEpisodes\":0,\"watchedSeconds\":0}", true));
    }

    /**
     * La forma exacta de cada entrada: en una película, los cuatro campos de
     * serie y temporada salen <b>presentes y a {@code null}</b>; en un
     * episodio, {@code id}/{@code title} son los del episodio y
     * {@code imageUrl}/{@code seriesId}/{@code seriesTitle} los de la serie.
     * Lo terminado sale con {@code finished} verdadero.
     */
    @Test
    void cadaEntradaTieneLaFormaDelContrato() throws Exception {
        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 1325, 8160);
        save(aliceToken, EPISODE_PROGRESS, dark12.getId(), 2700, 2700);
        setUpdatedAt("user_movie_progress", "movie_id", dune.getId(), BASE);
        setUpdatedAt("user_episode_progress", "episode_id", dark12.getId(), BASE.plusSeconds(1));

        JsonNode history = json(get(HISTORY).header("Authorization", aliceToken));

        assertEquals(2, history.size());
        for (JsonNode entry : history) {
            Set<String> fields = new HashSet<>();
            entry.fieldNames().forEachRemaining(fields::add);
            assertEquals(ENTRY_FIELDS, fields);
        }
        JsonNode episode = history.get(0);
        assertEquals("EPISODE", episode.get("type").asText());
        assertEquals(dark12.getId(), episode.get("id").asLong());
        assertEquals("Episodio 1x2", episode.get("title").asText());
        assertEquals("https://example.com/serie.jpg", episode.get("imageUrl").asText());
        assertEquals(1, episode.get("seasonNumber").asInt());
        assertEquals(2, episode.get("episodeNumber").asInt());
        assertEquals(dark.getId(), episode.get("seriesId").asLong());
        assertEquals("Dark", episode.get("seriesTitle").asText());
        assertEquals(2700, episode.get("positionSeconds").asInt());
        assertEquals(2700, episode.get("durationSeconds").asInt());
        assertTrue(episode.get("finished").asBoolean());
        assertEquals("2026-01-01T00:00:01Z", episode.get("updatedAt").asText());

        JsonNode movie = history.get(1);
        assertEquals("MOVIE", movie.get("type").asText());
        assertEquals(dune.getId(), movie.get("id").asLong());
        assertEquals("Dune", movie.get("title").asText());
        assertEquals("https://example.com/image.jpg", movie.get("imageUrl").asText());
        for (String field : List.of("seasonNumber", "episodeNumber", "seriesId", "seriesTitle")) {
            assertTrue(movie.get(field).isNull(), field);
        }
        assertEquals(1325, movie.get("positionSeconds").asInt());
        assertEquals(8160, movie.get("durationSeconds").asInt());
        assertEquals(false, movie.get("finished").asBoolean());
        assertEquals("2026-01-01T00:00:00Z", movie.get("updatedAt").asText());
    }

    /**
     * Las cifras: terminado desde el 95 % (cada episodio por separado) y el
     * tiempo visto suma la duración de lo terminado y la posición de lo que
     * está a medias.
     */
    @Test
    void lasCifrasCuentanTerminadosYSumanDuracionOPosicion() throws Exception {
        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 1325, 8160);
        save(aliceToken, MOVIE_PROGRESS, alien.getId(), 95, 100);
        save(aliceToken, EPISODE_PROGRESS, dark11.getId(), 2700, 2700);
        save(aliceToken, EPISODE_PROGRESS, dark12.getId(), 100, 2700);

        mockMvc.perform(get(STATS).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"finishedMovies\":1,\"finishedEpisodes\":1,\"watchedSeconds\":"
                        + (1325 + 100 + 2700 + 100) + "}", true));
    }

    // ------------------------------------------------------------------
    // Qué entra y en qué orden
    // ------------------------------------------------------------------

    /**
     * Una entrada por serie: la de su último episodio tocado, con su propio
     * progreso (aquí, el 1x01 terminado después del 1x02 a medias). El
     * historial no salta al siguiente como «Seguir viendo».
     */
    @Test
    void unaEntradaPorSerieConSuUltimoEpisodioTocado() throws Exception {
        save(aliceToken, EPISODE_PROGRESS, dark12.getId(), 100, 2700);
        save(aliceToken, EPISODE_PROGRESS, dark11.getId(), 2700, 2700);
        setUpdatedAt("user_episode_progress", "episode_id", dark12.getId(), BASE);
        setUpdatedAt("user_episode_progress", "episode_id", dark11.getId(), BASE.plusSeconds(1));

        JsonNode history = json(get(HISTORY).header("Authorization", aliceToken));

        assertEquals(1, history.size());
        assertEquals(dark11.getId(), history.get(0).get("id").asLong());
        assertTrue(history.get(0).get("finished").asBoolean());
    }

    /**
     * Un título que el administrador deja sin vídeo sigue en el historial y en
     * las cifras (lo visto sigue siendo verdad), aunque haya salido de «Seguir
     * viendo».
     */
    @Test
    void unTituloSinVideoSiSaleEnElHistorialYEnLasCifras() throws Exception {
        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 10, 100);
        save(aliceToken, EPISODE_PROGRESS, dark11.getId(), 100, 100);
        jdbc.update("UPDATE movies SET video_url = NULL WHERE id = ?", dune.getId());
        jdbc.update("UPDATE episodes SET video_url = NULL WHERE id = ?", dark11.getId());
        jdbc.update("UPDATE episodes SET video_url = NULL WHERE id = ?", dark12.getId());

        mockMvc.perform(get(CONTINUE).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.length()").value(0));
        assertEquals(Set.of("MOVIE:" + dune.getId(), "EPISODE:" + dark11.getId()),
                keys(json(get(HISTORY).header("Authorization", aliceToken))));
        mockMvc.perform(get(STATS).header("Authorization", aliceToken))
                .andExpect(content().json(
                        "{\"finishedMovies\":0,\"finishedEpisodes\":1,\"watchedSeconds\":110}", true));
    }

    /**
     * Por defecto, 12 entradas, las más recientes primero; {@code limit}
     * recorta respetando el orden (1 deja la más reciente; 24 las trae todas).
     */
    @Test
    void elLimitePorDefectoEs12YSePuedeCambiarDe1A24() throws Exception {
        for (int i = 0; i < 13; i++) {
            Movie movie = saveMovie("Película " + i, "https://example.com/" + i + ".mp4");
            save(aliceToken, MOVIE_PROGRESS, movie.getId(), 10, 100);
            setUpdatedAt("user_movie_progress", "movie_id", movie.getId(), BASE.plusSeconds(i));
        }

        JsonNode byDefault = json(get(HISTORY).header("Authorization", aliceToken));
        assertEquals(12, byDefault.size());
        List<String> titles = new ArrayList<>();
        byDefault.forEach(entry -> titles.add(entry.get("title").asText()));
        assertEquals("Película 12", titles.get(0));
        assertEquals("Película 1", titles.get(11));

        JsonNode one = json(get(HISTORY).param("limit", "1").header("Authorization", aliceToken));
        assertEquals(1, one.size());
        assertEquals("Película 12", one.get(0).get("title").asText());
        assertEquals(13, json(get(HISTORY).param("limit", "24").header("Authorization", aliceToken)).size());
    }

    /** Un límite fuera de 1–24 da 400 con el motivo en {@code validationErrors.limit}; uno no numérico, 400. */
    @Test
    void unLimiteFueraDeRangoONoNumericoDa400() throws Exception {
        String[][] cases = { { "0", "El límite mínimo es 1" }, { "25", "El límite máximo es 24" },
                { "-3", "El límite mínimo es 1" } };
        for (String[] c : cases) {
            mockMvc.perform(get(HISTORY).param("limit", c[0]).header("Authorization", aliceToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.validationErrors.limit").value(c[1]));
        }
        mockMvc.perform(get(HISTORY).param("limit", "doce").header("Authorization", aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ------------------------------------------------------------------
    // «Quitar de Seguir viendo» también lo quita de aquí
    // ------------------------------------------------------------------

    /**
     * El historial y las cifras no tienen tabla propia: «Quitar de Seguir
     * viendo» una película o una serie borra su progreso y deja de salir y de
     * contar aquí (lo documenta la operación del {@code DELETE}).
     */
    @Test
    void quitarDeSeguirViendoLoQuitaDelHistorialYDeLasCifras() throws Exception {
        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 10, 100);
        save(aliceToken, MOVIE_PROGRESS, alien.getId(), 100, 100);
        save(aliceToken, EPISODE_PROGRESS, dark11.getId(), 100, 100);
        save(aliceToken, EPISODE_PROGRESS, dark12.getId(), 20, 100);
        assertEquals(3, json(get(HISTORY).header("Authorization", aliceToken)).size());

        remove(aliceToken, CONTINUE + "/movies/" + alien.getId());
        remove(aliceToken, CONTINUE + "/series/" + dark.getId());

        assertEquals(Set.of("MOVIE:" + dune.getId()), keys(json(get(HISTORY).header("Authorization", aliceToken))));
        mockMvc.perform(get(STATS).header("Authorization", aliceToken))
                .andExpect(content().json(
                        "{\"finishedMovies\":0,\"finishedEpisodes\":0,\"watchedSeconds\":10}", true));
    }

    // ------------------------------------------------------------------
    // Aislamiento y sesión
    // ------------------------------------------------------------------

    /**
     * Cada uno ve solo lo suyo: ni otro USER ni un ADMIN ven el historial o las
     * cifras de Alice (no hay ruta con el id de otro usuario), y el ADMIN
     * también tiene el suyo.
     */
    @Test
    void cadaUsuarioVeSoloLoSuyoTambienUnAdmin() throws Exception {
        String adminToken = token(saveUser("histadmin", Role.ADMIN));
        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 100, 100);
        save(aliceToken, EPISODE_PROGRESS, dark11.getId(), 50, 100);
        save(adminToken, MOVIE_PROGRESS, alien.getId(), 30, 100);

        for (String other : List.of(bobToken, adminToken)) {
            JsonNode history = json(get(HISTORY).header("Authorization", other));
            assertTrue(keys(history).stream().noneMatch(key -> key.equals("MOVIE:" + dune.getId())
                    || key.equals("EPISODE:" + dark11.getId())));
        }
        mockMvc.perform(get(HISTORY).header("Authorization", bobToken)).andExpect(content().json("[]", true));
        mockMvc.perform(get(STATS).header("Authorization", bobToken))
                .andExpect(content().json(
                        "{\"finishedMovies\":0,\"finishedEpisodes\":0,\"watchedSeconds\":0}", true));
        assertEquals(Set.of("MOVIE:" + alien.getId()), keys(json(get(HISTORY).header("Authorization", adminToken))));
        mockMvc.perform(get(STATS).header("Authorization", adminToken))
                .andExpect(content().json(
                        "{\"finishedMovies\":0,\"finishedEpisodes\":0,\"watchedSeconds\":30}", true));
        mockMvc.perform(get(STATS).header("Authorization", aliceToken))
                .andExpect(content().json(
                        "{\"finishedMovies\":1,\"finishedEpisodes\":0,\"watchedSeconds\":150}", true));
    }

    /** Sin sesión, las dos rutas dan 401 (también con un {@code limit} inválido: la sesión va antes). */
    @Test
    void sinSesionDa401() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(get(HISTORY), get(HISTORY).param("limit", "0"),
                get(STATS))) {
            mockMvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
    }

    // ------------------------------------------------------------------
    // OpenAPI
    // ------------------------------------------------------------------

    /**
     * El contrato publicado: {@code operationId} sin sufijos (nombres únicos
     * en la API), los esquemas de respuesta y que el {@code DELETE} de «Seguir
     * viendo» avisa de que también quita del historial.
     */
    @Test
    void elOpenApiDocumentaElHistorialYLasCifras() throws Exception {
        JsonNode paths = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("paths");

        JsonNode history = paths.get(HISTORY).get("get");
        JsonNode stats = paths.get(STATS).get("get");
        assertEquals("getWatchHistory", history.get("operationId").asText());
        assertEquals("getWatchStats", stats.get("operationId").asText());
        assertEquals("#/components/schemas/WatchHistoryItemResponse", history.at("/responses/200/content")
                .elements().next().at("/schema/items/$ref").asText());
        assertEquals("#/components/schemas/WatchStatsResponse", stats.at("/responses/200/content")
                .elements().next().at("/schema/$ref").asText());
        for (String path : List.of(CONTINUE + "/movies/{movieId}", CONTINUE + "/series/{seriesId}")) {
            assertTrue(paths.get(path).get("delete").get("description").asText().contains(HISTORY), path);
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Identificación compacta de cada entrada: {@code TIPO:id}. */
    private static Set<String> keys(JsonNode history) {
        Set<String> keys = new HashSet<>();
        history.forEach(entry -> keys.add(entry.get("type").asText() + ":" + entry.get("id").asLong()));
        return keys;
    }

    private void save(String token, String base, long id, int position, int duration) throws Exception {
        mockMvc.perform(put(base + "/" + id).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"positionSeconds\":" + position + ",\"durationSeconds\":" + duration + "}"))
                .andExpect(status().isNoContent());
    }

    private void remove(String token, String url) throws Exception {
        mockMvc.perform(delete(url).header("Authorization", token))
                .andExpect(status().isNoContent());
    }

    private JsonNode json(MockHttpServletRequestBuilder request) throws Exception {
        String json = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private void setUpdatedAt(String table, String column, long id, Instant at) {
        jdbc.update("UPDATE " + table + " SET updated_at = ? WHERE user_id = ? AND " + column + " = ?",
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC), alice.getId(), id);
    }

    private String token(User user) {
        return "Bearer " + jwtService.generateToken(user);
    }

    private User saveUser(String name, Role role) {
        User user = new User();
        user.setEmailVerifiedAt(Instant.now()); // cuenta verificada (V9)
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

    private Movie saveMovie(String title, String videoUrl) {
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setDescription("Descripción de " + title);
        movie.setDuration(100);
        movie.setReleaseYear(2000);
        movie.setImageUrl("https://example.com/image.jpg");
        movie.setVideoUrl(videoUrl);
        movie.setGenres(new HashSet<>(List.of(drama)));
        return movieRepository.save(movie);
    }

    private Series saveSeries(String title) {
        Series series = new Series();
        series.setTitle(title);
        series.setDescription("Sinopsis de " + title);
        series.setReleaseYear(2010);
        series.setImageUrl("https://example.com/serie.jpg");
        series.setGenres(new HashSet<>(List.of(drama)));
        return seriesRepository.save(series);
    }

    private Episode saveEpisode(Series series, int season, int number, String videoUrl) {
        Episode episode = new Episode();
        episode.setSeries(series);
        episode.setSeasonNumber(season);
        episode.setEpisodeNumber(number);
        episode.setTitle("Episodio " + season + "x" + number);
        episode.setDuration(45);
        episode.setVideoUrl(videoUrl);
        return episodeRepository.save(episode);
    }

    /** Vacía las tablas respetando las claves foráneas (el progreso cae en cascada). */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
