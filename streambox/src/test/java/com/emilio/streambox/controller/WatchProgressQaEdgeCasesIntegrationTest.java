package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Revisión independiente (QA) del progreso de visionado y de «Seguir viendo»:
 * los casos límite que {@link WatchProgressIntegrationTest} no cubre.
 *
 * <ul>
 *   <li><b>Cuerpos raros:</b> números que no caben en un {@code int}, notación
 *       exponencial, booleanos, {@code null} literal, un array, JSON roto y
 *       campos de más. Ninguno debe acabar en 500 ni escribir nada.</li>
 *   <li><b>Tipo de contenido:</b> texto, formulario, YAML o sin
 *       {@code Content-Type} dan 415 (la API solo habla JSON).</li>
 *   <li><b>Métodos:</b> los que no existen dan 405, no 500 ni 403.</li>
 *   <li><b>Sesión:</b> una cuenta pendiente de confirmar el correo no lee ni
 *       escribe progreso con un token aún vivo; un ADMIN no tiene ninguna ruta
 *       para leer el progreso de otro usuario por su id.</li>
 *   <li><b>Fronteras del 95 %</b> y de los valores admitidos, y que volver a
 *       ver algo terminado lo devuelve a «Seguir viendo».</li>
 *   <li><b>Orden estable</b> con empates de fecha y <b>una tarjeta por
 *       serie</b> cuando se borra el episodio que la ocupaba.</li>
 * </ul>
 *
 * <p>
 * Sin {@code @Transactional}, como la suite original: los guardados hacen
 * commit real (el repositorio usa {@code JdbcClient}) y se limpia en
 * {@code @BeforeEach}/{@code @AfterEach}.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class WatchProgressQaEdgeCasesIntegrationTest {

    private static final String PROGRESS = "/api/users/me/progress";
    private static final String MOVIE_PROGRESS = PROGRESS + "/movies";
    private static final String EPISODE_PROGRESS = PROGRESS + "/episodes";
    private static final String CONTINUE = "/api/users/me/continue-watching";
    private static final Instant BASE = Instant.parse("2026-02-01T00:00:00Z");

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
    private Episode dark12;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        alice = saveUser("qaprogalice", Role.USER, true);
        aliceToken = bearer(alice);
        drama = saveGenre("Drama QA");
        dune = saveMovie("Dune", "https://example.com/dune.mp4");
        dark = saveSeries("Dark");
        dark11 = saveEpisode(dark, 1, 1, "https://example.com/1x01.mp4");
        dark12 = saveEpisode(dark, 1, 2, "https://example.com/1x02.mp4");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Cuerpos raros
    // ------------------------------------------------------------------

    /**
     * Cuerpos que Jackson no puede convertir en un {@code WatchProgressRequest}
     * válido: 400 {@code MALFORMED_REQUEST} con la ruta y sin detalles internos,
     * nunca un 500, y sin escribir nada. Incluye un número que no cabe en un
     * {@code int} (un cliente que mandara milisegundos de una película larga) y
     * la notación exponencial, que es un decimal para JSON.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"positionSeconds\":2147483648,\"durationSeconds\":100}",
            "{\"positionSeconds\":10,\"durationSeconds\":99999999999999999999}",
            "{\"positionSeconds\":1e1,\"durationSeconds\":100}",
            "{\"positionSeconds\":true,\"durationSeconds\":100}",
            "{\"positionSeconds\":[10],\"durationSeconds\":100}",
            "{\"positionSeconds\":{},\"durationSeconds\":100}",
            "[]",
            "\"10\"",
            "{\"positionSeconds\":10,",
            "{positionSeconds:10,durationSeconds:100}"
    })
    void unCuerpoQueNoEsUnProgresoDa400MalformedSinEscribirNada(String body) throws Exception {
        String path = MOVIE_PROGRESS + "/" + dune.getId();
        mockMvc.perform(put(path).header("Authorization", aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value(path))
                .andExpect(jsonPath("$.trace").doesNotExist());
        assertEquals(0, countRows("user_movie_progress"));
    }

    /**
     * El literal {@code null} como cuerpo entero no es un progreso: 400 (no un
     * 500 por un {@code null} que llegue al servicio). Da igual si lo trata el
     * lector de JSON o la validación: lo que importa es el 400 y que no se escribe.
     */
    @Test
    void elCuerpoNullDa400() throws Exception {
        mockMvc.perform(put(MOVIE_PROGRESS + "/" + dune.getId()).header("Authorization", aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("null"))
                .andExpect(status().isBadRequest());
        assertEquals(0, countRows("user_movie_progress"));
    }

    /**
     * Un objeto vacío o con los dos campos a {@code null}: 400
     * {@code VALIDATION_ERROR} con el mensaje de cada campo obligatorio.
     */
    @Test
    void unObjetoVacioDa400ConLosDosCampos() throws Exception {
        for (String body : List.of("{}", "{\"positionSeconds\":null,\"durationSeconds\":null}")) {
            mockMvc.perform(put(EPISODE_PROGRESS + "/" + dark11.getId()).header("Authorization", aliceToken)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.validationErrors.positionSeconds").value("La posición es obligatoria"))
                    .andExpect(jsonPath("$.validationErrors.durationSeconds").value("La duración es obligatoria"));
        }
        assertEquals(0, countRows("user_episode_progress"));
    }

    /**
     * Campos de más (un {@code userId} o un {@code finished} inventados por el
     * cliente) se ignoran: no permiten escribir el progreso de otro usuario ni
     * marcar algo como visto. El guardado vale con los dos campos conocidos.
     */
    @Test
    void losCamposDeMasSeIgnoranYNoCambianDeQuienEsNiSiEstaTerminado() throws Exception {
        User bob = saveUser("qaprogbob", Role.USER, true);
        String body = "{\"positionSeconds\":10,\"durationSeconds\":100,\"userId\":" + bob.getId()
                + ",\"finished\":true,\"updatedAt\":\"2000-01-01T00:00:00Z\"}";

        mockMvc.perform(put(MOVIE_PROGRESS + "/" + dune.getId()).header("Authorization", aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNoContent());

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_movie_progress WHERE user_id = ?",
                Integer.class, bob.getId()));
        mockMvc.perform(get(MOVIE_PROGRESS + "/" + dune.getId()).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.finished").value(false))
                .andExpect(jsonPath("$.positionSeconds").value(10));
    }

    /**
     * Un cuerpo que no es JSON (texto, formulario, YAML) o sin
     * {@code Content-Type}: 415 {@code UNSUPPORTED_MEDIA_TYPE}, sin escribir.
     * El formulario importa en particular: un {@code PUT} de formulario es lo
     * que otra web podría intentar mandar.
     */
    @Test
    void unCuerpoQueNoEsJsonDa415() throws Exception {
        String json = "{\"positionSeconds\":10,\"durationSeconds\":100}";
        List<MockHttpServletRequestBuilder> requests = List.of(
                put(MOVIE_PROGRESS + "/" + dune.getId()).contentType(MediaType.TEXT_PLAIN).content(json),
                put(MOVIE_PROGRESS + "/" + dune.getId()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("positionSeconds=10&durationSeconds=100"),
                put(EPISODE_PROGRESS + "/" + dark11.getId()).contentType("application/yaml")
                        .content("positionSeconds: 10\ndurationSeconds: 100\n"),
                put(EPISODE_PROGRESS + "/" + dark11.getId()).content(json));

        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(request.header("Authorization", aliceToken))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        }
        assertEquals(0, countRows("user_movie_progress") + countRows("user_episode_progress"));
    }

    // ------------------------------------------------------------------
    // Ids y métodos
    // ------------------------------------------------------------------

    /**
     * Un id que no cabe en un {@code long} es 400, no 500; uno negativo o cero
     * es simplemente un título que no existe (404 al leer, 204 al quitar).
     */
    @Test
    void idsFueraDeRangoNegativosOCero() throws Exception {
        mockMvc.perform(get(MOVIE_PROGRESS + "/99999999999999999999").header("Authorization", aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        for (String id : List.of("-1", "0")) {
            mockMvc.perform(get(MOVIE_PROGRESS + "/" + id).header("Authorization", aliceToken))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get(EPISODE_PROGRESS + "/" + id).header("Authorization", aliceToken))
                    .andExpect(status().isNotFound());
            mockMvc.perform(put(MOVIE_PROGRESS + "/" + id).header("Authorization", aliceToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"positionSeconds\":1,\"durationSeconds\":2}"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(delete(CONTINUE + "/movies/" + id).header("Authorization", aliceToken))
                    .andExpect(status().isNoContent());
        }
    }

    /** Los métodos que no existen en estas rutas dan 405 {@code METHOD_NOT_ALLOWED}. */
    @Test
    void losMetodosQueNoExistenDan405() throws Exception {
        String json = "{\"positionSeconds\":1,\"durationSeconds\":2}";
        List<MockHttpServletRequestBuilder> requests = List.of(
                post(MOVIE_PROGRESS + "/" + dune.getId()).contentType(MediaType.APPLICATION_JSON).content(json),
                patch(EPISODE_PROGRESS + "/" + dark11.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content(json),
                delete(MOVIE_PROGRESS + "/" + dune.getId()),
                put(PROGRESS + "/series/" + dark.getId()).contentType(MediaType.APPLICATION_JSON).content(json),
                post(CONTINUE).contentType(MediaType.APPLICATION_JSON).content("{}"),
                get(CONTINUE + "/movies/" + dune.getId()));

        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(request.header("Authorization", aliceToken).header("X-Requested-With", "StreamBox"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        }
    }

    /**
     * {@code HEAD} de las lecturas responde igual que {@code GET} para el propio
     * usuario (las reglas de lectura admiten {@code GET} y {@code HEAD}). El
     * cuerpo no se comprueba: quien lo quita en un {@code HEAD} es Tomcat, y
     * MockMvc no lo hace.
     */
    @Test
    void headDeLasLecturasFunciona() throws Exception {
        save(MOVIE_PROGRESS, dune.getId(), 10, 100);
        mockMvc.perform(head(MOVIE_PROGRESS + "/" + dune.getId()).header("Authorization", aliceToken))
                .andExpect(status().isOk());
        mockMvc.perform(head(MOVIE_PROGRESS + "/" + dune.getId()).header("Authorization", bearer(
                        saveUser("qaprogheadbob", Role.USER, true))))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        mockMvc.perform(head(CONTINUE).header("Authorization", aliceToken))
                .andExpect(status().isOk());
    }

    /**
     * {@code limit} vacío usa el valor por defecto; uno que no cabe en un
     * {@code int} es 400, no 500.
     */
    @Test
    void limitVacioOEnormeEnSeguirViendo() throws Exception {
        save(MOVIE_PROGRESS, dune.getId(), 10, 100);
        mockMvc.perform(get(CONTINUE).param("limit", "").header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        for (String limit : List.of("99999999999", "1.5", "%00")) {
            mockMvc.perform(get(CONTINUE).param("limit", limit).header("Authorization", aliceToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    // ------------------------------------------------------------------
    // Sesión y propiedad
    // ------------------------------------------------------------------

    /**
     * Una cuenta que aún no ha confirmado el correo no usa una sesión que siga
     * viva (el filtro JWT la rechaza): ni lee ni escribe progreso.
     */
    @Test
    void unaCuentaPendienteNoLeeNiEscribeProgreso() throws Exception {
        User pending = saveUser("qaprogpending", Role.USER, false);
        String token = bearer(pending);
        List<MockHttpServletRequestBuilder> requests = List.of(
                put(MOVIE_PROGRESS + "/" + dune.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positionSeconds\":10,\"durationSeconds\":100}"),
                get(MOVIE_PROGRESS + "/" + dune.getId()), get(CONTINUE),
                delete(CONTINUE + "/series/" + dark.getId()));

        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(request.header("Authorization", token))
                    .andExpect(status().isUnauthorized());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_movie_progress WHERE user_id = ?",
                Integer.class, pending.getId()));
    }

    /**
     * Un ADMIN no tiene forma de leer el progreso de otro usuario poniendo su
     * id en la URL (no existe ninguna ruta con el id: 404), y sus propias
     * rutas {@code /me} le devuelven solo lo suyo.
     */
    @Test
    void unAdminNoLeeElProgresoDeOtroPorUrl() throws Exception {
        save(MOVIE_PROGRESS, dune.getId(), 10, 100);
        User admin = saveUser("qaprogadmin", Role.ADMIN, true);
        String adminToken = bearer(admin);

        for (String url : List.of("/api/users/" + alice.getId() + "/progress/movies/" + dune.getId(),
                "/api/users/" + alice.getId() + "/continue-watching")) {
            mockMvc.perform(get(url).header("Authorization", adminToken))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.positionSeconds").doesNotExist());
        }
        mockMvc.perform(get(CONTINUE).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get(MOVIE_PROGRESS + "/" + dune.getId()).header("Authorization", adminToken))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // Fronteras
    // ------------------------------------------------------------------

    /**
     * Fronteras admitidas: posición 0, duración 1 y 86400, posición igual a la
     * duración (terminado). Y el 95 % exacto, al segundo: 9499/10000 sigue a
     * medias, 9500/10000 ya está visto.
     */
    @Test
    void lasFronterasDeLosValoresYDel95PorCiento() throws Exception {
        save(MOVIE_PROGRESS, dune.getId(), 0, 1);
        expectProgress(MOVIE_PROGRESS, dune.getId(), 0, 1, false);
        save(MOVIE_PROGRESS, dune.getId(), 1, 1);
        expectProgress(MOVIE_PROGRESS, dune.getId(), 1, 1, true);
        save(MOVIE_PROGRESS, dune.getId(), 86_400, 86_400);
        expectProgress(MOVIE_PROGRESS, dune.getId(), 86_400, 86_400, true);

        save(MOVIE_PROGRESS, dune.getId(), 9_499, 10_000);
        expectProgress(MOVIE_PROGRESS, dune.getId(), 9_499, 10_000, false);
        assertEquals(1, continueWatching().size());

        save(MOVIE_PROGRESS, dune.getId(), 9_500, 10_000);
        expectProgress(MOVIE_PROGRESS, dune.getId(), 9_500, 10_000, true);
        assertEquals(0, continueWatching().size());
    }

    /**
     * Volver a ver algo terminado: el siguiente guardado (una posición menor)
     * lo devuelve a «Seguir viendo» con la posición nueva.
     */
    @Test
    void volverAVerAlgoTerminadoLoDevuelveASeguirViendo() throws Exception {
        save(MOVIE_PROGRESS, dune.getId(), 100, 100);
        assertEquals(0, continueWatching().size());

        save(MOVIE_PROGRESS, dune.getId(), 12, 100);

        JsonNode row = continueWatching();
        assertEquals(1, row.size());
        assertEquals(12, row.get(0).get("positionSeconds").asInt());
    }

    // ------------------------------------------------------------------
    // Orden y «una tarjeta por serie»
    // ------------------------------------------------------------------

    /**
     * Con la misma fecha de guardado, el orden es estable y el documentado:
     * primero las películas y, dentro de cada tipo, el id mayor.
     */
    @Test
    void conLaMismaFechaElOrdenEsPeliculasPrimeroYDespuesIdMayor() throws Exception {
        Movie alien = saveMovie("Alien", "https://example.com/alien.mp4");
        Series lost = saveSeries("Lost");
        Episode lost11 = saveEpisode(lost, 1, 1, "https://example.com/lost.mp4");
        save(MOVIE_PROGRESS, dune.getId(), 10, 100);
        save(MOVIE_PROGRESS, alien.getId(), 10, 100);
        save(EPISODE_PROGRESS, dark11.getId(), 10, 100);
        save(EPISODE_PROGRESS, lost11.getId(), 10, 100);
        rewindAllTo(BASE);

        List<String> expected = List.of("MOVIE:" + Math.max(dune.getId(), alien.getId()),
                "MOVIE:" + Math.min(dune.getId(), alien.getId()),
                "EPISODE:" + Math.max(dark11.getId(), lost11.getId()),
                "EPISODE:" + Math.min(dark11.getId(), lost11.getId()));
        for (int i = 0; i < 3; i++) {
            assertEquals(expected, keys(continueWatching()));
        }
    }

    /**
     * Si se borra el episodio que ocupaba la tarjeta de la serie (desde el
     * panel), la tarjeta no desaparece ni se duplica: la ocupa el otro
     * episodio a medias de esa serie.
     */
    @Test
    void borrarElEpisodioDeLaTarjetaDejaElOtroEpisodioAMedias() throws Exception {
        save(EPISODE_PROGRESS, dark11.getId(), 10, 100);
        setUpdatedAt(dark11.getId(), BASE);
        save(EPISODE_PROGRESS, dark12.getId(), 20, 100);
        setUpdatedAt(dark12.getId(), BASE.plusSeconds(60));
        assertEquals(List.of("EPISODE:" + dark12.getId()), keys(continueWatching()));

        episodeRepository.deleteById(dark12.getId());

        JsonNode row = continueWatching();
        assertEquals(List.of("EPISODE:" + dark11.getId()), keys(row));
        assertEquals(dark.getId().longValue(), row.get(0).get("seriesId").asLong());
    }

    /**
     * Si el episodio más reciente de la tarjeta se queda sin vídeo, la tarjeta
     * pasa al otro episodio a medias que sí tiene: nunca una tarjeta cuyo
     * «Reanudar» sea imposible.
     */
    @Test
    void siElEpisodioDeLaTarjetaPierdeElVideoLaOcupaOtroConVideo() throws Exception {
        save(EPISODE_PROGRESS, dark11.getId(), 10, 100);
        setUpdatedAt(dark11.getId(), BASE);
        save(EPISODE_PROGRESS, dark12.getId(), 20, 100);
        setUpdatedAt(dark12.getId(), BASE.plusSeconds(60));

        jdbc.update("UPDATE episodes SET video_url = NULL WHERE id = ?", dark12.getId());

        assertEquals(List.of("EPISODE:" + dark11.getId()), keys(continueWatching()));
        mockMvc.perform(put(EPISODE_PROGRESS + "/" + dark12.getId()).header("Authorization", aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positionSeconds\":30,\"durationSeconds\":100}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_VIDEO_AVAILABLE"));
    }

    /**
     * Borrar la cuenta borra su progreso en cascada (sin dejar filas
     * huérfanas que fallen la clave foránea).
     */
    @Test
    void borrarLaCuentaBorraSuProgreso() throws Exception {
        save(MOVIE_PROGRESS, dune.getId(), 10, 100);
        save(EPISODE_PROGRESS, dark11.getId(), 10, 100);

        userRepository.deleteById(alice.getId());

        assertEquals(0, countRows("user_movie_progress") + countRows("user_episode_progress"));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private String bearer(User user) {
        return "Bearer " + jwtService.generateToken(user);
    }

    private void save(String base, long id, int position, int duration) throws Exception {
        mockMvc.perform(put(base + "/" + id).header("Authorization", aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"positionSeconds\":" + position + ",\"durationSeconds\":" + duration + "}"))
                .andExpect(status().isNoContent());
    }

    private void expectProgress(String base, long id, int position, int duration, boolean finished)
            throws Exception {
        mockMvc.perform(get(base + "/" + id).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positionSeconds").value(position))
                .andExpect(jsonPath("$.durationSeconds").value(duration))
                .andExpect(jsonPath("$.finished").value(finished));
    }

    private JsonNode continueWatching() throws Exception {
        String json = mockMvc.perform(get(CONTINUE).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json);
    }

    private static List<String> keys(JsonNode row) {
        return row.valueStream().map(node -> node.get("type").asText() + ":" + node.get("id").asLong()).toList();
    }

    private int countRows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Integer.class,
                alice.getId());
    }

    /**
     * Fija la fecha del último guardado. Si el episodio está visto, su
     * {@code finished_at} se lleva a la misma fecha: la {@code V15} exige
     * {@code finished_at <= updated_at} y aquí se mueve la fecha hacia atrás.
     */
    private void setUpdatedAt(long episodeId, Instant at) {
        OffsetDateTime when = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        jdbc.update("UPDATE user_episode_progress SET updated_at = ?, finished_at = CASE WHEN finished_at IS NOT NULL"
                + " THEN CAST(? AS TIMESTAMP WITH TIME ZONE) END WHERE user_id = ? AND episode_id = ?",
                when, when, alice.getId(), episodeId);
    }

    /**
     * Pone el mismo último guardado a <b>todo</b> el progreso (películas y
     * episodios, de cualquier usuario), moviendo también {@code finished_at} si
     * está visto (la {@code V15} exige {@code finished_at <= updated_at}).
     */
    private void rewindAllTo(Instant at) {
        OffsetDateTime when = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
        for (String table : List.of("user_movie_progress", "user_episode_progress")) {
            jdbc.update("UPDATE " + table + " SET updated_at = ?, finished_at = CASE WHEN finished_at IS NOT NULL"
                    + " THEN CAST(? AS TIMESTAMP WITH TIME ZONE) END", when, when);
        }
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
