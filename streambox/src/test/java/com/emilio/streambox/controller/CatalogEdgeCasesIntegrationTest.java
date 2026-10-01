package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.emilio.streambox.dto.MoviePageResponse;
import com.emilio.streambox.dto.MovieResponse;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Casos límite de {@code GET /api/movies} y {@code GET /api/movies/search}:
 * paginación fuera de rango, parámetros mal formados, búsqueda por título con
 * texto "raro", filtros de género/año con valores extremos y combinaciones de
 * ordenación.
 *
 * <p>
 * Regla general que protege esta clase: <b>ninguna petición de cliente mal
 * formada puede acabar en 500</b>; los valores sintácticamente inválidos dan
 * 400 con el formato {@code ErrorResponse} y los valores válidos pero sin
 * coincidencias dan 200 con una página vacía.
 * </p>
 *
 * <p>
 * No usa {@code @Transactional}: los datos se confirman de verdad (igual que
 * {@link CatalogIntegrationTest}) y se limpian antes y después de cada test.
 * Los parámetros se pasan con {@code .param(...)} para que caracteres como
 * {@code %} o {@code \} lleguen al servidor sin codificarse dos veces.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CatalogEdgeCasesIntegrationTest {

    private static final String LIST = "/api/movies";
    private static final String SEARCH = "/api/movies/search";

    @Autowired private MockMvc mockMvc;
    @Autowired private MovieRepository movieRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private String userToken;
    private Genre action;
    private Genre drama;
    private Genre empty;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        userToken = "Bearer " + jwtService.generateToken(saveUser("edgeuser", Role.USER));
        action = saveGenre("Action");
        drama = saveGenre("Drama");
        empty = saveGenre("SinPeliculas");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Paginación fuera de rango
    // ------------------------------------------------------------------

    /**
     * Pedir una página posterior a la última no es un error: es una consulta
     * válida sin resultados. Debe devolver 200 con {@code content} vacío, los
     * totales reales y {@code hasNext=false}, para que un cliente que pagina
     * "hasta que no haya más" termine sin fallos.
     */
    @ParameterizedTest
    @ValueSource(strings = { LIST, SEARCH })
    void unaPaginaMasAllaDeLaUltimaDevuelve200ConContenidoVacio(String endpoint) throws Exception {
        for (int i = 0; i < 5; i++) {
            saveMovie("Peli " + i, 2000, drama);
        }

        call(endpoint, "size=2", "page=10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.page").value(10))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.hasPrevious").value(true));
    }

    /**
     * Cerca de la frontera del desplazamiento: mientras {@code page × size}
     * quepa en un {@code int} ({@code Integer.MAX_VALUE - 1} con
     * {@code size=1}, 2.147.483.600 con {@code size=100}) debe responder como
     * cualquier otra página vacía (200).
     */
    @ParameterizedTest
    @ValueSource(strings = { LIST, SEARCH })
    void unaPaginaConOffsetJustoDentroDelLimiteDevuelve200Vacio(String endpoint) throws Exception {
        saveMovie("Unica", 2000, drama);

        call(endpoint, "page=" + (Integer.MAX_VALUE - 1), "size=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
        call(endpoint, "page=21474836", "size=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    /**
     * Cuando {@code page × size} no es menor que {@code Integer.MAX_VALUE}
     * Spring Data no puede calcular el desplazamiento (lanzaba
     * {@code InvalidDataAccessApiUsageException} y la API respondía 500) y
     * con {@code page=Integer.MAX_VALUE} desbordaba {@code page + 1}
     * ({@code hasNext=true} erróneo). Es un error del cliente: el controlador
     * responde 400 {@code VALIDATION_ERROR} señalando {@code page}, igual que
     * un {@code sort} no permitido, y sin filtrar detalles del framework.
     */
    @ParameterizedTest(name = "{0} page={1} size={2}")
    @MethodSource("hugeOffsets")
    void unOffsetQueNoCabeEnUnIntDa400EnLugarDeError500(String endpoint, String page, String size) throws Exception {
        saveMovie("Unica", 2000, drama);

        var response = call(endpoint, "page=" + page, "size=" + size)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.path").value(endpoint))
                .andExpect(jsonPath("$.validationErrors.page").isString())
                .andReturn().getResponse();

        String body = response.getContentAsString();
        assertFalse(body.contains("Integer.MAX_VALUE"), "la respuesta filtra detalles internos: " + body);
        assertFalse(body.contains("Exception"), "la respuesta filtra una excepción: " + body);
    }

    /**
     * Frontera exacta del límite, en ambos endpoints: con {@code size=2} el
     * último offset válido es 2.147.483.646 ({@code page=1073741823}, 200 con
     * página vacía y {@code hasNext=false}) y el siguiente ({@code page=1073741824},
     * offset 2^31) ya se rechaza. Con {@code size=1} pasa lo mismo entre
     * {@code page=Integer.MAX_VALUE - 1} y {@code page=Integer.MAX_VALUE}.
     */
    @ParameterizedTest
    @ValueSource(strings = { LIST, SEARCH })
    void elUltimoOffsetValidDa200YElPrimeroInvalidoDa400(String endpoint) throws Exception {
        saveMovie("Unica", 2000, drama);

        call(endpoint, "page=1073741823", "size=2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.hasNext").value(false));
        call(endpoint, "page=1073741824", "size=2")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.page").exists());

        call(endpoint, "page=" + (Integer.MAX_VALUE - 1), "size=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasNext").value(false));
        call(endpoint, "page=" + Integer.MAX_VALUE, "size=1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.page").exists());
    }

    static Stream<Arguments> hugeOffsets() {
        List<Arguments> cases = new ArrayList<>();
        for (String endpoint : new String[] { LIST, SEARCH }) {
            cases.add(Arguments.of(endpoint, String.valueOf(Integer.MAX_VALUE), "100"));
            cases.add(Arguments.of(endpoint, String.valueOf(Integer.MAX_VALUE), "1")); // offset = MAX_VALUE: page + 1 desbordaba
            cases.add(Arguments.of(endpoint, "21474837", "100"));     // offset 2.147.483.700
            cases.add(Arguments.of(endpoint, "1073741824", "2"));     // offset 2^31, justo uno más que el máximo
        }
        return cases.stream();
    }

    /**
     * Catálogo vacío: 200 con totales a cero y sin páginas siguientes ni
     * anteriores (en la página 0).
     */
    @ParameterizedTest
    @ValueSource(strings = { LIST, SEARCH })
    void unCatalogoVacioDevuelveUnaPaginaVaciaCoherente(String endpoint) throws Exception {
        call(endpoint)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.hasPrevious").value(false));

        // Pedir una página lejana de un catálogo vacío tampoco es un error
        call(endpoint, "page=3")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    /**
     * Los metadatos de paginación son coherentes página a página con un
     * catálogo de 7 películas y {@code size=3}: 3 + 3 + 1 (última parcial).
     */
    @ParameterizedTest
    @ValueSource(strings = { LIST, SEARCH })
    void losMetadatosDePaginacionSonCoherentesEnCadaPagina(String endpoint) throws Exception {
        for (int i = 0; i < 7; i++) {
            saveMovie("Peli " + i, 2000, drama);
        }

        call(endpoint, "size=3", "page=0")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.totalElements").value(7))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.hasPrevious").value(false));
        call(endpoint, "size=3", "page=1")
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.hasPrevious").value(true));
        // Última página, parcial: 1 película, sin siguiente
        call(endpoint, "size=3", "page=2")
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(7))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.hasPrevious").value(true));
    }

    /**
     * Cuando el total es múltiplo exacto del tamaño no debe aparecer una
     * página fantasma: 6 películas con {@code size=3} son 2 páginas, no 3.
     */
    @Test
    void unTotalMultiploDelTamanoNoGeneraUnaPaginaFantasma() throws Exception {
        for (int i = 0; i < 6; i++) {
            saveMovie("Peli " + i, 2000, drama);
        }

        call(LIST, "size=3", "page=1")
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.hasNext").value(false));
    }

    /** {@code size=100} (el máximo) y {@code size=1} (el mínimo) son válidos. */
    @ParameterizedTest
    @ValueSource(strings = { LIST, SEARCH })
    void losTamanosLimiteUnoYCienSonValidos(String endpoint) throws Exception {
        for (int i = 0; i < 3; i++) {
            saveMovie("Peli " + i, 2000, drama);
        }

        call(endpoint, "size=100")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100))
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.totalPages").value(1));
        call(endpoint, "size=1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    /**
     * Valores de {@code page} y {@code size} que el servidor debe rechazar con
     * 400 {@code VALIDATION_ERROR} (fuera de rango, no numéricos, decimales o
     * que no caben en un {@code int}), nunca con 500, y señalando el parámetro
     * culpable en {@code validationErrors}.
     */
    @ParameterizedTest(name = "{0} {1}={2}")
    @MethodSource("invalidPagination")
    void losParametrosDePaginacionInvalidosDan400(String endpoint, String name, String value) throws Exception {
        saveMovie("Peli", 2000, drama);

        ResultActions result = call(endpoint, name + "=" + value)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.path").value(endpoint))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.validationErrors." + name).exists());

        // Sin detalles internos del framework en la respuesta
        String body = result.andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("java.lang"), "la respuesta filtra nombres de clases: " + body);
        assertFalse(body.contains("NumberFormatException"), "la respuesta filtra la excepción: " + body);
        assertFalse(body.contains("Exception"), "la respuesta filtra una excepción: " + body);
    }

    static Stream<Arguments> invalidPagination() {
        List<Arguments> cases = new ArrayList<>();
        for (String endpoint : new String[] { LIST, SEARCH }) {
            for (String page : new String[] { "-1", "abc", "1.5", "99999999999", "1e2", "%20" }) {
                cases.add(Arguments.of(endpoint, "page", page));
            }
            for (String size : new String[] { "0", "-1", "101", "abc", "2.5", "2147483648", "100000" }) {
                cases.add(Arguments.of(endpoint, "size", size));
            }
        }
        return cases.stream();
    }

    /**
     * Un valor vacío ({@code page=}) lo trata Spring como "no indicado" y usa
     * el valor por defecto: se fija este contrato para que un cambio futuro
     * no sea una sorpresa.
     */
    @ParameterizedTest
    @ValueSource(strings = { LIST, SEARCH })
    void unParametroDePaginacionVacioUsaElValorPorDefecto(String endpoint) throws Exception {
        saveMovie("Peli", 2000, drama);

        call(endpoint, "page=", "size=")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10));
    }

    // ------------------------------------------------------------------
    // Búsqueda por título
    // ------------------------------------------------------------------

    /**
     * Un título vacío o solo con espacios se ignora (equivale a no filtrar) y
     * devuelve todo el catálogo; no filtra por "contiene espacios".
     */
    @ParameterizedTest
    @ValueSource(strings = { "", " ", "     ", "\t", "\n" })
    void unTituloVacioOEnBlancoNoFiltra(String title) throws Exception {
        saveMovie("Uno", 2000, drama);
        saveMovie("Dos palabras", 2001, drama);

        call(SEARCH, "title=" + title)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    /**
     * Un solo carácter es una búsqueda válida (sin longitud mínima) y busca
     * "contiene", no "empieza por".
     */
    @Test
    void unTituloDeUnSoloCaracterBuscaPorContenido() throws Exception {
        saveMovie("Zeta", 2000, drama);
        saveMovie("Beta", 2000, drama);
        saveMovie("Omega", 2000, drama);

        call(SEARCH, "title=z")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Zeta"));
        call(SEARCH, "title=e")
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    /**
     * Los espacios internos cuentan y los de los extremos también (no se
     * recorta el texto): "a b" no encuentra "ab", y " a" solo encuentra
     * títulos con un espacio antes de la "a".
     */
    @Test
    void losEspaciosDelTituloBuscadoSeRespetan() throws Exception {
        saveMovie("Star Wars", 1977, drama);
        saveMovie("Starwars", 1999, drama);

        call(SEARCH, "title=r w")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Star Wars"));
        call(SEARCH, "title= wars")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Star Wars"));
    }

    /**
     * Un texto de búsqueda larguísimo (más que el título máximo de 150
     * caracteres, más de 255 y más de 1000) no puede provocar un 500 ni
     * (en la práctica) devolver resultados: es una búsqueda válida sin
     * coincidencias.
     */
    @ParameterizedTest
    @ValueSource(ints = { 151, 256, 1001, 20_000 })
    void unTituloMuyLargoDevuelve200SinResultados(int length) throws Exception {
        saveMovie("Corta", 2000, drama);

        call(SEARCH, "title=" + "a".repeat(length))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    /**
     * Un título del tamaño máximo permitido (150) se encuentra buscándolo
     * entero: la longitud máxima de la columna no rompe la búsqueda.
     */
    @Test
    void unTituloDeLaLongitudMaximaSeEncuentraBuscandoloEntero() throws Exception {
        String longTitle = "x".repeat(150);
        saveMovie(longTitle, 2000, drama);

        call(SEARCH, "title=" + longTitle)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    /**
     * La comparación no distingue mayúsculas también con letras no ASCII
     * ({@code ñ}, vocales acentuadas): "MAÑANA" encuentra "Mañana" y al revés.
     * En cambio <b>sí distingue acentos</b>: "manana" no encuentra "Mañana".
     * Es el contrato actual (búsqueda sensible a diacríticos); si se quisiera
     * insensible habría que usar {@code unaccent} (PostgreSQL) y este test
     * documenta que hoy no se hace.
     */
    @Test
    void laBusquedaIgnoraMayusculasConEnyesYAcentosPeroNoIgnoraLosAcentos() throws Exception {
        saveMovie("Mañana", 2000, drama);
        saveMovie("ÁRBOL de la vida", 2011, drama);

        call(SEARCH, "title=MAÑANA")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Mañana"));
        call(SEARCH, "title=mañana")
                .andExpect(jsonPath("$.totalElements").value(1));
        call(SEARCH, "title=árbol")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("ÁRBOL de la vida"));
        call(SEARCH, "title=manana")
                .andExpect(jsonPath("$.totalElements").value(0));
        call(SEARCH, "title=arbol")
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    /**
     * Unicode fuera del plano básico (emoji, caracteres CJK) viaja
     * correctamente hasta la base de datos y se compara como texto.
     */
    @Test
    void laBusquedaAdmiteEmojiYCaracteresNoLatinos() throws Exception {
        saveMovie("Cine 🎬 en casa", 2020, drama);
        saveMovie("千と千尋の神隠し", 2001, drama);

        call(SEARCH, "title=🎬")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Cine 🎬 en casa"));
        call(SEARCH, "title=神隠")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    /**
     * Caracteres con significado especial en SQL, LIKE o HTML se tratan como
     * texto literal: no hay inyección, no hay error y se encuentra
     * exactamente el título que los contiene. Además, la tabla de películas
     * sigue intacta después de enviar un intento de {@code DROP TABLE}.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "O'Brien", "a;b", "a--b", "<script>alert(1)</script>", "100%", "a_b", "a\\b",
            "x' OR '1'='1", "'; DROP TABLE movies; --", "\"comillas\"", "%_\\", "a%b_c\\d" })
    void losCaracteresEspecialesSeBuscanComoTextoLiteral(String text) throws Exception {
        // Una película cuyo título contiene exactamente el texto, y otra "normal"
        saveMovie("Con " + text + " dentro", 2000, drama);
        saveMovie("Normal", 2000, drama);

        call(SEARCH, "title=" + text)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Con " + text + " dentro"));

        // La tabla sigue ahí con sus dos películas
        call(LIST).andExpect(jsonPath("$.totalElements").value(2));
    }

    /**
     * Un texto con comodines que no aparece como tal en ningún título no
     * debe coincidir "por casualidad" con otros: {@code a_c} no encuentra
     * "abc" y {@code %%} no encuentra todo.
     */
    @Test
    void losComodinesNoCoincidenConTitulosQueNoLosContienen() throws Exception {
        saveMovie("abc", 2000, drama);
        saveMovie("a.c", 2000, drama);

        call(SEARCH, "title=a_c").andExpect(jsonPath("$.totalElements").value(0));
        call(SEARCH, "title=%%").andExpect(jsonPath("$.totalElements").value(0));
        call(SEARCH, "title=a%c").andExpect(jsonPath("$.totalElements").value(0));
    }

    /**
     * Si {@code title} se repite ({@code title=a&title=b}) Spring une los
     * valores con una coma ({@code "a,b"}). No es un error: se busca el texto
     * literal "a,b". Se fija el contrato (200, sin 500) para que no se
     * confunda con un OR de dos títulos.
     */
    @Test
    void unTituloRepetidoSeUneConComaYNoDaError() throws Exception {
        saveMovie("a", 2000, drama);
        saveMovie("b", 2000, drama);
        saveMovie("Con a,b dentro", 2000, drama);

        call(SEARCH, "title=a", "title=b")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Con a,b dentro"));
    }

    // ------------------------------------------------------------------
    // Filtros de género y año
    // ------------------------------------------------------------------

    /**
     * Un género que no existe, o con un id imposible (0, negativo), es un
     * filtro válido sin coincidencias: 200 con página vacía (no 404, porque
     * el recurso consultado es el catálogo, no el género).
     */
    @ParameterizedTest
    @ValueSource(strings = { "987654321", "0", "-1", "-9223372036854775808", "9223372036854775807" })
    void unGeneroInexistenteDevuelve200ConPaginaVacia(String genreId) throws Exception {
        saveMovie("Peli", 2000, drama);

        call(SEARCH, "genreId=" + genreId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    /** {@code genreId} no numérico, decimal o que no cabe en un {@code long}: 400. */
    @ParameterizedTest
    @ValueSource(strings = { "abc", "1.5", "99999999999999999999", "1,2", "%20", "-", "1e3" })
    void unGenreIdMalFormadoDa400(String genreId) throws Exception {
        call(SEARCH, "genreId=" + genreId)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.genreId").exists());
    }

    /**
     * {@code releaseYear} fuera del rango que admite la base de datos (CHECK
     * 1888-2100) es un filtro válido sin coincidencias: 200 y página vacía,
     * no un error de integridad ni un 500.
     */
    @ParameterizedTest
    @ValueSource(strings = { "0", "-5", "1887", "2101", "3000", "2147483647", "-2147483648" })
    void unAnioFueraDelRangoDeLaBaseDeDatosDevuelve200ConPaginaVacia(String year) throws Exception {
        saveMovie("Peli", 2000, drama);

        call(SEARCH, "releaseYear=" + year)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    /** Los extremos válidos del CHECK (1888 y 2100) sí se pueden filtrar. */
    @Test
    void losAniosLimiteDelCheckSePuedenFiltrar() throws Exception {
        saveMovie("Primera", 1888, drama);
        saveMovie("Futura", 2100, drama);

        call(SEARCH, "releaseYear=1888").andExpect(jsonPath("$.totalElements").value(1));
        call(SEARCH, "releaseYear=2100").andExpect(jsonPath("$.totalElements").value(1));
    }

    /** {@code releaseYear} no numérico, decimal o que no cabe en un {@code int}: 400. */
    @ParameterizedTest
    @ValueSource(strings = { "abc", "1999.5", "2147483648", "99999999999", "1e3", "MCMXCIX" })
    void unReleaseYearMalFormadoDa400(String year) throws Exception {
        call(SEARCH, "releaseYear=" + year)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.releaseYear").exists());
    }

    /**
     * Valores vacíos de {@code genreId} y {@code releaseYear} se tratan como
     * "no indicados" (no son un 400): equivale a no filtrar.
     */
    @Test
    void genreIdYReleaseYearVaciosNoFiltran() throws Exception {
        saveMovie("Uno", 2000, drama);
        saveMovie("Dos", 2001, action);

        call(SEARCH, "genreId=", "releaseYear=")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    // ------------------------------------------------------------------
    // Combinaciones
    // ------------------------------------------------------------------

    /**
     * Título + género + año a la vez: cada filtro por separado coincide con
     * alguna película, pero ninguna cumple los tres (AND, no OR). Devuelve
     * 200 vacío; y basta cambiar un solo filtro para que aparezca la
     * película correcta.
     */
    @Test
    void losTresFiltrosJuntosSeCombinanConAndYPuedenNoTenerResultados() throws Exception {
        saveMovie("Alien", 1979, action);
        saveMovie("Aliens", 1986, drama);
        saveMovie("Casablanca", 1942, drama);

        // título de la 1ª, género de la 2ª/3ª, año de la 3ª: ninguna cumple todo
        call(SEARCH, "title=alien", "genreId=" + drama.getId(), "releaseYear=1942")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content.length()").value(0));

        call(SEARCH, "title=alien", "genreId=" + drama.getId(), "releaseYear=1986")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Aliens"));
    }

    /**
     * Una película sin ningún género aparece en el listado (con
     * {@code genres=[]}) y en las búsquedas sin filtro de género, pero no en
     * ningún filtro por género, ni siquiera en el de un género sin películas.
     */
    @Test
    void unaPeliculaSinGenerosApareceEnElListadoPeroNoEnElFiltroPorGenero() throws Exception {
        saveMovie("Sin genero", 2000);
        saveMovie("Con drama", 2000, drama);

        call(LIST)
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[?(@.title=='Sin genero')].genres.length()").value(0));
        call(SEARCH, "title=sin genero")
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].genres.length()").value(0));
        call(SEARCH, "genreId=" + drama.getId())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Con drama"));
        call(SEARCH, "genreId=" + empty.getId())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    /**
     * Una película con varios géneros sale una sola vez al listar y al filtrar
     * por cualquiera de ellos (la consulta usa {@code EXISTS}, no un
     * {@code JOIN} que duplicaría filas), y el total refleja películas, no
     * pares película-género.
     */
    @ParameterizedTest
    @ValueSource(strings = { LIST, SEARCH })
    void unaPeliculaConVariosGenerosNoApareceDuplicada(String endpoint) throws Exception {
        for (int i = 0; i < 4; i++) {
            saveMovie("Multi " + i, 2000, action, drama);
        }

        MoviePageResponse page = parse(call(endpoint, "size=100"));
        assertEquals(4, page.totalElements());
        assertEquals(4, page.content().size());
        assertEquals(4, ids(page).stream().distinct().count(), "hay películas duplicadas en la página");
        page.content().forEach(movie -> assertEquals(2, movie.genres().size()));

        for (Genre genre : List.of(action, drama)) {
            MoviePageResponse filtered = parse(call(SEARCH, "genreId=" + genre.getId(), "size=100"));
            assertEquals(4, filtered.totalElements(), "filtro por " + genre.getName());
            assertEquals(4, ids(filtered).stream().distinct().count());
        }
    }

    /**
     * Paginar una búsqueda con filtros y con empates en el campo de orden no
     * repite ni pierde películas, en ambas direcciones: 13 películas de la
     * misma duración (empate total), 2 de ellas fuera del filtro, páginas de 4.
     */
    @ParameterizedTest
    @ValueSource(strings = { "asc", "desc" })
    void paginarUnaBusquedaFiltradaConEmpatesNoRepiteNiPierdePeliculas(String direction) throws Exception {
        Set<Long> expected = new HashSet<>();
        for (int i = 0; i < 13; i++) {
            expected.add(saveMovie("Serie " + i, 2000, 100, action, drama).getId());
        }
        saveMovie("Serie ajena de otro genero", 2000, 100, drama);
        saveMovie("Otro titulo", 2000, 100, action);

        List<Long> seen = new ArrayList<>();
        for (int p = 0; p < 4; p++) {
            seen.addAll(ids(parse(call(SEARCH, "title=serie", "genreId=" + action.getId(),
                    "sort=duration", "direction=" + direction, "size=4", "page=" + p))));
        }

        assertEquals(13, seen.size(), "faltan o sobran películas: " + seen);
        assertEquals(expected, new HashSet<>(seen));
        List<Long> sortedIds = new ArrayList<>(seen);
        sortedIds.sort("asc".equals(direction) ? Comparator.naturalOrder() : Comparator.reverseOrder());
        assertEquals(sortedIds, seen, "el desempate por id debe seguir la dirección " + direction);
    }

    /**
     * Las 5 columnas permitidas × asc/desc × los 2 endpoints ordenan de
     * verdad: el orden devuelto coincide con el esperado calculado en Java
     * (con valores distintos en cada columna para que no haya empates).
     */
    @ParameterizedTest(name = "{0} sort={1} direction={2}")
    @MethodSource("sortCombinations")
    void todasLasColumnasPermitidasOrdenanEnAmbasDirecciones(String endpoint, String sort, String direction)
            throws Exception {
        // Orden de inserción (= createdAt e id ascendentes) distinto del de título, año y duración
        List<Movie> inserted = List.of(
                saveMovie("Charlie", 2001, 130, drama),
                saveMovie("Alpha", 1999, 90, drama),
                saveMovie("Echo", 2010, 70, drama),
                saveMovie("Bravo", 1985, 150, drama),
                saveMovie("Delta", 2005, 110, drama));

        Comparator<Movie> comparator = switch (sort) {
            case "id", "createdAt" -> Comparator.comparing(Movie::getId);
            case "title" -> Comparator.comparing(Movie::getTitle);
            case "releaseYear" -> Comparator.comparing(Movie::getReleaseYear);
            case "duration" -> Comparator.comparing(Movie::getDuration);
            default -> throw new IllegalArgumentException(sort);
        };
        if ("desc".equals(direction)) {
            comparator = comparator.reversed();
        }
        List<Long> expected = inserted.stream().sorted(comparator).map(Movie::getId).toList();

        assertEquals(expected, ids(parse(call(endpoint, "sort=" + sort, "direction=" + direction))));
    }

    static Stream<Arguments> sortCombinations() {
        List<Arguments> cases = new ArrayList<>();
        for (String endpoint : new String[] { LIST, SEARCH }) {
            for (String sort : new String[] { "id", "title", "releaseYear", "duration", "createdAt" }) {
                for (String direction : new String[] { "asc", "desc" }) {
                    cases.add(Arguments.of(endpoint, sort, direction));
                }
            }
        }
        return cases.stream();
    }

    /**
     * La lista blanca de {@code sort} es estricta: distingue mayúsculas, no
     * recorta espacios y no acepta nombres de columna SQL ni de relaciones
     * ({@code release_year}, {@code genres}). Cualquiera de ellos da 400
     * {@code VALIDATION_ERROR}, nunca un 500 por una propiedad inexistente.
     */
    @ParameterizedTest
    @MethodSource("invalidSorts")
    void laListaBlancaDeSortEsEstricta(String endpoint, String sort) throws Exception {
        saveMovie("Peli", 2000, drama);

        call(endpoint, "sort=" + sort)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.sort").exists());
    }

    static Stream<Arguments> invalidSorts() {
        List<Arguments> cases = new ArrayList<>();
        for (String endpoint : new String[] { LIST, SEARCH }) {
            for (String sort : new String[] { "Title", "TITLE", "releaseyear", "RELEASEYEAR", "createdat",
                    " title", "title ", "title,desc", "title;id", "title,id", "release_year", "created_at",
                    "genres", "genres.name", "title desc", "title)--", "id,asc", "%", "." }) {
                cases.add(Arguments.of(endpoint, sort));
            }
        }
        return cases.stream();
    }

    /**
     * Un {@code sort} o {@code direction} repetido llega a Spring como un
     * único texto con comas ("title,id", "asc,desc"), que no está en la lista
     * blanca: debe rechazarse con 400 y no aplicar silenciosamente uno de los
     * dos.
     */
    @ParameterizedTest
    @ValueSource(strings = { LIST, SEARCH })
    void sortYDirectionRepetidosDan400(String endpoint) throws Exception {
        call(endpoint, "sort=title", "sort=id")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.sort").exists());
        call(endpoint, "direction=asc", "direction=desc")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.direction").exists());
    }

    /** {@code sort=} vacío usa el orden por defecto (título ascendente), igual que no indicarlo. */
    @ParameterizedTest
    @ValueSource(strings = { LIST, SEARCH })
    void unSortVacioUsaElOrdenPorDefecto(String endpoint) throws Exception {
        saveMovie("B", 2000, drama);
        saveMovie("A", 2000, drama);

        call(endpoint, "sort=", "direction=")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("A"))
                .andExpect(jsonPath("$.content[1].title").value("B"));
    }

    /**
     * Si hay varios parámetros inválidos a la vez (p. ej. {@code size=0} y
     * {@code sort} no permitido) la respuesta es 400, no 500.
     */
    @ParameterizedTest
    @ValueSource(strings = { LIST, SEARCH })
    void variosParametrosInvalidosJuntosDan400(String endpoint) throws Exception {
        call(endpoint, "size=0", "page=-1", "sort=nope", "direction=up")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ------------------------------------------------------------------
    // Autenticación
    // ------------------------------------------------------------------

    /**
     * Sin token se responde 401 aunque los parámetros sean inválidos (la
     * autenticación se evalúa antes que la validación: no se filtra
     * información a anónimos).
     */
    @ParameterizedTest
    @ValueSource(strings = { LIST, SEARCH })
    void sinTokenSiempreDa401AunqueLosParametrosSeanInvalidos(String endpoint) throws Exception {
        mockMvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(endpoint).param("size", "-1").param("sort", "nope"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").exists());
    }

    /** Un usuario normal (no administrador) puede consultar ambos endpoints. */
    @ParameterizedTest
    @ValueSource(strings = { LIST, SEARCH })
    void unUsuarioNormalPuedeConsultarElCatalogo(String endpoint) throws Exception {
        saveMovie("Peli", 2000, drama);

        call(endpoint).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * Hace un GET autenticado con parámetros {@code clave=valor}. Se pasan con
     * {@code .param(...)} (sin codificar) y un nombre repetido añade un
     * segundo valor al mismo parámetro.
     */
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

    private static List<Long> ids(MoviePageResponse page) {
        return page.content().stream().map(MovieResponse::id).toList();
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

    private Movie saveMovie(String title, int year, Genre... genres) {
        return saveMovie(title, year, 100, genres);
    }

    private Movie saveMovie(String title, int year, int duration, Genre... genres) {
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setDescription("Descripción de " + title);
        movie.setDuration(duration);
        movie.setReleaseYear(year);
        movie.setImageUrl("https://example.com/image.jpg");
        movie.setVideoUrl("https://example.com/video.mp4");
        movie.setGenres(new HashSet<>(List.of(genres)));
        return movieRepository.save(movie);
    }

    private void cleanDatabase() {
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
