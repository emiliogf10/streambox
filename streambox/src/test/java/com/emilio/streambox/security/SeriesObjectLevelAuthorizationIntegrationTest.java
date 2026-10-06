package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Autorización a nivel de objeto (IDOR/BOLA) y fugas de información de la API
 * de series, vistas desde el atacante.
 *
 * <p>
 * {@link SeriesAuthorizationIntegrationTest} comprueba las reglas por ruta de
 * {@link SecurityConfig} (quién puede llamar a qué). Aquí se comprueba lo que
 * esas reglas no pueden ver: que, una vez dentro, un usuario solo toque
 * <em>sus</em> datos y no averigüe nada de lo que está oculto. Las amenazas
 * cubiertas son:
 * </p>
 *
 * <ul>
 * <li><b>Lista de otro usuario.</b> El usuario sale siempre del token: un
 * {@code userId} en la URL o en el cuerpo se ignora.</li>
 * <li><b>Oráculo de existencia.</b> Una serie sin episodios está oculta: toda
 * respuesta pública sobre ella debe ser <em>idéntica</em> a la de un id que no
 * existe (mismo estado y mismo cuerpo, salvo {@code timestamp} y
 * {@code path}). Si difiriera en algo, un usuario podría recorrer los ids y
 * descubrir qué series está preparando el administrador.</li>
 * <li><b>Objetos cruzados entre series.</b> Un episodio solo se edita o borra a
 * través de su propia serie; cambiar el {@code seriesId} de la URL no permite
 * tocar el de otra.</li>
 * <li><b>Asignación masiva.</b> Ni el id, ni la serie de un episodio, ni la
 * fecha de creación, ni los recuentos se pueden fijar desde el cuerpo.</li>
 * <li><b>Errores.</b> Los ids mal formados no devuelven detalles internos.</li>
 * </ul>
 *
 * <p>
 * Sin {@code @Transactional} (como los tests de series de {@code controller}):
 * cada petición se confirma y se comprueba el estado real de la base de datos
 * después. Se limpia en {@code @BeforeEach}/{@code @AfterEach}, series antes
 * que géneros.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SeriesObjectLevelAuthorizationIntegrationTest {

    private static final String SERIES = "/api/series";
    private static final String SERIES_FAVORITES = "/api/users/me/favorites/series";

    /** Id que ningún contador de identidad de los tests alcanza. */
    private static final long MISSING_ID = 987_654_321L;

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

    private User alice;
    private User bob;
    private String aliceToken;
    private String bobToken;
    private String adminToken;
    private Genre drama;
    private Series visible;
    private Series hidden;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        alice = saveUser("bolaalice", Role.USER);
        bob = saveUser("bolabob", Role.USER);
        aliceToken = tokenFor(alice);
        bobToken = tokenFor(bob);
        adminToken = tokenFor(saveUser("bolaadmin", Role.ADMIN));

        drama = saveGenre("Drama");
        visible = saveSeries("Dark", 2017);
        saveEpisode(visible, 1, 1, "Secretos");
        hidden = saveSeries("Dark borrador", 2017);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // "Mi lista": el usuario sale del token, nunca de la petición
    // ------------------------------------------------------------------

    /**
     * Un atacante añade el id de otro usuario en la URL y en el cuerpo, como
     * haría con una API que lo leyese de ahí. Debe actuar sobre su propia
     * lista y la de la víctima no debe cambiar ni dejarse leer.
     */
    @Test
    void unUserIdEnLaUrlOEnElCuerpoSeIgnoraYSoloSeTocaLaListaDelToken() throws Exception {
        String bobId = String.valueOf(bob.getId());

        mockMvc.perform(post(SERIES_FAVORITES + "/" + visible.getId())
                        .param("userId", bobId)
                        .header("Authorization", aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + bobId + "}"))
                .andExpect(status().isNoContent());

        assertEquals(1, favoritesOf(alice));
        assertEquals(0, favoritesOf(bob));

        // Bob tampoco puede leer la lista de Alice pasando su id
        mockMvc.perform(get(SERIES_FAVORITES).param("userId", String.valueOf(alice.getId()))
                        .header("Authorization", bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        // Ni vaciarla
        mockMvc.perform(delete(SERIES_FAVORITES).param("userId", String.valueOf(alice.getId()))
                        .header("Authorization", bobToken))
                .andExpect(status().isNoContent());
        assertEquals(1, favoritesOf(alice));
    }

    /** Bob no puede quitar de la lista de Alice una serie que solo tiene ella. */
    @Test
    void unUsuarioNoPuedeQuitarUnaSerieDeLaListaDeOtro() throws Exception {
        favorite(alice, visible);

        mockMvc.perform(delete(SERIES_FAVORITES + "/" + visible.getId())
                        .param("userId", String.valueOf(alice.getId()))
                        .header("Authorization", bobToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SERIES_NOT_IN_FAVORITES"));

        assertEquals(1, favoritesOf(alice));
    }

    // ------------------------------------------------------------------
    // Series ocultas: ninguna respuesta pública revela que existen
    // ------------------------------------------------------------------

    @Test
    void elDetalleDeUnaSerieOcultaEsIdenticoAlDeUnaInexistente() throws Exception {
        assertSameResponse(
                get(SERIES + "/" + hidden.getId()).header("Authorization", aliceToken),
                get(SERIES + "/" + MISSING_ID).header("Authorization", aliceToken),
                404);

        // HEAD es la misma lectura sin cuerpo: mismo estado
        int hiddenHead = mockMvc.perform(head(SERIES + "/" + hidden.getId()).header("Authorization", aliceToken))
                .andReturn().getResponse().getStatus();
        int missingHead = mockMvc.perform(head(SERIES + "/" + MISSING_ID).header("Authorization", aliceToken))
                .andReturn().getResponse().getStatus();
        assertEquals(404, hiddenHead);
        assertEquals(missingHead, hiddenHead);
    }

    /**
     * Añadir una serie oculta responde igual que una inexistente, también si
     * el usuario ya la tenía en su lista (de cuando tenía episodios): un 409
     * "ya está en tu lista" revelaría que existe.
     */
    @Test
    void anadirUnaSerieOcultaEsIdenticoAAnadirUnaInexistenteAunqueYaEstuvieraEnLaLista() throws Exception {
        assertSameResponse(
                post(SERIES_FAVORITES + "/" + hidden.getId()).header("Authorization", aliceToken),
                post(SERIES_FAVORITES + "/" + MISSING_ID).header("Authorization", aliceToken),
                404);

        favorite(bob, hidden);
        assertSameResponse(
                post(SERIES_FAVORITES + "/" + hidden.getId()).header("Authorization", bobToken),
                post(SERIES_FAVORITES + "/" + MISSING_ID).header("Authorization", bobToken),
                404);

        assertEquals(0, favoritesOf(alice));
        assertEquals(1, favoritesOf(bob));
    }

    /**
     * Quitar de la lista una serie oculta que el usuario <em>no</em> tenía
     * responde igual que con una serie inexistente.
     *
     * <p>
     * Regresión: {@code SeriesFavoriteService.removeFavorite} comprobaba la
     * existencia con {@code seriesRepository.existsById} (que no mira los
     * episodios), así que una serie oculta daba {@code SERIES_NOT_IN_FAVORITES}
     * ("La serie no está incluida en tu lista de favoritos") y una inexistente
     * {@code RESOURCE_NOT_FOUND} ("Serie no encontrada"). Recorriendo ids, un
     * usuario sabía qué series vacías existen. Gravedad baja (no revela el
     * título ni el contenido). Arreglo: borrar primero y, solo si no se borró
     * ninguna fila, responder {@code SERIES_NOT_IN_FAVORITES} si la serie es
     * <em>visible</em> ({@code episodeRepository.existsBySeriesId}) y
     * {@code Serie no encontrada} en otro caso. Así se sigue pudiendo quitar
     * una serie oculta que ya estaba en la lista (204), que es lo que fija el
     * contrato.
     * </p>
     */
    @Test
    void quitarUnaSerieOcultaQueNoEstabaEnLaListaEsIdenticoAQuitarUnaInexistente() throws Exception {
        assertSameResponse(
                delete(SERIES_FAVORITES + "/" + hidden.getId()).header("Authorization", aliceToken),
                delete(SERIES_FAVORITES + "/" + MISSING_ID).header("Authorization", aliceToken),
                404);
    }

    /**
     * No hay ninguna ruta de lectura de episodios fuera del detalle de la
     * serie: un {@code GET} sobre las rutas de episodios responde igual sea la
     * serie visible, oculta o inexistente, y nunca devuelve datos.
     */
    @Test
    void lasRutasDeEpisodiosNoSeLeenNiDistinguenSeriesOcultas() throws Exception {
        Long episodeId = episodeRepository.findAllBySeriesIdOrdered(visible.getId()).get(0).getId();

        String visibleBody = mockMvc.perform(get(SERIES + "/" + visible.getId() + "/episodes/" + episodeId)
                        .header("Authorization", aliceToken))
                .andExpect(status().isMethodNotAllowed())
                .andReturn().getResponse().getContentAsString();
        assertFalse(visibleBody.contains("Secretos"), "no debe devolver datos del episodio: " + visibleBody);

        assertSameResponse(
                get(SERIES + "/" + hidden.getId() + "/episodes").header("Authorization", aliceToken),
                get(SERIES + "/" + MISSING_ID + "/episodes").header("Authorization", aliceToken),
                405);
    }

    /**
     * La búsqueda pública no devuelve <em>ni cuenta</em> una serie oculta
     * aunque cumpla todos los filtros: si {@code totalElements} la incluyera,
     * el recuento serviría de oráculo aunque la lista viniese vacía.
     */
    @Test
    void laBusquedaYElListadoPublicosNiDevuelvenNiCuentanSeriesOcultas() throws Exception {
        mockMvc.perform(get(SERIES + "/search").param("title", "borrador").header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());

        mockMvc.perform(get(SERIES + "/search")
                        .param("title", "dark")
                        .param("genreId", String.valueOf(drama.getId()))
                        .param("releaseYear", "2017")
                        .header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(visible.getId()));

        mockMvc.perform(get(SERIES).param("sort", "createdAt").param("direction", "desc")
                        .header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(visible.getId()));
    }

    /**
     * Ordenar por una relación ({@code episodes.title}, {@code genres.name})
     * o por un campo no publicado permitiría deducir datos ocultos por la
     * posición de los resultados. La lista blanca lo impide, también en el
     * panel.
     */
    @ParameterizedTest
    @ValueSource(strings = { "episodes.title", "episodes", "genres.name", "description", "imageUrl",
            "title,desc", "id;drop" })
    void ordenarPorRelacionesOCamposNoPermitidosDa400(String sort) throws Exception {
        mockMvc.perform(get(SERIES).param("sort", sort).header("Authorization", aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/admin/series").param("sort", sort).header("Authorization", adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ------------------------------------------------------------------
    // Episodios: no se cruzan entre series
    // ------------------------------------------------------------------

    /**
     * Pedir el episodio de la serie A a través de la serie B da el mismo 404
     * que un episodio inexistente, y ninguna de las dos series cambia.
     */
    @Test
    void unEpisodioDeOtraSerieNoSeEditaNiSeBorraYResponde404IdenticoAUnoInexistente() throws Exception {
        Series other = saveSeries("Otra", 2010);
        Episode victim = episodeRepository.findAllBySeriesIdOrdered(visible.getId()).get(0);
        String crossUrl = SERIES + "/" + other.getId() + "/episodes/" + victim.getId();
        String missingUrl = SERIES + "/" + other.getId() + "/episodes/" + MISSING_ID;

        assertSameResponse(
                put(crossUrl).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(episodeJson(5, 5, "Cambiado", "")),
                put(missingUrl).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(episodeJson(5, 5, "Cambiado", "")),
                404);
        assertSameResponse(
                delete(crossUrl).header("Authorization", adminToken),
                delete(missingUrl).header("Authorization", adminToken),
                404);

        Episode after = episodeRepository.findById(victim.getId()).orElseThrow();
        assertEquals("Secretos", after.getTitle());
        assertEquals(1, after.getSeasonNumber());
        assertEquals(1, episodesOf(visible));
        assertEquals(0, episodesOf(other));
    }

    // ------------------------------------------------------------------
    // Asignación masiva
    // ------------------------------------------------------------------

    /**
     * El cuerpo de un episodio no puede elegir su id ni su serie: la serie es
     * la de la URL. Con un {@code id} de un episodio ajeno, crear no lo
     * sobrescribe y editar no lo toca.
     */
    @Test
    void elCuerpoDeUnEpisodioNoPuedeElegirSuIdNiSuSerie() throws Exception {
        Series target = saveSeries("Destino", 2012);
        Episode foreign = episodeRepository.findAllBySeriesIdOrdered(visible.getId()).get(0);
        String extra = ",\"id\":" + foreign.getId()
                + ",\"seriesId\":" + visible.getId()
                + ",\"series\":{\"id\":" + visible.getId() + "}"
                + ",\"createdAt\":\"2000-01-01T00:00:00Z\"";

        String created = mockMvc.perform(post(SERIES + "/" + target.getId() + "/episodes")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(episodeJson(1, 1, "Nuevo", extra)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long createdId = objectMapper.readTree(created).get("id").asLong();

        assertNotEquals(foreign.getId().longValue(), createdId);
        assertEquals(1, episodesOf(target));
        assertEquals(1, episodesOf(visible));
        assertEquals("Secretos", episodeRepository.findById(foreign.getId()).orElseThrow().getTitle());
        Instant createdAt = jdbc.queryForObject("SELECT created_at FROM episodes WHERE id = ?",
                java.sql.Timestamp.class, createdId).toInstant();
        assertTrue(createdAt.isAfter(Instant.now().minus(1, ChronoUnit.HOURS)),
                "la fecha de creación no debe venir del cliente: " + createdAt);

        // Editar con los mismos campos extra tampoco mueve el episodio ni toca el ajeno
        mockMvc.perform(put(SERIES + "/" + target.getId() + "/episodes/" + createdId)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(episodeJson(1, 2, "Editado", extra)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(createdId));

        assertEquals(target.getId(), jdbc.queryForObject(
                "SELECT series_id FROM episodes WHERE id = ?", Long.class, createdId));
        assertEquals("Secretos", episodeRepository.findById(foreign.getId()).orElseThrow().getTitle());
        assertEquals(1, episodesOf(visible));
    }

    /**
     * El cuerpo de una serie no puede fijar su id, su fecha de creación, sus
     * recuentos ni colarle episodios. Con el id de otra serie, editar solo
     * cambia la de la URL.
     */
    @Test
    void elCuerpoDeUnaSerieNoPuedeElegirIdFechaRecuentosNiEpisodios() throws Exception {
        String extra = ",\"id\":" + visible.getId()
                + ",\"createdAt\":\"2000-01-01T00:00:00Z\""
                + ",\"episodeCount\":99,\"seasonCount\":9"
                + ",\"seasons\":[{\"seasonNumber\":1,\"episodes\":[" + episodeJson(1, 1, "Colado", "") + "]}]"
                + ",\"episodes\":[" + episodeJson(1, 1, "Colado", "") + "]";

        String created = mockMvc.perform(post(SERIES).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seriesJson("Nueva", extra)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.episodeCount").value(0))
                .andExpect(jsonPath("$.seasons").isEmpty())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(created);
        long createdId = node.get("id").asLong();

        assertNotEquals(visible.getId().longValue(), createdId);
        assertTrue(Instant.parse(node.get("createdAt").asText()).isAfter(Instant.now().minus(1, ChronoUnit.HOURS)));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM episodes WHERE series_id = ?",
                Integer.class, createdId));
        assertEquals("Dark", seriesRepository.findById(visible.getId()).orElseThrow().getTitle());

        Instant hiddenCreatedAt = seriesRepository.findById(hidden.getId()).orElseThrow().getCreatedAt();
        mockMvc.perform(put(SERIES + "/" + hidden.getId()).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seriesJson("Editada", extra)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(hidden.getId()));

        Series edited = seriesRepository.findById(hidden.getId()).orElseThrow();
        assertEquals("Editada", edited.getTitle());
        assertEquals(hiddenCreatedAt, edited.getCreatedAt());
        assertEquals(0, episodesOf(hidden));
        assertEquals("Dark", seriesRepository.findById(visible.getId()).orElseThrow().getTitle());
    }

    // ------------------------------------------------------------------
    // Errores sin detalles internos
    // ------------------------------------------------------------------

    /**
     * Ids mal formados o fuera de rango en cualquier ruta de series responden
     * 400 con el formato de la API, sin nombres de clases, tipos Java ni SQL.
     */
    @ParameterizedTest
    @ValueSource(strings = { "GET " + SERIES + "/abc", "GET " + SERIES + "/99999999999999999999",
            "DELETE " + SERIES_FAVORITES + "/abc", "POST " + SERIES_FAVORITES + "/-",
            "GET /api/admin/series/1e3" })
    void unIdMalFormadoDa400SinDetallesInternos(String methodAndUrl) throws Exception {
        String[] parts = methodAndUrl.split(" ");
        String url = parts[1];
        String token = url.startsWith("/api/admin") ? adminToken : aliceToken;
        MockHttpServletRequestBuilder request = switch (parts[0]) {
            case "DELETE" -> delete(url);
            case "POST" -> post(url);
            default -> get(url);
        };
        String body = mockMvc.perform(request.header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andReturn().getResponse().getContentAsString();

        assertNoInternals(body);
    }

    @Test
    void unCuerpoConTiposErroneosDa400SinDetallesInternos() throws Exception {
        String body = mockMvc.perform(post(SERIES + "/" + hidden.getId() + "/episodes")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seasonNumber\":\"uno\",\"episodeNumber\":99999999999999999999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andReturn().getResponse().getContentAsString();

        assertNoInternals(body);
        assertEquals(0, episodesOf(hidden));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * Ejecuta las dos peticiones y comprueba que el estado es el esperado y que
     * los cuerpos son idénticos salvo {@code timestamp} y {@code path}, que
     * cambian siempre.
     */
    private void assertSameResponse(
            MockHttpServletRequestBuilder hiddenRequest,
            MockHttpServletRequestBuilder missingRequest,
            int expectedStatus) throws Exception {

        MvcResult hiddenResult = mockMvc.perform(hiddenRequest).andReturn();
        MvcResult missingResult = mockMvc.perform(missingRequest).andReturn();

        assertEquals(expectedStatus, hiddenResult.getResponse().getStatus());
        assertEquals(missingResult.getResponse().getStatus(), hiddenResult.getResponse().getStatus());
        assertEquals(
                withoutVolatileFields(missingResult.getResponse().getContentAsString()),
                withoutVolatileFields(hiddenResult.getResponse().getContentAsString()),
                "la respuesta no debe distinguir una serie oculta de una inexistente");
    }

    private JsonNode withoutVolatileFields(String json) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        if (node instanceof ObjectNode object) {
            object.remove("timestamp");
            object.remove("path");
        }
        return node;
    }

    private static void assertNoInternals(String body) {
        String lower = body.toLowerCase();
        for (String leak : new String[] { "java.", "exception", "jackson", "hibernate", "sql", "select ",
                "constraint", "numberformat", "com.emilio" }) {
            assertFalse(lower.contains(leak), "la respuesta filtra '" + leak + "': " + body);
        }
    }

    private String episodeJson(int season, int number, String title, String extraFields) {
        return "{\"seasonNumber\":" + season
                + ",\"episodeNumber\":" + number
                + ",\"title\":\"" + title + "\""
                + ",\"duration\":45"
                + ",\"videoUrl\":\"https://example.com/" + season + "x" + number + ".mp4\""
                + extraFields + "}";
    }

    private String seriesJson(String title, String extraFields) {
        return "{\"title\":\"" + title + "\""
                + ",\"description\":\"Sinopsis\""
                + ",\"releaseYear\":2014"
                + ",\"imageUrl\":\"https://example.com/serie.jpg\""
                + ",\"genreIds\":[" + drama.getId() + "]"
                + extraFields + "}";
    }

    private int favoritesOf(User user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_favorite_series WHERE user_id = ?",
                Integer.class, user.getId());
    }

    private int episodesOf(Series series) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM episodes WHERE series_id = ?",
                Integer.class, series.getId());
    }

    /** Inserta un favorito directamente (también de series ocultas, que la API no deja añadir). */
    private void favorite(User user, Series series) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(s -> seriesRepository.addFavorite(user.getId(), series.getId()));
    }

    private String tokenFor(User user) {
        return "Bearer " + jwtService.generateToken(user);
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

    private Series saveSeries(String title, int year) {
        Series series = new Series();
        series.setTitle(title);
        series.setDescription("Sinopsis de " + title);
        series.setReleaseYear(year);
        series.setImageUrl("https://example.com/serie.jpg");
        series.setGenres(new HashSet<>(Set.of(drama)));
        return seriesRepository.save(series);
    }

    private Episode saveEpisode(Series series, int season, int number, String title) {
        Episode episode = new Episode();
        episode.setSeries(series);
        episode.setSeasonNumber(season);
        episode.setEpisodeNumber(number);
        episode.setTitle(title);
        episode.setDuration(45);
        episode.setVideoUrl("https://example.com/episodio.mp4");
        return episodeRepository.save(episode);
    }

    /** Series primero (sus episodios, géneros y favoritos caen con ellas), luego el resto. */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
