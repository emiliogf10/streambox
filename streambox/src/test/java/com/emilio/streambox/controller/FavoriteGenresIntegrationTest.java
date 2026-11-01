package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import org.springframework.test.web.servlet.ResultActions;

import com.emilio.streambox.dto.FavoriteGenresRequest;
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
import com.emilio.streambox.service.FavoriteGenreService;
import com.emilio.streambox.support.TestAccounts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests de punta a punta de los géneros favoritos («Tus gustos»):
 * {@code GET/PUT /api/users/me/genres}, {@code GET /api/users/me/genres/options}
 * y {@code genresOnboardingPending} en {@code /api/users/me}.
 *
 * <p>
 * Sin {@code @Transactional}, con datos confirmados y limpieza antes y después
 * de cada test: el {@code PUT} cierra la pregunta con un {@code UPDATE} masivo
 * ({@code markGenresOnboarded}), y en un test transaccional MockMvc compartiría
 * la sesión de Hibernate entre peticiones, así que el siguiente
 * {@code GET /api/users/me} leería la cuenta ya cargada (con la fecha aún nula)
 * en lugar de la base de datos. Usa las mismas propiedades que
 * {@link RecommendationsIntegrationTest} para compartir su contexto de Spring.
 * </p>
 *
 * <p>
 * Las consultas en sí (visibilidad, cascada, orden) se prueban en H2 y
 * PostgreSQL en {@code FavoriteGenresQueryContract}; aquí, el contrato HTTP.
 * </p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class FavoriteGenresIntegrationTest {

    private static final String ME = "/api/users/me";
    private static final String GENRES = "/api/users/me/genres";
    private static final String OPTIONS = "/api/users/me/genres/options";

    private static final String COUNT_MESSAGE = "Elige entre 3 y 5 géneros, o ninguno.";
    private static final String DUPLICATES_MESSAGE = "Hay géneros repetidos.";
    private static final String NOT_ELIGIBLE_MESSAGE = "Alguno de los géneros no existe o no tiene títulos.";

    @Autowired private MockMvc mockMvc;
    @Autowired private FavoriteGenreService favoriteGenreService;
    @Autowired private MovieRepository movieRepository;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    private User alice;
    private String aliceToken;

    /** Géneros con títulos visibles, creados en un orden distinto del alfabético. */
    private Genre terror;
    private Genre accion;
    private Genre drama;
    private Genre comedia;
    private Genre western;
    private Genre musical;

    /** Género cuya única serie no tiene episodios: existe, pero no tiene títulos visibles. */
    private Genre documental;

    /** Género sin ningún título. */
    private Genre vacio;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        alice = saveUser("favgenalice", Role.USER, null);
        aliceToken = bearer(alice);

        terror = saveGenre("Terror");
        accion = saveGenre("Acción");
        drama = saveGenre("Drama");
        comedia = saveGenre("Comedia");
        western = saveGenre("Western");
        musical = saveGenre("Musical");
        documental = saveGenre("Documental");
        vacio = saveGenre("Vacío");

        saveMovie("Película de terror", terror);
        saveMovie("Película de acción y drama", accion, drama);
        saveMovie("Película de comedia", comedia);
        saveMovie("Película del oeste", western);
        Series musicalSeries = saveSeries("Serie musical", musical);
        saveEpisode(musicalSeries);
        saveSeries("Serie documental sin episodios", documental);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // genresOnboardingPending en /api/users/me
    // ------------------------------------------------------------------

    /** Una cuenta USER recién creada (sin fecha de la pregunta) la tiene pendiente. */
    @Test
    void unUsuarioNuevoTieneLaPreguntaPendiente() throws Exception {
        mockMvc.perform(get(ME).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.genresOnboardingPending").value(true));
    }

    /**
     * Por el camino real del alta (registro + confirmación del correo), la
     * cuenta nace con la pregunta pendiente: la columna no tiene valor por
     * defecto ni lo rellena el registro.
     */
    @Test
    void unaCuentaRegistradaPorLaApiNaceConLaPreguntaPendiente() throws Exception {
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"favgennueva\",\"email\":\"favgennueva@test.com\","
                                + "\"password\":\"Secure-Pass-2026\"}"))
                .andExpect(status().isAccepted());
        User registered = TestAccounts.markEmailVerified(userRepository, "favgennueva@test.com");
        assertNull(registered.getGenresOnboardedAt());

        mockMvc.perform(get(ME).header("Authorization", bearer(registered)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.genresOnboardingPending").value(true));
    }

    /** Guardar géneros cierra la pregunta. */
    @Test
    void trasGuardarGenerosLaPreguntaDejaDeEstarPendiente() throws Exception {
        putGenres(aliceToken, ids(terror, accion, drama)).andExpect(status().isOk());

        mockMvc.perform(get(ME).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.genresOnboardingPending").value(false));
    }

    /** «Ahora no» (la lista vacía) también la cierra, para siempre. */
    @Test
    void laListaVaciaTambienCierraLaPregunta() throws Exception {
        putGenres(aliceToken, "[]").andExpect(status().isOk());

        mockMvc.perform(get(ME).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.genresOnboardingPending").value(false));
        assertNotNull(userRepository.findById(alice.getId()).orElseThrow().getGenresOnboardedAt());
    }

    /** Una petición no válida no cierra la pregunta: no se ha contestado. */
    @Test
    void unaPeticionNoValidaNoCierraLaPregunta() throws Exception {
        putGenres(aliceToken, ids(terror, accion)).andExpect(status().isBadRequest());

        mockMvc.perform(get(ME).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.genresOnboardingPending").value(true));
    }

    /** Un ADMIN nunca la tiene pendiente, aunque no tenga fecha. */
    @Test
    void unAdminNuncaTieneLaPreguntaPendiente() throws Exception {
        User admin = saveUser("favgenadmin", Role.ADMIN, null);

        mockMvc.perform(get(ME).header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.genresOnboardingPending").value(false));
    }

    /**
     * Una cuenta que ya existía antes de la V11 (la migración le rellenó la
     * fecha) no la tiene pendiente.
     */
    @Test
    void unaCuentaExistenteTrasLaV11NoTieneLaPreguntaPendiente() throws Exception {
        User veteran = saveUser("favgenveterana", Role.USER, Instant.parse("2026-10-25T10:00:00Z"));

        mockMvc.perform(get(ME).header("Authorization", bearer(veteran)))
                .andExpect(jsonPath("$.genresOnboardingPending").value(false));
    }

    /**
     * {@code PATCH /api/users/me} devuelve el mismo DTO que el {@code GET}: el
     * frontend sustituye con él su copia del usuario y no debe perder el dato.
     */
    @Test
    void elPatchDelPerfilDevuelveTambienLaPreguntaPendiente() throws Exception {
        mockMvc.perform(patch(ME).header("Authorization", aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"avatar\":\"cat\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatar").value("cat"))
                .andExpect(jsonPath("$.genresOnboardingPending").value(true));
    }

    /** El listado de usuarios del panel no lleva el dato: es solo para el propio usuario. */
    @Test
    void elListadoDeUsuariosNoIncluyeLaPreguntaPendiente() throws Exception {
        User admin = saveUser("favgenadminlist", Role.ADMIN, null);

        mockMvc.perform(get("/api/users").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").exists())
                .andExpect(jsonPath("$[0].genresOnboardingPending").doesNotExist())
                .andExpect(jsonPath("$[1].genresOnboardingPending").doesNotExist());
    }

    // ------------------------------------------------------------------
    // GET /api/users/me/genres/options
    // ------------------------------------------------------------------

    /**
     * Solo los géneros con algún título visible (película o serie con
     * episodios), por nombre: ni el de la serie sin episodios ni el vacío.
     */
    @Test
    void lasOpcionesSonLosGenerosConTitulosVisiblesPorNombre() throws Exception {
        List<String> names = names(mockMvc.perform(get(OPTIONS).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(accion.getId())));

        assertEquals(List.of("Acción", "Comedia", "Drama", "Musical", "Terror", "Western"), names);
    }

    // ------------------------------------------------------------------
    // GET y PUT /api/users/me/genres
    // ------------------------------------------------------------------

    @Test
    void sinFavoritosLaListaEstaVacia() throws Exception {
        mockMvc.perform(get(GENRES).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** Con 3 géneros: 200 con la lista guardada por nombre, y el GET devuelve lo mismo. */
    @Test
    void guardarTresGenerosDevuelveLaListaPorNombre() throws Exception {
        String saved = putGenres(aliceToken, ids(terror, accion, musical))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(accion.getId()))
                .andExpect(jsonPath("$[0].name").value("Acción"))
                .andExpect(jsonPath("$[1].name").value("Musical"))
                .andExpect(jsonPath("$[2].name").value("Terror"))
                .andExpect(jsonPath("$.length()").value(3))
                .andReturn().getResponse().getContentAsString();

        String read = mockMvc.perform(get(GENRES).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(saved, read, "el PUT devuelve exactamente lo mismo que el GET");
    }

    @Test
    void guardarCincoGenerosEsValido() throws Exception {
        putGenres(aliceToken, ids(terror, accion, drama, comedia, western))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));
    }

    /** El PUT sustituye la lista entera: lo que no viene, desaparece. */
    @Test
    void elPutSustituyeLaListaEntera() throws Exception {
        putGenres(aliceToken, ids(terror, accion, drama)).andExpect(status().isOk());

        putGenres(aliceToken, ids(comedia, western, musical))
                .andExpect(status().isOk());

        assertEquals(List.of("Comedia", "Musical", "Western"), favoriteNames(aliceToken));
    }

    /** La lista vacía quita todos los gustos. */
    @Test
    void laListaVaciaQuitaTodosLosGustos() throws Exception {
        putGenres(aliceToken, ids(terror, accion, drama)).andExpect(status().isOk());

        putGenres(aliceToken, "[]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        assertEquals(List.of(), favoriteNames(aliceToken));
    }

    /**
     * Idempotente: repetir la misma lista (en otro orden) da la misma respuesta
     * y conserva la fecha en que se cerró la pregunta (la primera vez).
     */
    @Test
    void elPutEsIdempotenteYConservaLaFechaDeLaPrimeraVez() throws Exception {
        String first = putGenres(aliceToken, ids(terror, accion, drama))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Instant closedAt = userRepository.findById(alice.getId()).orElseThrow().getGenresOnboardedAt();
        jdbc.update("UPDATE users SET genres_onboarded_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(closedAt.minus(1, ChronoUnit.DAYS), ZoneOffset.UTC), alice.getId());

        String second = putGenres(aliceToken, ids(drama, terror, accion))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertEquals(first, second);
        assertEquals(closedAt.minus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS),
                userRepository.findById(alice.getId()).orElseThrow().getGenresOnboardedAt()
                        .truncatedTo(ChronoUnit.MILLIS),
                "la fecha de la pregunta no se pisa");
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM user_favorite_genres WHERE user_id = ?",
                Integer.class, alice.getId()));
    }

    /** Cualquier número que no sea 0 ni de 3 a 5 se rechaza con el mensaje de la regla. */
    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 6 })
    void unNumeroDeGenerosFueraDeRangoDa400(int count) throws Exception {
        List<Genre> pool = List.of(terror, accion, drama, comedia, western, musical);
        String body = ids(pool.subList(0, count).toArray(Genre[]::new));

        expectGenreIdsError(putGenres(aliceToken, body), COUNT_MESSAGE);
    }

    /** Más de 5 se rechaza sin consultar la base de datos, aunque los ids no existan. */
    @Test
    void unaListaEnormeDa400PorElNumero() throws Exception {
        String body = Stream.iterate(1L, i -> i + 1).limit(10_000).map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));

        expectGenreIdsError(putGenres(aliceToken, body), COUNT_MESSAGE);
    }

    @Test
    void losIdsRepetidosDan400() throws Exception {
        String body = "[" + terror.getId() + "," + terror.getId() + "," + accion.getId() + "]";

        expectGenreIdsError(putGenres(aliceToken, body), DUPLICATES_MESSAGE);
    }

    @Test
    void unGeneroInexistenteDa400() throws Exception {
        String body = "[" + terror.getId() + "," + accion.getId() + ",999999999]";

        expectGenreIdsError(putGenres(aliceToken, body), NOT_ELIGIBLE_MESSAGE);
    }

    /** Un género sin títulos visibles no se puede elegir: ni el vacío ni el de una serie sin episodios. */
    @Test
    void unGeneroSinTitulosVisiblesDa400() throws Exception {
        expectGenreIdsError(putGenres(aliceToken, ids(terror, accion, vacio)), NOT_ELIGIBLE_MESSAGE);
        expectGenreIdsError(putGenres(aliceToken, ids(terror, accion, documental)), NOT_ELIGIBLE_MESSAGE);
    }

    @Test
    void unIdNuloDentroDeLaListaDa400() throws Exception {
        String body = "[" + terror.getId() + ",null," + accion.getId() + "]";

        expectGenreIdsError(putGenres(aliceToken, body), NOT_ELIGIBLE_MESSAGE);
    }

    /** Sin {@code genreIds} o con {@code null}: 400 con el mensaje del campo obligatorio. */
    @ParameterizedTest
    @ValueSource(strings = { "{}", "{\"genreIds\":null}" })
    void sinListaDa400(String body) throws Exception {
        mockMvc.perform(put(GENRES).header("Authorization", aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.genreIds").value(FavoriteGenresRequest.NULL_MESSAGE));
    }

    /** Un id que no es un entero no se convierte: 400 {@code MALFORMED_REQUEST}. */
    @Test
    void unIdQueNoEsUnEnteroDa400Malformado() throws Exception {
        mockMvc.perform(put(GENRES).header("Authorization", aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"genreIds\":[\"uno\",2,3]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    /** Un PUT rechazado no toca la lista anterior. */
    @Test
    void unPutRechazadoNoCambiaLaListaAnterior() throws Exception {
        putGenres(aliceToken, ids(terror, accion, drama)).andExpect(status().isOk());

        putGenres(aliceToken, ids(comedia, western, vacio)).andExpect(status().isBadRequest());

        assertEquals(List.of("Acción", "Drama", "Terror"), favoriteNames(aliceToken));
    }

    /**
     * Un favorito que se ha quedado sin títulos visibles no sale en el GET
     * (sigue en la base de datos), y vuelve a salir si recupera alguno.
     */
    @Test
    void unFavoritoQueSeQuedaSinTitulosNoSaleEnElGet() throws Exception {
        putGenres(aliceToken, ids(terror, accion, western)).andExpect(status().isOk());
        jdbc.update("DELETE FROM movie_genres WHERE genre_id = ?", western.getId());

        assertEquals(List.of("Acción", "Terror"), favoriteNames(aliceToken));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM user_favorite_genres WHERE user_id = ?",
                Integer.class, alice.getId()), "sigue guardado, solo que no se muestra");

        saveMovie("Otra del oeste", western);
        assertEquals(List.of("Acción", "Terror", "Western"), favoriteNames(aliceToken));
    }

    /** Cada usuario ve solo sus gustos (el id sale del token). */
    @Test
    void otroUsuarioNoVeMisGustos() throws Exception {
        User bob = saveUser("favgenbob", Role.USER, null);
        putGenres(aliceToken, ids(terror, accion, drama)).andExpect(status().isOk());

        assertEquals(List.of(), favoriteNames(bearer(bob)));
        putGenres(bearer(bob), ids(comedia, western, musical)).andExpect(status().isOk());
        assertEquals(List.of("Acción", "Drama", "Terror"), favoriteNames(aliceToken));
    }

    /** Un ADMIN también puede tener gustos (sección «Tus gustos» del perfil). */
    @Test
    void unAdminTambienPuedeGuardarSusGustos() throws Exception {
        User admin = saveUser("favgenadminput", Role.ADMIN, null);

        putGenres(bearer(admin), ids(terror, accion, drama)).andExpect(status().isOk());

        assertEquals(List.of("Acción", "Drama", "Terror"), favoriteNames(bearer(admin)));
    }

    /**
     * Si la cuenta ya no existe (se borró durante la petición: el filtro JWT la
     * leyó antes), 404 y nada guardado. Se llama al servicio directamente
     * porque por HTTP el filtro ya respondería 401.
     */
    @Test
    void unaCuentaQueYaNoExisteDa404() {
        Long missing = alice.getId() + 1000;

        assertThrows(UserNotFoundException.class, () -> favoriteGenreService.replaceFavoriteGenres(missing,
                new FavoriteGenresRequest(List.of(terror.getId(), accion.getId(), drama.getId()))));
        assertThrows(UserNotFoundException.class,
                () -> favoriteGenreService.replaceFavoriteGenres(missing, new FavoriteGenresRequest(List.of())));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_favorite_genres", Integer.class));
    }

    @Test
    void sinSesionDa401() throws Exception {
        mockMvc.perform(get(GENRES)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(OPTIONS)).andExpect(status().isUnauthorized());
        mockMvc.perform(put(GENRES).contentType(MediaType.APPLICATION_JSON).content("{\"genreIds\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    /** De punta a punta: tras guardar los gustos, las recomendaciones se basan en ellos. */
    @Test
    void trasGuardarLosGustosLasRecomendacionesSeBasanEnGeneros() throws Exception {
        mockMvc.perform(get("/api/users/me/recommendations").header("Authorization", aliceToken))
                .andExpect(jsonPath("$.basis").value("NONE"));

        putGenres(aliceToken, ids(terror, comedia, musical)).andExpect(status().isOk());

        mockMvc.perform(get("/api/users/me/recommendations").header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basis").value("GENRES"))
                .andExpect(jsonPath("$.movies.length()").value(2))
                .andExpect(jsonPath("$.series.length()").value(1));
    }

    // ------------------------------------------------------------------
    // OpenAPI
    // ------------------------------------------------------------------

    /**
     * El contrato publicado coincide con el real: los tres {@code operationId}
     * son los nombres de los métodos (springdoc renombraría uno repetido a
     * {@code ..._1} y rompería los clientes generados), {@code /me} y el
     * {@code PATCH} usan {@code CurrentUserResponse} con
     * {@code genresOnboardingPending} y el listado sigue con
     * {@code UserResponse}, sin él; y {@code basis} publica sus cuatro valores
     * en lugar de {@code basedOnLikes}.
     */
    @Test
    void elOpenApiDocumentaElContratoDeLosGustos() throws Exception {
        JsonNode docs = new ObjectMapper().readTree(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode paths = docs.get("paths");
        JsonNode schemas = docs.get("components").get("schemas");

        assertEquals("getFavoriteGenres", paths.get(GENRES).get("get").get("operationId").asText());
        assertEquals("replaceFavoriteGenres", paths.get(GENRES).get("put").get("operationId").asText());
        assertEquals("getFavoriteGenreOptions", paths.get(OPTIONS).get("get").get("operationId").asText());
        assertTrue(paths.get(GENRES).get("put").get("responses").get("400").get("description").asText()
                .contains("validationErrors.genreIds"));

        assertEquals("#/components/schemas/CurrentUserResponse", paths.get(ME).get("get").get("responses")
                .get("200").get("content").get("*/*").get("schema").get("$ref").asText());
        assertEquals("#/components/schemas/CurrentUserResponse", paths.get(ME).get("patch").get("responses")
                .get("200").get("content").get("*/*").get("schema").get("$ref").asText());
        assertTrue(schemas.get("CurrentUserResponse").get("properties").has("genresOnboardingPending"));
        assertFalse(schemas.get("UserResponse").get("properties").has("genresOnboardingPending"));

        JsonNode recommendations = schemas.get("RecommendationsResponse").get("properties");
        assertFalse(recommendations.has("basedOnLikes"));
        List<String> basis = new ArrayList<>();
        recommendations.get("basis").get("enum").forEach(value -> basis.add(value.asText()));
        assertEquals(List.of("NONE", "GENRES", "LIKES", "GENRES_AND_LIKES"), basis);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private ResultActions putGenres(String token, String idsJson) throws Exception {
        return mockMvc.perform(put(GENRES).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"genreIds\":" + idsJson + "}"));
    }

    private static void expectGenreIdsError(ResultActions result, String message) throws Exception {
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.genreIds").value(message));
    }

    private List<String> favoriteNames(String token) throws Exception {
        return names(mockMvc.perform(get(GENRES).header("Authorization", token)).andExpect(status().isOk()));
    }

    private static List<String> names(ResultActions result) throws Exception {
        String json = result.andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(json, "$[*].name");
    }

    private static String ids(Genre... genres) {
        return Stream.of(genres).map(g -> String.valueOf(g.getId())).collect(Collectors.joining(",", "[", "]"));
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateToken(user);
    }

    private User saveUser(String name, Role role, Instant genresOnboardedAt) {
        User user = new User();
        user.setEmailVerifiedAt(Instant.now()); // cuenta verificada (V9)
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(role);
        user.setGenresOnboardedAt(genresOnboardedAt);
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

    private void saveEpisode(Series series) {
        Episode episode = new Episode();
        episode.setSeries(series);
        episode.setSeasonNumber(1);
        episode.setEpisodeNumber(1);
        episode.setTitle("Episodio 1x1");
        episode.setDuration(45);
        episode.setVideoUrl("https://example.com/episodio.mp4");
        episodeRepository.save(episode);
    }

    /** Vacía las tablas respetando las claves foráneas (favoritos y «me gusta» caen en cascada). */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
