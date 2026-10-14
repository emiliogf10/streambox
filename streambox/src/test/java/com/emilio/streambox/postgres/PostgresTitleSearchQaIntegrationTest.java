package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.service.MovieService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManagerFactory;

/**
 * Revisión de QA de la búsqueda por título sin acentos y tolerante a erratas
 * (ADR 0013) contra <b>PostgreSQL real</b>: lo que los tests del autor no
 * cubrían.
 *
 * <ul>
 * <li><b>Entradas hostiles</b> en los dos intentos y en los tres endpoints
 * ({@code /api/movies/search}, {@code /api/series/search} y
 * {@code /api/admin/series}): comillas, barras, marcadores de parámetro
 * ({@code ?}, {@code $1}, {@code :x}), escapes JDBC ({@code {fn ...}}),
 * comentarios SQL, comodines (también los de ancho completo), Unicode raro y
 * textos tan largos como permite la validación ({@value #MAX_TITLE_LENGTH}
 * caracteres; uno más es un 400). Cuando se escribió esta revisión el texto
 * llegaba a la consulta como literal SQL ({@code criteriaBuilder.literal}); hoy
 * va como parámetro, pero se sigue comprobando aquí, con el driver y el
 * analizador reales de PostgreSQL, donde una cadena mal escapada daría un 500
 * o devolvería todo el catálogo. H2 no sirve: su driver no interpreta los
 * escapes JDBC igual ni rechaza los mismos caracteres.</li>
 * <li><b>Número de consultas</b> de cada camino de
 * {@code TypoTolerantTitleSearch}: que el segundo intento no añada recuentos
 * ({@code COUNT}) innecesarios ni dispare un N+1.</li>
 * </ul>
 */
class PostgresTitleSearchQaIntegrationTest extends PostgresIntegrationTestSupport {

    private static final String MOVIE_SEARCH = "/api/movies/search";
    private static final String SERIES_SEARCH = "/api/series/search";
    private static final String ADMIN_SERIES = "/api/admin/series";

    /**
     * Longitud máxima del parámetro {@code title} (la de un título). Se repite
     * aquí en vez de leer la constante de producción para que el test fije el
     * contrato: si alguien cambiara el límite, este test lo haría notar.
     */
    private static final int MAX_TITLE_LENGTH = 150;

    /** Películas del catálogo de prueba: ninguna contiene ni se parece a las entradas hostiles. */
    private static final int CATALOG_MOVIES = 3;

    @Autowired private MockMvc mockMvc;
    @Autowired private MovieService movieService;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String userToken;
    private String adminToken;
    private Genre scifi;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        userToken = tokenFor(saveUser("pgqasearchuser", Role.USER));
        adminToken = tokenFor(saveUser("pgqasearchadmin", Role.ADMIN));
        scifi = saveGenre("Ciencia ficción");
        saveMovie("Alien", 1979, scifi);
        saveMovie("Matrix", 1999, scifi);
        saveMovie("Interstellar", 2014, scifi);
        saveEpisode(saveSeries("Dark", 2017), 1, 1);
        saveSeries("Oculta sin episodios", 2020);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Entradas hostiles
    // ------------------------------------------------------------------

    /**
     * Textos que podrían romper una consulta con el texto como literal SQL o
     * convertirse en un patrón que lo encuentre todo. Los hay de menos y de más
     * de 5 letras o dígitos, para pasar por el primer intento solo y por los dos.
     */
    static Stream<String> hostileTexts() {
        return Stream.of(
                "'", "''", "x' OR '1'='1", "'; DROP TABLE movies; --", "Matrix'--",
                "\\", "\\'", "a\\'b OR 1=1", "\\\\\\\\\\",
                "?", "??? ? ?", "$1", "$$", "$x$ alien $x$", ":title", "?title=alien",
                "{fn now()}", "{d '2020-01-01'}", "{call drop()}", "{escape '\\'}",
                "/* alien */", "alien */ OR 1=1 /*", "-- alien",
                "%", "_", "%_%", "\\%", "\\_", "％", "＿", "＼", "％％％％％", "＿＿＿＿＿",
                "\u202Ealien", "\u200B\u200B\u200B", "\u0301\u0301\u0301\u0301\u0301\u0301",
                "\uFEFF", "🙂🙂🙂🙂🙂🙂", "千と千尋の神隠し", "Ǆǅǆ İı ß ﬁ",
                // Los más largos que se aceptan (antes eran de 2000 y 1800 caracteres:
                // desde que hay límite, esos son un 400; ver unTextoDemasiadoLargoEsUnErrorDeValidacion)
                "x".repeat(MAX_TITLE_LENGTH),
                "alienx".repeat(MAX_TITLE_LENGTH / 6));
    }

    /**
     * Ninguna entrada hostil da un error 500 ni devuelve todo el catálogo en la
     * búsqueda de películas; y la base sigue intacta (ni la comilla ni el
     * {@code DROP} se ejecutan como SQL).
     */
    @ParameterizedTest(name = "[{index}] películas: {0}")
    @MethodSource("hostileTexts")
    void unaEntradaHostilNoRompeLaBusquedaDePeliculas(String text) throws Exception {
        JsonNode page = okJson(MOVIE_SEARCH, userToken, text);

        assertTrue(page.get("totalElements").asLong() < CATALOG_MOVIES,
                "no debe devolver todo el catálogo: " + page);
        assertEquals(CATALOG_MOVIES, movieRepository.count(), "la tabla movies sigue intacta");
    }

    /**
     * Lo mismo en la búsqueda pública de series, que además nunca muestra la
     * serie sin episodios, ni en el primer intento ni en el segundo.
     */
    @ParameterizedTest(name = "[{index}] series: {0}")
    @MethodSource("hostileTexts")
    void unaEntradaHostilNoRompeLaBusquedaPublicaDeSeries(String text) throws Exception {
        JsonNode page = okJson(SERIES_SEARCH, userToken, text);

        assertFalse(titles(page).contains("Oculta sin episodios"), "una serie vacía nunca es visible: " + page);
        assertTrue(page.get("totalElements").asLong() < 1, "no debe devolver todo el catálogo: " + page);
    }

    /** Y en el panel de series (que sí ve las vacías): sin 500 y sin devolverlas todas. */
    @ParameterizedTest(name = "[{index}] panel: {0}")
    @MethodSource("hostileTexts")
    void unaEntradaHostilNoRompeElPanelDeSeries(String text) throws Exception {
        JsonNode page = okJson(ADMIN_SERIES, adminToken, text);

        assertTrue(page.get("totalElements").asLong() < 2, "no debe devolver todas las series: " + page);
    }

    /**
     * El carácter NUL ({@code U+0000}) es un error del cliente: PostgreSQL no
     * admite NUL en un {@code text}, así que la consulta no puede ejecutarse
     * tal cual. Lo que nunca debe pasar es que acabe en un 500 genérico: ningún
     * error de cliente debe ser un fallo del servidor (y llenaría el log de
     * trazas que cualquiera puede provocar). Vale un 400 de validación o un 200
     * sin resultados.
     */
    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("endpointsWithNul")
    void unCaracterNulNoEsUnErrorDelServidor(String endpoint, String text) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get(endpoint)
                .header("Authorization", ADMIN_SERIES.equals(endpoint) ? adminToken : userToken)
                .param("title", text)).andReturn().getResponse();

        assertTrue(response.getStatus() == 200 || response.getStatus() == 400,
                endpoint + " con NUL respondió " + response.getStatus() + ": " + response.getContentAsString());
    }

    /**
     * Un texto más largo que el límite (y los que la revisión probaba sin
     * límite, de 2000 y 1800 caracteres) es un 400 de validación con el motivo
     * en {@code validationErrors.title}, en los tres endpoints: sin límite, un
     * texto así pasaba a la búsqueda tolerante a erratas y obligaba a calcular
     * {@code word_similarity} dos veces por fila recorriendo la tabla.
     */
    @ParameterizedTest(name = "[{index}] {0} con {1} caracteres")
    @MethodSource("endpointsWithTooLongText")
    void unTextoDemasiadoLargoEsUnErrorDeValidacion(String endpoint, int length) throws Exception {
        String text = "alienx".repeat(length / 6 + 1).substring(0, length);
        MockHttpServletResponse response = mockMvc.perform(get(endpoint)
                .header("Authorization", ADMIN_SERIES.equals(endpoint) ? adminToken : userToken)
                .param("title", text)).andReturn().getResponse();

        assertEquals(400, response.getStatus(), response.getContentAsString());
        JsonNode body = objectMapper.readTree(response.getContentAsString());
        assertEquals("VALIDATION_ERROR", body.get("code").asText());
        assertEquals("La búsqueda no puede superar los 150 caracteres",
                body.path("validationErrors").path("title").asText());
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> endpointsWithTooLongText() {
        List<org.junit.jupiter.params.provider.Arguments> arguments = new ArrayList<>();
        for (String endpoint : List.of(MOVIE_SEARCH, SERIES_SEARCH, ADMIN_SERIES)) {
            for (int length : List.of(MAX_TITLE_LENGTH + 1, 1800, 2000)) {
                arguments.add(org.junit.jupiter.params.provider.Arguments.of(endpoint, length));
            }
        }
        return arguments.stream();
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> endpointsWithNul() {
        List<org.junit.jupiter.params.provider.Arguments> arguments = new ArrayList<>();
        for (String endpoint : List.of(MOVIE_SEARCH, SERIES_SEARCH, ADMIN_SERIES)) {
            // Corto (solo primer intento) y largo (también el segundo)
            arguments.add(org.junit.jupiter.params.provider.Arguments.of(endpoint, "a\u0000b"));
            arguments.add(org.junit.jupiter.params.provider.Arguments.of(endpoint, "interstelar\u0000"));
        }
        return arguments.stream();
    }

    // ------------------------------------------------------------------
    // Número de consultas de cada camino
    // ------------------------------------------------------------------

    /**
     * Coincidencia exacta en la página 0 con menos resultados que el tamaño:
     * una consulta de películas y otra (por lotes) de sus géneros. Sin
     * {@code COUNT} (Spring Data deduce el total) y sin segundo intento.
     */
    @Test
    void unaCoincidenciaExactaHaceUnaSolaConsultaDePeliculas() {
        long statements = statementsFor(() -> movieService.searchMovies("matrix", null, null, page(0)));

        assertEquals(2, statements, "películas + géneros por lotes");
    }

    /**
     * Errata con parecidos: el primer intento (vacío en la página 0, sin
     * {@code COUNT}), el segundo (con menos resultados que el tamaño, sin
     * {@code COUNT}) y los géneros por lotes. Tres sentencias, no cinco.
     */
    @Test
    void elSegundoIntentoNoAnadeRecuentosNiNMasUno() {
        saveMovie("Interestelar", 2012, scifi);
        saveMovie("Internacional", 2009, scifi);

        long statements = statementsFor(() -> {
            var result = movieService.searchMovies("interstelar", null, null, page(0));
            assertTrue(result.approximate());
            assertEquals(3, result.content().size());
        });

        assertEquals(3, statements, "exacta + parecidos + géneros por lotes");
    }

    /** Errata sin parecidos: dos consultas y ninguna de géneros (no hay nada que mapear). */
    @Test
    void sinParecidosSonDosConsultas() {
        long statements = statementsFor(() -> {
            var result = movieService.searchMovies("zzzzzzzz", null, null, page(0));
            assertFalse(result.approximate());
        });

        assertEquals(2, statements);
    }

    /** Un texto corto sin coincidencias no intenta la tolerancia: una sola consulta. */
    @Test
    void unaBusquedaCortaSinCoincidenciasEsUnaSolaConsulta() {
        long statements = statementsFor(() -> movieService.searchMovies("zzzz", null, null, page(0)));

        assertEquals(1, statements);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Página 0 de 10 ordenada por título y desempate por id, como la pide el controlador. */
    private static PageRequest page(int number) {
        return PageRequest.of(number, 10, Sort.by("title").ascending().and(Sort.by("id")));
    }

    private JsonNode okJson(String endpoint, String token, String title) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get(endpoint).header("Authorization", token)
                .param("title", title).param("size", "100")).andReturn().getResponse();
        assertEquals(200, response.getStatus(), endpoint + " respondió: " + response.getContentAsString());
        return objectMapper.readTree(response.getContentAsString());
    }

    private static List<String> titles(JsonNode page) {
        List<String> titles = new ArrayList<>();
        page.get("content").forEach(item -> titles.add(item.get("title").asText()));
        return titles;
    }

    private long statementsFor(Runnable operation) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        operation.run();
        return statistics.getPrepareStatementCount();
    }
}
