package com.emilio.streambox.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.dto.MovieRequest;
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
import com.emilio.streambox.service.SeriesFavoriteService;
import com.emilio.streambox.service.SeriesService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManagerFactory;

/**
 * Revisión independiente (QA) de la funcionalidad de series: casos límite que
 * no cubrían las suites de los agentes que la implementaron.
 *
 * <p>
 * Agrupa lo que protege cada bloque:
 * </p>
 * <ul>
 * <li><b>Contrato JSON:</b> {@code endYear} y la sinopsis del episodio van como
 * {@code null} <em>explícito</em> en todas las respuestas (alta, listado,
 * búsqueda, detalle, panel y favoritos), no solo en el detalle.</li>
 * <li><b>Límites exactos</b> de episodios y series a través de HTTP (los
 * validadores tienen tests unitarios, pero aquí se comprueba que el valor llega
 * a la base de datos sin chocar con sus {@code CHECK} ni longitudes).</li>
 * <li><b>Ciclo de visibilidad completo</b> por la API: una serie creada vacía
 * no aparece ni se cuenta en ninguna ruta pública, aparece con su primer
 * episodio, desaparece al borrar el último (también de "Mi lista", sin perder
 * la fila) y reaparece al volver a tener episodios.</li>
 * <li><b>Búsqueda y paginación</b> de series con Unicode, comodines, filtros
 * mal formados y el límite exacto de {@code page × size}; y que las películas
 * conservan su lista blanca tras extraer {@code PageableFactory}.</li>
 * <li><b>Errores de protocolo</b> (405, 415, JSON mal formado, números
 * desbordados): ninguno acaba en 500.</li>
 * <li><b>Géneros:</b> los recuentos incluyen series ocultas y las
 * combinaciones de singular y plural que faltaban.</li>
 * <li><b>Concurrencia real</b> (varios hilos a la vez contra H2): altas
 * simultáneas del mismo episodio o del mismo favorito dan exactamente un
 * éxito y el resto 409, nunca un 500 ni un duplicado.</li>
 * </ul>
 *
 * <p>
 * Sin {@code @Transactional}: las peticiones se confirman de verdad (hace
 * falta para las restricciones reales y para la concurrencia) y la base se
 * limpia antes y después de cada test, series primero. Usa las mismas
 * propiedades que {@link CatalogIntegrationTest} para compartir su contexto y
 * poder contar consultas con las estadísticas de Hibernate.
 * </p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SeriesQaEdgeCasesIntegrationTest {

    private static final String SERIES = "/api/series";
    private static final String SEARCH = SERIES + "/search";
    private static final String ADMIN_SERIES = "/api/admin/series";
    private static final String SERIES_FAVORITES = "/api/users/me/favorites/series";

    @Autowired private MockMvc mockMvc;
    @Autowired private SeriesService seriesService;
    @Autowired private SeriesFavoriteService seriesFavoriteService;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String adminToken;
    private String userToken;
    private User user;
    private Genre drama;
    private Genre comedy;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        adminToken = "Bearer " + jwtService.generateToken(saveUser("qaseriesadmin", Role.ADMIN));
        user = saveUser("qaseriesuser", Role.USER);
        userToken = "Bearer " + jwtService.generateToken(user);

        drama = saveGenre("Drama");
        comedy = saveGenre("Comedia");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Contrato JSON: nulos explícitos en todas las respuestas
    // ------------------------------------------------------------------

    /**
     * El frontend distingue "en emisión" por {@code endYear === null} y la
     * sinopsis ausente por {@code description === null}. Si alguna respuesta
     * omitiera el campo (p. ej. con {@code @JsonInclude(NON_NULL)} en un DTO),
     * el frontend recibiría {@code undefined}. Se comprueba en cada respuesta
     * que lleva esos campos, no solo en el detalle.
     */
    @Test
    void losCamposOpcionalesVanComoNullExplicitoEnTodasLasRespuestas() throws Exception {
        JsonNode created = json(send(post(SERIES), adminToken, seriesBody("En emision", drama.getId()))
                .andExpect(status().isCreated()));
        assertExplicitNull(created, "endYear");
        long id = created.get("id").asLong();

        JsonNode episode = json(send(post(SERIES + "/" + id + "/episodes"), adminToken, episodeBody(1, 1))
                .andExpect(status().isCreated()));
        assertExplicitNull(episode, "description");

        mockMvc.perform(post(SERIES_FAVORITES + "/" + id).header("Authorization", userToken))
                .andExpect(status().isNoContent());

        assertExplicitNull(json(getOk(SERIES, userToken)).get("content").get(0), "endYear");
        assertExplicitNull(json(getOk(SEARCH + "?title=emision", userToken)).get("content").get(0), "endYear");
        assertExplicitNull(json(getOk(ADMIN_SERIES, adminToken)).get("content").get(0), "endYear");
        assertExplicitNull(json(getOk(SERIES_FAVORITES, userToken)).get(0), "endYear");

        JsonNode detail = json(getOk(SERIES + "/" + id, userToken));
        assertExplicitNull(detail, "endYear");
        assertExplicitNull(detail.get("seasons").get(0).get("episodes").get(0), "description");
        JsonNode adminDetail = json(getOk(ADMIN_SERIES + "/" + id, adminToken));
        assertExplicitNull(adminDetail, "endYear");
        assertExplicitNull(adminDetail.get("seasons").get(0).get("episodes").get(0), "description");

        // Al editar sin endYear, una serie terminada vuelve a "en emisión" (PUT sustituye todo)
        Map<String, Object> finished = seriesBody("En emision", drama.getId());
        finished.put("endYear", 2016);
        send(put(SERIES + "/" + id), adminToken, finished).andExpect(status().isOk());
        assertExplicitNull(json(send(put(SERIES + "/" + id), adminToken, seriesBody("En emision", drama.getId()))
                .andExpect(status().isOk())), "endYear");
    }

    // ------------------------------------------------------------------
    // Episodios: límites exactos, edición y duplicados
    // ------------------------------------------------------------------

    /**
     * Los extremos admitidos por la validación (temporada 1 y 100, número 1 y
     * 1000, duración 1 y 600) también los acepta la base de datos: ningún
     * {@code CHECK} de {@code V3} es más estricto que el DTO.
     */
    @ParameterizedTest
    @CsvSource({ "1, 1, 1", "100, 1000, 600", "1, 1000, 600", "100, 1, 1" })
    void losLimitesInclusivosDelEpisodioSeAceptanPorHttp(int season, int number, int duration) throws Exception {
        Series series = saveSeries("Limites", 2010, drama);
        Map<String, Object> body = episodeBody(season, number);
        body.put("duration", duration);

        send(post(SERIES + "/" + series.getId() + "/episodes"), adminToken, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seasonNumber").value(season))
                .andExpect(jsonPath("$.episodeNumber").value(number))
                .andExpect(jsonPath("$.duration").value(duration));

        assertEquals(1, count("SELECT COUNT(*) FROM episodes WHERE series_id = ?", series.getId()));
    }

    /** Un valor justo por fuera de cada límite da 400 con el mensaje de su campo y no guarda nada. */
    @ParameterizedTest
    @CsvSource({
            "0, 1, 45, seasonNumber, La temporada debe estar entre 1 y 100",
            "101, 1, 45, seasonNumber, La temporada debe estar entre 1 y 100",
            "1, 0, 45, episodeNumber, El número de episodio debe estar entre 1 y 1000",
            "1, 1001, 45, episodeNumber, El número de episodio debe estar entre 1 y 1000",
            "1, 1, 0, duration, La duración debe estar entre 1 y 600 minutos",
            "1, 1, 601, duration, La duración debe estar entre 1 y 600 minutos",
            "-1, 1, 45, seasonNumber, La temporada debe estar entre 1 y 100" })
    void unValorJustoFueraDeCadaLimiteDa400ConSuMensaje(
            int season, int number, int duration, String field, String message) throws Exception {
        Series series = saveSeries("Fuera", 2010, drama);
        Map<String, Object> body = episodeBody(season, number);
        body.put("duration", duration);

        send(post(SERIES + "/" + series.getId() + "/episodes"), adminToken, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors." + field).value(message));

        assertEquals(0, count("SELECT COUNT(*) FROM episodes WHERE series_id = ?", series.getId()));
    }

    /** Título de 150 y sinopsis de 1000 caracteres caben en la base de datos; 151 en el título no. */
    @Test
    void losTextosDelEpisodioEnElLimite() throws Exception {
        Series series = saveSeries("Textos", 2010, drama);
        String url = SERIES + "/" + series.getId() + "/episodes";

        Map<String, Object> atLimit = episodeBody(1, 1);
        atLimit.put("title", "t".repeat(150));
        atLimit.put("description", "d".repeat(1000));
        send(post(url), adminToken, atLimit)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("t".repeat(150)))
                .andExpect(jsonPath("$.description").value("d".repeat(1000)));

        Map<String, Object> tooLong = episodeBody(1, 2);
        tooLong.put("title", "t".repeat(151));
        send(post(url), adminToken, tooLong)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.title").value("El título no puede superar los 150 caracteres"));

        Map<String, Object> blankTitle = episodeBody(1, 3);
        blankTitle.put("title", "   ");
        send(post(url), adminToken, blankTitle)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.title").value("El título es obligatorio"));

        assertEquals(1, count("SELECT COUNT(*) FROM episodes WHERE series_id = ?", series.getId()));
    }

    /**
     * Guardar un episodio exactamente igual (misma temporada y número) no es un
     * conflicto consigo mismo: 200 y nada cambia. Protege la exclusión del
     * propio id en la comprobación de duplicados.
     */
    @Test
    void editarUnEpisodioSinCambiarNadaDevuelve200() throws Exception {
        Series series = saveSeries("Identica", 2010, drama);
        Map<String, Object> body = episodeBody(2, 4);
        body.put("description", "Sinopsis");
        long episodeId = json(send(post(SERIES + "/" + series.getId() + "/episodes"), adminToken, body)
                .andExpect(status().isCreated())).get("id").asLong();

        send(put(SERIES + "/" + series.getId() + "/episodes/" + episodeId), adminToken, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(episodeId))
                .andExpect(jsonPath("$.seasonNumber").value(2))
                .andExpect(jsonPath("$.episodeNumber").value(4))
                .andExpect(jsonPath("$.description").value("Sinopsis"));

        assertEquals(1, count("SELECT COUNT(*) FROM episodes WHERE series_id = ?", series.getId()));
    }

    /** Al editar, una sinopsis solo con espacios, tabuladores o saltos de línea se guarda como {@code null}. */
    @Test
    void editarConUnaSinopsisEnBlancoLaDejaANull() throws Exception {
        Series series = saveSeries("Sinopsis", 2010, drama);
        Map<String, Object> body = episodeBody(1, 1);
        body.put("description", "Tenía sinopsis");
        long episodeId = json(send(post(SERIES + "/" + series.getId() + "/episodes"), adminToken, body)
                .andExpect(status().isCreated())).get("id").asLong();

        body.put("description", " \n\t ");
        JsonNode updated = json(send(put(SERIES + "/" + series.getId() + "/episodes/" + episodeId), adminToken, body)
                .andExpect(status().isOk()));

        assertExplicitNull(updated, "description");
        assertNull(episodeRepository.findById(episodeId).orElseThrow().getDescription());
    }

    /**
     * Mover un episodio a una temporada <em>distinta</em> cuya posición está
     * ocupada da 409 con los números pedidos; a una libre, 200.
     */
    @Test
    void moverUnEpisodioAOtraTemporadaOcupadaDa409YAUnaLibre200() throws Exception {
        Series series = saveSeries("Mover", 2010, drama);
        Episode first = saveEpisode(series, 1, 1);
        saveEpisode(series, 2, 3);
        String url = SERIES + "/" + series.getId() + "/episodes/" + first.getId();

        send(put(url), adminToken, episodeBody(2, 3))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EPISODE_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value("Ya existe el episodio 3 de la temporada 2"));

        send(put(url), adminToken, episodeBody(2, 4))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seasonNumber").value(2))
                .andExpect(jsonPath("$.episodeNumber").value(4));
        // Su antigua posición queda libre
        send(post(SERIES + "/" + series.getId() + "/episodes"), adminToken, episodeBody(1, 1))
                .andExpect(status().isCreated());
    }

    /**
     * Números enteros desbordados o con texto en el cuerpo: 400 sin detalles
     * internos, nunca 500.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"seasonNumber\":99999999999,\"episodeNumber\":1,\"title\":\"t\",\"duration\":40,\"videoUrl\":\"https://e.com/v.mp4\"}",
            "{\"seasonNumber\":1,\"episodeNumber\":1,\"title\":\"t\",\"duration\":\"mucho\",\"videoUrl\":\"https://e.com/v.mp4\"}",
            "{\"seasonNumber\":1,\"episodeNumber\":1,\"title\":\"t\",\"duration\":40,\"videoUrl\":\"https://e.com/v.mp4\"",
            "[]" })
    void cuerposDeEpisodioMalFormadosDan400(String body) throws Exception {
        Series series = saveSeries("Cuerpos", 2010, drama);

        String response = mockMvc.perform(post(SERIES + "/" + series.getId() + "/episodes")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value(SERIES + "/" + series.getId() + "/episodes"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(response.contains("Exception") || response.contains("jackson") || response.contains("at com."),
                "el error no debe exponer detalles internos: " + response);
        assertEquals(0, count("SELECT COUNT(*) FROM episodes WHERE series_id = ?", series.getId()));
    }

    /**
     * Un decimal en un campo entero ({@code seasonNumber: 1.5},
     * {@code duration: 45.9}) no debe guardarse truncado en silencio como la
     * temporada 1 o 45 minutos: el administrador cree haber guardado otra cosa.
     *
     * <p>
     * <b>Regresión de la incidencia QA-1 (fase 5 de series).</b> Respondía 201
     * y guardaba el valor truncado ({@code 1.5 → 1}) porque Jackson acepta
     * decimales en enteros por defecto ({@code ACCEPT_FLOAT_AS_INT}). Corregido
     * desactivando esa característica en {@code application.properties}.
     * </p>
     */
    @ParameterizedTest
    @ValueSource(strings = { "seasonNumber", "episodeNumber", "duration" })
    void unDecimalEnUnCampoEnteroNoSeTruncaEnSilencio(String field) throws Exception {
        Series series = saveSeries("Decimales", 2010, drama);
        Map<String, Object> body = episodeBody(1, 1);
        body.put(field, 1.5);

        send(post(SERIES + "/" + series.getId() + "/episodes"), adminToken, body)
                .andExpect(status().isBadRequest());

        assertEquals(0, count("SELECT COUNT(*) FROM episodes WHERE series_id = ?", series.getId()));
    }

    /**
     * Lo mismo en las demás altas con campos enteros: el año de una serie y la
     * duración de una película. Demuestra que el problema no es del DTO de
     * episodios sino de la deserialización JSON de toda la API.
     *
     * <p>
     * <b>Regresión de la incidencia QA-1</b> (sin el arreglo da {@code [201, 201]}).
     * </p>
     */
    @Test
    void unDecimalEnLosEnterosDeSeriesYPeliculasTampocoSeTrunca() throws Exception {
        Map<String, Object> seriesBody = seriesBody("Anio decimal", drama.getId());
        seriesBody.put("releaseYear", 2014.5);
        int seriesStatus = send(post(SERIES), adminToken, seriesBody).andReturn().getResponse().getStatus();

        Map<String, Object> movieBody = new HashMap<>();
        movieBody.put("title", "Duracion decimal");
        movieBody.put("description", "d");
        movieBody.put("duration", 100.5);
        movieBody.put("releaseYear", 2000);
        movieBody.put("imageUrl", "https://example.com/i.jpg");
        movieBody.put("videoUrl", "https://example.com/v.mp4");
        movieBody.put("genreIds", List.of(drama.getId()));
        int movieStatus = send(post("/api/movies"), adminToken, movieBody).andReturn().getResponse().getStatus();

        assertEquals(List.of(400, 400), List.of(seriesStatus, movieStatus), "[serie, película]");
        assertEquals(0, seriesRepository.count());
        assertEquals(0, movieRepository.count());
    }

    // ------------------------------------------------------------------
    // Series: géneros, años y portadas
    // ------------------------------------------------------------------

    /** Lista de géneros vacía, con un nulo o con ids repetidos. */
    @Test
    void generosVaciosNulosORepetidos() throws Exception {
        Map<String, Object> empty = seriesBody("Sin generos");
        send(post(SERIES), adminToken, empty)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.genreIds").value("Indica al menos un género"));

        Map<String, Object> withNull = seriesBody("Con nulo");
        withNull.put("genreIds", java.util.Arrays.asList(drama.getId(), null));
        send(post(SERIES), adminToken, withNull)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.*",
                        hasItem("Los identificadores de género no pueden ser nulos")));
        assertEquals(0, seriesRepository.count());

        // Ids repetidos: es un conjunto, la serie queda con un solo género
        send(post(SERIES), adminToken, seriesBody("Repetidos", drama.getId(), drama.getId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.genres.length()").value(1))
                .andExpect(jsonPath("$.genres[0].name").value("Drama"));
    }

    /** Los extremos de años (1888, 2100, fin igual al estreno) caben en {@code ck_series_*}. */
    @ParameterizedTest
    @CsvSource(value = { "1888, 2100", "2100, 2100", "1888, 1888", "2100, NULL" }, nullValues = "NULL")
    void losAniosLimiteDeUnaSerieSeAceptan(int releaseYear, Integer endYear) throws Exception {
        Map<String, Object> body = seriesBody("Anios", drama.getId());
        body.put("releaseYear", releaseYear);
        body.put("endYear", endYear);

        send(post(SERIES), adminToken, body)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.releaseYear").value(releaseYear));
        assertEquals(1, seriesRepository.count());
    }

    /**
     * Si el año de fin está fuera de rango <em>y</em> además es anterior al
     * estreno, solo se informa del rango (un error por campo, con el mensaje
     * útil): la comparación entre años solo opina con los dos en rango.
     */
    @Test
    void unAnioDeFinFueraDeRangoYAnteriorSoloDaElErrorDeRango() throws Exception {
        Map<String, Object> body = seriesBody("Rango", drama.getId());
        body.put("releaseYear", 1900);
        body.put("endYear", 1887);

        send(post(SERIES), adminToken, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.endYear").value(
                        "El año de finalización debe estar entre 1888 y 2100"));
    }

    /** URLs de portada que la validación debe rechazar con el mismo mensaje que películas. */
    @ParameterizedTest
    @ValueSource(strings = { "/covers/", "/covers/../secreto.webp", "ftp://example.com/a.jpg",
            "https://usuario:clave@example.com/a.jpg", "javascript:alert(1)", "//example.com/a.jpg",
            "https://example.com/con espacio.jpg" })
    void portadasNoValidasDan400(String imageUrl) throws Exception {
        Map<String, Object> body = seriesBody("Portada", drama.getId());
        body.put("imageUrl", imageUrl);

        send(post(SERIES), adminToken, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.imageUrl").value(MovieRequest.IMAGE_URL_FORMAT_MESSAGE));
        assertEquals(0, seriesRepository.count());
    }

    /**
     * Borrar por la API una serie que un usuario tenía en su lista: su lista
     * sigue funcionando (sin esa serie) y quitarla después da el 404 de serie.
     */
    @Test
    void borrarUnaSerieQueEstabaEnLaListaDeUnUsuarioNoRompeSuLista() throws Exception {
        Series doomed = visible("Condenada");
        Series kept = visible("Superviviente");
        addFavorite(userToken, doomed);
        addFavorite(userToken, kept);

        mockMvc.perform(delete(SERIES + "/" + doomed.getId()).header("Authorization", adminToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(SERIES_FAVORITES).header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Superviviente"));
        mockMvc.perform(delete(SERIES_FAVORITES + "/" + doomed.getId()).header("Authorization", userToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Serie no encontrada"));
    }

    // ------------------------------------------------------------------
    // Visibilidad: ciclo completo por la API
    // ------------------------------------------------------------------

    /**
     * Una serie creada por la API nace oculta en <em>todas</em> las rutas
     * públicas (listado, total, búsqueda por cada filtro, detalle y alta en
     * favoritos), aparece con su primer episodio, desaparece al borrar el
     * último (también de "Mi lista", aunque la fila se conserva) y reaparece al
     * subir otro. El panel la ve siempre.
     */
    @Test
    void elCicloDeVisibilidadSeReflejaEnTodasLasRutasPublicas() throws Exception {
        visible("Otra visible");
        Map<String, Object> body = seriesBody("Ciclo", comedy.getId());
        body.put("releaseYear", 1999);
        long id = json(send(post(SERIES), adminToken, body).andExpect(status().isCreated())).get("id").asLong();

        // 1) Recién creada: oculta
        assertHidden(id);
        assertEquals(2, json(getOk(ADMIN_SERIES, adminToken)).get("totalElements").asInt());

        // 2) Primer episodio: visible en todas las rutas
        long episodeId = json(send(post(SERIES + "/" + id + "/episodes"), adminToken, episodeBody(1, 1))
                .andExpect(status().isCreated())).get("id").asLong();
        assertVisible(id);
        mockMvc.perform(post(SERIES_FAVORITES + "/" + id).header("Authorization", userToken))
                .andExpect(status().isNoContent());
        assertEquals(List.of("Ciclo"), favoriteTitles());

        // 3) Se borra el último episodio: oculta otra vez, también en "Mi lista"
        mockMvc.perform(delete(SERIES + "/" + id + "/episodes/" + episodeId).header("Authorization", adminToken))
                .andExpect(status().isNoContent());
        assertHidden(id);
        assertEquals(List.of(), favoriteTitles());
        assertEquals(1, count("SELECT COUNT(*) FROM user_favorite_series WHERE series_id = ?", id),
                "la fila de favoritos se conserva");
        JsonNode adminRow = findById(json(getOk(ADMIN_SERIES, adminToken)).get("content"), id);
        assertEquals(0, adminRow.get("episodeCount").asInt());
        assertEquals(0, adminRow.get("seasonCount").asInt());

        // 4) Vuelve a tener episodios: reaparece sola en la lista del usuario
        send(post(SERIES + "/" + id + "/episodes"), adminToken, episodeBody(3, 1)).andExpect(status().isCreated());
        assertVisible(id);
        assertEquals(List.of("Ciclo"), favoriteTitles());
    }

    // ------------------------------------------------------------------
    // Búsqueda y paginación
    // ------------------------------------------------------------------

    /**
     * Unicode (emoji, CJK), comodines y barra invertida se buscan como texto
     * literal; un título solo con espacios se ignora (devuelve todo).
     */
    @Test
    void laBusquedaTrataUnicodeYComodinesComoTextoLiteral() throws Exception {
        visible("Cine 🎬 en casa");
        visible("千と千尋の神隠し");
        visible("50_50");
        visible("5050");
        visible("Ruta C:\\series");

        assertEquals(List.of("Cine 🎬 en casa"), searchTitles("🎬"));
        assertEquals(List.of("千と千尋の神隠し"), searchTitles("神隠"));
        assertEquals(List.of("50_50"), searchTitles("0_5"));
        assertEquals(List.of("Ruta C:\\series"), searchTitles("C:\\"));
        assertEquals(List.of(), searchTitles("%%"));
        assertEquals(5, searchPage(Map.of("title", "   ")).get("totalElements").asInt());
    }

    /**
     * Comillas e intentos de inyección SQL en el título se buscan como texto
     * (la búsqueda usa {@code criteriaBuilder.literal}, compartido con
     * películas en {@code LikePatterns}), tanto en la búsqueda pública como en
     * el filtro del panel, y las tablas siguen intactas.
     */
    @ParameterizedTest
    @ValueSource(strings = { "O'Brien", "x' OR '1'='1", "'; DROP TABLE series; --", "a--b", "\"comillas\"" })
    void lasComillasYLosIntentosDeInyeccionSeBuscanComoTexto(String title) throws Exception {
        visible("Con " + title + " dentro");
        visible("Normal");

        assertEquals(List.of("Con " + title + " dentro"), searchTitles(title));
        JsonNode admin = json(mockMvc.perform(get(ADMIN_SERIES).param("title", title)
                        .header("Authorization", adminToken))
                .andExpect(status().isOk()));
        assertEquals(List.of("Con " + title + " dentro"), titles(admin));
        assertEquals(2, seriesRepository.count());
    }

    /** Filtros que no casan con nada: 200 con página vacía, no 404. */
    @Test
    void filtrosSinResultadosDevuelvenPaginaVacia() throws Exception {
        visible("Algo");

        JsonNode byGenre = searchPage(Map.of("genreId", "987654"));
        assertEquals(0, byGenre.get("totalElements").asInt());
        assertEquals(0, byGenre.get("totalPages").asInt());
        assertFalse(byGenre.get("hasNext").asBoolean());
        assertEquals(0, searchPage(Map.of("releaseYear", "1700")).get("totalElements").asInt());
    }

    /** Filtros con un tipo incorrecto: 400 de validación con el nombre del parámetro, nunca 500. */
    @ParameterizedTest
    @CsvSource({ "genreId, abc", "genreId, 1.5", "releaseYear, 1999.5", "releaseYear, 2147483648" })
    void filtrosMalFormadosDan400(String parameter, String value) throws Exception {
        mockMvc.perform(get(SEARCH).param(parameter, value).header("Authorization", userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors." + parameter).exists());
    }

    /**
     * Campos que existen en la serie o en su JSON pero no están en la lista
     * blanca (incluidos los recuentos, que no son columnas, y las relaciones):
     * 400, nunca un 500 de Hibernate.
     */
    @ParameterizedTest
    @ValueSource(strings = { "endYear", "imageUrl", "description", "seasonCount", "episodeCount", "genres",
            "genres.name", "seasons", "title,desc", "TITLE" })
    void camposDeOrdenacionNoPermitidosEnSeries(String sort) throws Exception {
        for (String url : List.of(SERIES, SEARCH)) {
            mockMvc.perform(get(url).param("sort", sort).header("Authorization", userToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.validationErrors.sort").value(
                            "Campo de ordenación no permitido. Valores válidos: createdAt, id, releaseYear, title"));
        }
    }

    /**
     * La dirección no distingue mayúsculas; una desconocida da 400. Los
     * parámetros vacíos ({@code sort=}, {@code direction=}) usan el valor por
     * defecto, igual que en películas ({@code CatalogEdgeCasesIntegrationTest}).
     */
    @Test
    void laDireccionNoDistingueMayusculasYLosVaciosUsanElValorPorDefecto() throws Exception {
        visible("B");
        visible("A");

        assertEquals(List.of("B", "A"), titles(json(getOk(SERIES + "?direction=DeSc", userToken))));
        assertEquals(List.of("A", "B"), titles(json(getOk(SERIES + "?direction=ASC", userToken))));
        assertEquals(List.of("A", "B"), titles(json(getOk(SERIES + "?sort=&direction=", userToken))));
        mockMvc.perform(get(SERIES).param("direction", "descendente").header("Authorization", userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.direction").exists());
    }

    /**
     * Límite exacto del desplazamiento: {@code page × size} justo por debajo de
     * {@code Integer.MAX_VALUE} es una página vacía válida (200, sin
     * {@code hasNext}); justo por encima, 400. Una página posterior a la última
     * también es 200 vacía.
     */
    @Test
    void elLimiteExactoDelDesplazamientoYLasPaginasPosterioresALaUltima() throws Exception {
        visible("Unica");

        JsonNode edge = json(getOk(SERIES + "?page=21474836&size=100", userToken));
        assertEquals(0, edge.get("content").size());
        assertFalse(edge.get("hasNext").asBoolean());
        assertTrue(edge.get("hasPrevious").asBoolean());
        assertEquals(1, edge.get("totalElements").asInt());

        mockMvc.perform(get(SERIES).param("page", "21474837").param("size", "100").header("Authorization", userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.page").value(
                        "La página solicitada es demasiado grande para el tamaño de página indicado"));

        JsonNode afterLast = json(getOk(SERIES + "?page=5&size=10", userToken));
        assertEquals(0, afterLast.get("content").size());
        assertEquals(1, afterLast.get("totalElements").asInt());
    }

    /**
     * Regresión de películas tras extraer {@code PageableFactory} y
     * {@code LikePatterns}: su lista blanca sigue incluyendo {@code duration}
     * (y el mensaje la nombra), los campos de series no se cuelan y la
     * búsqueda sigue escapando comodines.
     */
    @Test
    void lasPeliculasConservanSuListaBlancaYSuBusquedaTrasExtraerLoComun() throws Exception {
        saveMovie("50_50", 2000, 120);
        saveMovie("5050", 2001, 90);

        JsonNode byDuration = json(getOk("/api/movies?sort=duration", userToken));
        assertEquals(List.of("5050", "50_50"), titles(byDuration));
        mockMvc.perform(get("/api/movies").param("sort", "endYear").header("Authorization", userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.sort").value(
                        "Campo de ordenación no permitido. Valores válidos: createdAt, duration, id, releaseYear, title"));
        mockMvc.perform(get("/api/movies/search").param("title", "0_5").header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("50_50"));
        mockMvc.perform(get("/api/movies").param("page", "21474837").param("size", "100")
                        .header("Authorization", userToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.page").exists());
    }

    // ------------------------------------------------------------------
    // Errores de protocolo en las rutas de series
    // ------------------------------------------------------------------

    /** 405, 415, JSON mal formado, ids mal formados y rutas inexistentes: códigos de cliente, nunca 500. */
    @Test
    void erroresDeProtocoloEnLasRutasDeSeries() throws Exception {
        Series series = visible("Protocolo");
        String id = String.valueOf(series.getId());

        mockMvc.perform(patch(SERIES + "/" + id).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mockMvc.perform(post(ADMIN_SERIES).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(get(SERIES + "/" + id + "/episodes").header("Authorization", userToken))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(post(SERIES).header("Authorization", adminToken)
                        .contentType(MediaType.TEXT_PLAIN).content("serie"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mockMvc.perform(put(SERIES + "/" + id).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mockMvc.perform(post(SERIES_FAVORITES + "/abc").header("Authorization", userToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get(SERIES + "/" + id + "/temporadas").header("Authorization", userToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(get(ADMIN_SERIES + "/abc").header("Authorization", adminToken))
                .andExpect(status().isBadRequest());

        assertEquals("Protocolo", seriesRepository.findById(series.getId()).orElseThrow().getTitle());
    }

    // ------------------------------------------------------------------
    // Géneros usados por series
    // ------------------------------------------------------------------

    /** Una serie oculta (sin episodios) también cuenta: borrar su género la dejaría sin él. */
    @Test
    void unGeneroQueSoloUsaUnaSerieOcultaTampocoSePuedeBorrar() throws Exception {
        saveSeries("Oculta", 2010, comedy);

        mockMvc.perform(delete("/api/genres/" + comedy.getId()).header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENRE_IN_USE"))
                .andExpect(jsonPath("$.message").value("No se puede eliminar el género \"Comedia\": lo usa "
                        + "1 serie. Quítalo de esa serie antes de borrarlo."));
        assertTrue(genreRepository.existsById(comedy.getId()));
    }

    /** Combinaciones de singular y plural que no cubrían los demás tests de la API. */
    @Test
    void elMensajeDeGeneroEnUsoConMezclasDeSingularYPlural() throws Exception {
        saveMovie("Peli drama", 2000, 100, drama);
        saveSeries("Serie drama 1", 2010, drama);
        saveSeries("Serie drama 2", 2011, drama);
        saveMovie("Peli comedia 1", 2000, 100, comedy);
        saveMovie("Peli comedia 2", 2001, 100, comedy);
        saveSeries("Serie comedia", 2010, comedy);

        mockMvc.perform(delete("/api/genres/" + drama.getId()).header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("No se puede eliminar el género \"Drama\": lo usan "
                        + "1 película y 2 series. Quítalo de esa película y de esas series antes de borrarlo."));
        mockMvc.perform(delete("/api/genres/" + comedy.getId()).header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("No se puede eliminar el género \"Comedia\": lo usan "
                        + "2 películas y 1 serie. Quítalo de esas películas y de esa serie antes de borrarlo."));
    }

    /**
     * Con solo películas el mensaje es exactamente el anterior a las series
     * (singular), y un género sin uso se borra.
     */
    @Test
    void conSoloPeliculasElMensajeNoMencionaSeriesYUnGeneroLibreSeBorra() throws Exception {
        saveMovie("Unica", 2000, 100, drama);

        mockMvc.perform(delete("/api/genres/" + drama.getId()).header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("No se puede eliminar el género \"Drama\": lo usa "
                        + "1 película. Quítalo de esa película antes de borrarlo."));
        mockMvc.perform(delete("/api/genres/" + comedy.getId()).header("Authorization", adminToken))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // Consultas por operación (N+1)
    // ------------------------------------------------------------------

    /**
     * "Mi lista" de series cuesta lo mismo con 1 que con 12 series (≤ 3
     * consultas) y una lista vacía no lanza la consulta de recuentos.
     */
    @Test
    void laListaDeSeriesCuestaLoMismoConUnaQueConDoce() {
        assertEquals(1, statementsFor(() -> seriesFavoriteService.getFavorites(user.getId())),
                "lista vacía: solo la consulta de la lista");

        Series first = visible("Primera");
        favorite(user, first);
        long one = statementsFor(() -> seriesFavoriteService.getFavorites(user.getId()));

        for (int i = 0; i < 11; i++) {
            Series series = saveSeries(String.format("Serie %02d", i), 2000, drama, comedy);
            saveEpisode(series, 1, 1);
            saveEpisode(series, 2, 1);
            favorite(user, series);
        }
        long twelve = statementsFor(() -> seriesFavoriteService.getFavorites(user.getId()));

        assertTrue(one <= 3, "una serie: " + one + " consultas");
        assertEquals(one, twelve, "el coste no debe depender del número de series");
    }

    /** La búsqueda con todos los filtros cuesta lo mismo con páginas de 5 que de 25. */
    @Test
    void laBusquedaConFiltrosNoCreceConElTamanoDePagina() {
        for (int i = 0; i < 30; i++) {
            Series series = saveSeries(String.format("Buscable %02d", i), 2000, drama, comedy);
            saveEpisode(series, 1, 1);
        }
        org.springframework.data.domain.PageRequest small = org.springframework.data.domain.PageRequest.of(0, 5,
                org.springframework.data.domain.Sort.by("title").and(org.springframework.data.domain.Sort.by("id")));
        org.springframework.data.domain.PageRequest large = org.springframework.data.domain.PageRequest.of(0, 25,
                org.springframework.data.domain.Sort.by("title").and(org.springframework.data.domain.Sort.by("id")));

        long five = statementsFor(() -> seriesService.searchSeries("buscable", drama.getId(), 2000, small));
        long twentyFive = statementsFor(() -> seriesService.searchSeries("buscable", drama.getId(), 2000, large));
        long adminFive = statementsFor(() -> seriesService.getAdminSeries("buscable", small));
        long adminTwentyFive = statementsFor(() -> seriesService.getAdminSeries("buscable", large));

        assertTrue(five <= 4, "búsqueda de 5: " + five);
        assertEquals(five, twentyFive);
        assertEquals(adminFive, adminTwentyFive);
    }

    // ------------------------------------------------------------------
    // Concurrencia real (varios hilos a la vez contra H2)
    // ------------------------------------------------------------------

    /**
     * Cuatro altas simultáneas del mismo episodio, en varias rondas: siempre
     * exactamente un 201 y tres 409 {@code EPISODE_ALREADY_EXISTS}, y un solo
     * episodio en la base de datos. Gane quien gane la carrera (la comprobación
     * previa o la restricción única), el cliente ve el mismo 409, nunca un 500.
     */
    @Test
    void altasSimultaneasDelMismoEpisodioDanUnExitoYElRestoConflicto() throws Exception {
        Series series = saveSeries("Concurrente", 2010, drama);
        String url = SERIES + "/" + series.getId() + "/episodes";

        for (int round = 1; round <= 5; round++) {
            String body = objectMapper.writeValueAsString(episodeBody(1, round));
            List<Integer> statuses = concurrently(4, () -> mockMvc.perform(post(url)
                            .header("Authorization", adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn().getResponse().getStatus());

            assertEquals(List.of(201, 409, 409, 409), sorted(statuses), "ronda " + round + ": " + statuses);
        }
        assertEquals(5, count("SELECT COUNT(*) FROM episodes WHERE series_id = ?", series.getId()));
    }

    /**
     * Cuatro altas simultáneas de la misma serie en "Mi lista" del mismo
     * usuario: un 204 y tres 409 {@code SERIES_ALREADY_IN_FAVORITES}, y una
     * sola fila.
     */
    @Test
    void altasSimultaneasDelMismoFavoritoDanUnExitoYElRestoConflicto() throws Exception {
        for (int round = 1; round <= 5; round++) {
            Series series = visible("Favorita " + round);
            String url = SERIES_FAVORITES + "/" + series.getId();

            List<Integer> statuses = concurrently(4, () -> mockMvc.perform(post(url)
                            .header("Authorization", userToken))
                    .andReturn().getResponse().getStatus());

            assertEquals(List.of(204, 409, 409, 409), sorted(statuses), "ronda " + round + ": " + statuses);
            assertEquals(1, count("SELECT COUNT(*) FROM user_favorite_series WHERE series_id = ?", series.getId()));
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * Lanza {@code threads} tareas a la vez (todas esperan en una barrera y
     * salen juntas) y devuelve sus resultados.
     */
    private <T> List<T> concurrently(int threads, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static List<Integer> sorted(List<Integer> values) {
        return values.stream().sorted().toList();
    }

    /** La serie no aparece ni se cuenta en ninguna ruta pública y su detalle y alta en favoritos dan 404. */
    private void assertHidden(long id) throws Exception {
        assertEquals(1, json(getOk(SERIES, userToken)).get("totalElements").asInt());
        assertEquals(0, searchPage(Map.of("title", "Ciclo")).get("totalElements").asInt());
        assertEquals(0, searchPage(Map.of("genreId", String.valueOf(comedy.getId()))).get("totalElements").asInt());
        assertEquals(0, searchPage(Map.of("releaseYear", "1999")).get("totalElements").asInt());
        assertEquals(1, searchPage(Map.of()).get("totalElements").asInt());
        mockMvc.perform(get(SERIES + "/" + id).header("Authorization", userToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Serie no encontrada"));
        mockMvc.perform(get(SERIES + "/" + id).header("Authorization", adminToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(ADMIN_SERIES + "/" + id).header("Authorization", adminToken))
                .andExpect(status().isOk());
    }

    /** La serie aparece y se cuenta en el listado, en la búsqueda por cada filtro y en su detalle. */
    private void assertVisible(long id) throws Exception {
        JsonNode list = json(getOk(SERIES, userToken));
        assertEquals(2, list.get("totalElements").asInt());
        assertTrue(findById(list.get("content"), id) != null);
        assertEquals(1, searchPage(Map.of("title", "Ciclo")).get("totalElements").asInt());
        assertEquals(1, searchPage(Map.of("genreId", String.valueOf(comedy.getId()))).get("totalElements").asInt());
        assertEquals(1, searchPage(Map.of("releaseYear", "1999")).get("totalElements").asInt());
        assertEquals(2, searchPage(Map.of()).get("totalElements").asInt());
        mockMvc.perform(get(SERIES + "/" + id).header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.episodeCount").value(1));
    }

    private List<String> favoriteTitles() throws Exception {
        List<String> titles = new ArrayList<>();
        json(getOk(SERIES_FAVORITES, userToken)).forEach(node -> titles.add(node.get("title").asText()));
        return titles;
    }

    private static JsonNode findById(JsonNode content, long id) {
        for (JsonNode node : content) {
            if (node.get("id").asLong() == id) {
                return node;
            }
        }
        return null;
    }

    private JsonNode searchPage(Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder request = get(SEARCH).header("Authorization", userToken);
        params.forEach(request::param);
        return json(mockMvc.perform(request).andExpect(status().isOk()));
    }

    /** Busca por título pasando el texto con {@code .param} (sin codificar dos veces {@code %} ni {@code \}). */
    private List<String> searchTitles(String title) throws Exception {
        return titles(searchPage(Map.of("title", title)));
    }

    private static List<String> titles(JsonNode page) {
        List<String> titles = new ArrayList<>();
        page.get("content").forEach(node -> titles.add(node.get("title").asText()));
        return titles;
    }

    private ResultActions getOk(String url, String token) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", token)).andExpect(status().isOk());
    }

    private JsonNode json(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static void assertExplicitNull(JsonNode node, String field) {
        assertTrue(node.has(field), "el campo " + field + " debe ir en el JSON (como null), no omitirse: " + node);
        assertTrue(node.get(field).isNull(), field + " debería ser null: " + node);
    }

    private ResultActions send(MockHttpServletRequestBuilder builder, String token, Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(builder.header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private void addFavorite(String token, Series series) throws Exception {
        mockMvc.perform(post(SERIES_FAVORITES + "/" + series.getId()).header("Authorization", token))
                .andExpect(status().isNoContent());
    }

    private void favorite(User owner, Series series) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(s -> seriesRepository.addFavorite(owner.getId(), series.getId()));
    }

    private long statementsFor(Runnable operation) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        operation.run();
        return statistics.getPrepareStatementCount();
    }

    private Map<String, Object> seriesBody(String title, Long... genreIds) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("description", "Sinopsis de " + title);
        body.put("releaseYear", 2014);
        body.put("imageUrl", "https://example.com/serie.jpg");
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

    private Movie saveMovie(String title, int year, int duration, Genre... genres) {
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setDescription("Descripción de " + title);
        movie.setDuration(duration);
        movie.setReleaseYear(year);
        movie.setImageUrl("https://example.com/i.jpg");
        movie.setVideoUrl("https://example.com/v.mp4");
        movie.setGenres(new HashSet<>(Set.of(genres.length == 0 ? new Genre[] { drama } : genres)));
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

    /** Serie visible (un episodio 1x1) del año 2000 con el género Drama. */
    private Series visible(String title) {
        Series series = saveSeries(title, 2000, drama);
        saveEpisode(series, 1, 1);
        return series;
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
