package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Collections;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.emilio.streambox.dto.SeriesDetailResponse;
import com.emilio.streambox.dto.SeriesPageResponse;
import com.emilio.streambox.dto.SeriesResponse;
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
import com.emilio.streambox.service.SeriesService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import jakarta.persistence.EntityManagerFactory;

/**
 * Tests de las lecturas del catálogo de series ({@code /api/series},
 * {@code /api/series/search}, {@code /api/series/{id}}) y de las vistas de
 * gestión ({@code /api/admin/series}), con datos confirmados de verdad (sin
 * {@code @Transactional}).
 *
 * <p>
 * Comprueban sobre todo tres cosas:
 * </p>
 * <ul>
 * <li><b>Visibilidad:</b> una serie sin episodios no aparece en el listado ni
 * en la búsqueda y su detalle da 404, pero el panel sí la ve.</li>
 * <li><b>Coste en consultas</b> (estadísticas de Hibernate, como
 * {@link CatalogIntegrationTest}, con las mismas propiedades para compartir su
 * contexto de Spring): una página de series no ejecuta una consulta por serie
 * ni para los géneros ni para los recuentos de temporadas y episodios, y el
 * detalle cuesta dos consultas.</li>
 * <li><b>Paginación y orden:</b> las mismas reglas que películas (lista
 * blanca, dirección, desempate por id, límites de página).</li>
 * </ul>
 *
 * <p>
 * Limpieza: las series se borran <em>antes</em> que los géneros, porque
 * {@code series_genres} no tiene cascada hacia {@code genres}.
 * </p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SeriesCatalogIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private SeriesService seriesService;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private String userToken;
    private String adminToken;
    private Genre drama;
    private Genre comedy;
    private Genre scifi;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        userToken = "Bearer " + jwtService.generateToken(saveUser("seriescatuser", Role.USER));
        adminToken = "Bearer " + jwtService.generateToken(saveUser("seriescatadmin", Role.ADMIN));

        drama = saveGenre("Drama");
        comedy = saveGenre("Comedia");
        scifi = saveGenre("Scifi");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Visibilidad: las series sin episodios se ocultan a los usuarios
    // ------------------------------------------------------------------

    @Test
    void elListadoSoloDevuelveLasSeriesConEpisodios() throws Exception {
        Series visible = saveSeries("Con episodios", 2015, drama);
        saveEpisode(visible, 1, 1);
        saveSeries("Vacia", 2015, drama);

        SeriesPageResponse page = list("/api/series", userToken, "");

        assertEquals(1, page.totalElements());
        assertEquals("Con episodios", page.content().get(0).title());
    }

    @Test
    void laBusquedaTampocoDevuelveSeriesVacias() throws Exception {
        Series visible = saveSeries("Dark", 2017, scifi);
        saveEpisode(visible, 1, 1);
        saveSeries("Dark vacia", 2017, scifi);

        assertEquals(List.of("Dark"), titles(list("/api/series/search", userToken, "title=dark")));
        assertEquals(List.of("Dark"), titles(list("/api/series/search", userToken, "genreId=" + scifi.getId())));
        assertEquals(List.of("Dark"), titles(list("/api/series/search", userToken, "releaseYear=2017")));
        assertEquals(List.of("Dark"), titles(list("/api/series/search", userToken, "")));
    }

    @Test
    void elDetalleDeUnaSerieVaciaDa404ComoSiNoExistiera() throws Exception {
        Series empty = saveSeries("Vacia", 2015, drama);

        String vacia = mockMvc.perform(get("/api/series/" + empty.getId()).header("Authorization", userToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Serie no encontrada"))
                .andReturn().getResponse().getContentAsString();
        String inexistente = mockMvc.perform(get("/api/series/987654").header("Authorization", userToken))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        // Mismo código y mensaje: la respuesta no revela que la serie existe.
        assertEquals(objectMapper.readTree(vacia).get("message"), objectMapper.readTree(inexistente).get("message"));
        assertEquals(objectMapper.readTree(vacia).get("code"), objectMapper.readTree(inexistente).get("code"));
    }

    @Test
    void elPanelVeLasSeriesVaciasConRecuentosACero() throws Exception {
        Series full = saveSeries("Llena", 2015, drama);
        saveEpisode(full, 1, 1);
        saveEpisode(full, 2, 1);
        saveSeries("Vacia", 2016, drama);

        SeriesPageResponse page = list("/api/admin/series", adminToken, "");

        assertEquals(2, page.totalElements());
        SeriesResponse llena = page.content().get(0);
        SeriesResponse vacia = page.content().get(1);
        assertEquals("Llena", llena.title());
        assertEquals(2, llena.seasonCount());
        assertEquals(2, llena.episodeCount());
        assertEquals("Vacia", vacia.title());
        assertEquals(0, vacia.seasonCount());
        assertEquals(0, vacia.episodeCount());
    }

    @Test
    void elPanelFiltraPorTituloYSinTituloDevuelveTodas() throws Exception {
        saveSeries("The Office", 2005, comedy);
        saveSeries("Office Space", 2010, comedy);
        saveSeries("Fargo", 2014, drama);

        assertEquals(List.of("Office Space", "The Office"), titles(list("/api/admin/series", adminToken, "title=OFFICE")));
        assertEquals(3, list("/api/admin/series", adminToken, "title=").totalElements());
        assertEquals(3, list("/api/admin/series", adminToken, "").totalElements());
    }

    @Test
    void elDetalleDelPanelMuestraUnaSerieVaciaConTemporadasVacias() throws Exception {
        Series empty = saveSeries("Vacia", 2015, drama);

        mockMvc.perform(get("/api/admin/series/" + empty.getId()).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Vacia"))
                .andExpect(jsonPath("$.seasons").isArray())
                .andExpect(jsonPath("$.seasons").isEmpty())
                .andExpect(jsonPath("$.seasonCount").value(0))
                .andExpect(jsonPath("$.episodeCount").value(0));

        mockMvc.perform(get("/api/admin/series/987654").header("Authorization", adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void unUsuarioNoPuedeUsarLasVistasDelPanel() throws Exception {
        Series empty = saveSeries("Vacia", 2015, drama);

        mockMvc.perform(get("/api/admin/series").header("Authorization", userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/admin/series/" + empty.getId()).header("Authorization", userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void sinTokenLasLecturasDan401() throws Exception {
        mockMvc.perform(get("/api/series")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/series/search")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/series/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/series")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Forma de la respuesta
    // ------------------------------------------------------------------

    @Test
    void elDetalleAgrupaLosEpisodiosPorTemporadaEnOrden() throws Exception {
        Series series = saveSeries("Ordenada", 2015, scifi, drama, comedy);
        // Desordenados a propósito, con una temporada que falta (la 2) y la 10
        saveEpisode(series, 3, 2);
        saveEpisode(series, 1, 2);
        saveEpisode(series, 10, 1);
        saveEpisode(series, 3, 1);
        saveEpisode(series, 1, 1);

        mockMvc.perform(get("/api/series/" + series.getId()).header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.genres[0].name").value("Comedia"))
                .andExpect(jsonPath("$.genres[1].name").value("Drama"))
                .andExpect(jsonPath("$.genres[2].name").value("Scifi"))
                .andExpect(jsonPath("$.seasonCount").value(3))
                .andExpect(jsonPath("$.episodeCount").value(5))
                .andExpect(jsonPath("$.seasons.length()").value(3))
                .andExpect(jsonPath("$.seasons[0].seasonNumber").value(1))
                .andExpect(jsonPath("$.seasons[0].episodes[0].episodeNumber").value(1))
                .andExpect(jsonPath("$.seasons[0].episodes[1].episodeNumber").value(2))
                .andExpect(jsonPath("$.seasons[1].seasonNumber").value(3))
                .andExpect(jsonPath("$.seasons[1].episodes.length()").value(2))
                .andExpect(jsonPath("$.seasons[2].seasonNumber").value(10))
                .andExpect(jsonPath("$.seasons[0].episodes[0].seasonNumber").value(1))
                .andExpect(jsonPath("$.seasons[0].episodes[0].title").value("Episodio 1x1"))
                .andExpect(jsonPath("$.seasons[0].episodes[0].duration").value(45))
                .andExpect(jsonPath("$.seasons[0].episodes[0].videoUrl").value("https://example.com/episodio.mp4"))
                .andExpect(jsonPath("$.createdAt").value(
                        org.hamcrest.Matchers.matchesPattern("\\d{4}-\\d{2}-\\d{2}T.+Z")));
    }

    /**
     * Los campos opcionales vacíos van como {@code null} explícito (no se
     * omiten): {@code endYear} de una serie en emisión y {@code description}
     * de un episodio sin sinopsis. El frontend los usa así.
     */
    @Test
    void losCamposOpcionalesVaciosVanComoNull() throws Exception {
        Series series = saveSeries("En emision", 2020, drama);
        saveEpisode(series, 1, 1);

        mockMvc.perform(get("/api/series/" + series.getId()).header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endYear").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.seasons[0].episodes[0].description")
                        .value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void elListadoIncluyeLosRecuentosDeCadaSerie() throws Exception {
        Series a = saveSeries("A", 2010, drama);
        saveEpisode(a, 1, 1);
        saveEpisode(a, 1, 2);
        saveEpisode(a, 2, 1);
        Series b = saveSeries("B", 2010, drama);
        saveEpisode(b, 4, 7);

        SeriesPageResponse page = list("/api/series", userToken, "");

        assertEquals(2, page.content().get(0).seasonCount());
        assertEquals(3, page.content().get(0).episodeCount());
        assertEquals(1, page.content().get(1).seasonCount());
        assertEquals(1, page.content().get(1).episodeCount());
    }

    // ------------------------------------------------------------------
    // Búsqueda
    // ------------------------------------------------------------------

    @Test
    void losComodinesDeLikeSeTratanComoTextoLiteral() throws Exception {
        visible("100% Real");
        visible("Normal");
        visible("Con_guion");

        assertEquals(List.of("100% Real"), titles(list("/api/series/search", userToken, "title=%")));
        assertEquals(List.of("Con_guion"), titles(list("/api/series/search", userToken, "title=_")));
        assertEquals(0, list("/api/series/search", userToken, "title=\\").totalElements());
        // El panel usa el mismo filtro
        assertEquals(List.of("100% Real"), titles(list("/api/admin/series", adminToken, "title=%")));
    }

    @Test
    void laBusquedaNoDistingueMayusculasTampocoConLetrasAcentuadas() throws Exception {
        visible("ÉLITE");
        visible("Ñandú salvaje");
        visible("Otra");

        assertEquals(List.of("ÉLITE"), titles(list("/api/series/search", userToken, "title=élite")));
        assertEquals(List.of("Ñandú salvaje"), titles(list("/api/series/search", userToken, "title=ÑANDÚ")));
    }

    @Test
    void losFiltrosSeCombinanYElDeGeneroNoDuplicaSeries() throws Exception {
        Series alien = saveSeries("Alien serie", 1986, scifi, drama, comedy);
        saveEpisode(alien, 1, 1);
        Series other = saveSeries("Alien otra", 1979, scifi);
        saveEpisode(other, 1, 1);
        Series drama1986 = saveSeries("Alien drama", 1986, drama);
        saveEpisode(drama1986, 1, 1);

        SeriesPageResponse result = list("/api/series/search", userToken,
                "title=alien&genreId=" + scifi.getId() + "&releaseYear=1986");
        assertEquals(List.of("Alien serie"), titles(result));

        // Una serie con tres géneros sale una sola vez al filtrar por género
        assertEquals(2, list("/api/series/search", userToken, "genreId=" + drama.getId()).totalElements());
    }

    // ------------------------------------------------------------------
    // Paginación y orden (mismas reglas que películas)
    // ------------------------------------------------------------------

    @Test
    void ordenarDescendentePorAnioYPorFechaDeCreacion() throws Exception {
        List<Long> insertion = new ArrayList<>();
        insertion.add(visible("B", 2005).getId());
        insertion.add(visible("A", 1999).getId());
        insertion.add(visible("C", 2020).getId());
        List<Long> newestFirst = new ArrayList<>(insertion);
        Collections.reverse(newestFirst);

        assertEquals(List.of(2020, 2005, 1999), years(list("/api/series", userToken, "sort=releaseYear&direction=desc")));
        assertEquals(List.of(1999, 2005, 2020), years(list("/api/series", userToken, "sort=releaseYear")));
        assertEquals(newestFirst, ids(list("/api/series", userToken, "sort=createdAt&direction=DESC")));
        assertEquals(newestFirst, ids(list("/api/series/search", userToken, "sort=createdAt&direction=desc")));
        assertEquals(newestFirst, ids(list("/api/admin/series", adminToken, "sort=createdAt&direction=desc")));
        // Por defecto, por título ascendente
        assertEquals(List.of("A", "B", "C"), titles(list("/api/series", userToken, "")));
    }

    @Test
    void conEmpatesElDesempatePorIdNoRepiteNiPierdeSeriesEntrePaginas() throws Exception {
        for (int i = 0; i < 17; i++) {
            visible("Mismo anio " + i, 2020);
        }

        List<Long> seen = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            seen.addAll(ids(list("/api/series", userToken, "sort=releaseYear&direction=desc&size=7&page=" + p)));
        }

        assertEquals(17, new HashSet<>(seen).size());
        List<Long> expected = new ArrayList<>(seen);
        expected.sort(Comparator.reverseOrder());
        assertEquals(expected, seen, "el desempate por id debe seguir la dirección descendente");
    }

    @Test
    void laPaginaIndicaElTotalYSiHayMas() throws Exception {
        for (int i = 0; i < 5; i++) {
            visible("Serie " + i);
        }

        SeriesPageResponse first = list("/api/series", userToken, "size=2&page=0");
        SeriesPageResponse last = list("/api/series", userToken, "size=2&page=2");

        assertEquals(5, first.totalElements());
        assertEquals(3, first.totalPages());
        assertTrue(first.hasNext());
        assertEquals(1, last.content().size());
        assertTrue(last.hasPrevious());
        assertEquals(false, last.hasNext());
    }

    /** {@code duration} se puede usar en películas, pero las series no tienen duración. */
    @ParameterizedTest
    @ValueSource(strings = { "/api/series", "/api/series/search", "/api/admin/series" })
    void unCampoDeOrdenacionFueraDeLaListaBlancaDa400(String url) throws Exception {
        mockMvc.perform(get(url).param("sort", "duration").header("Authorization", tokenFor(url)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.sort").value(
                        "Campo de ordenación no permitido. Valores válidos: createdAt, id, releaseYear, title"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "/api/series", "/api/series/search", "/api/admin/series" })
    void parametrosDePaginacionInvalidosDan400(String url) throws Exception {
        String token = tokenFor(url);

        mockMvc.perform(get(url).param("direction", "arriba").header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.direction").value(
                        "Dirección de ordenación no permitida. Valores válidos: asc, desc"));
        mockMvc.perform(get(url).param("page", "-1").header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.page").value("La página no puede ser negativa"));
        mockMvc.perform(get(url).param("size", "0").header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.size").value("El tamaño mínimo de página es 1"));
        mockMvc.perform(get(url).param("size", "101").header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.size").value("El tamaño máximo de página es 100"));
        mockMvc.perform(get(url).param("page", String.valueOf(Integer.MAX_VALUE)).param("size", "100")
                        .header("Authorization", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.page").value(
                        "La página solicitada es demasiado grande para el tamaño de página indicado"));
    }

    // ------------------------------------------------------------------
    // Consultas por operación (N+1)
    // ------------------------------------------------------------------

    /**
     * Una página de series cuesta lo mismo con 5 que con 25 series: la página,
     * el total, los géneros por lotes y una única consulta agrupada con los
     * recuentos. Si los recuentos se pidieran serie a serie, la página de 25
     * costaría 20 consultas más que la de 5.
     */
    @Test
    void unaPaginaDeSeriesNoEjecutaConsultasPorSerie() {
        for (int i = 0; i < 30; i++) {
            Series series = saveSeries(String.format("Serie %02d", i), 2000, drama, comedy, scifi);
            saveEpisode(series, 1, 1);
            saveEpisode(series, 2, 1);
        }

        long small = statementsFor(() -> seriesService.getSeries(page(0, 5)));
        long large = statementsFor(() -> seriesService.getSeries(page(0, 25)));
        long search = statementsFor(() -> seriesService.searchSeries("serie", drama.getId(), 2000, page(0, 25)));
        long admin = statementsFor(() -> seriesService.getAdminSeries(null, page(0, 25)));

        assertTrue(small <= 4, "página de 5 series: " + small + " consultas");
        assertEquals(small, large, "el número de consultas no debe depender del tamaño de página");
        assertTrue(search <= 4, "búsqueda de 25 series: " + search + " consultas");
        assertTrue(admin <= 4, "panel, 25 series: " + admin + " consultas");
    }

    /** Una página vacía no lanza la consulta de recuentos (no hay ids que pasarle). */
    @Test
    void unaPaginaVaciaNoPideRecuentos() {
        saveSeries("Vacia", 2000, drama);

        long statements = statementsFor(() -> seriesService.getSeries(page(0, 10)));

        // Solo la consulta de la página (el total se deduce sin contar).
        assertEquals(1, statements);
    }

    /**
     * El detalle son dos consultas (serie con géneros y episodios ordenados),
     * tenga los episodios que tenga.
     */
    @Test
    void elDetalleCuestaDosConsultasSinImportarCuantosEpisodiosTenga() {
        Series few = saveSeries("Pocos", 2000, drama, comedy);
        saveEpisode(few, 1, 1);
        Series many = saveSeries("Muchos", 2000, drama, comedy);
        for (int season = 1; season <= 4; season++) {
            for (int number = 1; number <= 10; number++) {
                saveEpisode(many, season, number);
            }
        }

        assertEquals(2, statementsFor(() -> seriesService.getSeriesById(few.getId())));
        assertEquals(2, statementsFor(() -> seriesService.getSeriesById(many.getId())));
        assertEquals(2, statementsFor(() -> seriesService.getAdminSeriesById(many.getId())));
    }

    @Test
    void elDtoYaTraeGenerosYEpisodiosSinNecesitarSesionAbierta() {
        Series series = saveSeries("Sin sesion", 2000, drama, comedy);
        saveEpisode(series, 1, 1);

        // Sin transacción en el test: si el mapeo se hiciera fuera del
        // servicio, leer los géneros lanzaría LazyInitializationException.
        SeriesDetailResponse detail = seriesService.getSeriesById(series.getId());
        SeriesResponse listed = seriesService.getSeries(page(0, 10)).getContent().get(0);

        assertEquals(2, detail.genres().size());
        assertEquals(1, detail.seasons().get(0).episodes().size());
        assertEquals(2, listed.genres().size());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * Llama a un listado con parámetros {@code clave=valor} separados por
     * {@code &}, pasados con {@code .param} para que {@code %} llegue tal cual.
     */
    private SeriesPageResponse list(String url, String token, String query) throws Exception {
        MockHttpServletRequestBuilder request = get(url).header("Authorization", token);
        if (!query.isEmpty()) {
            for (String pair : query.split("&")) {
                int separator = pair.indexOf('=');
                request.param(pair.substring(0, separator), pair.substring(separator + 1));
            }
        }
        String json = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(json, SeriesPageResponse.class);
    }

    private String tokenFor(String url) {
        return url.startsWith("/api/admin") ? adminToken : userToken;
    }

    private static List<String> titles(SeriesPageResponse page) {
        return page.content().stream().map(SeriesResponse::title).toList();
    }

    private static List<Long> ids(SeriesPageResponse page) {
        return page.content().stream().map(SeriesResponse::id).toList();
    }

    private static List<Integer> years(SeriesPageResponse page) {
        return page.content().stream().map(SeriesResponse::releaseYear).toList();
    }

    private static PageRequest page(int page, int size) {
        return PageRequest.of(page, size, Sort.by("title").ascending().and(Sort.by("id")));
    }

    private long statementsFor(Runnable operation) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        operation.run();
        return statistics.getPrepareStatementCount();
    }

    /** Serie visible (con un episodio) del año 2000. */
    private Series visible(String title) {
        return visible(title, 2000);
    }

    private Series visible(String title, int year) {
        Series series = saveSeries(title, year, drama);
        saveEpisode(series, 1, 1);
        return series;
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

    private Series saveSeries(String title, int year, Genre... genres) {
        Series series = new Series();
        series.setTitle(title);
        series.setDescription("Sinopsis de " + title);
        series.setReleaseYear(year);
        series.setImageUrl("https://example.com/serie.jpg");
        series.setGenres(new HashSet<>(Set.of(genres)));
        return seriesRepository.save(series);
    }

    private Episode saveEpisode(Series series, int season, int number) {
        Episode episode = new Episode();
        episode.setSeries(series);
        episode.setSeasonNumber(season);
        episode.setEpisodeNumber(number);
        episode.setTitle("Episodio " + season + "x" + number);
        episode.setDuration(45);
        episode.setVideoUrl("https://example.com/episodio.mp4");
        return episodeRepository.save(episode);
    }

    /** Series primero (sus géneros y episodios caen con ellas), luego el resto. */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
