package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.dto.MovieResponse;
import com.emilio.streambox.dto.SeriesResponse;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.service.MovieService;
import com.emilio.streambox.service.SeriesService;
import com.emilio.streambox.specification.MovieSpecification;
import com.emilio.streambox.specification.SeriesSpecification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Búsqueda por título sin acentos y tolerante a erratas (migración V5 de
 * {@code db/vendor/postgresql} y {@code specification.TitleSearch}) sobre
 * <b>PostgreSQL real</b>.
 *
 * <p>
 * Casi todo lo de aquí no se puede probar en H2: {@code unaccent} de verdad,
 * {@code pg_trgm}, los índices GIN, el plan de ejecución y las propiedades de la
 * función ({@code IMMUTABLE}, cuerpo enlazado por OID). Lo común a los dos
 * motores está en {@code controller.TitleSearchIntegrationTest}.
 * </p>
 */
class PostgresTitleSearchIntegrationTest extends PostgresIntegrationTestSupport {

    private static final String MOVIE_SEARCH = "/api/movies/search";
    private static final String SERIES_SEARCH = "/api/series/search";
    private static final String ADMIN_SERIES = "/api/admin/series";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private MovieService movieService;
    @Autowired private SeriesService seriesService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String userToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        cleanUp();
        userToken = tokenFor(saveUser("pgtitleuser", Role.USER));
        adminToken = tokenFor(saveUser("pgtitleadmin", Role.ADMIN));
    }

    @AfterEach
    void tearDown() {
        cleanUp();
    }

    // ------------------------------------------------------------------
    // La migración: extensiones, funciones e índices
    // ------------------------------------------------------------------

    /** La V5 de PostgreSQL se aplica desde {@code db/vendor/postgresql} (la de H2 no). */
    @Test
    void laV5DePostgresqlEstaAplicada() {
        assertEquals(List.of("V5__accent_insensitive_title_search.sql"), jdbc.queryForList(
                "SELECT script FROM flyway_schema_history WHERE version = '5' AND success", String.class));
    }

    /**
     * Las extensiones van al esquema {@code extensions} (como en Supabase), no a
     * {@code public}: así {@code public} solo tiene objetos de StreamBox y el
     * callback que cierra la API pública puede cerrarlos todos.
     */
    @Test
    void lasExtensionesQuedanEnElEsquemaExtensions() {
        assertEquals(List.of("pg_trgm:extensions", "unaccent:extensions"), jdbc.queryForList(
                "SELECT e.extname || ':' || n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace"
                        + " WHERE e.extname IN ('unaccent', 'pg_trgm') ORDER BY e.extname", String.class));
    }

    /**
     * Las dos funciones son {@code IMMUTABLE} (requisito para usarlas en un
     * índice), {@code STRICT}, aptas para paralelo y con cuerpo SQL estándar
     * ({@code BEGIN ATOMIC}: {@code prosqlbody} no nulo), que enlaza
     * {@code unaccent} y su diccionario por OID al crearlas, y además con
     * {@code SET search_path = ''} (lo que exige el Security Advisor de
     * Supabase). {@code unaccent} original es solo {@code STABLE}: por eso hace
     * falta el envoltorio.
     */
    @Test
    void lasFuncionesSonInmutablesYConCuerpoEnlazadoAlCrearlas() {
        for (String function : List.of("public.streambox_unaccent(text)", "public.streambox_word_similarity(text,text)")) {
            Map<String, Object> row = jdbc.queryForMap("SELECT provolatile::text AS volatility, proisstrict,"
                    + " proparallel::text AS parallel, prosqlbody IS NOT NULL AS sqlbody,"
                    + " coalesce('search_path=\"\"' = ANY (proconfig), false) AS fixedpath"
                    + " FROM pg_proc WHERE oid = ?::regprocedure", function);
            assertEquals(Map.of("volatility", "i", "proisstrict", true, "parallel", "s", "sqlbody", true,
                    "fixedpath", true), row, function);
        }
        assertEquals("s", jdbc.queryForObject(
                "SELECT provolatile::text FROM pg_proc WHERE oid = 'extensions.unaccent(text)'::regprocedure", String.class),
                "unaccent(text) es STABLE: no se podría indexar directamente");
    }

    /**
     * Con el {@code search_path} vacío (como el que usan VACUUM y ANALYZE desde
     * PostgreSQL 17) las funciones siguen funcionando: no buscan nada por nombre
     * al ejecutarse. Sin {@code BEGIN ATOMIC} (con un cuerpo entre comillas que
     * llamase a {@code unaccent} sin cualificar) fallaría con
     * "function unaccent does not exist".
     */
    @Test
    void lasFuncionesNoDependenDelSearchPath() {
        List<String> results = jdbc.execute((ConnectionCallback<List<String>>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET search_path = ''");
                try (ResultSet rs = statement.executeQuery("SELECT public.streambox_unaccent(lower('Pokémon ÉLITE')),"
                        + " public.streambox_word_similarity('matirx', 'matrix')::text")) {
                    rs.next();
                    return List.of(rs.getString(1), rs.getString(2));
                } finally {
                    statement.execute("RESET search_path");
                }
            }
        });

        assertEquals(List.of("pokemon elite", "0.42857143"), results);
    }

    /**
     * La función depende de la extensión (lo registra {@code BEGIN ATOMIC}):
     * {@code DROP EXTENSION unaccent} falla en lugar de dejar los índices
     * apuntando a una función rota. Se ejecuta en una transacción que se
     * deshace siempre, por si acaso.
     */
    @Test
    void noSePuedeBorrarLaExtensionMientrasLaUsenLosIndices() {
        String sqlState = transactionTemplate.execute(status -> {
            status.setRollbackOnly();
            try {
                jdbc.execute("DROP EXTENSION unaccent");
                return null;
            } catch (DataAccessException e) {
                return sqlState(e);
            }
        });

        assertEquals("2BP01", sqlState, "dependent_objects_still_exist");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'unaccent'", Integer.class));
    }

    /**
     * Índices GIN de trigramas sobre EXACTAMENTE la expresión que genera
     * {@code TitleSearch} ({@code streambox_unaccent(lower(title))}): si la
     * consulta normalizase de otra forma, PostgreSQL no los usaría.
     */
    @Test
    void losIndicesSonGinDeTrigramasSobreLaExpresionDeLaBusqueda() {
        for (String table : List.of("movies", "series")) {
            assertEquals("CREATE INDEX idx_" + table + "_title_search ON public." + table
                    + " USING gin (streambox_unaccent(lower((title)::text)) extensions.gin_trgm_ops)",
                    jdbc.queryForObject("SELECT pg_get_indexdef(?::regclass)", String.class,
                            "public.idx_" + table + "_title_search"));
        }
    }

    // ------------------------------------------------------------------
    // El plan de ejecución: la consulta real de Hibernate usa el índice
    // ------------------------------------------------------------------

    /**
     * Con un catálogo de 2.000 películas (tras {@code VACUUM ANALYZE}, como lo
     * dejaría autovacuum), la consulta que genera Hibernate para la búsqueda usa
     * el índice GIN y no recorre la tabla. Se mide con los contadores de
     * {@code pg_stat_get_xact_numscans}, así que se comprueba la consulta de verdad (con
     * sus parámetros), no una copia escrita a mano.
     */
    @Test
    void laBusquedaDePeliculasUsaElIndiceGin() {
        insertFiller("movies", 2_000);
        saveMovie("Mañana", 2000);
        vacuumAnalyze("movies");

        List<String> found = searchCountingIndexScans("idx_movies_title_search", () ->
                movieService.searchMovies("manana", null, null, PageRequest.of(0, 10, Sort.by("title", "id")))
                        .content().stream().map(MovieResponse::title).toList());

        assertEquals(List.of("Mañana"), found);
    }

    /** Lo mismo para las series: en el panel y en la búsqueda pública (con su {@code EXISTS}). */
    @Test
    void laBusquedaDeSeriesUsaElIndiceGin() {
        insertFiller("series", 2_000);
        saveEpisode(saveSeries("Pokémon: la serie", 2000), 1, 1);
        vacuumAnalyze("series");

        assertEquals(List.of("Pokémon: la serie"), searchCountingIndexScans("idx_series_title_search", () ->
                seriesService.getAdminSeries("pokemon", PageRequest.of(0, 10, Sort.by("title", "id")))
                        .content().stream().map(SeriesResponse::title).toList()));
        assertEquals(List.of("Pokémon: la serie"), searchCountingIndexScans("idx_series_title_search", () ->
                seriesService.searchSeries("pokemon", null, null, PageRequest.of(0, 10, Sort.by("title", "id")))
                        .content().stream().map(SeriesResponse::title).toList()));
    }

    // ------------------------------------------------------------------
    // Sin acentos (endpoints)
    // ------------------------------------------------------------------

    /**
     * Cada búsqueda encuentra su título y solo ese, con el {@code unaccent} y el
     * {@code lower()} reales de PostgreSQL. Incluye las letras que ya dieron un
     * bug con {@code lower()} ({@code İ}, sigma final): se siguen encontrando
     * buscándolas enteras porque los dos lados se normalizan en la base de datos.
     */
    @ParameterizedTest(name = "\"{0}\" encuentra \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "manana          | Mañana",
            "MANANA          | Mañana",
            "pokemon         | Pokémon: la película",
            "elite           | ÉLITE",
            "nono            | Ñoño",
            "strasse         | Straße",
            "aeon            | Æon",
            "istanbul        | İstanbul",
            "İstanbul        | İstanbul",
            "ΟΔΥΣΣΕΥΣ        | ΟΔΥΣΣΕΥΣ",
            "οδυσσευσ        | ΟΔΥΣΣΕΥΣ" })
    void laBusquedaDePeliculasIgnoraLosAcentosEnPostgresql(String text, String expected) throws Exception {
        for (String title : List.of("Mañana", "Pokémon: la película", "ÉLITE", "Ñoño", "Straße", "Æon", "İstanbul",
                "ΟΔΥΣΣΕΥΣ", "Otra cosa")) {
            saveMovie(title, 2000);
        }

        assertEquals(List.of(expected), titles(call(MOVIE_SEARCH, userToken, text)));
    }

    /** Series: búsqueda pública (solo las que tienen episodios) y panel (todas). */
    @Test
    void laBusquedaDeSeriesIgnoraLosAcentosEnPostgresql() throws Exception {
        saveEpisode(saveSeries("Pokémon: la serie", 2000), 1, 1);
        saveSeries("Pokémon vacía", 2001);

        assertEquals(List.of("Pokémon: la serie"), titles(call(SERIES_SEARCH, userToken, "POKEMON")));
        assertEquals(List.of("Pokémon vacía"), titles(call(ADMIN_SERIES, adminToken, "pokemon vacia")));
    }

    /**
     * {@code unaccent} convierte {@code ％ ＿ ＼} en {@code % _ \}: escapar en la
     * base de datos DESPUÉS de normalizar los mantiene literales. Con el orden
     * contrario {@code ＿} encontraría todos los títulos.
     */
    @ParameterizedTest(name = "\"{0}\" es texto literal")
    @CsvSource(delimiter = '|', value = { "＿ | a_b", "％ | 100%", "＼ | C:\\pelis", "_ | a_b", "% | 100%" })
    void losComodinesTambienLosDeAnchoCompletoSonTextoLiteral(String text, String expected) throws Exception {
        for (String title : List.of("a_b", "100%", "C:\\pelis", "Normal")) {
            saveMovie(title, 2000);
        }

        assertEquals(List.of(expected), titles(call(MOVIE_SEARCH, userToken, text)));
    }

    // ------------------------------------------------------------------
    // Tolerante a erratas (Specification hasSimilarTitle)
    // ------------------------------------------------------------------

    /**
     * Erratas pequeñas (y palabras sueltas) encuentran el título. Los valores
     * de {@code word_similarity} medidos están en {@code TitleSearch}; el más
     * justo es "matirx" (0,43), por eso el umbral es 0,4 y no el 0,6 por
     * defecto de {@code pg_trgm}.
     */
    @ParameterizedTest(name = "\"{0}\" encuentra \"{1}\"")
    @CsvSource(delimiter = '|', value = {
            "interstelar   | Interstellar",
            "matirx        | The Matrix",
            "pokemno       | Pokémon: la película",
            "incepcion     | Inception",
            "starwars      | Star Wars: Episodio IV",
            "senor anillo  | El señor de los anillos" })
    void unaErrataPequenaEncuentraElTitulo(String text, String expected) {
        saveTypoCatalog();

        List<String> found = similarMovies(text, PageRequest.of(0, 20));

        assertTrue(found.contains(expected), text + " -> " + found);
        assertFalse(found.contains("Alien"), "un título sin parecido no aparece: " + found);
    }

    /**
     * Con la página sin orden propio, los resultados salen del más parecido al
     * menos (0,79, 0,67 y 0,42) y el que no se parece lo bastante
     * ({@code Inception}) no sale. Con orden propio, Spring Data lo sustituye.
     * La paginación es estable: el desempate es por {@code id}.
     */
    @Test
    void sinOrdenPropioLosResultadosSalenDelMasParecidoAlMenos() {
        for (String title : List.of("Internacional", "Inception", "Interestelar", "Interstellar")) {
            saveMovie(title, 2000);
        }

        assertEquals(List.of("Interstellar", "Interestelar", "Internacional"),
                similarMovies("interstelar", PageRequest.of(0, 10)));
        assertEquals(List.of("Interestelar", "Internacional", "Interstellar"),
                similarMovies("interstelar", PageRequest.of(0, 10, Sort.by("title", "id"))));

        List<String> paged = new ArrayList<>();
        for (int page = 0; page < 3; page++) {
            Page<Movie> result = movieRepository.findAll(MovieSpecification.hasSimilarTitle("interstelar"),
                    PageRequest.of(page, 1));
            assertEquals(3, result.getTotalElements(), "el recuento no depende del orden");
            paged.addAll(result.map(Movie::getTitle).getContent());
        }
        assertEquals(List.of("Interstellar", "Interestelar", "Internacional"), paged);
    }

    /**
     * Textos cortos: sin tolerancia. "amro" tiene un parecido de 0,4 con "Amor",
     * "Amadeus" y "Amélie" (solo comparten "am"), que llegaría al umbral: por eso
     * se exige un mínimo de 5 letras. Con 1–2 letras, todo se parecería.
     */
    @ParameterizedTest
    @ValueSource(strings = { "amro", "ma", "a", "a b c d", "!!!!!!" })
    void unaBusquedaCortaNoDevuelveTitulosParecidos(String text) {
        for (String title : List.of("Amor", "Amadeus", "Amélie", "Matrix", "Alien")) {
            saveMovie(title, 2000);
        }

        assertEquals(List.of(), similarMovies(text, PageRequest.of(0, 20)));
        assertEquals(0.4f, jdbc.queryForObject("SELECT streambox_word_similarity('amro', 'amor')", Float.class),
                "sin el mínimo de letras, 'amro' llegaría justo al umbral");
    }

    /**
     * Por qué la tolerancia es un SEGUNDO intento: sumada a la búsqueda exacta,
     * buscar "matrix" bien escrito traería también "Matilda" (comparten
     * "mat"). La búsqueda exacta solo encuentra Matrix.
     */
    @Test
    void laToleranciaSumadaALaBusquedaExactaMeteRuido() {
        saveTypoCatalog();

        assertEquals(List.of("The Matrix"), movieRepository.findAll(MovieSpecification.hasTitle("matrix"))
                .stream().map(Movie::getTitle).toList());
        assertTrue(similarMovies("matrix", PageRequest.of(0, 20)).contains("Matilda"));
    }

    /**
     * Series: la misma tolerancia, combinada con {@code hasEpisodes} en la
     * búsqueda pública: una serie vacía parecida no aparece.
     */
    @Test
    void laToleranciaEnSeriesRespetaLaVisibilidad() {
        saveEpisode(saveSeries("Interstellar: la serie", 2014), 1, 1);
        saveSeries("Interstellar vacía", 2015);

        Page<Series> visible = seriesRepository.findAll(SeriesSpecification.hasSimilarTitle("interstelar")
                .and(SeriesSpecification.hasEpisodes()), PageRequest.of(0, 10));
        Page<Series> all = seriesRepository.findAll(SeriesSpecification.hasSimilarTitle("interstelar"),
                PageRequest.of(0, 10));

        assertEquals(List.of("Interstellar: la serie"), visible.map(Series::getTitle).getContent());
        assertEquals(2, all.getTotalElements());
    }

    // ------------------------------------------------------------------
    // El texto del usuario va como parámetro, no como literal SQL
    // ------------------------------------------------------------------

    /**
     * Segunda barrera tras el 400 del controlador: si un NUL ({@code U+0000})
     * llegara a la consulta (un uso futuro de la {@code Specification} sin
     * pasar por el controlador), la consulta falla con un error de
     * <b>datos</b> ({@code 22021}, el valor no es UTF-8 válido para
     * PostgreSQL) y <b>la conexión sigue sana</b>: tras volver a un punto de
     * guardado ({@code SAVEPOINT} en SQL: el gestor de transacciones de JPA no
     * los ofrece), la misma sesión ({@code pg_backend_pid}) sigue respondiendo.
     *
     * <p>
     * Con el texto como literal ({@code criteriaBuilder.literal}) el NUL iba
     * dentro de la propia sentencia, que el protocolo de PostgreSQL termina
     * con NUL: el servidor respondía {@code insufficient data left in message}
     * ({@code 08P01}, violación de protocolo, de la clase {@code 08}
     * "conexión") y Hikari descartaba la conexión, así que cada petición con
     * {@code %00} costaba una conexión del pool. Como parámetro, el valor
     * viaja aparte, con su longitud, y el servidor lo rechaza como un valor
     * inválido más. Se prueban los dos predicados que llevan el texto:
     * "contiene" y "se parece".
     * </p>
     */
    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void unNulQueLlegaraALaConsultaNoEstropeaLaConexion(boolean similar) {
        String text = "interstelar\u0000";

        transactionTemplate.executeWithoutResult(status -> {
            status.setRollbackOnly();
            Integer backendBefore = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
            jdbc.execute("SAVEPOINT antes_del_nul");

            RuntimeException error = assertThrows(RuntimeException.class,
                    () -> movieRepository.findAll(similar
                            ? MovieSpecification.hasSimilarTitle(text)
                            : MovieSpecification.hasTitle(text)));

            SQLException driverError = sqlException(error);
            assertEquals("22021", driverError.getSQLState(),
                    "error de datos, no de conexión: " + driverError.getMessage());

            jdbc.execute("ROLLBACK TO SAVEPOINT antes_del_nul");
            assertEquals(backendBefore, jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class),
                    "la misma conexión sigue respondiendo");
        });
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** La excepción del driver (la {@code SQLException} más profunda de la cadena de causas). */
    private static SQLException sqlException(Throwable error) {
        SQLException found = null;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                found = sql;
            }
        }
        assertTrue(found != null, "sin SQLException en la cadena: " + error);
        return found;
    }

    private void saveTypoCatalog() {
        for (String title : List.of("Interstellar", "The Matrix", "Matilda", "Pokémon: la película", "Inception",
                "Star Wars: Episodio IV", "El señor de los anillos", "Alien")) {
            saveMovie(title, 2000);
        }
    }

    private List<String> similarMovies(String text, Pageable pageable) {
        return movieRepository.findAll(MovieSpecification.hasSimilarTitle(text), pageable)
                .map(Movie::getTitle).getContent();
    }

    /**
     * Ejecuta la búsqueda dentro de una transacción y cuenta, en esa misma
     * conexión ({@code JdbcTemplate} se une a la transacción de JPA), cuántas
     * veces se leyó el índice y cuántas se recorrió la tabla entera: la
     * diferencia de {@code pg_stat_get_xact_numscans} antes y después de la
     * búsqueda. Esos contadores son los aún no publicados de la conexión (pueden
     * incluir transacciones anteriores, por eso se resta) y no se publican a
     * mitad de una transacción: el resultado es determinista, sin esperas.
     */
    private List<String> searchCountingIndexScans(String index, Supplier<List<String>> search) {
        String table = index.contains("movies") ? "public.movies" : "public.series";
        String indexName = "public." + index;
        return transactionTemplate.execute(status -> {
            long indexBefore = scans(indexName);
            long tableBefore = scans(table);

            List<String> found = search.get();

            assertTrue(scans(indexName) > indexBefore, "la búsqueda no usó " + index);
            assertEquals(tableBefore, scans(table), "la búsqueda recorrió " + table + " entera");
            return found;
        });
    }

    /** Lecturas del índice (o recorridos completos, si es una tabla) pendientes en esta conexión. */
    private long scans(String relation) {
        return jdbc.queryForObject("SELECT pg_stat_get_xact_numscans(?::regclass)", Long.class, relation);
    }

    /**
     * {@code VACUUM ANALYZE}, lo que hace autovacuum en una base en uso. No basta
     * {@code ANALYZE}: recién insertadas, las filas están en la lista de
     * pendientes del índice GIN ({@code fastupdate}), que PostgreSQL debe leer
     * entera en cada búsqueda, y el planificador (con razón) prefiere recorrer
     * la tabla. {@code VACUUM} las pasa al índice y actualiza sus estadísticas.
     * No se puede ejecutar dentro de una transacción: aquí va en autocommit.
     */
    private void vacuumAnalyze(String table) {
        jdbc.execute("VACUUM ANALYZE " + table);
    }

    /** Inserta filas de relleno directamente (miles de entidades por JPA serían lentas). */
    private void insertFiller(String table, int rows) {
        String sql = "movies".equals(table)
                ? "INSERT INTO movies (title, description, duration, release_year, image_url, video_url, created_at)"
                        + " VALUES (?, 'd', 100, 2000, 'i', 'v', now())"
                : "INSERT INTO series (title, description, release_year, image_url, created_at)"
                        + " VALUES (?, 'd', 2000, 'i', now())";
        jdbc.batchUpdate(sql, IntStream.rangeClosed(1, rows)
                .mapToObj(i -> new Object[] { "Título de relleno número " + i }).toList());
    }

    private JsonNode call(String endpoint, String token, String title) throws Exception {
        MockHttpServletRequestBuilder request = get(endpoint).header("Authorization", token)
                .param("title", title).param("size", "100");
        return objectMapper.readTree(mockMvc.perform(request).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static List<String> titles(JsonNode page) {
        List<String> titles = new ArrayList<>();
        page.get("content").forEach(item -> titles.add(item.get("title").asText()));
        return titles;
    }

    /** Las filas de relleno se borran con SQL (las cascadas de V1/V3 limpian el resto). */
    private void cleanUp() {
        jdbc.update("DELETE FROM series");
        jdbc.update("DELETE FROM movies");
        cleanDatabase();
    }
}
