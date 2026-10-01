package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.emilio.streambox.dto.MoviePageResponse;
import com.emilio.streambox.dto.MovieResponse;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.service.MovieService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import jakarta.persistence.EntityManagerFactory;

/**
 * Subconjunto del catálogo ({@code GET /api/movies} y {@code /api/movies/search})
 * que depende de diferencias entre H2 y <b>PostgreSQL real</b>: el orden por
 * texto (<i>collation</i>), {@code lower(...) LIKE ... ESCAPE}, la subconsulta
 * {@code EXISTS}, la paginación fuera de rango, el desempate de órdenes
 * descendentes y el número de sentencias SQL (sin N+1).
 *
 * <p>
 * No se copia la suite completa de {@code CatalogIntegrationTest} ni de
 * {@code CatalogEdgeCasesIntegrationTest}: estas ya cubren la lógica de
 * validación (que no depende del motor). Aquí solo está lo que el motor puede
 * cambiar.
 * </p>
 */
class PostgresCatalogIntegrationTest extends PostgresIntegrationTestSupport {

    private static final String LIST = "/api/movies";
    private static final String SEARCH = "/api/movies/search";

    @Autowired private MockMvc mockMvc;
    @Autowired private MovieService movieService;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private String userToken;
    private Genre action;
    private Genre drama;
    private Genre scifi;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        userToken = tokenFor(saveUser("pgcatalog", Role.USER));
        action = saveGenre("Action");
        drama = saveGenre("Drama");
        scifi = saveGenre("Scifi");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Orden por título (collation)
    // ------------------------------------------------------------------

    /**
     * Orden real de {@code ORDER BY title} en PostgreSQL con la imagen
     * {@code postgres:16} (Debian, locale {@code en_US.utf8} de glibc). Es
     * distinto del de H2, que compara por código de carácter (todas las
     * mayúsculas antes que las minúsculas y los acentos al final):
     * <ul>
     *   <li>H2: {@code 1984, Apple, Cereza, Zebra, banana, cereza, nube, zeta, Árbol, Ñu, árbol, ñandú}</li>
     *   <li>PostgreSQL: se ignoran mayúsculas y acentos como primer criterio
     *       ({@code Apple} antes que {@code árbol}), a igual letra la minúscula va
     *       antes que la mayúscula, y {@code ñ} se ordena como una {@code n}
     *       acentuada (no como letra propia después de la {@code n}, que es
     *       lo que haría un locale español): {@code ñandú} antes que {@code nube}.</li>
     * </ul>
     * Para el usuario final el orden de PostgreSQL es el más natural, pero es un
     * dato del servidor: cambiar de imagen/collation lo cambia. Por eso se fija
     * aquí con una imagen concreta.
     */
    @Test
    void ordenarPorTituloUsaLaCollationDePostgreSql() throws Exception {
        List<String> titles = List.of("zeta", "Zebra", "Árbol", "árbol", "Apple", "banana", "ñandú", "Ñu",
                "nube", "Cereza", "cereza", "1984");
        titles.forEach(title -> saveMovie(title, 2000, drama));

        List<String> ascending = titles(call(LIST, "sort=title", "size=100"));

        assertEquals(List.of("1984", "Apple", "árbol", "Árbol", "banana", "cereza", "Cereza", "ñandú", "Ñu",
                "nube", "Zebra", "zeta"), ascending);
        assertEquals(ascending.reversed(), titles(call(LIST, "sort=title", "direction=desc", "size=100")),
                "desc es exactamente el inverso de asc (los empates no existen aquí)");
    }

    /**
     * La paginación por título sigue siendo estable con la collation de
     * PostgreSQL: títulos que se diferencian solo por mayúsculas o acentos
     * ({@code árbol/Árbol}, {@code cereza/Cereza}) y títulos IDÉNTICOS se
     * reparten entre páginas sin repetirse ni perderse, en ambos sentidos y en
     * ambos endpoints. El desempate por {@code id} garantiza el orden total.
     */
    @Test
    void laPaginacionPorTituloEsEstableConMayusculasAcentosYTitulosRepetidos() throws Exception {
        for (String title : List.of("árbol", "Árbol", "arbol", "ARBOL", "Cereza", "cereza", "ñu", "Ñu", "nu")) {
            saveMovie(title, 2000, drama);
        }
        saveMovie("Igual", 2001, drama);
        saveMovie("Igual", 2002, drama);
        saveMovie("Igual", 2003, drama);

        for (String endpoint : new String[] { LIST, SEARCH }) {
            for (String direction : new String[] { "asc", "desc" }) {
                List<Long> all = ids(parse(call(endpoint, "sort=title", "direction=" + direction, "size=100")));
                List<Long> paged = new ArrayList<>();
                for (int page = 0; page < 6; page++) {
                    paged.addAll(ids(parse(call(endpoint, "sort=title", "direction=" + direction,
                            "size=2", "page=" + page))));
                }
                assertEquals(12, new HashSet<>(paged).size(), endpoint + " " + direction + ": repetidas o perdidas");
                assertEquals(all, paged, endpoint + " " + direction + ": el orden cambia entre páginas");
            }
        }
        // Los tres "Igual" salen por id ascendente o descendente según la dirección
        List<Long> igualAsc = parse(call(SEARCH, "title=igual", "sort=title")).content().stream()
                .map(MovieResponse::id).toList();
        assertEquals(igualAsc.stream().sorted().toList(), igualAsc);
        List<Long> igualDesc = parse(call(SEARCH, "title=igual", "sort=title", "direction=desc")).content().stream()
                .map(MovieResponse::id).toList();
        assertEquals(igualDesc.stream().sorted(Comparator.reverseOrder()).toList(), igualDesc);
    }

    // ------------------------------------------------------------------
    // lower(title) LIKE ... ESCAPE
    // ------------------------------------------------------------------

    /**
     * {@code lower()} de PostgreSQL convierte también las mayúsculas no ASCII
     * (depende del locale de la base de datos, en {@code en_US.utf8} sí): la
     * búsqueda ignora mayúsculas con {@code ñ} y vocales acentuadas, y sigue
     * distinguiendo acentos ("manana" no encuentra "Mañana"), igual que en H2.
     * Con una imagen {@code -alpine} (musl, locale C) {@code lower('Ñ')} no
     * cambiaría y este test fallaría: de ahí la nota de la imagen en
     * {@link PostgresIntegrationTestSupport}.
     */
    @Test
    void laBusquedaIgnoraMayusculasConEnyesYAcentosPeroNoIgnoraLosAcentos() throws Exception {
        saveMovie("Mañana", 2000, drama);
        saveMovie("ÁRBOL de la vida", 2011, drama);
        saveMovie("Ñoño", 2012, drama);

        assertEquals(List.of("Mañana"), titles(call(SEARCH, "title=MAÑANA")));
        assertEquals(List.of("Mañana"), titles(call(SEARCH, "title=mañana")));
        assertEquals(List.of("ÁRBOL de la vida"), titles(call(SEARCH, "title=árbol")));
        assertEquals(List.of("Ñoño"), titles(call(SEARCH, "title=ñOÑ")));
        assertEquals(List.of(), titles(call(SEARCH, "title=manana")));
        assertEquals(List.of(), titles(call(SEARCH, "title=arbol")));
    }

    /**
     * Los comodines de {@code LIKE} ({@code %}, {@code _}) y el carácter de
     * escape ({@code \}) viajan como texto literal a PostgreSQL real: se
     * encuentra exactamente el título que los contiene y la tabla sigue intacta.
     * Es el caso en el que PostgreSQL podría diferir de H2 en cómo interpreta
     * {@code ESCAPE '\'} (con {@code standard_conforming_strings}).
     */
    @ParameterizedTest
    @ValueSource(strings = { "100%", "a_b", "a\\b", "%_\\", "a%b_c\\d", "O'Brien", "'; DROP TABLE movies; --",
            "\\%", "\\\\", "%%", "__" })
    void losComodinesYElEscapeSeBuscanComoTextoLiteralEnPostgreSql(String text) throws Exception {
        saveMovie("Con " + text + " dentro", 2000, drama);
        saveMovie("Normal", 2000, drama);
        saveMovie("abc", 2000, drama);

        assertEquals(List.of("Con " + text + " dentro"), titles(call(SEARCH, "title=" + text)));
        assertEquals(3, parse(call(LIST)).totalElements());
    }

    /** Un comodín que no está en ningún título no coincide "por casualidad" con otros. */
    @Test
    void losComodinesNoCoincidenConTitulosQueNoLosContienen() throws Exception {
        saveMovie("abc", 2000, drama);
        saveMovie("a.c", 2000, drama);

        assertEquals(0, parse(call(SEARCH, "title=a_c")).totalElements());
        assertEquals(0, parse(call(SEARCH, "title=%%")).totalElements());
        assertEquals(0, parse(call(SEARCH, "title=a%c")).totalElements());
        assertEquals(0, parse(call(SEARCH, "title=\\")).totalElements());
    }

    /**
     * Letras cuyo paso a minúsculas depende del idioma o del contexto, pero que
     * Java y PostgreSQL resuelven igual: un título se encuentra a sí mismo.
     */
    @ParameterizedTest
    @ValueSource(strings = { "STRASSE", "Straße", "Σίσυφος", "ÑANDÚ" })
    void unTituloSeEncuentraBuscandoloEntero(String title) throws Exception {
        saveMovie(title, 2000, drama);

        assertEquals(List.of(title), titles(call(SEARCH, "title=" + title)));
    }

    /**
     * BUG REAL que H2 ocultaba (ya corregido; este test lo protege): {@code hasTitle}
     * pasa el texto buscado por {@code String.toLowerCase(Locale.ROOT)} de Java
     * y compara contra {@code lower(title)} de PostgreSQL. Ambas funciones NO
     * coinciden en dos casos: la {@code İ} (I mayúscula con punto) que Java
     * convierte en {@code i} + U+0307 y PostgreSQL en {@code i}, y la sigma
     * mayúscula FINAL de palabra ({@code ΟΔΥΣΣΕΥΣ}) que Java convierte en
     * {@code ς} y PostgreSQL en {@code σ}. Resultado: un título con esas letras
     * NO se encuentra buscándolo entero. En H2 pasa porque su {@code lower()}
     * también la hace Java.
     *
     * <p>
     * Arreglo ({@code MovieSpecification.hasTitle}): no minusculizar en Java,
     * que el motor minusculice ambos lados:
     * {@code criteriaBuilder.like(lower(title), lower(literal("%" + escapeLike(title) + "%")), '\')}.
     * </p>
     */
    @ParameterizedTest
    @ValueSource(strings = { "İstanbul", "ΟΔΥΣΣΕΥΣ" })
    void unTituloConLetrasDeMinusculizacionEspecialSeEncuentraBuscandoloEntero(String title) throws Exception {
        saveMovie(title, 2000, drama);

        assertEquals(List.of(title), titles(call(SEARCH, "title=" + title)));
    }

    /** Emoji y caracteres CJK (fuera del plano básico) llegan intactos a PostgreSQL y se comparan como texto. */
    @Test
    void laBusquedaAdmiteEmojiYCaracteresNoLatinos() throws Exception {
        saveMovie("Cine 🎬 en casa", 2020, drama);
        saveMovie("千と千尋の神隠し", 2001, drama);

        assertEquals(List.of("Cine 🎬 en casa"), titles(call(SEARCH, "title=🎬")));
        assertEquals(List.of("千と千尋の神隠し"), titles(call(SEARCH, "title=神隠")));
    }

    // ------------------------------------------------------------------
    // EXISTS por género
    // ------------------------------------------------------------------

    /**
     * Una película con varios géneros aparece una sola vez al listar y al
     * filtrar por cualquiera de ellos, y el total cuenta películas (no pares
     * película-género), también paginando: la subconsulta {@code EXISTS} se
     * ejecuta en PostgreSQL sin duplicar filas.
     */
    @Test
    void filtrarPorGeneroNoDuplicaPeliculasConVariosGenerosNiRompeLaPaginacion() throws Exception {
        for (int i = 0; i < 12; i++) {
            saveMovie(String.format("Multi %02d", i), 2000 + i, action, scifi, drama);
        }
        saveMovie("Solo drama", 2000, drama);
        saveMovie("Sin genero", 2000);

        assertEquals(14, parse(call(LIST, "size=100")).totalElements());
        for (Genre genre : List.of(action, scifi, drama)) {
            MoviePageResponse first = parse(call(SEARCH, "genreId=" + genre.getId(), "size=5", "page=0"));
            assertEquals(genre == drama ? 13 : 12, first.totalElements(), genre.getName());

            Set<Long> seen = new HashSet<>();
            for (int page = 0; page < first.totalPages(); page++) {
                for (MovieResponse movie : parse(call(SEARCH, "genreId=" + genre.getId(), "size=5", "page=" + page)).content()) {
                    assertTrue(seen.add(movie.id()), "repetida entre páginas: " + movie.title());
                }
            }
            assertEquals(first.totalElements(), seen.size());
        }
    }

    /** Género sin películas, inexistente o con el id más extremo del {@code bigint}: 200 vacío. */
    @ParameterizedTest
    @ValueSource(strings = { "987654321", "0", "-1", "9223372036854775807", "-9223372036854775808" })
    void unGeneroSinPeliculasDevuelveUnaPaginaVacia(String genreId) throws Exception {
        saveMovie("Peli", 2000, drama);

        call(SEARCH, "genreId=" + genreId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    // ------------------------------------------------------------------
    // Paginación y orden descendente
    // ------------------------------------------------------------------

    /**
     * Página fuera de rango y desplazamientos enormes: PostgreSQL acepta un
     * {@code OFFSET} hasta 2^31-2 sin error y la API responde 200 con
     * {@code content} vacío y los totales reales. (Los desplazamientos que
     * desbordan un {@code int} los rechaza la API con 400 antes de llegar a la
     * base de datos: eso no depende del motor.)
     */
    @ParameterizedTest
    @ValueSource(strings = { LIST, SEARCH })
    void unaPaginaFueraDeRangoDevuelve200VacioConLosTotalesReales(String endpoint) throws Exception {
        for (int i = 0; i < 5; i++) {
            saveMovie("Peli " + i, 2000, drama);
        }

        call(endpoint, "size=2", "page=10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.hasNext").value(false));
        call(endpoint, "size=2", "page=1073741823") // offset 2.147.483.646
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(5));
    }

    /**
     * Orden descendente con empates en el campo de orden (25 películas del mismo
     * año, páginas de 7): no se repite ni se pierde ninguna y el desempate por id
     * sigue la dirección, en ambos endpoints.
     */
    @Test
    void ordenDescendenteConEmpatesNoRepiteNiPierdePeliculasEntrePaginas() throws Exception {
        for (int i = 0; i < 25; i++) {
            saveMovie("Misma fecha " + i, 2020, drama);
        }

        for (String endpoint : new String[] { LIST, SEARCH }) {
            List<Long> seen = new ArrayList<>();
            for (int page = 0; page < 4; page++) {
                seen.addAll(ids(parse(call(endpoint, "sort=releaseYear", "direction=desc", "size=7", "page=" + page))));
            }
            assertEquals(25, new HashSet<>(seen).size(), endpoint);
            assertEquals(seen.stream().sorted(Comparator.reverseOrder()).toList(), seen,
                    "el desempate por id debe ser descendente en " + endpoint);
        }
    }

    /**
     * {@code sort=createdAt}: el orden por fecha de creación coincide con el de
     * inserción. {@code created_at} es {@code timestamptz} con precisión de
     * microsegundo; si dos filas empataran, el desempate por id mantiene el
     * orden total.
     */
    @Test
    void ordenarPorFechaDeCreacionRespetaElOrdenDeInsercion() throws Exception {
        List<Long> insertionOrder = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            insertionOrder.add(saveMovie("Reciente " + i, 2000, drama).getId());
        }

        assertEquals(insertionOrder, ids(parse(call(LIST, "sort=createdAt", "size=100"))));
        assertEquals(insertionOrder.reversed(),
                ids(parse(call(SEARCH, "sort=createdAt", "direction=desc", "size=100"))));
    }

    // ------------------------------------------------------------------
    // Sin N+1
    // ------------------------------------------------------------------

    /**
     * El número de sentencias SQL de una página de catálogo no depende del
     * tamaño de la página (carga de géneros por lotes), también en PostgreSQL:
     * una página + el total + los géneros por lote como máximo.
     */
    @Test
    void unaPaginaDeCatalogoNoEjecutaUnaConsultaPorPelicula() {
        for (int i = 0; i < 30; i++) {
            saveMovie(String.format("Peli %02d", i), 2000, action, scifi, drama);
        }

        long small = statementsFor(() -> movieService.getMovies(page(5)));
        long large = statementsFor(() -> movieService.getMovies(page(25)));

        assertTrue(small <= 3, "página de 5 películas: " + small + " consultas");
        assertEquals(small, large, "el número de consultas no debe depender del tamaño de página");
    }

    /** La búsqueda con los tres filtros (título + género + año) tampoco hace N+1. */
    @Test
    void laBusquedaConFiltrosTampocoEjecutaUnaConsultaPorPelicula() {
        for (int i = 0; i < 30; i++) {
            saveMovie(String.format("Busqueda %02d", i), 2010, action, scifi);
        }

        long small = statementsFor(() -> movieService.searchMovies("busqueda", action.getId(), 2010, page(5)));
        long large = statementsFor(() -> movieService.searchMovies("busqueda", action.getId(), 2010, page(25)));

        assertTrue(small <= 3, "búsqueda de 5 resultados: " + small + " consultas");
        assertEquals(small, large);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private ResultActions call(String endpoint, String... params) throws Exception {
        MockHttpServletRequestBuilder request = get(endpoint).header("Authorization", userToken);
        for (String pair : params) {
            int separator = pair.indexOf('=');
            request.param(pair.substring(0, separator), pair.substring(separator + 1));
        }
        return mockMvc.perform(request);
    }

    private MoviePageResponse parse(ResultActions result) throws Exception {
        String json = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(json, MoviePageResponse.class);
    }

    private List<String> titles(ResultActions result) throws Exception {
        return parse(result).content().stream().map(MovieResponse::title).toList();
    }

    private static List<Long> ids(MoviePageResponse page) {
        return page.content().stream().map(MovieResponse::id).toList();
    }

    private static PageRequest page(int size) {
        return PageRequest.of(0, size, Sort.by("title").ascending().and(Sort.by("id")));
    }

    private long statementsFor(Runnable operation) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        operation.run();
        return statistics.getPrepareStatementCount();
    }
}
