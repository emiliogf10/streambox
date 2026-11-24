package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * Revisión independiente (QA) del <b>historial de visionado</b>
 * ({@code GET /api/users/me/watch-history}) y de las <b>cifras</b>
 * ({@code GET /api/users/me/watch-stats}) del perfil: los casos límite que
 * {@link WatchHistoryIntegrationTest} no cubre.
 *
 * <ul>
 *   <li><b>{@code limit} raros:</b> vacío (usa el de por defecto), decimal,
 *       un carácter nulo, un número que no cabe en un {@code int}, repetido.
 *       Ninguno acaba en 500 y el error tiene el formato
 *       {@code ErrorResponse} sin detalles internos.</li>
 *   <li><b>Métodos:</b> las dos rutas son de solo lectura (405 para el resto)
 *       y {@code HEAD} responde como {@code GET}.</li>
 *   <li><b>Propiedad (IDOR):</b> ni un ADMIN ni un USER pueden leer el
 *       historial o las cifras de otro poniendo su id en la URL o en un
 *       parámetro; una cuenta pendiente de confirmar o borrada no usa su token
 *       aún vivo.</li>
 *   <li><b>Datos:</b> orden estable con empates de fecha, la temporada de la
 *       entrada de una serie es la del episodio tocado, y si se borra ese
 *       episodio la serie no desaparece del historial (la ocupa el anterior).</li>
 * </ul>
 *
 * <p>
 * Sin {@code @Transactional}: el progreso se guarda con {@code JdbcClient}
 * (commit real), así que se limpia en {@code @BeforeEach}/{@code @AfterEach}.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class WatchHistoryQaEdgeCasesIntegrationTest {

    private static final String HISTORY = "/api/users/me/watch-history";
    private static final String STATS = "/api/users/me/watch-stats";
    private static final String MOVIE_PROGRESS = "/api/users/me/progress/movies";
    private static final String EPISODE_PROGRESS = "/api/users/me/progress/episodes";
    private static final Instant BASE = Instant.parse("2026-03-01T00:00:00Z");

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
    private Genre drama;
    private Movie dune;
    private Series dark;
    private Episode dark11;
    private Episode dark21;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        alice = saveUser("qahistalice", Role.USER, true);
        aliceToken = bearer(alice);
        drama = saveGenre("Drama QA historial");
        dune = saveMovie("Dune");
        dark = saveSeries("Dark");
        dark11 = saveEpisode(dark, 1, 1);
        dark21 = saveEpisode(dark, 2, 1);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // limit raros
    // ------------------------------------------------------------------

    /**
     * {@code limit} vacío se trata como ausente: el valor por defecto (12), no
     * un 400 ni un 500. Con 13 películas empezadas salen 12.
     */
    @Test
    void unLimitVacioUsaElValorPorDefecto() throws Exception {
        for (int i = 0; i < 13; i++) {
            Movie movie = saveMovie("Peli QA " + i);
            save(aliceToken, MOVIE_PROGRESS, movie.getId(), 10, 100);
        }

        mockMvc.perform(get(HISTORY).param("limit", "").header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(12));
    }

    /**
     * Un {@code limit} que no es un entero de {@code int} (decimal, un
     * carácter nulo, uno que desborda, letras detrás o solo un espacio) es un
     * 400 {@code VALIDATION_ERROR} con el formato {@code ErrorResponse}
     * completo y el motivo en {@code validationErrors.limit}; nunca un 500 ni
     * el texto de una excepción de Java.
     */
    @ParameterizedTest
    @ValueSource(strings = { "1.5", "%00", "\u0000", "99999999999", "2147483648", "-2147483649", "12abc", " " })
    void unLimitQueNoEsUnEnteroDa400SinDetallesInternos(String limit) throws Exception {
        String body = mockMvc.perform(get(HISTORY).param("limit", limit).header("Authorization", aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value(HISTORY))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.validationErrors.limit").value("El valor no tiene el formato esperado"))
                .andReturn().getResponse().getContentAsString();

        for (String leak : List.of("Exception", "NumberFormat", "java.", "org.springframework", "For input")) {
            assertFalse(body.contains(leak), "el error no debe filtrar «" + leak + "»: " + body);
        }
    }

    /**
     * Los extremos de {@code int} que sí son enteros válidos pero quedan fuera
     * de 1–24 dan el mensaje del rango, no el de formato.
     */
    @Test
    void losExtremosDeIntDanElMensajeDelRango() throws Exception {
        mockMvc.perform(get(HISTORY).param("limit", String.valueOf(Integer.MAX_VALUE))
                        .header("Authorization", aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.limit").value("El límite máximo es 24"));
        mockMvc.perform(get(HISTORY).param("limit", String.valueOf(Integer.MIN_VALUE))
                        .header("Authorization", aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.limit").value("El límite mínimo es 1"));
    }

    /**
     * {@code limit} repetido no acaba en 500: el conversor de Spring se queda
     * con el primer valor ({@code ArrayToObjectConverter}), así que
     * {@code ?limit=1&limit=24} devuelve una sola entrada. Y si el primero es
     * inválido, es el 400 de siempre (no se «salva» con el segundo).
     */
    @Test
    void unLimitRepetidoUsaElPrimero() throws Exception {
        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 10, 100);
        save(aliceToken, EPISODE_PROGRESS, dark11.getId(), 10, 100);

        mockMvc.perform(get(HISTORY).param("limit", "1", "24").header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get(HISTORY).param("limit", "0", "24").header("Authorization", aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    /**
     * Las cifras no tienen parámetros: los desconocidos (también un
     * {@code limit} absurdo) se ignoran y la respuesta es la de siempre.
     */
    @Test
    void lasCifrasIgnoranLosParametrosDesconocidos() throws Exception {
        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 100, 100);

        mockMvc.perform(get(STATS).param("limit", "%00").param("userId", "1").header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(content().json(
                        "{\"finishedMovies\":1,\"finishedEpisodes\":0,\"watchedSeconds\":100}", true));
    }

    // ------------------------------------------------------------------
    // Métodos
    // ------------------------------------------------------------------

    /**
     * Las dos rutas son de solo lectura: cualquier otro método es 405
     * {@code METHOD_NOT_ALLOWED} (con la cabecera CSRF, para que no lo pare
     * antes el filtro), no 403 ni 500.
     */
    @Test
    void losMetodosDeEscrituraDan405() throws Exception {
        for (String url : List.of(HISTORY, STATS)) {
            List<MockHttpServletRequestBuilder> requests = List.of(
                    post(url).contentType(MediaType.APPLICATION_JSON).content("{}"),
                    put(url).contentType(MediaType.APPLICATION_JSON).content("{}"),
                    patch(url).contentType(MediaType.APPLICATION_JSON).content("{}"),
                    delete(url));
            for (MockHttpServletRequestBuilder request : requests) {
                mockMvc.perform(request.header("Authorization", aliceToken)
                                .header("X-Requested-With", "StreamBox"))
                        .andExpect(status().isMethodNotAllowed())
                        .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
            }
        }
        assertEquals(0, countRows("user_movie_progress"));
    }

    /** {@code HEAD} responde como {@code GET} (las reglas de lectura admiten los dos). */
    @Test
    void headDeLasDosRutasFunciona() throws Exception {
        mockMvc.perform(head(HISTORY).header("Authorization", aliceToken)).andExpect(status().isOk());
        mockMvc.perform(head(STATS).header("Authorization", aliceToken)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Propiedad y sesión
    // ------------------------------------------------------------------

    /**
     * Ni un ADMIN ni un USER tienen una ruta con el id de otro usuario (404,
     * sin datos: la regla de ADMIN de {@code /api/users} es de coincidencia
     * exacta, así que estas rutas caen en «autenticado» y no existen), y un
     * parámetro {@code userId} en la suya se ignora: ve solo lo suyo (aquí,
     * nada).
     */
    @Test
    void nadieLeeElHistorialNiLasCifrasDeOtroPorUrlNiPorParametro() throws Exception {
        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 100, 100);
        String adminToken = bearer(saveUser("qahistadmin", Role.ADMIN, true));
        String bobToken = bearer(saveUser("qahistbob", Role.USER, true));
        List<String> othersUrls = List.of("/api/users/" + alice.getId() + "/watch-history",
                "/api/users/" + alice.getId() + "/watch-stats");

        for (String url : othersUrls) {
            for (String token : List.of(adminToken, bobToken)) {
                mockMvc.perform(get(url).header("Authorization", token))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").exists())
                        .andExpect(jsonPath("$.finishedMovies").doesNotExist())
                        .andExpect(jsonPath("$[0]").doesNotExist());
            }
        }
        for (String token : List.of(adminToken, bobToken)) {
            mockMvc.perform(get(HISTORY).param("userId", alice.getId().toString()).header("Authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(content().json("[]", true));
            mockMvc.perform(get(STATS).param("userId", alice.getId().toString()).header("Authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(content().json(
                            "{\"finishedMovies\":0,\"finishedEpisodes\":0,\"watchedSeconds\":0}", true));
        }
    }

    /**
     * Una cuenta pendiente de confirmar el correo no usa un token aún vivo, y
     * el token de una cuenta borrada tampoco sirve: 401 en las dos rutas.
     */
    @Test
    void unaCuentaPendienteOBorradaNoLeeNada() throws Exception {
        String pendingToken = bearer(saveUser("qahistpending", Role.USER, false));
        User gone = saveUser("qahistgone", Role.USER, true);
        String goneToken = bearer(gone);
        userRepository.deleteById(gone.getId());

        for (String token : List.of(pendingToken, goneToken)) {
            for (String url : List.of(HISTORY, STATS)) {
                mockMvc.perform(get(url).header("Authorization", token))
                        .andExpect(status().isUnauthorized())
                        .andExpect(jsonPath("$.status").value(401));
            }
        }
    }

    // ------------------------------------------------------------------
    // Datos
    // ------------------------------------------------------------------

    /**
     * Con la misma fecha de guardado, el orden del historial es estable y el
     * documentado (películas primero y, dentro de cada tipo, id mayor), igual
     * en peticiones repetidas, y {@code limit} corta respetándolo.
     */
    @Test
    void conLaMismaFechaElOrdenEsEstable() throws Exception {
        Movie alien = saveMovie("Alien");
        Series lost = saveSeries("Lost");
        Episode lost11 = saveEpisode(lost, 1, 1);
        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 10, 100);
        save(aliceToken, MOVIE_PROGRESS, alien.getId(), 100, 100);
        save(aliceToken, EPISODE_PROGRESS, dark11.getId(), 10, 100);
        save(aliceToken, EPISODE_PROGRESS, lost11.getId(), 100, 100);
        jdbc.update("UPDATE user_movie_progress SET updated_at = ?", OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));
        jdbc.update("UPDATE user_episode_progress SET updated_at = ?",
                OffsetDateTime.ofInstant(BASE, ZoneOffset.UTC));

        List<String> expected = List.of("MOVIE:" + Math.max(dune.getId(), alien.getId()),
                "MOVIE:" + Math.min(dune.getId(), alien.getId()),
                "EPISODE:" + Math.max(dark11.getId(), lost11.getId()),
                "EPISODE:" + Math.min(dark11.getId(), lost11.getId()));
        for (int i = 0; i < 3; i++) {
            assertEquals(expected, keys(history(null)));
        }
        assertEquals(expected.subList(0, 3), keys(history("3")));
    }

    /**
     * La entrada de una serie lleva la temporada y el número del episodio que
     * se tocó el último (el cliente abre la serie en esa temporada), aunque se
     * haya visto antes otro de otra temporada.
     */
    @Test
    void laEntradaDeUnaSerieLlevaLaTemporadaDelUltimoEpisodioTocado() throws Exception {
        save(aliceToken, EPISODE_PROGRESS, dark11.getId(), 100, 100);
        setEpisodeUpdatedAt(dark11.getId(), BASE);
        save(aliceToken, EPISODE_PROGRESS, dark21.getId(), 30, 100);
        setEpisodeUpdatedAt(dark21.getId(), BASE.plusSeconds(60));

        JsonNode entry = history(null).get(0);
        assertEquals(dark21.getId().longValue(), entry.get("id").asLong());
        assertEquals(2, entry.get("seasonNumber").asInt());
        assertEquals(1, entry.get("episodeNumber").asInt());
        assertEquals(dark.getId().longValue(), entry.get("seriesId").asLong());
        assertFalse(entry.get("finished").asBoolean());
    }

    /**
     * Si el administrador borra el episodio que ocupaba la entrada de la
     * serie, la serie no desaparece del historial: la ocupa el otro episodio
     * con progreso (terminado o no), y las cifras dejan de contar el borrado.
     */
    @Test
    void borrarElEpisodioDeLaEntradaDejaLaSerieConElAnterior() throws Exception {
        save(aliceToken, EPISODE_PROGRESS, dark11.getId(), 100, 100);
        setEpisodeUpdatedAt(dark11.getId(), BASE);
        save(aliceToken, EPISODE_PROGRESS, dark21.getId(), 100, 100);
        setEpisodeUpdatedAt(dark21.getId(), BASE.plusSeconds(60));
        mockMvc.perform(get(STATS).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.finishedEpisodes").value(2));

        episodeRepository.deleteById(dark21.getId());

        assertEquals(List.of("EPISODE:" + dark11.getId()), keys(history(null)));
        mockMvc.perform(get(STATS).header("Authorization", aliceToken))
                .andExpect(content().json(
                        "{\"finishedMovies\":0,\"finishedEpisodes\":1,\"watchedSeconds\":100}", true));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private JsonNode history(String limit) throws Exception {
        MockHttpServletRequestBuilder request = get(HISTORY).header("Authorization", aliceToken);
        if (limit != null) {
            request.param("limit", limit);
        }
        String json = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private static List<String> keys(JsonNode history) {
        return history.valueStream().map(node -> node.get("type").asText() + ":" + node.get("id").asLong()).toList();
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateToken(user);
    }

    private void save(String token, String base, long id, int position, int duration) throws Exception {
        mockMvc.perform(put(base + "/" + id).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positionSeconds\":" + position + ",\"durationSeconds\":" + duration + "}"))
                .andExpect(status().isNoContent());
    }

    private int countRows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class,
                alice.getId());
    }

    private void setEpisodeUpdatedAt(long episodeId, Instant at) {
        jdbc.update("UPDATE user_episode_progress SET updated_at = ? WHERE user_id = ? AND episode_id = ?",
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC), alice.getId(), episodeId);
    }

    private User saveUser(String name, Role role, boolean verified) {
        User user = new User();
        user.setEmailVerifiedAt(verified ? Instant.now() : null);
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

    private Movie saveMovie(String title) {
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setDescription("Descripción de " + title);
        movie.setDuration(100);
        movie.setReleaseYear(2000);
        movie.setImageUrl("https://example.com/image.jpg");
        movie.setVideoUrl("https://example.com/" + title.replace(' ', '-') + ".mp4");
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

    private Episode saveEpisode(Series series, int season, int number) {
        Episode episode = new Episode();
        episode.setSeries(series);
        episode.setSeasonNumber(season);
        episode.setEpisodeNumber(number);
        episode.setTitle("Episodio " + season + "x" + number);
        episode.setDuration(45);
        episode.setVideoUrl("https://example.com/" + season + "x" + number + ".mp4");
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
