package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.dto.EpisodeRequest;
import com.emilio.streambox.dto.MovieRequest;
import com.emilio.streambox.entity.Episode;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.EpisodeAlreadyExistsException;
import com.emilio.streambox.exception.SeriesNotFoundException;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.emilio.streambox.service.EpisodeService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests de las escrituras del catálogo de series (solo ADMIN): alta, edición y
 * borrado de series ({@code /api/series}) y de episodios
 * ({@code /api/series/{id}/episodes}), su validación, y el borrado de un
 * género que usan series.
 *
 * <p>
 * Sin {@code @Transactional}: cada petición se confirma de verdad, para
 * comprobar también las restricciones y cascadas reales de la base de datos
 * (unicidad de temporada + número, borrado en cascada de episodios y
 * favoritos). Las series se limpian antes que los géneros.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SeriesAdminIntegrationTest {

    private static final String SERIES = "/api/series";

    @Autowired private MockMvc mockMvc;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String adminToken;
    private String userToken;
    private User user;
    private Genre drama;
    private Genre comedy;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        adminToken = "Bearer " + jwtService.generateToken(saveUser("seriesadmin", Role.ADMIN));
        user = saveUser("seriesuser", Role.USER);
        userToken = "Bearer " + jwtService.generateToken(user);

        drama = saveGenre("Drama");
        comedy = saveGenre("Comedia");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Series: alta, edición y borrado
    // ------------------------------------------------------------------

    @Test
    void crearUnaSerieDevuelve201ConElDetalleVacioYQuedaOcultaHastaTenerEpisodios() throws Exception {
        Map<String, Object> body = seriesBody("Fargo", comedy.getId(), drama.getId());
        body.put("endYear", 2024);

        String json = send(post(SERIES), adminToken, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title").value("Fargo"))
                .andExpect(jsonPath("$.releaseYear").value(2014))
                .andExpect(jsonPath("$.endYear").value(2024))
                .andExpect(jsonPath("$.imageUrl").value("https://example.com/fargo.jpg"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.genres[0].name").value("Comedia"))
                .andExpect(jsonPath("$.genres[1].name").value("Drama"))
                .andExpect(jsonPath("$.seasonCount").value(0))
                .andExpect(jsonPath("$.episodeCount").value(0))
                .andExpect(jsonPath("$.seasons").isEmpty())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(json).get("id").asLong();

        // Recién creada no tiene episodios: oculta para los usuarios
        mockMvc.perform(get(SERIES + "/" + id).header("Authorization", userToken))
                .andExpect(status().isNotFound());

        // Con el primer episodio pasa a ser visible
        send(post(SERIES + "/" + id + "/episodes"), adminToken, episodeBody(1, 1))
                .andExpect(status().isCreated());
        mockMvc.perform(get(SERIES + "/" + id).header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episodeCount").value(1));
    }

    @Test
    void unUsuarioNoPuedeCrearSeriesNiSeCreaNada() throws Exception {
        send(post(SERIES), userToken, seriesBody("Intrusa", drama.getId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(post(SERIES).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(seriesBody("Anonima", drama.getId()))))
                .andExpect(status().isUnauthorized());

        assertEquals(0, seriesRepository.count());
    }

    @Test
    void unGeneroInexistenteDa404YNoCreaLaSerie() throws Exception {
        send(post(SERIES), adminToken, seriesBody("Sin genero", drama.getId(), 987654L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Género no encontrado: 987654"));

        assertEquals(0, seriesRepository.count());
    }

    @Test
    void modificarUnaSerieSustituyeSusDatosYGenerosSinTocarSusEpisodios() throws Exception {
        Series series = saveSeries("Antigua", 2010, drama);
        saveEpisode(series, 1, 1);
        saveEpisode(series, 2, 1);
        Map<String, Object> body = seriesBody("Nueva", comedy.getId());
        body.put("endYear", 2014);

        send(put(SERIES + "/" + series.getId()), adminToken, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Nueva"))
                .andExpect(jsonPath("$.endYear").value(2014))
                .andExpect(jsonPath("$.genres.length()").value(1))
                .andExpect(jsonPath("$.genres[0].name").value("Comedia"))
                .andExpect(jsonPath("$.seasonCount").value(2))
                .andExpect(jsonPath("$.seasons.length()").value(2));

        mockMvc.perform(get("/api/admin/series/" + series.getId()).header("Authorization", adminToken))
                .andExpect(jsonPath("$.title").value("Nueva"))
                .andExpect(jsonPath("$.genres[0].name").value("Comedia"))
                .andExpect(jsonPath("$.episodeCount").value(2));
    }

    @Test
    void modificarOBorrarUnaSerieInexistenteDa404() throws Exception {
        send(put(SERIES + "/987654"), adminToken, seriesBody("Nada", drama.getId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Serie no encontrada"));
        mockMvc.perform(delete(SERIES + "/987654").header("Authorization", adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /**
     * Borrar una serie arrastra en la base de datos sus episodios, sus filas
     * de géneros y las de "Mi lista" de todos los usuarios, sin tocar al
     * usuario, al género ni a otras series.
     */
    @Test
    void borrarUnaSerieArrastraSusEpisodiosGenerosYFavoritos() throws Exception {
        Series doomed = saveSeries("Condenada", 2010, drama, comedy);
        saveEpisode(doomed, 1, 1);
        saveEpisode(doomed, 1, 2);
        Series kept = saveSeries("Superviviente", 2010, drama);
        saveEpisode(kept, 1, 1);
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            seriesRepository.addFavorite(user.getId(), doomed.getId());
            seriesRepository.addFavorite(user.getId(), kept.getId());
        });

        mockMvc.perform(delete(SERIES + "/" + doomed.getId()).header("Authorization", adminToken))
                .andExpect(status().isNoContent());

        assertFalse(seriesRepository.existsById(doomed.getId()));
        assertEquals(0, count("SELECT COUNT(*) FROM episodes WHERE series_id = ?", doomed.getId()));
        assertEquals(0, count("SELECT COUNT(*) FROM series_genres WHERE series_id = ?", doomed.getId()));
        assertEquals(0, count("SELECT COUNT(*) FROM user_favorite_series WHERE series_id = ?", doomed.getId()));
        // Lo ajeno sigue igual
        assertEquals(1, count("SELECT COUNT(*) FROM episodes WHERE series_id = ?", kept.getId()));
        assertEquals(1, count("SELECT COUNT(*) FROM user_favorite_series WHERE series_id = ?", kept.getId()));
        assertTrue(userRepository.existsById(user.getId()));
        assertEquals(2, genreRepository.count());
    }

    // ------------------------------------------------------------------
    // Validación de SeriesRequest
    // ------------------------------------------------------------------

    @Test
    void unaSerieSinDatosDa400ConUnMensajePorCampo() throws Exception {
        send(post(SERIES), adminToken, Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.title").value("El título es obligatorio"))
                .andExpect(jsonPath("$.validationErrors.description").value("La descripción es obligatoria"))
                .andExpect(jsonPath("$.validationErrors.releaseYear").value("El año de estreno es obligatorio"))
                .andExpect(jsonPath("$.validationErrors.imageUrl").value("La URL de la imagen es obligatoria"))
                .andExpect(jsonPath("$.validationErrors.genreIds").value("Indica al menos un género"))
                .andExpect(jsonPath("$.validationErrors.endYear").doesNotExist());
    }

    @Test
    void unAnioDeFinalizacionAnteriorAlDeEstrenoDa400EnEndYear() throws Exception {
        Map<String, Object> body = seriesBody("Al reves", drama.getId());
        body.put("releaseYear", 2015);
        body.put("endYear", 2014);

        send(post(SERIES), adminToken, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.endYear").value(
                        "El año de finalización no puede ser anterior al año de estreno"));

        // También al editar
        Series series = saveSeries("Existente", 2010, drama);
        send(put(SERIES + "/" + series.getId()), adminToken, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.endYear").value(
                        "El año de finalización no puede ser anterior al año de estreno"));
        assertEquals(0, seriesRepository.findAll().stream().filter(s -> "Al reves".equals(s.getTitle())).count());
    }

    @Test
    void unaSerieQueTerminaElMismoAnioQueEmpiezaEsValida() throws Exception {
        Map<String, Object> body = seriesBody("Miniserie", drama.getId());
        body.put("releaseYear", 2019);
        body.put("endYear", 2019);

        send(post(SERIES), adminToken, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.endYear").value(2019));
    }

    @Test
    void losAniosFueraDeRangoDan400ConSuMensaje() throws Exception {
        Map<String, Object> body = seriesBody("Fuera de rango", drama.getId());
        body.put("releaseYear", 1887);
        body.put("endYear", 2101);

        send(post(SERIES), adminToken, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.releaseYear").value(
                        "El año de estreno debe estar entre 1888 y 2100"))
                .andExpect(jsonPath("$.validationErrors.endYear").value(
                        "El año de finalización debe estar entre 1888 y 2100"));
    }

    @Test
    void lasUrlsYLongitudesDeLaSerieUsanLosMismosMensajesQueLasPeliculas() throws Exception {
        Map<String, Object> httpImage = seriesBody("Http", drama.getId());
        httpImage.put("imageUrl", "http://example.com/i.jpg");
        send(post(SERIES), adminToken, httpImage)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.imageUrl").value(MovieRequest.IMAGE_URL_FORMAT_MESSAGE));

        Map<String, Object> longImage = seriesBody("Larga", drama.getId());
        longImage.put("imageUrl", "https://example.com/" + "a".repeat(500));
        send(post(SERIES), adminToken, longImage)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.imageUrl").value(MovieRequest.IMAGE_URL_SIZE_MESSAGE));

        Map<String, Object> longTexts = seriesBody("t".repeat(151), drama.getId());
        longTexts.put("description", "d".repeat(1001));
        send(post(SERIES), adminToken, longTexts)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.title").value("El título no puede superar los 150 caracteres"))
                .andExpect(jsonPath("$.validationErrors.description")
                        .value("La descripción no puede superar los 1000 caracteres"));

        // Una portada propia del frontend también vale para las series
        Map<String, Object> localCover = seriesBody("Portada local", drama.getId());
        localCover.put("imageUrl", "/covers/serie.webp");
        send(post(SERIES), adminToken, localCover).andExpect(status().isCreated());

        assertEquals(1, seriesRepository.count());
    }

    // ------------------------------------------------------------------
    // Episodios
    // ------------------------------------------------------------------

    @Test
    void crearUnEpisodioDevuelve201ConSusDatos() throws Exception {
        Series series = saveSeries("Con episodios", 2010, drama);
        Map<String, Object> body = episodeBody(2, 5);
        body.put("description", "Sinopsis del episodio");

        send(post(SERIES + "/" + series.getId() + "/episodes"), adminToken, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.seasonNumber").value(2))
                .andExpect(jsonPath("$.episodeNumber").value(5))
                .andExpect(jsonPath("$.title").value("Episodio 2x5"))
                .andExpect(jsonPath("$.description").value("Sinopsis del episodio"))
                .andExpect(jsonPath("$.duration").value(50))
                .andExpect(jsonPath("$.videoUrl").value("https://example.com/2x5.mp4"));

        assertEquals(1, count("SELECT COUNT(*) FROM episodes WHERE series_id = ?", series.getId()));
    }

    @Test
    void unaSinopsisEnBlancoSeGuardaComoNull() throws Exception {
        Series series = saveSeries("Sin sinopsis", 2010, drama);
        Map<String, Object> body = episodeBody(1, 1);
        body.put("description", "   ");

        String json = send(post(SERIES + "/" + series.getId() + "/episodes"), adminToken, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.description").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString();

        assertNull(episodeRepository.findById(objectMapper.readTree(json).get("id").asLong())
                .orElseThrow().getDescription());
    }

    @Test
    void unEpisodioDuplicadoDa409ConElMensajeDelContrato() throws Exception {
        Series series = saveSeries("Duplicados", 2010, drama);
        saveEpisode(series, 1, 3);

        send(post(SERIES + "/" + series.getId() + "/episodes"), adminToken, episodeBody(1, 3))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EPISODE_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value("Ya existe el episodio 3 de la temporada 1"));

        assertEquals(1, count("SELECT COUNT(*) FROM episodes WHERE series_id = ?", series.getId()));
    }

    /** La misma posición en <em>otra</em> serie no es un duplicado. */
    @Test
    void laMismaTemporadaYNumeroEnOtraSerieNoEsDuplicado() throws Exception {
        Series one = saveSeries("Una", 2010, drama);
        Series other = saveSeries("Otra", 2010, drama);
        saveEpisode(one, 1, 1);

        send(post(SERIES + "/" + other.getId() + "/episodes"), adminToken, episodeBody(1, 1))
                .andExpect(status().isCreated());
    }

    @Test
    void crearUnEpisodioEnUnaSerieInexistenteDa404() throws Exception {
        send(post(SERIES + "/987654/episodes"), adminToken, episodeBody(1, 1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Serie no encontrada"));
    }

    @Test
    void modificarUnEpisodioCambiaSusDatosYPuedeConservarSuPosicion() throws Exception {
        Series series = saveSeries("Editable", 2010, drama);
        Episode episode = saveEpisode(series, 1, 1);

        Map<String, Object> sameSlot = episodeBody(1, 1);
        sameSlot.put("title", "Titulo nuevo");
        send(put(SERIES + "/" + series.getId() + "/episodes/" + episode.getId()), adminToken, sameSlot)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(episode.getId()))
                .andExpect(jsonPath("$.title").value("Titulo nuevo"));

        send(put(SERIES + "/" + series.getId() + "/episodes/" + episode.getId()), adminToken, episodeBody(3, 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seasonNumber").value(3))
                .andExpect(jsonPath("$.episodeNumber").value(7));

        Episode saved = episodeRepository.findById(episode.getId()).orElseThrow();
        assertEquals(3, saved.getSeasonNumber());
        assertEquals(7, saved.getEpisodeNumber());
    }

    @Test
    void moverUnEpisodioAUnaPosicionOcupadaDa409YNoCambiaNada() throws Exception {
        Series series = saveSeries("Ocupada", 2010, drama);
        saveEpisode(series, 1, 1);
        Episode second = saveEpisode(series, 1, 2);

        send(put(SERIES + "/" + series.getId() + "/episodes/" + second.getId()), adminToken, episodeBody(1, 1))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EPISODE_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value("Ya existe el episodio 1 de la temporada 1"));

        assertEquals(2, episodeRepository.findById(second.getId()).orElseThrow().getEpisodeNumber());
    }

    /**
     * Un episodio solo se puede tocar a través de su propia serie: con el id
     * de otra serie en la ruta es un 404 y no se modifica ni se borra.
     */
    @Test
    void unEpisodioDeOtraSerieDa404AlModificarloYAlBorrarlo() throws Exception {
        Series owner = saveSeries("Duena", 2010, drama);
        Series other = saveSeries("Ajena", 2010, drama);
        Episode episode = saveEpisode(owner, 1, 1);
        String wrongUrl = SERIES + "/" + other.getId() + "/episodes/" + episode.getId();

        send(put(wrongUrl), adminToken, episodeBody(2, 2))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Episodio no encontrado"));
        mockMvc.perform(delete(wrongUrl).header("Authorization", adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Episodio no encontrado"));

        Episode untouched = episodeRepository.findById(episode.getId()).orElseThrow();
        assertEquals(1, untouched.getSeasonNumber());
        assertEquals(1, untouched.getEpisodeNumber());
    }

    @Test
    void modificarOBorrarUnEpisodioInexistenteOEnUnaSerieInexistenteDa404() throws Exception {
        Series series = saveSeries("Existe", 2010, drama);
        Episode episode = saveEpisode(series, 1, 1);

        send(put(SERIES + "/" + series.getId() + "/episodes/987654"), adminToken, episodeBody(1, 2))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Episodio no encontrado"));
        mockMvc.perform(delete(SERIES + "/987654/episodes/" + episode.getId()).header("Authorization", adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Serie no encontrada"));
    }

    @Test
    void borrarElUltimoEpisodioVuelveAOcultarLaSerie() throws Exception {
        Series series = saveSeries("Efimera", 2010, drama);
        Episode only = saveEpisode(series, 1, 1);
        mockMvc.perform(get(SERIES + "/" + series.getId()).header("Authorization", userToken))
                .andExpect(status().isOk());

        mockMvc.perform(delete(SERIES + "/" + series.getId() + "/episodes/" + only.getId())
                        .header("Authorization", adminToken))
                .andExpect(status().isNoContent());

        assertFalse(episodeRepository.existsById(only.getId()));
        mockMvc.perform(get(SERIES + "/" + series.getId()).header("Authorization", userToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/admin/series/" + series.getId()).header("Authorization", adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void unEpisodioSinDatosOFueraDeRangoDa400ConSusMensajes() throws Exception {
        Series series = saveSeries("Validada", 2010, drama);
        String url = SERIES + "/" + series.getId() + "/episodes";

        send(post(url), adminToken, Map.of())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.seasonNumber").value("La temporada es obligatoria"))
                .andExpect(jsonPath("$.validationErrors.episodeNumber").value("El número de episodio es obligatorio"))
                .andExpect(jsonPath("$.validationErrors.title").value("El título es obligatorio"))
                .andExpect(jsonPath("$.validationErrors.duration").value("La duración es obligatoria"))
                .andExpect(jsonPath("$.validationErrors.videoUrl").value("La URL del vídeo es obligatoria"))
                .andExpect(jsonPath("$.validationErrors.description").doesNotExist());

        Map<String, Object> tooBig = episodeBody(101, 1001);
        tooBig.put("duration", 601);
        tooBig.put("description", "d".repeat(1001));
        send(post(url), adminToken, tooBig)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.seasonNumber").value(EpisodeRequest.SEASON_RANGE_MESSAGE))
                .andExpect(jsonPath("$.validationErrors.episodeNumber").value(EpisodeRequest.EPISODE_RANGE_MESSAGE))
                .andExpect(jsonPath("$.validationErrors.duration").value(EpisodeRequest.DURATION_RANGE_MESSAGE))
                .andExpect(jsonPath("$.validationErrors.description")
                        .value("La descripción no puede superar los 1000 caracteres"));

        Map<String, Object> zero = episodeBody(0, 0);
        zero.put("duration", 0);
        send(post(url), adminToken, zero)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.seasonNumber").value(EpisodeRequest.SEASON_RANGE_MESSAGE))
                .andExpect(jsonPath("$.validationErrors.episodeNumber").value(EpisodeRequest.EPISODE_RANGE_MESSAGE))
                .andExpect(jsonPath("$.validationErrors.duration").value(EpisodeRequest.DURATION_RANGE_MESSAGE));

        Map<String, Object> badVideo = episodeBody(1, 1);
        badVideo.put("videoUrl", "javascript:alert(1)");
        send(post(url), adminToken, badVideo)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.videoUrl").value(MovieRequest.VIDEO_URL_FORMAT_MESSAGE));

        Map<String, Object> longVideo = episodeBody(1, 1);
        longVideo.put("videoUrl", "https://example.com/" + "v".repeat(500));
        send(put(url + "/1"), adminToken, longVideo)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.videoUrl").value(MovieRequest.VIDEO_URL_SIZE_MESSAGE));

        assertEquals(0, count("SELECT COUNT(*) FROM episodes WHERE series_id = ?", series.getId()));
    }

    @Test
    void unUsuarioNoPuedeTocarEpisodios() throws Exception {
        Series series = saveSeries("Protegida", 2010, drama);
        Episode episode = saveEpisode(series, 1, 1);

        send(post(SERIES + "/" + series.getId() + "/episodes"), userToken, episodeBody(1, 2))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(SERIES + "/" + series.getId() + "/episodes/" + episode.getId())
                        .header("Authorization", userToken))
                .andExpect(status().isForbidden());

        assertEquals(1, count("SELECT COUNT(*) FROM episodes WHERE series_id = ?", series.getId()));
    }

    // --- Carreras entre la comprobación previa y la escritura (SQLSTATE reales de H2) ---

    /**
     * Dos altas simultáneas del mismo episodio: las dos pasan la comprobación
     * previa (aquí, un repositorio que "no ve" el episodio ya guardado) y la
     * restricción única de la base de datos rechaza la segunda. Debe salir el
     * mismo 409 de dominio, no un error de integridad genérico.
     */
    @Test
    void siDosAltasDelMismoEpisodioSeCruzanLaRestriccionUnicaDa409() {
        Series series = saveSeries("Carrera", 2010, drama);
        saveEpisode(series, 1, 1);
        EpisodeRepository blind = mock(EpisodeRepository.class, delegatesTo(episodeRepository));
        doReturn(false).when(blind).existsBySeriesIdAndSeasonNumberAndEpisodeNumber(anyLong(), anyInt(), anyInt());
        EpisodeService service = new EpisodeService(blind, seriesRepository);

        EpisodeAlreadyExistsException thrown = assertThrows(EpisodeAlreadyExistsException.class,
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(
                        s -> service.createEpisode(series.getId(), request(1, 1))));

        assertEquals("Ya existe el episodio 1 de la temporada 1", thrown.getMessage());
        assertEquals(1, count("SELECT COUNT(*) FROM episodes WHERE series_id = ?", series.getId()));
    }

    /** Lo mismo al mover un episodio a una posición que otro acaba de ocupar. */
    @Test
    void siUnaEdicionChocaConLaRestriccionUnicaPorUnaCarreraDa409() {
        Series series = saveSeries("Carrera edicion", 2010, drama);
        saveEpisode(series, 1, 1);
        Episode second = saveEpisode(series, 1, 2);
        EpisodeRepository blind = mock(EpisodeRepository.class, delegatesTo(episodeRepository));
        doReturn(false).when(blind)
                .existsBySeriesIdAndSeasonNumberAndEpisodeNumberAndIdNot(anyLong(), anyInt(), anyInt(), anyLong());
        EpisodeService service = new EpisodeService(blind, seriesRepository);

        assertThrows(EpisodeAlreadyExistsException.class,
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(
                        s -> service.updateEpisode(series.getId(), second.getId(), request(1, 1))));

        assertEquals(2, episodeRepository.findById(second.getId()).orElseThrow().getEpisodeNumber());
    }

    /**
     * La serie se borra entre la comprobación de existencia y el
     * {@code INSERT} del episodio: la clave foránea lo rechaza y debe salir un
     * 404 de serie, no un 409.
     */
    @Test
    void siLaSerieDesapareceAntesDelInsertDelEpisodioSeTraduceA404() {
        SeriesRepository stale = mock(SeriesRepository.class, delegatesTo(seriesRepository));
        when(stale.existsById(987654L)).thenReturn(true);
        EpisodeService service = new EpisodeService(episodeRepository, stale);

        assertThrows(SeriesNotFoundException.class,
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(
                        s -> service.createEpisode(987654L, request(1, 1))));
    }

    // ------------------------------------------------------------------
    // Géneros usados por series
    // ------------------------------------------------------------------

    @Test
    void unGeneroQueUsanSeriesNoSePuedeBorrar() throws Exception {
        saveSeries("Una", 2010, drama);
        saveSeries("Dos", 2011, drama);

        mockMvc.perform(delete("/api/genres/" + drama.getId()).header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENRE_IN_USE"))
                .andExpect(jsonPath("$.message").value("No se puede eliminar el género \"Drama\": lo usan 2 series. "
                        + "Quítalo de esas series antes de borrarlo."));

        assertTrue(genreRepository.existsById(drama.getId()));
    }

    @Test
    void elMensajeDeGeneroEnUsoCuentaPeliculasYSeries() throws Exception {
        for (int i = 0; i < 3; i++) {
            saveMovie("Peli " + i, drama);
        }
        saveSeries("Serie 1", 2010, drama);
        saveSeries("Serie 2", 2010, drama);
        saveMovie("Peli comedia", comedy);
        saveSeries("Serie comedia", 2010, comedy);

        mockMvc.perform(delete("/api/genres/" + drama.getId()).header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("No se puede eliminar el género \"Drama\": lo usan "
                        + "3 películas y 2 series. Quítalo de esas películas y de esas series antes de borrarlo."));
        mockMvc.perform(delete("/api/genres/" + comedy.getId()).header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("No se puede eliminar el género \"Comedia\": lo usan "
                        + "1 película y 1 serie. Quítalo de esa película y de esa serie antes de borrarlo."));
    }

    @Test
    void alBorrarLaSerieQueLoUsabaElGeneroYaSePuedeBorrar() throws Exception {
        Series series = saveSeries("Unica", 2010, comedy);

        mockMvc.perform(delete("/api/genres/" + comedy.getId()).header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("No se puede eliminar el género \"Comedia\": lo usa "
                        + "1 serie. Quítalo de esa serie antes de borrarlo."));

        mockMvc.perform(delete(SERIES + "/" + series.getId()).header("Authorization", adminToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/genres/" + comedy.getId()).header("Authorization", adminToken))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private ResultActions send(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
            String token, Map<String, Object> body) throws Exception {
        return mockMvc.perform(builder.header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private Map<String, Object> seriesBody(String title, Long... genreIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("description", "Sinopsis de " + title);
        body.put("releaseYear", 2014);
        body.put("imageUrl", "https://example.com/fargo.jpg");
        body.put("genreIds", List.of(genreIds));
        return body;
    }

    private Map<String, Object> episodeBody(int season, int number) {
        Map<String, Object> body = new HashMap<>();
        body.put("seasonNumber", season);
        body.put("episodeNumber", number);
        body.put("title", "Episodio " + season + "x" + number);
        body.put("duration", 50);
        body.put("videoUrl", "https://example.com/" + season + "x" + number + ".mp4");
        return body;
    }

    private static EpisodeRequest request(int season, int number) {
        return new EpisodeRequest(season, number, "Episodio", null, 40, "https://example.com/v.mp4");
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private User saveUser(String name, Role role) {
        User u = new User();
        u.setUsername(name);
        u.setEmail(name + "@test.com");
        u.setPassword(passwordEncoder.encode("password123"));
        u.setRole(role);
        return userRepository.save(u);
    }

    private Genre saveGenre(String name) {
        Genre genre = new Genre();
        genre.setName(name);
        return genreRepository.save(genre);
    }

    private Movie saveMovie(String title, Genre genre) {
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setDescription("Descripción");
        movie.setDuration(100);
        movie.setReleaseYear(2000);
        movie.setImageUrl("https://example.com/i.jpg");
        movie.setVideoUrl("https://example.com/v.mp4");
        movie.setGenres(new HashSet<>(Set.of(genre)));
        return movieRepository.save(movie);
    }

    private Series saveSeries(String title, int year, Genre... genres) {
        Series series = new Series();
        series.setTitle(title);
        series.setDescription("Sinopsis de " + title);
        series.setReleaseYear(year);
        series.setImageUrl("https://example.com/serie.jpg");
        series.setGenres(new HashSet<>(Set.of(genres)));
        return seriesRepository.save(series);
    }

    private Episode saveEpisode(Series series, int season, int number) {
        Episode episode = new Episode();
        episode.setSeries(series);
        episode.setSeasonNumber(season);
        episode.setEpisodeNumber(number);
        episode.setTitle("Episodio " + season + "x" + number);
        episode.setDuration(45);
        episode.setVideoUrl("https://example.com/episodio.mp4");
        return episodeRepository.save(episode);
    }

    /** Series primero (sus géneros, episodios y favoritos caen con ellas), luego el resto. */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
