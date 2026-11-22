package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
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

import com.emilio.streambox.dto.WatchProgressRequest;
import com.emilio.streambox.entity.Episode;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.EpisodeNotFoundException;
import com.emilio.streambox.exception.MovieNotFoundException;
import com.emilio.streambox.exception.UserNotFoundException;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.repository.WatchProgressRepository;
import com.emilio.streambox.security.JwtService;
import com.emilio.streambox.service.WatchProgressService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

/**
 * Tests de punta a punta del progreso de visionado
 * ({@code /api/users/me/progress/...}) y de «Seguir viendo»
 * ({@code /api/users/me/continue-watching/...}), con datos confirmados de
 * verdad (sin {@code @Transactional}) y limpieza antes y después de cada test.
 *
 * <p>
 * Cubren el contrato HTTP: guardar es idempotente (204, gana el último), la
 * validación (400 con el campo), el 404 del título inexistente (mismo cuerpo
 * que su detalle), el 409 {@code NO_VIDEO_AVAILABLE}, el {@code GET} sin
 * progreso (204), la serie oculta indistinguible de una inexistente, que lo
 * terminado (≥ 95 %) sale de «Seguir viendo», el límite, que lo de otro
 * usuario no se ve ni se toca, la sesión (401), la defensa CSRF (403 con
 * cookie sin {@code X-Requested-With}) y las carreras con el borrado del
 * título o de la cuenta. Los casos finos de las consultas están en
 * {@code repository.WatchProgressQueryContract} (H2 y PostgreSQL).
 * </p>
 *
 * <p>
 * Usa las mismas propiedades que {@link LikesIntegrationTest} para compartir su
 * contexto de Spring.
 * </p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class WatchProgressIntegrationTest {

    private static final String PROGRESS = "/api/users/me/progress";
    private static final String MOVIE_PROGRESS = PROGRESS + "/movies";
    private static final String EPISODE_PROGRESS = PROGRESS + "/episodes";
    private static final String SERIES_PROGRESS = PROGRESS + "/series";
    private static final String CONTINUE = "/api/users/me/continue-watching";

    private static final Instant BASE = Instant.parse("2026-01-01T00:00:00Z");
    private static final long MISSING_ID = 987654L;

    @Autowired private MockMvc mockMvc;
    @Autowired private WatchProgressService watchProgressService;
    @Autowired private WatchProgressRepository watchProgressRepository;
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
    private User bob;
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
        alice = saveUser("progalice");
        bob = saveUser("progbob");
        aliceToken = "Bearer " + jwtService.generateToken(alice);
        bobToken = "Bearer " + jwtService.generateToken(bob);
        drama = saveGenre("Drama");
        dune = saveMovie("Dune", "https://example.com/video.mp4");
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
    // Guardar y leer: películas
    // ------------------------------------------------------------------

    /** Guardar responde 204 sin cuerpo y el GET lo devuelve con todos sus campos. */
    @Test
    void guardarElProgresoDeUnaPeliculaYLeerlo() throws Exception {
        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 1325, 8160);

        mockMvc.perform(get(MOVIE_PROGRESS + "/" + dune.getId()).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positionSeconds").value(1325))
                .andExpect(jsonPath("$.durationSeconds").value(8160))
                .andExpect(jsonPath("$.finished").value(false))
                .andExpect(jsonPath("$.updatedAt").isString());
    }

    /** Repetir el guardado sobrescribe (gana el último) sin duplicar la fila. */
    @Test
    void guardarOtraVezSobrescribeElProgreso() throws Exception {
        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 100, 8160);
        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 100, 8160);
        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 50, 8000);

        assertEquals(1, countRows("user_movie_progress", alice));
        mockMvc.perform(get(MOVIE_PROGRESS + "/" + dune.getId()).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.positionSeconds").value(50))
                .andExpect(jsonPath("$.durationSeconds").value(8000));
    }

    /** {@code updated_at} es el instante del {@link Clock} de la aplicación, no el de la base de datos. */
    @Test
    void laFechaDelGuardadoEsLaDelRelojDeLaAplicacion() {
        WatchProgressService fixedClock = new WatchProgressService(watchProgressRepository, movieRepository,
                episodeRepository, Clock.fixed(BASE, ZoneOffset.UTC));

        fixedClock.saveMovieProgress(alice.getId(), dune.getId(), new WatchProgressRequest(10, 100));
        fixedClock.saveEpisodeProgress(alice.getId(), dark11.getId(), new WatchProgressRequest(10, 100));

        assertEquals(BASE, watchProgressService.getMovieProgress(alice.getId(), dune.getId()).orElseThrow()
                .updatedAt());
        assertEquals(BASE, watchProgressService.getEpisodeProgress(alice.getId(), dark11.getId()).orElseThrow()
                .updatedAt());
    }

    /** {@code finished} es verdadero desde el 95 % exacto, no antes. */
    @Test
    void finishedEmpiezaEnEl95PorCiento() throws Exception {
        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 94, 100);
        mockMvc.perform(get(MOVIE_PROGRESS + "/" + dune.getId()).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.finished").value(false));

        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 95, 100);
        mockMvc.perform(get(MOVIE_PROGRESS + "/" + dune.getId()).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.finished").value(true));
    }

    /** Sin progreso, 204 sin cuerpo (no es un error: el título existe). */
    @Test
    void leerSinProgresoDa204SinCuerpo() throws Exception {
        for (String url : List.of(MOVIE_PROGRESS + "/" + dune.getId(), EPISODE_PROGRESS + "/" + dark11.getId())) {
            mockMvc.perform(get(url).header("Authorization", aliceToken))
                    .andExpect(status().isNoContent())
                    .andExpect(content().string(""));
        }
    }

    /** Una película inexistente da el mismo 404 que su detalle, al guardar y al leer; no guarda nada. */
    @Test
    void unaPeliculaInexistenteDaElMismo404QueSuDetalle() throws Exception {
        JsonNode detail = error(get("/api/movies/" + MISSING_ID).header("Authorization", aliceToken), 404);
        JsonNode saved = error(saveRequest(aliceToken, MOVIE_PROGRESS, MISSING_ID, 10, 100), 404);
        JsonNode read = error(get(MOVIE_PROGRESS + "/" + MISSING_ID).header("Authorization", aliceToken), 404);

        assertEquals("RESOURCE_NOT_FOUND", saved.get("code").asText());
        assertEquals("Película no encontrada", saved.get("message").asText());
        assertSameError(detail, saved);
        assertSameError(detail, read);
        assertEquals(0, countRows("user_movie_progress", alice));
    }

    /** Una película sin vídeo da 409 {@code NO_VIDEO_AVAILABLE} y no guarda nada; leerla sigue siendo 204. */
    @Test
    void unaPeliculaSinVideoDa409() throws Exception {
        Movie noVideo = saveMovie("Sin vídeo", null);

        JsonNode error = error(saveRequest(aliceToken, MOVIE_PROGRESS, noVideo.getId(), 10, 100), 409);

        assertEquals("NO_VIDEO_AVAILABLE", error.get("code").asText());
        assertEquals("Esta película no tiene vídeo: no se puede guardar por dónde vas", error.get("message").asText());
        assertEquals(409, error.get("status").asInt());
        assertEquals(0, countRows("user_movie_progress", alice));
        mockMvc.perform(get(MOVIE_PROGRESS + "/" + noVideo.getId()).header("Authorization", aliceToken))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // Guardar y leer: episodios y series
    // ------------------------------------------------------------------

    @Test
    void guardarElProgresoDeUnEpisodioYLeerlo() throws Exception {
        save(aliceToken, EPISODE_PROGRESS, dark11.getId(), 610, 2700);

        mockMvc.perform(get(EPISODE_PROGRESS + "/" + dark11.getId()).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positionSeconds").value(610))
                .andExpect(jsonPath("$.durationSeconds").value(2700))
                .andExpect(jsonPath("$.finished").value(false));
    }

    /** Un episodio inexistente da 404 «Episodio no encontrado» al guardar y al leer. */
    @Test
    void unEpisodioInexistenteDa404() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(
                saveRequest(aliceToken, EPISODE_PROGRESS, MISSING_ID, 10, 100),
                get(EPISODE_PROGRESS + "/" + MISSING_ID).header("Authorization", aliceToken))) {
            JsonNode error = error(request, 404);
            assertEquals("RESOURCE_NOT_FOUND", error.get("code").asText());
            assertEquals("Episodio no encontrado", error.get("message").asText());
        }
        assertEquals(0, countRows("user_episode_progress", alice));
    }

    @Test
    void unEpisodioSinVideoDa409() throws Exception {
        Episode noVideo = saveEpisode(dark, 2, 1, null);

        JsonNode error = error(saveRequest(aliceToken, EPISODE_PROGRESS, noVideo.getId(), 10, 100), 409);

        assertEquals("NO_VIDEO_AVAILABLE", error.get("code").asText());
        assertEquals("Este episodio no tiene vídeo: no se puede guardar por dónde vas",
                error.get("message").asText());
        assertEquals(0, countRows("user_episode_progress", alice));
    }

    /** El progreso de una serie: un elemento por episodio empezado, en orden, con los terminados. */
    @Test
    void elProgresoDeUnaSerieListaSusEpisodiosEmpezadosEnOrden() throws Exception {
        Episode dark21 = saveEpisode(dark, 2, 1, "https://example.com/2x01.mp4");
        save(aliceToken, EPISODE_PROGRESS, dark21.getId(), 30, 100);
        save(aliceToken, EPISODE_PROGRESS, dark11.getId(), 100, 100);

        mockMvc.perform(get(SERIES_PROGRESS + "/" + dark.getId()).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].episodeId").value(dark11.getId()))
                .andExpect(jsonPath("$[0].positionSeconds").value(100))
                .andExpect(jsonPath("$[0].durationSeconds").value(100))
                .andExpect(jsonPath("$[0].finished").value(true))
                .andExpect(jsonPath("$[0].updatedAt").isString())
                .andExpect(jsonPath("$[1].episodeId").value(dark21.getId()))
                .andExpect(jsonPath("$[1].finished").value(false));
    }

    @Test
    void unaSerieSinEmpezarDaUnaListaVacia() throws Exception {
        mockMvc.perform(get(SERIES_PROGRESS + "/" + dark.getId()).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * Una serie sin episodios es indistinguible de una inexistente: el mismo
     * 404, con el mismo cuerpo que el detalle de una serie que no existe.
     */
    @Test
    void unaSerieSinEpisodiosDaElMismo404QueUnaInexistenteYQueSuDetalle() throws Exception {
        Series hidden = saveSeries("Oculta");

        JsonNode hiddenProgress = error(get(SERIES_PROGRESS + "/" + hidden.getId())
                .header("Authorization", aliceToken), 404);
        JsonNode missingProgress = error(get(SERIES_PROGRESS + "/" + MISSING_ID)
                .header("Authorization", aliceToken), 404);
        JsonNode missingDetail = error(get("/api/series/" + MISSING_ID).header("Authorization", aliceToken), 404);

        assertEquals("RESOURCE_NOT_FOUND", hiddenProgress.get("code").asText());
        assertEquals("Serie no encontrada", hiddenProgress.get("message").asText());
        assertSameError(missingProgress, hiddenProgress);
        assertSameError(missingDetail, hiddenProgress);
    }

    // ------------------------------------------------------------------
    // Validación
    // ------------------------------------------------------------------

    /** Cada regla rechaza con 400 {@code VALIDATION_ERROR} en su campo y no guarda nada. */
    @ParameterizedTest
    @ValueSource(strings = { MOVIE_PROGRESS, EPISODE_PROGRESS })
    void unProgresoFueraDeRangoDa400EnSuCampo(String base) throws Exception {
        long id = base.equals(MOVIE_PROGRESS) ? dune.getId() : dark11.getId();
        String[][] cases = {
                { "{\"positionSeconds\":-1,\"durationSeconds\":100}", "positionSeconds",
                        "La posición no puede ser negativa" },
                { "{\"positionSeconds\":101,\"durationSeconds\":100}", "positionSeconds",
                        "La posición no puede ser mayor que la duración" },
                { "{\"positionSeconds\":0,\"durationSeconds\":0}", "durationSeconds",
                        "La duración debe ser de al menos 1 segundo" },
                { "{\"positionSeconds\":10,\"durationSeconds\":86401}", "durationSeconds",
                        "La duración no puede superar las 24 horas" },
                { "{\"durationSeconds\":100}", "positionSeconds", "La posición es obligatoria" },
                { "{\"positionSeconds\":10,\"durationSeconds\":null}", "durationSeconds",
                        "La duración es obligatoria" },
        };
        for (String[] c : cases) {
            mockMvc.perform(put(base + "/" + id).header("Authorization", aliceToken)
                    .contentType(MediaType.APPLICATION_JSON).content(c[0]))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.validationErrors.length()").value(1))
                    .andExpect(jsonPath("$.validationErrors." + c[1]).value(c[2]));
        }
        assertEquals(0, countRows("user_movie_progress", alice) + countRows("user_episode_progress", alice));
    }

    /**
     * Un decimal o un texto en un campo entero es 400 {@code MALFORMED_REQUEST}
     * (no se redondea), y un cuerpo vacío también es 400.
     */
    @Test
    void unValorQueNoEsEnteroOUnCuerpoVacioDa400() throws Exception {
        for (String body : List.of("{\"positionSeconds\":12.5,\"durationSeconds\":100}",
                "{\"positionSeconds\":\"12\",\"durationSeconds\":100}", "")) {
            mockMvc.perform(put(MOVIE_PROGRESS + "/" + dune.getId()).header("Authorization", aliceToken)
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        }
        assertEquals(0, countRows("user_movie_progress", alice));
    }

    /** La validación va antes que el 404: un cuerpo inválido sobre un id inexistente es 400. */
    @Test
    void laValidacionVaAntesQueLaExistenciaDelTitulo() throws Exception {
        mockMvc.perform(put(MOVIE_PROGRESS + "/" + MISSING_ID).header("Authorization", aliceToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"positionSeconds\":-1,\"durationSeconds\":100}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void unIdNoNumericoDa400() throws Exception {
        List<MockHttpServletRequestBuilder> requests = List.of(
                put(MOVIE_PROGRESS + "/abc").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positionSeconds\":1,\"durationSeconds\":2}"),
                get(EPISODE_PROGRESS + "/1.5"), get(SERIES_PROGRESS + "/x"),
                delete(CONTINUE + "/movies/abc"), delete(CONTINUE + "/series/abc"));
        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(request.header("Authorization", aliceToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    // ------------------------------------------------------------------
    // «Seguir viendo»
    // ------------------------------------------------------------------

    @Test
    void sinNadaAMedioVerLaFilaEstaVacia() throws Exception {
        mockMvc.perform(get(CONTINUE).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * Mezcla películas y episodios del guardado más reciente al más antiguo,
     * con todos los campos de la tarjeta (los de serie, a {@code null} en las
     * películas).
     */
    @Test
    void laFilaMezclaPeliculasYEpisodiosDelMasRecienteAlMasAntiguo() throws Exception {
        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 1325, 8160);
        save(aliceToken, EPISODE_PROGRESS, dark12.getId(), 600, 2700);
        save(aliceToken, MOVIE_PROGRESS, alien.getId(), 30, 6000);
        setUpdatedAt("user_movie_progress", "movie_id", dune.getId(), BASE.plusSeconds(120));
        setUpdatedAt("user_episode_progress", "episode_id", dark12.getId(), BASE.plusSeconds(60));
        setUpdatedAt("user_movie_progress", "movie_id", alien.getId(), BASE);

        mockMvc.perform(get(CONTINUE).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].type").value("MOVIE"))
                .andExpect(jsonPath("$[0].id").value(dune.getId()))
                .andExpect(jsonPath("$[0].title").value("Dune"))
                .andExpect(jsonPath("$[0].imageUrl").value("https://example.com/image.jpg"))
                .andExpect(jsonPath("$[0].seasonNumber").doesNotExist())
                .andExpect(jsonPath("$[0].seriesId").doesNotExist())
                .andExpect(jsonPath("$[0].positionSeconds").value(1325))
                .andExpect(jsonPath("$[0].durationSeconds").value(8160))
                .andExpect(jsonPath("$[0].updatedAt").value("2026-01-01T00:02:00Z"))
                .andExpect(jsonPath("$[1].type").value("EPISODE"))
                .andExpect(jsonPath("$[1].id").value(dark12.getId()))
                .andExpect(jsonPath("$[1].title").value("Episodio 1x2"))
                .andExpect(jsonPath("$[1].imageUrl").value("https://example.com/serie.jpg"))
                .andExpect(jsonPath("$[1].seasonNumber").value(1))
                .andExpect(jsonPath("$[1].episodeNumber").value(2))
                .andExpect(jsonPath("$[1].seriesId").value(dark.getId()))
                .andExpect(jsonPath("$[1].seriesTitle").value("Dark"))
                .andExpect(jsonPath("$[2].id").value(alien.getId()));
    }

    /** Las claves de serie salen en el JSON de una película, con {@code null} (contrato estable). */
    @Test
    void enUnaPeliculaLosCamposDeSerieVanANull() throws Exception {
        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 10, 100);

        JsonNode item = json(get(CONTINUE).header("Authorization", aliceToken)).get(0);

        for (String field : List.of("seasonNumber", "episodeNumber", "seriesId", "seriesTitle")) {
            assertTrue(item.has(field), field);
            assertTrue(item.get(field).isNull(), field);
        }
        assertFalse(item.has("finished"));
    }

    /** Una sola tarjeta por serie: la del episodio guardado más recientemente. */
    @Test
    void unaSolaTarjetaPorSerie() throws Exception {
        save(aliceToken, EPISODE_PROGRESS, dark11.getId(), 10, 100);
        save(aliceToken, EPISODE_PROGRESS, dark12.getId(), 10, 100);
        setUpdatedAt("user_episode_progress", "episode_id", dark11.getId(), BASE.plusSeconds(60));
        setUpdatedAt("user_episode_progress", "episode_id", dark12.getId(), BASE);

        mockMvc.perform(get(CONTINUE).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(dark11.getId()));
    }

    /**
     * Al terminar (≥ 95 %) desaparece de «Seguir viendo», en películas y en
     * episodios; el 94 % sigue dentro. El progreso terminado se conserva.
     */
    @Test
    void loTerminadoDesapareceDeSeguirViendo() throws Exception {
        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 94, 100);
        save(aliceToken, EPISODE_PROGRESS, dark11.getId(), 94, 100);
        assertEquals(2, json(get(CONTINUE).header("Authorization", aliceToken)).size());

        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 95, 100);
        save(aliceToken, EPISODE_PROGRESS, dark11.getId(), 100, 100);

        mockMvc.perform(get(CONTINUE).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get(MOVIE_PROGRESS + "/" + dune.getId()).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.finished").value(true));
    }

    /** Si el administrador quita el vídeo, la tarjeta se oculta (y vuelve si el vídeo vuelve). */
    @Test
    void unTituloQueSeQuedaSinVideoSaleDeLaFila() throws Exception {
        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 10, 100);
        jdbc.update("UPDATE movies SET video_url = NULL WHERE id = ?", dune.getId());

        mockMvc.perform(get(CONTINUE).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(saveRequest(aliceToken, MOVIE_PROGRESS, dune.getId(), 20, 100))
                .andExpect(status().isConflict());
    }

    /** Por defecto, como mucho 10; {@code limit} recorta respetando el orden. */
    @Test
    void elLimitePorDefectoEs10YSePuedeCambiar() throws Exception {
        for (int i = 0; i < 11; i++) {
            Movie movie = saveMovie("Película " + i, "https://example.com/" + i + ".mp4");
            save(aliceToken, MOVIE_PROGRESS, movie.getId(), 10, 100);
            setUpdatedAt("user_movie_progress", "movie_id", movie.getId(), BASE.plusSeconds(i));
        }

        assertEquals(10, json(get(CONTINUE).header("Authorization", aliceToken)).size());
        JsonNode one = json(get(CONTINUE).param("limit", "1").header("Authorization", aliceToken));
        assertEquals(1, one.size());
        assertEquals("Película 10", one.get(0).get("title").asText());
        assertEquals(11, json(get(CONTINUE).param("limit", "24").header("Authorization", aliceToken)).size());
    }

    @Test
    void unLimiteFueraDeRangoDa400() throws Exception {
        String[][] cases = { { "0", "El límite mínimo es 1" }, { "25", "El límite máximo es 24" },
                { "-3", "El límite mínimo es 1" } };
        for (String[] c : cases) {
            mockMvc.perform(get(CONTINUE).param("limit", c[0]).header("Authorization", aliceToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.validationErrors.limit").value(c[1]));
        }
        mockMvc.perform(get(CONTINUE).param("limit", "diez").header("Authorization", aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ------------------------------------------------------------------
    // Quitar de «Seguir viendo»
    // ------------------------------------------------------------------

    /** Quitar una película borra su progreso; repetirlo o con un id inexistente también es 204. */
    @Test
    void quitarUnaPeliculaEsIdempotenteYNuncaDa404() throws Exception {
        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 10, 100);

        remove(aliceToken, CONTINUE + "/movies/" + dune.getId());
        remove(aliceToken, CONTINUE + "/movies/" + dune.getId());
        remove(aliceToken, CONTINUE + "/movies/" + MISSING_ID);

        assertEquals(0, countRows("user_movie_progress", alice));
        mockMvc.perform(get(MOVIE_PROGRESS + "/" + dune.getId()).header("Authorization", aliceToken))
                .andExpect(status().isNoContent());
    }

    /**
     * Quitar una serie borra el progreso de <b>todos</b> sus episodios: no la
     * sustituye otro episodio a medias. Otras series y las películas no se
     * tocan. Una serie oculta o inexistente también da 204.
     */
    @Test
    void quitarUnaSerieBorraElProgresoDeTodosSusEpisodios() throws Exception {
        Series lost = saveSeries("Lost");
        Episode lost11 = saveEpisode(lost, 1, 1, "https://example.com/lost.mp4");
        Series hidden = saveSeries("Oculta");
        save(aliceToken, EPISODE_PROGRESS, dark11.getId(), 10, 100);
        save(aliceToken, EPISODE_PROGRESS, dark12.getId(), 10, 100);
        save(aliceToken, EPISODE_PROGRESS, lost11.getId(), 10, 100);
        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 10, 100);

        remove(aliceToken, CONTINUE + "/series/" + dark.getId());
        remove(aliceToken, CONTINUE + "/series/" + dark.getId());
        remove(aliceToken, CONTINUE + "/series/" + hidden.getId());
        remove(aliceToken, CONTINUE + "/series/" + MISSING_ID);

        JsonNode row = json(get(CONTINUE).header("Authorization", aliceToken));
        assertEquals(2, row.size());
        assertEquals(java.util.Set.of("EPISODE:" + lost11.getId(), "MOVIE:" + dune.getId()),
                row.valueStream().map(node -> node.get("type").asText() + ":" + node.get("id").asLong())
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(1, countRows("user_episode_progress", alice));
    }

    // ------------------------------------------------------------------
    // Aislamiento entre usuarios
    // ------------------------------------------------------------------

    /** Cada usuario ve solo su progreso, y lo que hace uno no toca el del otro. */
    @Test
    void elProgresoDeOtroUsuarioNiSeVeNiSeToca() throws Exception {
        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 1000, 8000);
        save(aliceToken, EPISODE_PROGRESS, dark11.getId(), 500, 2700);

        mockMvc.perform(get(MOVIE_PROGRESS + "/" + dune.getId()).header("Authorization", bobToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(EPISODE_PROGRESS + "/" + dark11.getId()).header("Authorization", bobToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(SERIES_PROGRESS + "/" + dark.getId()).header("Authorization", bobToken))
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get(CONTINUE).header("Authorization", bobToken))
                .andExpect(jsonPath("$.length()").value(0));

        save(bobToken, MOVIE_PROGRESS, dune.getId(), 5, 8000);
        remove(bobToken, CONTINUE + "/movies/" + dune.getId());
        remove(bobToken, CONTINUE + "/series/" + dark.getId());

        mockMvc.perform(get(MOVIE_PROGRESS + "/" + dune.getId()).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.positionSeconds").value(1000));
        assertEquals(2, json(get(CONTINUE).header("Authorization", aliceToken)).size());
        assertEquals(0, countRows("user_movie_progress", bob));
    }

    /** Borrar el título (desde el panel) borra su progreso en cascada, sin errores. */
    @Test
    void borrarUnTituloBorraSuProgreso() throws Exception {
        save(aliceToken, MOVIE_PROGRESS, dune.getId(), 10, 100);
        save(aliceToken, EPISODE_PROGRESS, dark11.getId(), 10, 100);
        User admin = saveUser("progadmin");
        admin.setRole(Role.ADMIN);
        admin = userRepository.save(admin);
        String adminToken = "Bearer " + jwtService.generateToken(admin);

        mockMvc.perform(delete("/api/movies/" + dune.getId()).header("Authorization", adminToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/series/" + dark.getId()).header("Authorization", adminToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(CONTINUE).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.length()").value(0));
        assertEquals(0, countRows("user_movie_progress", alice) + countRows("user_episode_progress", alice));
    }

    // ------------------------------------------------------------------
    // Carreras con el borrado
    // ------------------------------------------------------------------

    /**
     * Si la película se borra entre la comprobación y el guardado, la clave
     * foránea lo rechaza y se traduce al mismo 404, no a un 500. Se simula
     * borrándola de verdad <b>dentro</b> de la comprobación (que responde
     * «existe»), así el {@code INSERT} real choca con la clave foránea real.
     */
    @Test
    void siLaPeliculaSeBorraDuranteElGuardadoEsUn404() {
        MovieRepository racing = mock(MovieRepository.class);
        when(racing.existsByIdAndVideoUrlIsNotNull(dune.getId())).thenAnswer(invocation -> {
            movieRepository.deleteById(dune.getId());
            return true;
        });
        WatchProgressService service = new WatchProgressService(watchProgressRepository, racing,
                episodeRepository, Clock.systemUTC());

        MovieNotFoundException error = assertThrows(MovieNotFoundException.class,
                () -> service.saveMovieProgress(alice.getId(), dune.getId(), new WatchProgressRequest(10, 100)));
        assertEquals("Película no encontrada", error.getMessage());
        assertEquals(0, countRows("user_movie_progress", alice));
    }

    @Test
    void siElEpisodioSeBorraDuranteElGuardadoEsUn404() {
        EpisodeRepository racing = mock(EpisodeRepository.class);
        when(racing.existsByIdAndVideoUrlIsNotNull(dark11.getId())).thenAnswer(invocation -> {
            episodeRepository.deleteById(dark11.getId());
            return true;
        });
        WatchProgressService service = new WatchProgressService(watchProgressRepository, movieRepository,
                racing, Clock.systemUTC());

        assertThrows(EpisodeNotFoundException.class, () -> service.saveEpisodeProgress(alice.getId(),
                dark11.getId(), new WatchProgressRequest(10, 100)));
        assertEquals(0, countRows("user_episode_progress", alice));
    }

    /**
     * Si la cuenta se borra durante la petición (el filtro ya la leyó), la
     * clave foránea del usuario se traduce a «Usuario no encontrado», no a un
     * «Película no encontrada» falso ni a un 500. Con {@code JdbcClient} el
     * nombre de la restricción sale del mensaje del driver (ver
     * {@code SqlStates#isForeignKeyViolation}); sin eso, este test fallaba con
     * {@code MovieNotFoundException}.
     */
    @Test
    void siElUsuarioNoExisteLaClaveForaneaSeTraduceAUsuarioNoEncontrado() {
        WatchProgressRequest request = new WatchProgressRequest(10, 100);
        assertThrows(UserNotFoundException.class,
                () -> watchProgressService.saveMovieProgress(MISSING_ID, dune.getId(), request));
        assertThrows(UserNotFoundException.class,
                () -> watchProgressService.saveEpisodeProgress(MISSING_ID, dark11.getId(), request));
    }

    // ------------------------------------------------------------------
    // Sesión y CSRF
    // ------------------------------------------------------------------

    @Test
    void sinSesionTodasLasRutasDan401() throws Exception {
        List<MockHttpServletRequestBuilder> requests = List.of(
                put(MOVIE_PROGRESS + "/" + dune.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(10, 100)),
                put(EPISODE_PROGRESS + "/" + dark11.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(10, 100)),
                get(MOVIE_PROGRESS + "/" + dune.getId()), get(EPISODE_PROGRESS + "/" + dark11.getId()),
                get(SERIES_PROGRESS + "/" + dark.getId()), get(CONTINUE),
                delete(CONTINUE + "/movies/" + dune.getId()), delete(CONTINUE + "/series/" + dark.getId()));

        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(request.header("X-Requested-With", "StreamBox"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        assertEquals(0, countRows("user_movie_progress", alice) + countRows("user_episode_progress", alice));
    }

    /**
     * Con la cookie de sesión (como el navegador), las escrituras sin
     * {@code X-Requested-With} dan 403 {@code CSRF_REJECTED} y no cambian nada;
     * con la cabecera, 204. Las lecturas no la necesitan.
     */
    @Test
    void conCookieLasEscriturasExigenLaCabeceraCsrf() throws Exception {
        Cookie session = new Cookie("streambox_token", jwtService.generateToken(alice));
        save(aliceToken, MOVIE_PROGRESS, alien.getId(), 10, 100);
        save(aliceToken, EPISODE_PROGRESS, dark11.getId(), 10, 100);
        List<MockHttpServletRequestBuilder> writes = List.of(
                put(MOVIE_PROGRESS + "/" + dune.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(10, 100)),
                put(EPISODE_PROGRESS + "/" + dark12.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(10, 100)),
                delete(CONTINUE + "/movies/" + alien.getId()), delete(CONTINUE + "/series/" + dark.getId()));

        for (MockHttpServletRequestBuilder write : writes) {
            mockMvc.perform(write.cookie(session))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        }
        assertEquals(1, countRows("user_movie_progress", alice));
        assertEquals(1, countRows("user_episode_progress", alice));

        mockMvc.perform(put(MOVIE_PROGRESS + "/" + dune.getId()).cookie(session)
                .header("X-Requested-With", "StreamBox")
                .contentType(MediaType.APPLICATION_JSON).content(body(20, 100)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(MOVIE_PROGRESS + "/" + dune.getId()).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positionSeconds").value(20));
        mockMvc.perform(get(CONTINUE).cookie(session))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static String body(int position, int duration) {
        return "{\"positionSeconds\":" + position + ",\"durationSeconds\":" + duration + "}";
    }

    private static MockHttpServletRequestBuilder saveRequest(String token, String base, long id, int position,
            int duration) {
        return put(base + "/" + id).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(body(position, duration));
    }

    private void save(String token, String base, long id, int position, int duration) throws Exception {
        mockMvc.perform(saveRequest(token, base, id, position, duration))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    private void remove(String token, String url) throws Exception {
        mockMvc.perform(delete(url).header("Authorization", token))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    private JsonNode json(MockHttpServletRequestBuilder request) throws Exception {
        String json = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private JsonNode error(MockHttpServletRequestBuilder request, int expectedStatus) throws Exception {
        String json = mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    /** Mismo estado, código, mensaje y errores de validación (la ruta y la hora cambian). */
    private static void assertSameError(JsonNode expected, JsonNode actual) {
        for (String field : List.of("status", "error", "code", "message", "validationErrors")) {
            assertEquals(expected.get(field), actual.get(field), field);
        }
        assertNull(actual.get("trace"));
    }

    private int countRows(String table, User user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class,
                user.getId());
    }

    private void setUpdatedAt(String table, String column, long id, Instant at) {
        jdbc.update("UPDATE " + table + " SET updated_at = ? WHERE user_id = ? AND " + column + " = ?",
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC), alice.getId(), id);
    }

    private User saveUser(String name) {
        User user = new User();
        user.setEmailVerifiedAt(Instant.now()); // cuenta verificada (V9)
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(Role.USER);
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
