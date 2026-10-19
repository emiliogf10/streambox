package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
import com.emilio.streambox.exception.UserNotFoundException;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.emilio.streambox.service.LikeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;

/**
 * Tests de punta a punta de los «me gusta» ({@code /api/users/me/likes/...}),
 * con datos confirmados de verdad (sin {@code @Transactional}) y limpieza antes
 * y después de cada test.
 *
 * <p>
 * Cubren el contrato HTTP: {@code PUT}/{@code DELETE} idempotentes (204 siempre,
 * sin 409 ni «no te gustaba»), el 404 de una película inexistente y el de una
 * serie inexistente u oculta (el mismo cuerpo que su detalle), el orden por
 * fecha del «me gusta», que lo de otro usuario no se ve ni se toca, la sesión
 * (401) y la defensa CSRF (403 con cookie sin {@code X-Requested-With}), y el
 * coste en sentencias de los listados. Los casos finos de las consultas están
 * en {@code repository.LikesQueryContract} (H2 y PostgreSQL).
 * </p>
 *
 * <p>
 * Usa las mismas propiedades que {@link CatalogIntegrationTest} (estadísticas de
 * Hibernate) para compartir su contexto de Spring.
 * </p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LikesIntegrationTest {

    private static final String LIKES = "/api/users/me/likes";
    private static final String MOVIES = LIKES + "/movies";
    private static final String SERIES = LIKES + "/series";

    /** Instante base para fijar a mano la fecha de los «me gusta». */
    private static final Instant BASE = Instant.parse("2025-01-01T00:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private LikeService likeService;
    @Autowired private MovieRepository movieRepository;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User alice;
    private User bob;
    private String aliceToken;
    private String bobToken;
    private Genre drama;
    private Movie dune;
    private Movie alien;
    private Series dark;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        alice = saveUser("likealice");
        bob = saveUser("likebob");
        aliceToken = "Bearer " + jwtService.generateToken(alice);
        bobToken = "Bearer " + jwtService.generateToken(bob);
        drama = saveGenre("Drama");
        dune = saveMovie("Dune", drama);
        alien = saveMovie("Alien", drama);
        dark = saveSeries("Dark", drama);
        saveEpisode(dark, 1, 1);
        saveEpisode(dark, 1, 2);
        saveEpisode(dark, 2, 1);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Películas
    // ------------------------------------------------------------------

    /** Dar «me gusta» responde 204 sin cuerpo y la película aparece completa en la lista. */
    @Test
    void darMeGustaAUnaPeliculaLaAnadeALaLista() throws Exception {
        mockMvc.perform(put(MOVIES + "/" + dune.getId()).header("Authorization", aliceToken))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        mockMvc.perform(get(MOVIES).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(dune.getId()))
                .andExpect(jsonPath("$[0].title").value("Dune"))
                .andExpect(jsonPath("$[0].genres[0].name").value("Drama"));
    }

    /**
     * Repetirlo es 204 otra vez, no duplica la fila y <b>conserva la fecha
     * original</b>: un doble clic no sube la película al principio de la lista.
     */
    @Test
    void repetirElMeGustaDeUnaPeliculaEsIdempotenteYConservaLaFecha() throws Exception {
        like(aliceToken, MOVIES, dune.getId());
        setMovieLikedAt(alice, dune, BASE);

        like(aliceToken, MOVIES, dune.getId());

        assertEquals(1, countMovieLikes(alice));
        assertEquals(BASE, movieLikedAt(alice, dune));
    }

    /** La fecha guardada es la del reloj de la aplicación en el momento de la petición. */
    @Test
    void laFechaDelMeGustaEsLaDeLaPeticion() throws Exception {
        Instant before = Instant.now().minusSeconds(1);
        like(aliceToken, MOVIES, dune.getId());
        Instant after = Instant.now().plusSeconds(1);

        Instant likedAt = movieLikedAt(alice, dune);
        assertTrue(likedAt.isAfter(before) && likedAt.isBefore(after), "fecha guardada: " + likedAt);
    }

    /** Una película inexistente da el mismo 404 que su detalle (estado, código y mensaje). */
    @Test
    void darMeGustaAUnaPeliculaInexistenteDaElMismo404QueSuDetalle() throws Exception {
        JsonNode like = error(put(MOVIES + "/987654").header("Authorization", aliceToken), 404);
        JsonNode detail = error(get("/api/movies/987654").header("Authorization", aliceToken), 404);

        assertEquals("RESOURCE_NOT_FOUND", like.get("code").asText());
        assertEquals("Película no encontrada", like.get("message").asText());
        assertSameError(detail, like);
        assertEquals(0, countMovieLikes(alice));
    }

    /** Quitar es 204 si le gustaba, si ya no le gustaba y si la película no existe. */
    @Test
    void quitarElMeGustaDeUnaPeliculaEsIdempotenteYNuncaDa404() throws Exception {
        like(aliceToken, MOVIES, dune.getId());

        unlike(aliceToken, MOVIES, dune.getId());
        assertEquals(0, countMovieLikes(alice));
        unlike(aliceToken, MOVIES, dune.getId());
        unlike(aliceToken, MOVIES, 987654L);

        mockMvc.perform(get(MOVIES).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * Orden: la que le gustó más recientemente primero (no la creada más
     * recientemente) y, a igual fecha, id descendente.
     */
    @Test
    void laListaDePeliculasVaDeLaQueGustoMasRecientementeALaMasAntigua() throws Exception {
        Movie arrival = saveMovie("Arrival", drama);
        like(aliceToken, MOVIES, arrival.getId());
        like(aliceToken, MOVIES, dune.getId());
        like(aliceToken, MOVIES, alien.getId());
        // Arrival es la más nueva del catálogo pero la que gustó antes; Dune y Alien empatan.
        setMovieLikedAt(alice, arrival, BASE);
        setMovieLikedAt(alice, dune, BASE.plusSeconds(60));
        setMovieLikedAt(alice, alien, BASE.plusSeconds(60));

        assertEquals(List.of(alien.getId(), dune.getId(), arrival.getId()), ids(MOVIES, aliceToken));
    }

    // ------------------------------------------------------------------
    // Series
    // ------------------------------------------------------------------

    /** La serie aparece con sus recuentos de temporadas y episodios, como en los listados. */
    @Test
    void darMeGustaAUnaSerieLaAnadeConSusRecuentos() throws Exception {
        like(aliceToken, SERIES, dark.getId());

        mockMvc.perform(get(SERIES).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(dark.getId()))
                .andExpect(jsonPath("$[0].title").value("Dark"))
                .andExpect(jsonPath("$[0].seasonCount").value(2))
                .andExpect(jsonPath("$[0].episodeCount").value(3))
                .andExpect(jsonPath("$[0].genres[0].name").value("Drama"));
    }

    @Test
    void repetirElMeGustaDeUnaSerieEsIdempotenteYConservaLaFecha() throws Exception {
        like(aliceToken, SERIES, dark.getId());
        setSeriesLikedAt(alice, dark, BASE);

        like(aliceToken, SERIES, dark.getId());

        assertEquals(1, countSeriesLikes(alice));
        assertEquals(BASE, jdbc.queryForObject("SELECT created_at FROM user_liked_series WHERE user_id = ? "
                + "AND series_id = ?", OffsetDateTime.class, alice.getId(), dark.getId()).toInstant());
    }

    /**
     * Una serie sin episodios es indistinguible de una inexistente: el mismo
     * 404, con el mismo cuerpo que el detalle de una serie que no existe.
     */
    @Test
    void unaSerieSinEpisodiosDaElMismo404QueUnaInexistenteYQueSuDetalle() throws Exception {
        Series hidden = saveSeries("Oculta", drama);

        JsonNode hiddenLike = error(put(SERIES + "/" + hidden.getId()).header("Authorization", aliceToken), 404);
        JsonNode missingLike = error(put(SERIES + "/987654").header("Authorization", aliceToken), 404);
        JsonNode missingDetail = error(get("/api/series/987654").header("Authorization", aliceToken), 404);

        assertEquals("RESOURCE_NOT_FOUND", hiddenLike.get("code").asText());
        assertEquals("Serie no encontrada", hiddenLike.get("message").asText());
        assertSameError(missingLike, hiddenLike);
        assertSameError(missingDetail, hiddenLike);
        assertEquals(0, countSeriesLikes(alice));
    }

    /** Quitar es 204 siempre: le gustara o no, exista o no, esté oculta o no. */
    @Test
    void quitarElMeGustaDeUnaSerieEsIdempotenteYNuncaDa404() throws Exception {
        Series hidden = saveSeries("Oculta", drama);
        like(aliceToken, SERIES, dark.getId());

        unlike(aliceToken, SERIES, dark.getId());
        assertEquals(0, countSeriesLikes(alice));
        unlike(aliceToken, SERIES, dark.getId());
        unlike(aliceToken, SERIES, hidden.getId());
        unlike(aliceToken, SERIES, 987654L);
    }

    /**
     * Una serie que le gustaba y se queda sin episodios deja de aparecer, pero
     * el «me gusta» se conserva y vuelve a aparecer cuando recupera alguno.
     */
    @Test
    void unaSerieQueSeQuedaSinEpisodiosDesapareceYVuelveConSuMeGusta() throws Exception {
        like(aliceToken, SERIES, dark.getId());

        episodeRepository.deleteAll(episodeRepository.findAll());
        assertEquals(List.of(), ids(SERIES, aliceToken));
        assertEquals(1, countSeriesLikes(alice));

        saveEpisode(dark, 1, 1);
        assertEquals(List.of(dark.getId()), ids(SERIES, aliceToken));
    }

    @Test
    void laListaDeSeriesVaDeLaQueGustoMasRecientementeALaMasAntigua() throws Exception {
        Series lost = saveSeries("Lost", drama);
        saveEpisode(lost, 1, 1);
        Series fargo = saveSeries("Fargo", drama);
        saveEpisode(fargo, 1, 1);
        like(aliceToken, SERIES, fargo.getId());
        like(aliceToken, SERIES, dark.getId());
        like(aliceToken, SERIES, lost.getId());
        setSeriesLikedAt(alice, fargo, BASE);
        setSeriesLikedAt(alice, dark, BASE.plusSeconds(60));
        setSeriesLikedAt(alice, lost, BASE.plusSeconds(60));

        // Lost y Dark empatan: id descendente (Lost se creó después); Fargo, la más antigua, al final.
        assertEquals(List.of(lost.getId(), dark.getId(), fargo.getId()), ids(SERIES, aliceToken));
    }

    // ------------------------------------------------------------------
    // Aislamiento entre usuarios
    // ------------------------------------------------------------------

    /** Cada usuario ve solo sus «me gusta», y quitar el suyo no toca los del otro. */
    @Test
    void losMeGustaDeOtroUsuarioNiSeVenNiSeTocan() throws Exception {
        like(aliceToken, MOVIES, dune.getId());
        like(aliceToken, SERIES, dark.getId());

        assertEquals(List.of(), ids(MOVIES, bobToken));
        assertEquals(List.of(), ids(SERIES, bobToken));

        unlike(bobToken, MOVIES, dune.getId());
        unlike(bobToken, SERIES, dark.getId());
        like(bobToken, MOVIES, alien.getId());

        assertEquals(List.of(dune.getId()), ids(MOVIES, aliceToken));
        assertEquals(List.of(dark.getId()), ids(SERIES, aliceToken));
        assertEquals(List.of(alien.getId()), ids(MOVIES, bobToken));
    }

    /** Borrar la película o la serie borra sus «me gusta» (cascada de la V8), sin errores. */
    @Test
    void borrarUnTituloLoQuitaDeLosMeGusta() throws Exception {
        like(aliceToken, MOVIES, dune.getId());
        like(aliceToken, SERIES, dark.getId());

        movieRepository.deleteById(dune.getId());
        seriesRepository.deleteById(dark.getId());

        assertEquals(List.of(), ids(MOVIES, aliceToken));
        assertEquals(List.of(), ids(SERIES, aliceToken));
        assertEquals(0, countMovieLikes(alice) + countSeriesLikes(alice));
    }

    /**
     * Lo mismo, pero como lo hace el administrador desde el panel:
     * {@code DELETE /api/movies/{id}} y {@code DELETE /api/series/{id}} dan 204
     * aunque el título tenga «me gusta» de varios usuarios (la cascada de la
     * base de datos no choca con lo que borra cada servicio: favoritos y
     * episodios), y los «me gusta» desaparecen de todas las listas y de las
     * recomendaciones. Los de otros títulos se conservan.
     */
    @Test
    void borrarUnTituloDesdeElPanelDa204YQuitaSusMeGustaATodos() throws Exception {
        User admin = saveUser("likeadmin");
        admin.setRole(Role.ADMIN);
        admin = userRepository.save(admin);
        String adminToken = "Bearer " + jwtService.generateToken(admin);
        for (String token : List.of(aliceToken, bobToken)) {
            like(token, MOVIES, dune.getId());
            like(token, MOVIES, alien.getId());
            like(token, SERIES, dark.getId());
        }

        mockMvc.perform(delete("/api/movies/" + dune.getId()).header("Authorization", adminToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/series/" + dark.getId()).header("Authorization", adminToken))
                .andExpect(status().isNoContent());

        for (String token : List.of(aliceToken, bobToken)) {
            assertEquals(List.of(alien.getId()), ids(MOVIES, token));
            assertEquals(List.of(), ids(SERIES, token));
        }
        assertEquals(1, countMovieLikes(alice));
        assertEquals(0, countSeriesLikes(alice) + countSeriesLikes(bob));
    }

    /**
     * Si la cuenta se borra durante la petición (el filtro ya la leyó), la
     * clave foránea del usuario se traduce a «Usuario no encontrado», no a un
     * «Película no encontrada» falso ni a un 500.
     */
    @Test
    void siElUsuarioNoExisteLaClaveForaneaSeTraduceAUsuarioNoEncontrado() {
        assertThrows(UserNotFoundException.class, () -> likeService.likeMovie(987654L, dune.getId()));
        assertThrows(UserNotFoundException.class, () -> likeService.likeSeries(987654L, dark.getId()));
    }

    // ------------------------------------------------------------------
    // Sesión, CSRF y parámetros
    // ------------------------------------------------------------------

    @Test
    void sinSesionTodasLasRutasDan401() throws Exception {
        List<MockHttpServletRequestBuilder> requests = List.of(
                get(MOVIES), get(SERIES),
                put(MOVIES + "/" + dune.getId()), delete(MOVIES + "/" + dune.getId()),
                put(SERIES + "/" + dark.getId()), delete(SERIES + "/" + dark.getId()));

        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(request.header("X-Requested-With", "StreamBox"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
    }

    /**
     * Petición de {@code security} (paso 2.4): {@code HEAD} y {@code OPTIONS}
     * sin sesión también dan 401 en todas las rutas de «me gusta». Las rutas
     * personales caen en {@code anyRequest().authenticated()}; si alguien
     * añadiera una regla atada a {@code GET} (como pasó con {@code /api/users}),
     * {@code HEAD} u {@code OPTIONS} podrían colarse sin token. {@code HEAD}
     * sobre un listado ejecuta el mismo método que {@code GET}: sin esta
     * comprobación filtraría si hay «me gusta» por la longitud de la respuesta.
     */
    @Test
    void headYOptionsSinSesionDan401() throws Exception {
        List<String> urls = List.of(MOVIES, SERIES, MOVIES + "/" + dune.getId(), SERIES + "/" + dark.getId());
        for (String url : urls) {
            mockMvc.perform(head(url))
                    .andExpect(status().isUnauthorized());
            mockMvc.perform(options(url))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
    }

    /**
     * Con sesión, {@code HEAD} de un listado es 200 (MockMvc no quita el
     * cuerpo; Tomcat sí) y
     * {@code OPTIONS} anuncia los métodos de verdad sin ejecutar nada: no da
     * ni quita «me gusta».
     */
    @Test
    void headYOptionsConSesionNoCambianNada() throws Exception {
        like(aliceToken, MOVIES, dune.getId());

        mockMvc.perform(head(MOVIES).header("Authorization", aliceToken))
                .andExpect(status().isOk());
        mockMvc.perform(options(MOVIES + "/" + alien.getId()).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Allow", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("PUT"),
                        org.hamcrest.Matchers.containsString("DELETE"))));
        mockMvc.perform(options(MOVIES + "/" + dune.getId()).header("Authorization", aliceToken)
                .header("X-Requested-With", "StreamBox"))
                .andExpect(status().isOk());

        assertEquals(List.of(dune.getId()), ids(MOVIES, aliceToken));
    }

    /**
     * Un método que la ruta no admite da 405 con el {@code ErrorResponse} de
     * siempre, nunca 500 ni una página de Tomcat: {@code POST}/{@code PATCH}
     * sobre un «me gusta» y {@code PUT}/{@code DELETE} sobre el listado (sin
     * id). Ninguno cambia nada.
     */
    @Test
    void unMetodoNoAdmitidoDa405EnJson() throws Exception {
        List<MockHttpServletRequestBuilder> requests = List.of(
                post(MOVIES + "/" + dune.getId()), patch(SERIES + "/" + dark.getId()),
                put(MOVIES), delete(SERIES));

        for (MockHttpServletRequestBuilder request : requests) {
            mockMvc.perform(request.header("Authorization", aliceToken).header("X-Requested-With", "StreamBox"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                    .andExpect(jsonPath("$.status").value(405));
        }
        assertEquals(0, countMovieLikes(alice) + countSeriesLikes(alice));
    }

    /**
     * Con la cookie de sesión (como el navegador), las escrituras sin
     * {@code X-Requested-With} dan 403 {@code CSRF_REJECTED} y no cambian nada;
     * con la cabecera, 204. La lectura no la necesita.
     */
    @Test
    void conCookieLasEscriturasExigenLaCabeceraCsrf() throws Exception {
        Cookie session = new Cookie("streambox_token", jwtService.generateToken(alice));
        List<MockHttpServletRequestBuilder> writes = List.of(
                put(MOVIES + "/" + dune.getId()), delete(MOVIES + "/" + dune.getId()),
                put(SERIES + "/" + dark.getId()), delete(SERIES + "/" + dark.getId()));

        for (MockHttpServletRequestBuilder write : writes) {
            mockMvc.perform(write.cookie(session))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CSRF_REJECTED"));
        }
        assertEquals(0, countMovieLikes(alice) + countSeriesLikes(alice));

        mockMvc.perform(put(MOVIES + "/" + dune.getId()).cookie(session).header("X-Requested-With", "StreamBox"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(MOVIES).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(dune.getId()));
    }

    /** Un id que no es un número entero da 400 en las cuatro escrituras, nunca 500. */
    @ParameterizedTest
    @ValueSource(strings = { MOVIES, SERIES })
    void unIdNoNumericoDa400(String base) throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(put(base + "/abc"), delete(base + "/1.5"))) {
            mockMvc.perform(request.header("Authorization", aliceToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    // ------------------------------------------------------------------
    // Coste en sentencias SQL
    // ------------------------------------------------------------------

    /**
     * Los listados tienen coste fijo, tengan los títulos que tengan: películas,
     * dos sentencias (lista + géneros por lotes); series, tres (lista +
     * recuentos + géneros). Vacíos, una.
     */
    @Test
    void losListadosTienenCosteFijoSinNMasUno() {
        Genre scifi = saveGenre("Ciencia ficcion");
        assertEquals(1, statementsFor(() -> likeService.getLikedMovies(alice.getId())));
        assertEquals(1, statementsFor(() -> likeService.getLikedSeries(alice.getId())));

        for (int i = 0; i < 6; i++) {
            Movie movie = saveMovie("Película " + i, drama, scifi);
            likeService.likeMovie(alice.getId(), movie.getId());
            Series series = saveSeries("Serie " + i, drama, scifi);
            saveEpisode(series, 1, 1);
            likeService.likeSeries(alice.getId(), series.getId());
        }

        assertEquals(2, statementsFor(() -> assertEquals(6, likeService.getLikedMovies(alice.getId()).size())));
        assertEquals(3, statementsFor(() -> assertEquals(6, likeService.getLikedSeries(alice.getId()).size())));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private void like(String token, String base, Long id) throws Exception {
        mockMvc.perform(put(base + "/" + id).header("Authorization", token))
                .andExpect(status().isNoContent());
    }

    private void unlike(String token, String base, Long id) throws Exception {
        mockMvc.perform(delete(base + "/" + id).header("Authorization", token))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
    }

    /** Ids de un listado de «me gusta», en el orden de la respuesta. */
    private List<Long> ids(String url, String token) throws Exception {
        String json = mockMvc.perform(get(url).header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).valueStream().map(node -> node.get("id").asLong()).toList();
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
        assertFalse(actual.has("trace"));
    }

    private int countMovieLikes(User user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_liked_movies WHERE user_id = ?",
                Integer.class, user.getId());
    }

    private int countSeriesLikes(User user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_liked_series WHERE user_id = ?",
                Integer.class, user.getId());
    }

    private Instant movieLikedAt(User user, Movie movie) {
        return jdbc.queryForObject("SELECT created_at FROM user_liked_movies WHERE user_id = ? AND movie_id = ?",
                OffsetDateTime.class, user.getId(), movie.getId()).toInstant();
    }

    private void setMovieLikedAt(User user, Movie movie, Instant likedAt) {
        jdbc.update("UPDATE user_liked_movies SET created_at = ? WHERE user_id = ? AND movie_id = ?",
                OffsetDateTime.ofInstant(likedAt, ZoneOffset.UTC), user.getId(), movie.getId());
    }

    private void setSeriesLikedAt(User user, Series series, Instant likedAt) {
        jdbc.update("UPDATE user_liked_series SET created_at = ? WHERE user_id = ? AND series_id = ?",
                OffsetDateTime.ofInstant(likedAt, ZoneOffset.UTC), user.getId(), series.getId());
    }

    private long statementsFor(Runnable operation) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        operation.run();
        return statistics.getPrepareStatementCount();
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

    private Movie saveMovie(String title, Genre... genres) {
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setDescription("Descripción de " + title);
        movie.setDuration(100);
        movie.setReleaseYear(2000);
        movie.setImageUrl("https://example.com/image.jpg");
        movie.setVideoUrl("https://example.com/video.mp4");
        movie.setGenres(new HashSet<>(List.of(genres)));
        return movieRepository.save(movie);
    }

    private Series saveSeries(String title, Genre... genres) {
        Series series = new Series();
        series.setTitle(title);
        series.setDescription("Sinopsis de " + title);
        series.setReleaseYear(2010);
        series.setImageUrl("https://example.com/serie.jpg");
        series.setGenres(new HashSet<>(List.of(genres)));
        return seriesRepository.save(series);
    }

    private void saveEpisode(Series series, int season, int number) {
        Episode episode = new Episode();
        episode.setSeries(series);
        episode.setSeasonNumber(season);
        episode.setEpisodeNumber(number);
        episode.setTitle("Episodio " + season + "x" + number);
        episode.setDuration(45);
        episode.setVideoUrl("https://example.com/episodio.mp4");
        episodeRepository.save(episode);
    }

    /** Vacía las tablas respetando las claves foráneas (los «me gusta» caen en cascada). */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
