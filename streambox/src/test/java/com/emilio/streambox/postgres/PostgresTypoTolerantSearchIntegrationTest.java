package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Role;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Búsqueda tolerante a erratas <b>conectada a los endpoints</b>
 * ({@code /api/movies/search}, {@code /api/series/search} y
 * {@code /api/admin/series}) sobre <b>PostgreSQL real</b>: el segundo intento
 * de {@code service.TypoTolerantTitleSearch} y el campo {@code approximate}.
 *
 * <p>
 * Solo se puede probar aquí: en H2 la búsqueda tolerante nunca encuentra nada.
 * Las {@code Specification} por separado (umbral, orden por parecido) están en
 * {@link PostgresTitleSearchIntegrationTest}; aquí se prueba lo que ve el
 * cliente.
 * </p>
 */
class PostgresTypoTolerantSearchIntegrationTest extends PostgresIntegrationTestSupport {

    private static final String MOVIE_SEARCH = "/api/movies/search";
    private static final String SERIES_SEARCH = "/api/series/search";
    private static final String ADMIN_SERIES = "/api/admin/series";

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String userToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        userToken = tokenFor(saveUser("pgtypouser", Role.USER));
        adminToken = tokenFor(saveUser("pgtypoadmin", Role.ADMIN));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Películas
    // ------------------------------------------------------------------

    /** La errata del enunciado: "interstelar" encuentra "Interstellar" y la respuesta lo dice. */
    @Test
    void unaErrataEncuentraElTituloYLaRespuestaEsAproximada() throws Exception {
        saveMovie("Interstellar", 2014);
        saveMovie("Alien", 1979);

        JsonNode page = call(MOVIE_SEARCH, userToken, "title", "interstelar");

        assertEquals(List.of("Interstellar"), titles(page));
        assertTrue(page.get("approximate").asBoolean());
        assertEquals(1, page.get("totalElements").asLong());
    }

    /**
     * Un título bien escrito no es aproximado y no trae ruido de parecidos:
     * "matrix" se parece a "Matilda" (comparten "mat"), pero como hay una
     * coincidencia exacta no se hace el segundo intento.
     */
    @Test
    void unTituloBienEscritoNoEsAproximadoNiTraeParecidos() throws Exception {
        saveMovie("The Matrix", 1999);
        saveMovie("Matilda", 1996);
        saveMovie("Matador", 1986);

        JsonNode page = call(MOVIE_SEARCH, userToken, "title", "matrix");

        assertEquals(List.of("The Matrix"), titles(page));
        assertFalse(page.get("approximate").asBoolean());
    }

    /**
     * Búsquedas cortas sin coincidencias: vacío y no aproximado. "amro" llegaría
     * justo al umbral con "Amor" si no se exigieran 5 letras o dígitos.
     */
    @ParameterizedTest
    @ValueSource(strings = { "amro", "mtx", "a b c d" })
    void unaBusquedaCortaSinCoincidenciasDevuelveVacioNoAproximado(String text) throws Exception {
        saveMovie("Amor", 2012);
        saveMovie("Amadeus", 1984);
        saveMovie("Matrix", 1999);

        JsonNode page = call(MOVIE_SEARCH, userToken, "title", text);

        assertEquals(List.of(), titles(page));
        assertFalse(page.get("approximate").asBoolean());
        assertEquals(0, page.get("totalElements").asLong());
    }

    /** Sin coincidencias ni parecidos: "sin resultados", no una respuesta aproximada vacía. */
    @Test
    void sinCoincidenciasNiParecidosNoEsAproximado() throws Exception {
        saveMovie("Interstellar", 2014);

        JsonNode page = call(MOVIE_SEARCH, userToken, "title", "zzzzzzzz");

        assertEquals(List.of(), titles(page));
        assertFalse(page.get("approximate").asBoolean());
    }

    /**
     * Los resultados aproximados salen del más parecido al menos (0,79, 0,67 y
     * 0,42, medidos en {@code TitleSearch}) aunque se pida otro orden: por
     * título ascendente saldrían Interestelar, Internacional, Interstellar.
     * "Inception" no se parece lo bastante.
     */
    @Test
    void losResultadosAproximadosSeOrdenanPorParecidoIgnorandoElSortPedido() throws Exception {
        saveSimilarCatalog();

        for (String[] order : List.of(new String[] { "title", "asc" }, new String[] { "releaseYear", "desc" },
                new String[] { "id", "asc" })) {
            JsonNode page = call(MOVIE_SEARCH, userToken, "title", "interstelar", "sort", order[0],
                    "direction", order[1]);

            assertEquals(List.of("Interstellar", "Interestelar", "Internacional"), titles(page), order[0]);
            assertTrue(page.get("approximate").asBoolean());
        }
    }

    /** El segundo intento respeta los filtros de género y año, solos y combinados. */
    @Test
    void elSegundoIntentoRespetaElGeneroYElAnio() throws Exception {
        Genre scifi = saveGenre("Ciencia ficción");
        Genre drama = saveGenre("Drama");
        saveMovie("Interstellar", 2014, scifi);
        saveMovie("Interestelar", 2014, drama);
        saveMovie("Internacional", 2009, scifi);

        JsonNode byGenre = call(MOVIE_SEARCH, userToken, "title", "interstelar", "genreId", scifi.getId().toString());
        JsonNode byYear = call(MOVIE_SEARCH, userToken, "title", "interstelar", "releaseYear", "2014");
        JsonNode both = call(MOVIE_SEARCH, userToken, "title", "interstelar", "genreId", drama.getId().toString(),
                "releaseYear", "2014");
        JsonNode none = call(MOVIE_SEARCH, userToken, "title", "interstelar", "genreId", drama.getId().toString(),
                "releaseYear", "2009");

        assertEquals(List.of("Interstellar", "Internacional"), titles(byGenre));
        assertTrue(byGenre.get("approximate").asBoolean());
        assertEquals(List.of("Interstellar", "Interestelar"), titles(byYear));
        assertTrue(byYear.get("approximate").asBoolean());
        assertEquals(List.of("Interestelar"), titles(both));
        assertTrue(both.get("approximate").asBoolean());
        assertEquals(List.of(), titles(none));
        assertFalse(none.get("approximate").asBoolean());
    }

    /**
     * Paginación del segundo intento: páginas de 1 con el total, el número de
     * páginas y {@code hasNext}/{@code hasPrevious} de los parecidos, en orden
     * estable y sin repetir ninguno. Una página fuera de rango sigue siendo
     * aproximada (el total no es 0).
     */
    @Test
    void elSegundoIntentoSePagina() throws Exception {
        saveSimilarCatalog();

        List<String> seen = new ArrayList<>();
        for (int number = 0; number < 3; number++) {
            JsonNode page = call(MOVIE_SEARCH, userToken, "title", "interstelar", "size", "1",
                    "page", String.valueOf(number));

            assertTrue(page.get("approximate").asBoolean());
            assertEquals(number, page.get("page").asInt());
            assertEquals(1, page.get("size").asInt());
            assertEquals(3, page.get("totalElements").asLong());
            assertEquals(3, page.get("totalPages").asInt());
            assertEquals(number < 2, page.get("hasNext").asBoolean());
            assertEquals(number > 0, page.get("hasPrevious").asBoolean());
            seen.addAll(titles(page));
        }
        assertEquals(List.of("Interstellar", "Interestelar", "Internacional"), seen);

        JsonNode outOfRange = call(MOVIE_SEARCH, userToken, "title", "interstelar", "size", "1", "page", "7");
        assertEquals(List.of(), titles(outOfRange));
        assertEquals(3, outOfRange.get("totalElements").asLong());
        assertTrue(outOfRange.get("approximate").asBoolean());
    }

    /**
     * Una página fuera de rango de una búsqueda BIEN escrita (con resultados)
     * no pasa al segundo intento: se decide por el total, no por el contenido.
     * Si se decidiera por el contenido, "interstellar" en la página 5 traería
     * los parecidos ("Interestelar"...) marcados como aproximados.
     */
    @Test
    void unaPaginaFueraDeRangoDeUnaBusquedaConResultadosNoEsAproximada() throws Exception {
        saveSimilarCatalog();

        JsonNode page = call(MOVIE_SEARCH, userToken, "title", "interstellar", "size", "1", "page", "5");

        assertEquals(List.of(), titles(page));
        assertEquals(1, page.get("totalElements").asLong());
        assertFalse(page.get("approximate").asBoolean());
    }

    // ------------------------------------------------------------------
    // Series
    // ------------------------------------------------------------------

    /** Búsqueda pública de series: el mismo segundo intento, solo con series visibles. */
    @Test
    void laBusquedaPublicaDeSeriesToleraErratas() throws Exception {
        saveEpisode(saveSeries("Stranger Things", 2016), 1, 1);
        saveEpisode(saveSeries("Dark", 2017), 1, 1);

        JsonNode page = call(SERIES_SEARCH, userToken, "title", "strnger things");

        assertEquals(List.of("Stranger Things"), titles(page));
        assertTrue(page.get("approximate").asBoolean());
    }

    /**
     * Una serie sin episodios no aparece nunca en la búsqueda pública, tampoco
     * entre los parecidos, ni decide si hay segundo intento: con solo la vacía
     * la respuesta es exactamente la de una serie inexistente.
     */
    @Test
    void lasSeriesSinEpisodiosNoAparecenNuncaEnLaBusquedaPublica() throws Exception {
        saveSeries("Interstellar vacía", 2015);

        JsonNode onlyEmpty = call(SERIES_SEARCH, userToken, "title", "interstelar");
        JsonNode exactEmpty = call(SERIES_SEARCH, userToken, "title", "interstellar");
        JsonNode nonexistent = call(SERIES_SEARCH, userToken, "title", "zzzzzzzz");

        assertEquals(nonexistent, onlyEmpty);
        assertEquals(nonexistent, exactEmpty);
        assertFalse(onlyEmpty.get("approximate").asBoolean());

        saveEpisode(saveSeries("Interstellar: la serie", 2014), 1, 1);

        JsonNode withVisible = call(SERIES_SEARCH, userToken, "title", "interstelar");
        assertEquals(List.of("Interstellar: la serie"), titles(withVisible));
        assertTrue(withVisible.get("approximate").asBoolean());
    }

    /** Filtros de género y año en el segundo intento de series. */
    @Test
    void elSegundoIntentoDeSeriesRespetaElGeneroYElAnio() throws Exception {
        Genre scifi = saveGenre("Ciencia ficción");
        Genre drama = saveGenre("Drama");
        saveEpisode(saveSeries("Interstellar", 2014, scifi), 1, 1);
        saveEpisode(saveSeries("Interestelar", 2014, drama), 1, 1);

        JsonNode byGenre = call(SERIES_SEARCH, userToken, "title", "interstelar", "genreId", drama.getId().toString());
        JsonNode byYear = call(SERIES_SEARCH, userToken, "title", "interstelar", "releaseYear", "2015");

        assertEquals(List.of("Interestelar"), titles(byGenre));
        assertTrue(byGenre.get("approximate").asBoolean());
        assertEquals(List.of(), titles(byYear));
        assertFalse(byYear.get("approximate").asBoolean());
    }

    /** El panel también tolera erratas y ve las series vacías, ordenadas por parecido. */
    @Test
    void elPanelDeSeriesToleraErratasYVeLasVacias() throws Exception {
        saveSeries("Interstellar vacía", 2015);
        saveEpisode(saveSeries("Interestelar", 2014), 1, 1);
        saveSeries("Alien", 1979);

        JsonNode page = call(ADMIN_SERIES, adminToken, "title", "interstelar", "sort", "title");

        assertEquals(List.of("Interstellar vacía", "Interestelar"), titles(page));
        assertTrue(page.get("approximate").asBoolean());

        JsonNode exact = call(ADMIN_SERIES, adminToken, "title", "alien");
        assertEquals(List.of("Alien"), titles(exact));
        assertFalse(exact.get("approximate").asBoolean());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Tres títulos parecidos a "interstelar" en distinto grado y uno que no lo es. */
    private void saveSimilarCatalog() {
        saveMovie("Internacional", 2009);
        saveMovie("Inception", 2010);
        saveMovie("Interestelar", 2012);
        saveMovie("Interstellar", 2014);
    }

    /** GET autenticado con pares nombre/valor ({@code size=100} salvo que se indique otro). */
    private JsonNode call(String endpoint, String token, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(endpoint).header("Authorization", token);
        boolean sized = false;
        for (int i = 0; i < params.length; i += 2) {
            request.param(params[i], params[i + 1]);
            sized |= "size".equals(params[i]);
        }
        if (!sized) {
            request.param("size", "100");
        }
        return objectMapper.readTree(mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static List<String> titles(JsonNode page) {
        List<String> titles = new ArrayList<>();
        page.get("content").forEach(item -> titles.add(item.get("title").asText()));
        return titles;
    }
}
