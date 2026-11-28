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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.emilio.streambox.entity.Episode;
import com.emilio.streambox.entity.Genre;
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
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Revisión independiente (QA) de {@code GET /api/series/{id}/similar}: los
 * casos límite que no cubre {@link SimilarSeriesIntegrationTest}, gemelos de
 * {@link SimilarMoviesQaIntegrationTest} más lo propio de las series (la
 * visibilidad por episodios).
 *
 * <ul>
 * <li><strong>404 indistinguible</strong>: una serie sin episodios y una
 * inexistente responden igual por {@code GET} y por {@code HEAD}: mismo estado,
 * mismo cuerpo (salvo hora y ruta) y <em>mismas cabeceras</em> (también
 * {@code Content-Length}, comparando ids con el mismo número de cifras). Una
 * diferencia en cualquiera de ellas bastaría para saber qué series vacías
 * existen.</li>
 * <li><strong>Ids raros y subrutas inexistentes</strong>: nunca un 500 ni una
 * lista vacía que haga creer que la serie existe.</li>
 * <li><strong>{@code limit} raro</strong> (vacío o repetido, p. ej.
 * {@code ?limit=1&limit=2}): el valor usado siempre pasa por 1..24.</li>
 * <li><strong>Métodos y roles</strong>: un ADMIN lee lo mismo que un USER (y
 * recibe el mismo 404 por una serie vacía: esta ruta es la pública); escribir
 * da 403 al USER y 405 al ADMIN.</li>
 * <li><strong>Nada que el catálogo no enseñe</strong>: cada elemento es el mismo
 * JSON que esa serie tiene en el listado público.</li>
 * <li><strong>Sin caché en el servidor</strong>: cambiar géneros, borrar una
 * candidata, quitarle su último episodio o quitárselo al origen se ve en la
 * llamada siguiente.</li>
 * </ul>
 *
 * <p>
 * Datos confirmados de verdad (sin {@code @Transactional}) y limpieza antes y
 * después de cada test; mismas propiedades que {@link SimilarSeriesIntegrationTest}
 * para compartir su contexto de Spring.
 * </p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SimilarSeriesQaIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private MovieRepository movieRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private EpisodeRepository episodeRepository;
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
        userToken = "Bearer " + jwtService.generateToken(saveUser("qasimseriesuser", Role.USER));
        adminToken = "Bearer " + jwtService.generateToken(saveUser("qasimseriesadmin", Role.ADMIN));
        drama = saveGenre("Drama");
        comedy = saveGenre("Comedia");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // 404 indistinguible: estado, cuerpo y cabeceras, por GET y por HEAD
    // ------------------------------------------------------------------

    /**
     * Una serie sin episodios (que comparte géneros con una visible, así que la
     * consulta de ids sí le encontraría similares) y una inexistente con un id
     * del mismo número de cifras: mismo estado, mismo cuerpo salvo
     * {@code timestamp} y {@code path}, y exactamente las mismas cabeceras,
     * para USER y para ADMIN (esta ruta es la pública: el ADMIN ve las series
     * vacías en el panel, no aquí).
     */
    @Test
    void unaSerieOcultaYUnaInexistenteRespondenIgualPorGetConLasMismasCabeceras() throws Exception {
        Series hidden = saveEmptySeries("Oculta", 2000, drama);
        saveVisibleSeries("Visible parecida", 2000, drama);
        long missing = missingIdWithSameDigits(hidden.getId());

        for (String token : List.of(userToken, adminToken)) {
            MockHttpServletResponse ofHidden = perform(get("/api/series/" + hidden.getId() + "/similar"), token);
            MockHttpServletResponse ofMissing = perform(get("/api/series/" + missing + "/similar"), token);

            assertEquals(404, ofHidden.getStatus());
            assertEquals(ofMissing.getStatus(), ofHidden.getStatus());
            assertEquals(headers(ofMissing), headers(ofHidden), "las cabeceras no deben delatar la serie oculta");
            assertEquals(withoutVolatileFields(ofMissing), withoutVolatileFields(ofHidden));
            assertEquals("RESOURCE_NOT_FOUND", objectMapper.readTree(ofHidden.getContentAsString()).get("code").asText());
        }
    }

    /**
     * Lo mismo por {@code HEAD} (el que usaría quien quiera sondear sin
     * descargar cuerpos): mismo estado y mismas cabeceras para la serie oculta
     * y la inexistente; y {@code HEAD} sobre una visible, 200.
     */
    @Test
    void porHeadUnaSerieOcultaYUnaInexistenteTambienSonIndistinguibles() throws Exception {
        Series hidden = saveEmptySeries("Oculta", 2000, drama);
        Series visible = saveVisibleSeries("Visible parecida", 2000, drama);
        long missing = missingIdWithSameDigits(hidden.getId());

        MockHttpServletResponse ofHidden = perform(head("/api/series/" + hidden.getId() + "/similar"), userToken);
        MockHttpServletResponse ofMissing = perform(head("/api/series/" + missing + "/similar"), userToken);

        assertEquals(404, ofHidden.getStatus());
        assertEquals(ofMissing.getStatus(), ofHidden.getStatus());
        assertEquals(headers(ofMissing), headers(ofHidden));
        assertEquals(200, perform(head("/api/series/" + visible.getId() + "/similar"), userToken).getStatus());
        assertEquals(401, mockMvc.perform(head("/api/series/" + hidden.getId() + "/similar"))
                .andReturn().getResponse().getStatus());
    }

    /**
     * El 404 de las similares de una serie oculta es el mismo (salvo la ruta)
     * que el de su propio detalle: ninguna de las dos rutas públicas la delata.
     */
    @Test
    void elDe404DeLasSimilaresDeUnaSerieOcultaEsElMismoQueElDeSuDetalle() throws Exception {
        Series hidden = saveEmptySeries("Oculta", 2000, drama);
        saveVisibleSeries("Visible parecida", 2000, drama);

        MockHttpServletResponse similar = perform(get("/api/series/" + hidden.getId() + "/similar"), userToken);
        MockHttpServletResponse detail = perform(get("/api/series/" + hidden.getId()), userToken);

        assertEquals(404, similar.getStatus());
        assertEquals(detail.getStatus(), similar.getStatus());
        assertEquals(withoutVolatileFields(detail), withoutVolatileFields(similar));
    }

    // ------------------------------------------------------------------
    // Ids raros y subrutas
    // ------------------------------------------------------------------

    /**
     * Ids que encajan en un {@code Long} pero no pueden existir: el mismo 404
     * (estado, código y mensaje) que el detalle, no {@code []} ni un 500.
     */
    @ParameterizedTest
    @ValueSource(strings = { "-1", "0", "9223372036854775807", "-9223372036854775808" })
    void unIdImposibleDaElMismo404QueElDetalle(String id) throws Exception {
        saveVisibleSeries("Una", 2000, drama);

        MockHttpServletResponse similar = perform(get("/api/series/" + id + "/similar"), userToken);
        MockHttpServletResponse detail = perform(get("/api/series/" + id), userToken);

        assertEquals(404, similar.getStatus());
        assertEquals(withoutVolatileFields(detail), withoutVolatileFields(similar));
    }

    /**
     * Rutas que cuelgan de la de similares pero no existen: 404 de ruta (no
     * 500), y la misma respuesta para una serie visible que para una oculta.
     */
    @ParameterizedTest
    @ValueSource(strings = { "/similar/", "/similar/extra", "/similares", "/similar/1" })
    void unaSubrutaQueNoExisteDa404IgualParaUnaVisibleQueParaUnaOculta(String suffix) throws Exception {
        Series visible = saveVisibleSeries("Visible", 2000, drama);
        Series hidden = saveEmptySeries("Oculta", 2000, drama);

        MockHttpServletResponse ofVisible = perform(get("/api/series/" + visible.getId() + suffix), userToken);
        MockHttpServletResponse ofHidden = perform(get("/api/series/" + hidden.getId() + suffix), userToken);

        assertEquals(404, ofVisible.getStatus());
        assertEquals(404, ofHidden.getStatus());
        assertEquals(withoutVolatileFields(ofVisible), withoutVolatileFields(ofHidden));
    }

    // ------------------------------------------------------------------
    // Parámetro limit raro
    // ------------------------------------------------------------------

    /** {@code ?limit=} (vacío) se trata como si no viniera: el valor por defecto (12). */
    @Test
    void unLimiteVacioUsaElValorPorDefecto() throws Exception {
        Series origin = saveVisibleSeries("Origen", 2000, drama);
        for (int i = 0; i < 15; i++) {
            saveVisibleSeries("Candidata " + i, 2001, drama);
        }

        mockMvc.perform(get("/api/series/" + origin.getId() + "/similar").param("limit", "")
                        .header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(12));
    }

    /**
     * Con {@code limit} repetido ({@code ?limit=1&limit=2} y compañía) nunca se
     * salta la validación: o se rechaza (400 {@code VALIDATION_ERROR}) o se usa
     * uno de los valores, dentro de 1..24. Con 30 candidatas visibles, más de 24
     * en la respuesta significaría que el tope se puede saltar.
     */
    @ParameterizedTest
    @ValueSource(strings = { "1,2", "1,999", "999,1", "30,30", "2,abc" })
    void unLimiteRepetidoNuncaSaltaElTope(String values) throws Exception {
        Series origin = saveVisibleSeries("Origen", 2000, drama);
        for (int i = 0; i < 30; i++) {
            saveVisibleSeries("Candidata " + i, 2001, drama);
        }

        MvcResult result = mockMvc.perform(get("/api/series/" + origin.getId() + "/similar")
                        .param("limit", values.split(","))
                        .header("Authorization", userToken))
                .andReturn();

        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        assertTrue(status == 200 || status == 400, "estado inesperado " + status + ": " + body);
        if (status == 200) {
            int size = objectMapper.readTree(body).size();
            List<Integer> asked = Arrays.stream(values.split(",")).filter(v -> v.matches("[0-9]+"))
                    .map(Integer::valueOf).toList();
            assertTrue(size <= 24, "más de 24 series: " + body);
            assertTrue(asked.contains(size), "debe usar uno de los límites pedidos " + asked + ", no " + size);
        } else {
            assertEquals("VALIDATION_ERROR", objectMapper.readTree(body).get("code").asText());
        }
    }

    // ------------------------------------------------------------------
    // Métodos y roles
    // ------------------------------------------------------------------

    /** Un ADMIN lee lo mismo que un USER (GET y HEAD). */
    @Test
    void unAdministradorLoLeeIgualQueUnUsuario() throws Exception {
        Series origin = saveVisibleSeries("Origen", 2000, drama);
        saveVisibleSeries("Candidata", 2001, drama);
        saveEmptySeries("Oculta", 2001, drama);

        String asUser = perform(get("/api/series/" + origin.getId() + "/similar"), userToken).getContentAsString();
        String asAdmin = perform(get("/api/series/" + origin.getId() + "/similar"), adminToken).getContentAsString();

        assertEquals(1, objectMapper.readTree(asAdmin).size(), "el ADMIN tampoco ve aquí la serie vacía");
        assertEquals(objectMapper.readTree(asUser), objectMapper.readTree(asAdmin));
        assertEquals(200, perform(head("/api/series/" + origin.getId() + "/similar"), adminToken).getStatus());
    }

    /**
     * Escribir en la ruta: un USER se queda en la regla de cierre (403) y un
     * ADMIN, que sí pasa la seguridad, recibe 405 en el formato de la API,
     * nunca un 500.
     */
    @Test
    void escribirEnLaRutaDa403AlUsuarioY405AlAdministrador() throws Exception {
        Series origin = saveVisibleSeries("Origen", 2000, drama);
        String url = "/api/series/" + origin.getId() + "/similar";

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
     * Cada elemento es exactamente el JSON que esa serie tiene en el listado
     * público ({@code GET /api/series}): mismos campos y valores, recuentos
     * incluidos. La fila no expone nada que el catálogo no enseñe ya, ni le
     * falta nada a la tarjeta.
     */
    @Test
    void cadaElementoEsIdenticoAlDeEsaSerieEnElListadoPublico() throws Exception {
        Series origin = saveVisibleSeries("Origen", 2000, drama, comedy);
        Series candidate = saveVisibleSeries("Candidata", 2003, comedy, drama);
        addEpisode(candidate, 1, 2);
        addEpisode(candidate, 2, 1);

        JsonNode similar = objectMapper.readTree(
                perform(get("/api/series/" + origin.getId() + "/similar"), userToken).getContentAsString());
        JsonNode listing = objectMapper.readTree(
                perform(get("/api/series").param("size", "50"), userToken).getContentAsString());
        JsonNode inListing = null;
        for (JsonNode item : listing.get("content")) {
            if (item.get("id").asLong() == candidate.getId()) {
                inListing = item;
            }
        }

        assertEquals(1, similar.size());
        assertEquals(inListing, similar.get(0));
        assertEquals(2, similar.get(0).get("seasonCount").asInt());
        assertEquals(3, similar.get(0).get("episodeCount").asInt());
    }

    // ------------------------------------------------------------------
    // Cambios del panel: sin caché en el servidor
    // ------------------------------------------------------------------

    /**
     * Tras cambiar los géneros con el {@code PUT} del panel, una serie deja de
     * ser similar (o pasa a serlo) en la llamada siguiente; tras borrar una
     * candidata, desaparece; tras borrar el origen, 404.
     */
    @Test
    void losCambiosDeGenerosYLosBorradosDelPanelSeVenEnLaLlamadaSiguiente() throws Exception {
        Series origin = saveVisibleSeries("Origen", 2000, drama);
        Series stays = saveVisibleSeries("Se queda", 2001, drama);
        Series changes = saveVisibleSeries("Cambia de genero", 2001, drama);
        Series joins = saveVisibleSeries("Se une", 2001, comedy);
        assertEquals(sorted(stays.getId(), changes.getId()), sorted(similarIds(origin)));

        updateGenres(changes, comedy);
        updateGenres(joins, drama);
        assertEquals(sorted(stays.getId(), joins.getId()), sorted(similarIds(origin)));

        mockMvc.perform(delete("/api/series/" + stays.getId()).header("Authorization", adminToken))
                .andExpect(status().isNoContent());
        assertEquals(List.of(joins.getId()), similarIds(origin));

        mockMvc.perform(delete("/api/series/" + origin.getId()).header("Authorization", adminToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/series/" + origin.getId() + "/similar").header("Authorization", userToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /**
     * Una candidata que pierde su último episodio (borrado con el endpoint del
     * panel) deja de salir en la llamada siguiente; mientras le quede uno,
     * sigue. Y si es el origen el que pierde el último, sus similares pasan a
     * dar el mismo 404 que una serie inexistente.
     */
    @Test
    void quitarElUltimoEpisodioOcultaALaCandidataYAlOrigen() throws Exception {
        Series origin = saveVisibleSeries("Origen", 2000, drama);
        Series candidate = saveEmptySeries("Candidata", 2001, drama);
        Episode first = addEpisode(candidate, 1, 1);
        Episode second = addEpisode(candidate, 1, 2);
        Series other = saveVisibleSeries("Otra", 2002, drama);
        assertEquals(List.of(candidate.getId(), other.getId()), similarIds(origin));

        deleteEpisode(candidate, first);
        assertEquals(List.of(candidate.getId(), other.getId()), similarIds(origin), "le queda un episodio");

        deleteEpisode(candidate, second);
        assertEquals(List.of(other.getId()), similarIds(origin), "sin episodios no puede salir");

        long missing = missingIdWithSameDigits(origin.getId());
        Episode originEpisode = episodeRepository.findAllBySeriesIdOrdered(origin.getId()).get(0);
        deleteEpisode(origin, originEpisode);
        MockHttpServletResponse ofHidden = perform(get("/api/series/" + origin.getId() + "/similar"), userToken);
        MockHttpServletResponse ofMissing = perform(get("/api/series/" + missing + "/similar"), userToken);
        assertEquals(404, ofHidden.getStatus());
        assertEquals(headers(ofMissing), headers(ofHidden));
        assertEquals(withoutVolatileFields(ofMissing), withoutVolatileFields(ofHidden));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request, String token) throws Exception {
        return mockMvc.perform(request.header("Authorization", token)).andReturn().getResponse();
    }

    /** Todas las cabeceras de la respuesta (nombre en minúsculas → valores), en orden estable. */
    private static Map<String, List<String>> headers(MockHttpServletResponse response) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        response.getHeaderNames().stream().sorted()
                .forEach(name -> headers.put(name.toLowerCase(), response.getHeaders(name)));
        return headers;
    }

    /** Cuerpo de error sin lo que cambia entre peticiones sin revelar nada: la hora y la ruta pedida. */
    private JsonNode withoutVolatileFields(MockHttpServletResponse response) throws Exception {
        ObjectNode body = (ObjectNode) objectMapper.readTree(response.getContentAsString());
        assertTrue(body.has("message") && !body.get("message").asText().isBlank(), "sin mensaje: " + body);
        body.remove("timestamp");
        body.remove("path");
        return body;
    }

    /**
     * Un id que no es de ninguna serie y tiene las mismas cifras que el dado:
     * así el {@code path} del cuerpo mide lo mismo y también se puede comparar
     * {@code Content-Length}.
     */
    private long missingIdWithSameDigits(long id) {
        int digits = Long.toString(id).length();
        long low = digits == 1 ? 1 : (long) Math.pow(10, digits - 1);
        long high = (long) Math.pow(10, digits) - 1;
        for (long candidate = high; candidate >= low; candidate--) {
            if (candidate != id && !seriesRepository.existsById(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no hay ningún id libre de " + digits + " cifras");
    }

    private List<Long> similarIds(Series origin) throws Exception {
        String json = mockMvc.perform(get("/api/series/" + origin.getId() + "/similar")
                        .header("Authorization", userToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Long> ids = new ArrayList<>();
        objectMapper.readTree(json).forEach(node -> ids.add(node.get("id").asLong()));
        return ids;
    }

    private static List<Long> sorted(Long... ids) {
        return Arrays.stream(ids).sorted().toList();
    }

    private static List<Long> sorted(List<Long> ids) {
        return ids.stream().sorted().toList();
    }

    /** Cambia los géneros de una serie con el {@code PUT} real del panel (como ADMIN). */
    private void updateGenres(Series series, Genre... genres) throws Exception {
        Map<String, Object> body = Map.of("title", series.getTitle(), "description", series.getDescription(),
                "releaseYear", series.getReleaseYear(), "imageUrl", series.getImageUrl(),
                "genreIds", Arrays.stream(genres).map(Genre::getId).toList());
        mockMvc.perform(put("/api/series/" + series.getId()).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    /** Borra un episodio con el endpoint real del panel (como ADMIN). */
    private void deleteEpisode(Series series, Episode episode) throws Exception {
        mockMvc.perform(delete("/api/series/" + series.getId() + "/episodes/" + episode.getId())
                        .header("Authorization", adminToken))
                .andExpect(status().isNoContent());
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

    /** Serie sin episodios: existe, pero para los usuarios es como si no existiera. */
    private Series saveEmptySeries(String title, int year, Genre... genres) {
        Series series = new Series();
        series.setTitle(title);
        series.setDescription("Sinopsis de " + title);
        series.setReleaseYear(year);
        series.setImageUrl("https://example.com/serie.jpg");
        series.setGenres(new HashSet<>(List.of(genres)));
        return seriesRepository.save(series);
    }

    /** Serie visible: con un episodio (1x1). */
    private Series saveVisibleSeries(String title, int year, Genre... genres) {
        Series series = saveEmptySeries(title, year, genres);
        addEpisode(series, 1, 1);
        return series;
    }

    private Episode addEpisode(Series series, int season, int number) {
        Episode episode = new Episode();
        episode.setSeries(series);
        episode.setSeasonNumber(season);
        episode.setEpisodeNumber(number);
        episode.setTitle("Episodio " + season + "x" + number);
        episode.setDuration(45);
        episode.setVideoUrl("https://example.com/episodio.mp4");
        return episodeRepository.save(episode);
    }

    /**
     * Vacía las tablas respetando las claves foráneas: las series arrastran sus
     * episodios y {@code series_genres} ({@code ON DELETE CASCADE}).
     */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
