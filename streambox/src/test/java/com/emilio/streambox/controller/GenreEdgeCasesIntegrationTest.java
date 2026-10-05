package com.emilio.streambox.controller;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsStringIgnoringCase;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Casos límite de la edición y el borrado de géneros
 * ({@code PUT/DELETE /api/genres/{id}}) que no cubre
 * {@link GenreControllerIntegrationTest}: identificadores con formato
 * incorrecto, cuerpos ausentes o mal formados, nombres que solo cambian
 * mayúsculas en letras acentuadas, el ciclo "quitar el género de sus películas
 * y luego borrarlo" y nombres que eludirían la validación de longitud si solo
 * se comprobara el texto recibido y no el ya normalizado.
 *
 * <p>
 * No usa {@code @Transactional}: los datos se confirman de verdad (como
 * {@link CatalogEdgeCasesIntegrationTest}) para que cada petición vea lo que
 * confirmó la anterior, igual que en producción, y no un único contexto de
 * persistencia compartido que podría ocultar errores de {@code flush}. Se
 * limpia antes y después de cada test en el orden que exigen las claves
 * foráneas (usuarios, películas, géneros).
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class GenreEdgeCasesIntegrationTest {

    private static final String GENRES = "/api/genres";
    private static final String GENRE = "/api/genres/{id}";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        adminToken = "Bearer " + jwtService.generateToken(saveUser("genreedgeadmin", Role.ADMIN));
        userToken = "Bearer " + jwtService.generateToken(saveUser("genreedgeuser", Role.USER));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Identificador de la ruta
    // ------------------------------------------------------------------

    /**
     * Un {@code id} que no es un {@code Long} (texto, decimal o fuera de rango)
     * es un error del cliente: 400 señalando el parámetro, nunca 500 ni 404.
     */
    @ParameterizedTest
    @ValueSource(strings = { "abc", "1.5", "99999999999999999999", "1e3" })
    void unIdConFormatoIncorrectoEnPutYDeleteRetorna400(String id) throws Exception {
        mockMvc.perform(put("/api/genres/" + id).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json("Terror")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.id").exists());

        mockMvc.perform(delete("/api/genres/" + id).header("Authorization", adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.id").exists());
    }

    /**
     * Un {@code id} negativo o cero tiene formato válido pero no puede existir
     * (la identidad empieza en 1): 404, igual que cualquier id inexistente.
     */
    @ParameterizedTest
    @ValueSource(strings = { "-1", "0" })
    void unIdNegativoOCeroRetorna404(String id) throws Exception {
        mockMvc.perform(put("/api/genres/" + id).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json("Terror")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(delete("/api/genres/" + id).header("Authorization", adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // ------------------------------------------------------------------
    // Cuerpo de la edición
    // ------------------------------------------------------------------

    @Test
    void putSinCuerpoRetorna400YNoModificaElGenero() throws Exception {
        Genre drama = saveGenre("Drama");

        mockMvc.perform(put(GENRE, drama.getId()).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        assertEquals("Drama", nameOf(drama));
    }

    /** El mensaje es el genérico de la API: no expone el error del parser JSON. */
    @Test
    void putConJsonMalFormadoRetorna400SinDetallesInternos() throws Exception {
        Genre drama = saveGenre("Drama");

        mockMvc.perform(put(GENRE, drama.getId()).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{name: Drama"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("La petición no se puede interpretar. Revisa el formato de los datos"))
                .andExpect(jsonPath("$.path").value("/api/genres/" + drama.getId()));

        assertEquals("Drama", nameOf(drama));
    }

    /** {@code name} nulo o ausente: 400 de validación con el mensaje en español. */
    @ParameterizedTest
    @ValueSource(strings = { "{\"name\":null}", "{}", "{\"nombre\":\"Drama\"}" })
    void putConNameNuloOAusenteRetorna400DeValidacion(String body) throws Exception {
        Genre drama = saveGenre("Drama");

        mockMvc.perform(put(GENRE, drama.getId()).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.name").value("El nombre del género es obligatorio"));

        assertEquals("Drama", nameOf(drama));
    }

    /** {@code name} con un tipo que no se puede convertir a texto: 400, no 500. */
    @ParameterizedTest
    @ValueSource(strings = { "{\"name\":[\"Drama\"]}", "{\"name\":{\"es\":\"Drama\"}}", "[\"Drama\"]", "\"Drama\"" })
    void putConNameDeTipoIncorrectoRetorna400(String body) throws Exception {
        Genre drama = saveGenre("Drama");

        mockMvc.perform(put(GENRE, drama.getId()).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        assertEquals("Drama", nameOf(drama));
    }

    @Test
    void putConUnTipoDeContenidoQueNoEsJsonRetorna415() throws Exception {
        Genre drama = saveGenre("Drama");

        mockMvc.perform(put(GENRE, drama.getId()).header("Authorization", adminToken)
                        .contentType(MediaType.TEXT_PLAIN).content("Terror"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        assertEquals("Drama", nameOf(drama));
    }

    // ------------------------------------------------------------------
    // Autorización con efecto real
    // ------------------------------------------------------------------

    /**
     * Los tests de seguridad usan un id ficticio; aquí se comprueba con un
     * género real que el 403 llega <em>antes</em> de cualquier efecto: ni se
     * renombra ni se borra.
     */
    @Test
    void unUserNoPuedeRenombrarNiBorrarUnGeneroReal() throws Exception {
        Genre drama = saveGenre("Drama");

        mockMvc.perform(put(GENRE, drama.getId()).header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json("Hackeado")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(delete(GENRE, drama.getId()).header("Authorization", userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        assertEquals("Drama", nameOf(drama));
    }

    // ------------------------------------------------------------------
    // Mayúsculas y normalización
    // ------------------------------------------------------------------

    /**
     * Una fila anterior a la normalización ({@code "CIENCIA FICCIÓN"}) se
     * puede renombrar a sí misma cambiando solo mayúsculas (incluida la de una
     * letra acentuada): no es un duplicado de sí misma y queda normalizada.
     */
    @Test
    void renombrarUnaFilaHeredadaCambiandoSoloMayusculasLaNormaliza() throws Exception {
        Genre legacy = saveGenre("CIENCIA FICCIÓN");

        mockMvc.perform(put(GENRE, legacy.getId()).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json("ciencia ficción")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(legacy.getId()))
                .andExpect(jsonPath("$.name").value("Ciencia ficción"));

        assertEquals("Ciencia ficción", nameOf(legacy));
        assertEquals(1, genreRepository.count());
    }

    /**
     * La restricción {@code UNIQUE} distingue mayúsculas, así que
     * {@code "Ciencia ficción"} no choca con la fila heredada
     * {@code "CIENCIA FICCIÓN"} (difieren también en la "Ó"). Debe detectarlo
     * la comprobación del servicio, en el alta y en la edición.
     */
    @Test
    void unNombreQueSoloDifiereEnMayusculasAcentuadasDeOtraFilaRetorna409() throws Exception {
        saveGenre("CIENCIA FICCIÓN");
        Genre other = saveGenre("Fantasía");

        mockMvc.perform(post(GENRES).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json("ciencia ficción")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENRE_ALREADY_EXISTS"));

        mockMvc.perform(put(GENRE, other.getId()).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json("Ciencia Ficción")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENRE_ALREADY_EXISTS"));

        assertEquals(2, genreRepository.count());
        assertEquals("Fantasía", nameOf(other));
    }

    /** Borrar un género libera su nombre: se puede volver a crear (con otro id). */
    @Test
    void borrarYVolverACrearUnGeneroConElMismoNombreFunciona() throws Exception {
        Genre western = saveGenre("Western");

        mockMvc.perform(delete(GENRE, western.getId()).header("Authorization", adminToken))
                .andExpect(status().isNoContent());

        String body = mockMvc.perform(post(GENRES).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json("WESTERN")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Western"))
                .andReturn().getResponse().getContentAsString();

        long newId = objectMapper.readTree(body).get("id").asLong();
        assertNotEquals(western.getId().longValue(), newId);
        assertEquals(1, genreRepository.count());
    }

    /**
     * Renombrar un género asignado no rompe la relación (va por id): las
     * películas lo muestran con el nombre nuevo.
     */
    @Test
    void renombrarUnGeneroAsignadoSeReflejaEnSusPeliculas() throws Exception {
        Genre scifi = saveGenre("Scifi");
        Movie dune = saveMovie("Dune", scifi);

        mockMvc.perform(put(GENRE, scifi.getId()).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json("ciencia ficción")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/movies/{id}", dune.getId()).header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.genres.length()").value(1))
                .andExpect(jsonPath("$.genres[0].id").value(scifi.getId()))
                .andExpect(jsonPath("$.genres[0].name").value("Ciencia ficción"));
    }

    // ------------------------------------------------------------------
    // Borrado de un género en uso
    // ------------------------------------------------------------------

    /**
     * El 409 {@code GENRE_IN_USE} se resuelve como dice su mensaje: quitando el
     * género de cada película ({@code PUT /api/movies/{id}} con otros
     * {@code genreIds}). Después el borrado da 204 y las películas conservan el
     * resto de sus géneros.
     */
    @Test
    void trasQuitarElGeneroDeTodasSusPeliculasSePuedeBorrar() throws Exception {
        Genre drama = saveGenre("Drama");
        Genre comedy = saveGenre("Comedia");
        Movie uno = saveMovie("Uno", drama, comedy);
        Movie dos = saveMovie("Dos", drama);

        mockMvc.perform(delete(GENRE, drama.getId()).header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENRE_IN_USE"));

        updateMovieGenres(uno, comedy);
        updateMovieGenres(dos, comedy);

        mockMvc.perform(delete(GENRE, drama.getId()).header("Authorization", adminToken))
                .andExpect(status().isNoContent());

        assertTrue(genreRepository.findById(drama.getId()).isEmpty());
        for (Movie movie : List.of(uno, dos)) {
            mockMvc.perform(get("/api/movies/{id}", movie.getId()).header("Authorization", userToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.genres.length()").value(1))
                    .andExpect(jsonPath("$.genres[0].name").value("Comedia"));
        }
    }

    /**
     * Un borrado rechazado no deja efectos a medias: el género sigue, las
     * películas conservan todos sus géneros (en la API y en la tabla
     * {@code movie_genres}) y el listado de géneros no cambia.
     */
    @Test
    void unBorradoRechazadoPorEstarEnUsoNoAlteraLasPeliculas() throws Exception {
        Genre drama = saveGenre("Drama");
        Genre comedy = saveGenre("Comedia");
        Movie uno = saveMovie("Uno", drama, comedy);
        saveMovie("Dos", drama);

        mockMvc.perform(delete(GENRE, drama.getId()).header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENRE_IN_USE"))
                .andExpect(jsonPath("$.message").value(
                        "No se puede eliminar el género \"Drama\": lo usan 2 películas. "
                                + "Quítalo de esas películas antes de borrarlo."));

        mockMvc.perform(get("/api/movies/{id}", uno.getId()).header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.genres.length()").value(2));
        mockMvc.perform(get(GENRES).header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM movie_genres", Integer.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM movie_genres WHERE genre_id = ?", Integer.class, drama.getId()));
    }

    /**
     * Lo que el servicio da por hecho cuando traduce la carrera del borrado:
     * en el esquema real (migraciones de Flyway) la clave foránea de
     * {@code movie_genres} hacia {@code genres} no tiene cascada, de modo que
     * un {@code DELETE} que se salte el recuento lo rechaza la base de datos
     * con {@code SQLSTATE 23503} como {@link DataIntegrityViolationException}.
     * Si alguien añadiera {@code ON DELETE CASCADE}, este test fallaría (y
     * las películas perderían el género en silencio).
     */
    @Test
    void elBorradoDirectoDeUnGeneroAsignadoLoRechazaLaClaveForanea() {
        Genre drama = saveGenre("Drama");
        saveMovie("Uno", drama);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> tx.executeWithoutResult(status -> {
                    genreRepository.deleteById(drama.getId());
                    genreRepository.flush();
                }));

        assertEquals("23503", sqlState(error));
        assertTrue(genreRepository.existsById(drama.getId()));
    }

    // ------------------------------------------------------------------
    // Nombres que eluden la validación de longitud
    // ------------------------------------------------------------------

    /**
     * Nombre de 50 caracteres que <em>crece</em> al normalizar: con
     * {@code toLowerCase(Locale.ROOT)} la «İ» (I con punto, U+0130) pasa a ser
     * «i» + punto combinante (2 caracteres), así que {@code "A" + 49 × "İ"}
     * acaba con 99 caracteres y no cabe en {@code VARCHAR(50)}.
     *
     * <p>
     * Comportamiento mínimo exigible, se corrija o no la incidencia: es un
     * error del cliente (4xx, nunca 500), no se guarda nada y la respuesta no
     * filtra el error de la base de datos. El código exacto que debería
     * devolver lo fija {@link #unNombreQueCreceAlNormalizarDevuelve400DeValidacion}.
     * </p>
     */
    @Test
    void unNombreQueCreceAlNormalizarNoProvocaUn500NiSeGuarda() throws Exception {
        String growing = "A" + "İ".repeat(49);
        assertEquals(50, growing.length());
        Genre drama = saveGenre("Drama");

        expectSafeClientError(mockMvc.perform(post(GENRES).header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(json(growing))));
        expectSafeClientError(mockMvc.perform(put(GENRE, drama.getId()).header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(json(growing))));

        assertEquals(1, genreRepository.count());
        assertEquals("Drama", nameOf(drama));
    }

    /**
     * Contrato esperado para el caso anterior: un nombre demasiado largo es un
     * error de validación (400 {@code VALIDATION_ERROR} sobre {@code name}),
     * no un "conflicto con datos existentes" (409
     * {@code DATA_INTEGRITY_VIOLATION}), que es lo que se respondía cuando la
     * longitud solo se validaba antes de normalizar y quien lo detectaba era
     * la columna {@code VARCHAR(50)}.
     */
    @Test
    void unNombreQueCreceAlNormalizarDevuelve400DeValidacion() throws Exception {
        String growing = "A" + "İ".repeat(49);
        Genre drama = saveGenre("Drama");

        mockMvc.perform(post(GENRES).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json(growing)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.name").exists());
        mockMvc.perform(put(GENRE, drama.getId()).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json(growing)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.name").exists());
    }

    /**
     * {@code @Size(min = 2)} y {@code @NotBlank} se evalúan sobre el texto
     * recibido, pero se guarda el texto recortado: {@code " a"} mide 2 y pasa,
     * aunque el género resultante sea {@code "A"} (1 carácter). Con dos
     * espacios duros (U+00A0, que {@code trim()} no quita) se crearía un
     * género de nombre invisible. El contrato publicado es "entre 2 y 50
     * caracteres", así que responde 400.
     */
    @ParameterizedTest
    @ValueSource(strings = { " a", "a ", "  x  ", "  " })
    void unNombreConMenosDeDosCaracteresVisiblesSeRechaza(String name) throws Exception {
        mockMvc.perform(post(GENRES).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json(name)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.name").exists());

        assertEquals(0, genreRepository.count());
    }

    // ------------------------------------------------------------------
    // Documentación
    // ------------------------------------------------------------------

    /**
     * El contrato que consume el frontend sale de OpenAPI: los endpoints nuevos
     * deben aparecer con sus códigos reales (el 409 de cada uno incluido).
     */
    @Test
    void laDocumentacionOpenApiPublicaLaEdicionYElBorradoDeGeneros() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/genres/{id}'].put.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/genres/{id}'].put.responses['404']").exists())
                .andExpect(jsonPath("$.paths['/api/genres/{id}'].put.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/genres/{id}'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/genres/{id}'].delete.responses['409']").exists())
                .andExpect(jsonPath("$.paths['/api/genres'].post.responses['409']").exists());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * Error de cliente "seguro": 4xx con el formato de la API, sin código de
     * error interno y sin texto de la base de datos ni de Hibernate.
     */
    private static void expectSafeClientError(ResultActions result) throws Exception {
        result.andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.code").exists())
                .andExpect(jsonPath("$.code").value(not("INTERNAL_ERROR")))
                .andExpect(jsonPath("$.message").value(not(anyOf(
                        containsStringIgnoringCase("sql"),
                        containsStringIgnoringCase("varchar"),
                        containsStringIgnoringCase("could not"),
                        containsStringIgnoringCase("value too long"),
                        containsStringIgnoringCase("genres")))));
    }

    /** Sustituye los géneros de una película con el {@code PUT} real de la API (como ADMIN). */
    private void updateMovieGenres(Movie movie, Genre... genres) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", movie.getTitle());
        body.put("description", movie.getDescription());
        body.put("duration", movie.getDuration());
        body.put("releaseYear", movie.getReleaseYear());
        body.put("imageUrl", movie.getImageUrl());
        body.put("videoUrl", movie.getVideoUrl());
        body.put("genreIds", java.util.Arrays.stream(genres).map(Genre::getId).toList());

        mockMvc.perform(put("/api/movies/{id}", movie.getId()).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    private String json(String name) throws Exception {
        return objectMapper.writeValueAsString(Map.of("name", name));
    }

    private String nameOf(Genre genre) {
        return genreRepository.findById(genre.getId()).orElseThrow().getName();
    }

    private User saveUser(String name, Role role) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(role);
        user.setCreatedAt(Instant.now());
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
        movie.setDuration(110);
        movie.setReleaseYear(2001);
        movie.setImageUrl("https://cdn.example.com/" + title.toLowerCase() + ".jpg");
        movie.setVideoUrl("https://cdn.example.com/" + title.toLowerCase() + ".mp4");
        movie.setCreatedAt(Instant.now());
        movie.setGenres(new HashSet<>(Set.of(genres)));
        return movieRepository.save(movie);
    }

    private void cleanDatabase() {
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }

    /** Primer {@code SQLSTATE} de la cadena de causas (el que envía el motor). */
    private static String sqlState(Throwable error) {
        for (Throwable t = error; t != null && t.getCause() != t; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }
}
