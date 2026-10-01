package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

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
import com.emilio.streambox.service.MovieService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import jakarta.persistence.EntityManagerFactory;

/**
 * Tests del catálogo con datos confirmados de verdad (sin {@code @Transactional}):
 * número de consultas por página, filtros de búsqueda, orden estable entre
 * páginas y formato de las fechas.
 *
 * <p>
 * Se activan las estadísticas de Hibernate para contar las sentencias SQL que
 * ejecuta cada operación y detectar el problema N+1 (una consulta extra por
 * cada película).
 * </p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CatalogIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private MovieService movieService;
    @Autowired private MovieRepository movieRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private String userToken;
    private String adminToken;
    private Genre action;
    private Genre scifi;
    private Genre drama;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        userToken = "Bearer " + jwtService.generateToken(saveUser("catuser", Role.USER));
        adminToken = "Bearer " + jwtService.generateToken(saveUser("catadmin", Role.ADMIN));

        action = saveGenre("Action");
        scifi = saveGenre("Scifi");
        drama = saveGenre("Drama");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // --- Consultas por página (N+1) ---

    @Test
    void unaPaginaDeCatalogoNoEjecutaUnaConsultaPorPelicula() {
        for (int i = 0; i < 30; i++) {
            saveMovie(String.format("Peli %02d", i), 2000, action, scifi, drama);
        }
        Statistics statistics = statistics();

        long small = statementsFor(() -> movieService.getMovies(page(0, 5)));
        long large = statementsFor(() -> movieService.getMovies(page(0, 25)));

        // Página + total + géneros por lotes. Lo importante: no crece con
        // el número de películas de la página (5 frente a 25).
        assertTrue(small <= 3, "página de 5 películas: " + small + " consultas");
        assertEquals(small, large, "el número de consultas no debe depender del tamaño de página");
        assertTrue(statistics.getPrepareStatementCount() > 0);
    }

    @Test
    void laBusquedaConFiltrosTampocoEjecutaUnaConsultaPorPelicula() {
        for (int i = 0; i < 30; i++) {
            saveMovie(String.format("Busqueda %02d", i), 2010, action, scifi);
        }
        statistics();

        long statements = statementsFor(() ->
                movieService.searchMovies("busqueda", action.getId(), 2010, page(0, 20)));

        assertTrue(statements <= 3, "búsqueda con 20 resultados: " + statements + " consultas");
    }

    @Test
    void elDtoYaTraeLosGenerosSinNecesitarSesionAbierta() {
        saveMovie("Con generos", 2000, drama, action);

        // Este test no tiene transacción: si la conversión a DTO se hiciera
        // fuera del servicio, leer los géneros lanzaría LazyInitializationException.
        MovieResponse response = movieService.getMovies(page(0, 10)).getContent().get(0);

        assertEquals(2, response.genres().size());
    }

    // --- Filtros ---

    @Test
    void filtrarPorGeneroNoDuplicaPeliculasConVariosGenerosNiRompeLaPaginacion() throws Exception {
        for (int i = 0; i < 12; i++) {
            saveMovie(String.format("Multi %02d", i), 2000 + i, action, scifi, drama);
        }
        saveMovie("Solo drama", 2000, drama);

        MoviePageResponse first = search("genreId=" + action.getId() + "&size=5&page=0");

        assertEquals(12, first.totalElements());
        assertEquals(3, first.totalPages());

        Set<Long> ids = new HashSet<>();
        for (int p = 0; p < 3; p++) {
            for (MovieResponse movie : search("genreId=" + action.getId() + "&size=5&page=" + p).content()) {
                assertTrue(ids.add(movie.id()), "película repetida entre páginas: " + movie.title());
            }
        }
        assertEquals(12, ids.size());
    }

    @Test
    void losFiltrosSeCombinan() throws Exception {
        saveMovie("Alien", 1979, scifi);
        saveMovie("Aliens", 1986, scifi, action);
        saveMovie("Alien Drama", 1986, drama);

        MoviePageResponse result = search("title=alien&genreId=" + scifi.getId() + "&releaseYear=1986");

        assertEquals(1, result.totalElements());
        assertEquals("Aliens", result.content().get(0).title());
    }

    @Test
    void losComodinesDeLikeSeTratanComoTextoLiteral() throws Exception {
        saveMovie("100% Real", 2000, drama);
        saveMovie("Normal", 2000, drama);
        saveMovie("Con_guion", 2000, drama);

        assertEquals(1, search("title=%").totalElements());      // "%"
        assertEquals("100% Real", search("title=%").content().get(0).title());
        assertEquals(1, search("title=_").totalElements());        // "_"
        assertEquals("Con_guion", search("title=_").content().get(0).title());
        assertEquals(0, search("title=\\").totalElements());       // "\"
    }

    @Test
    void laBusquedaDeTituloNoDistingueMayusculas() throws Exception {
        saveMovie("Blade Runner", 1982, scifi);

        assertEquals(1, search("title=BLADE").totalElements());
        assertEquals(1, search("title=runner").totalElements());
    }

    // --- Orden estable ---

    @Test
    void ordenarPorUnCampoConEmpatesNoRepiteNiPierdePeliculasEntrePaginas() throws Exception {
        for (int i = 0; i < 25; i++) {
            saveMovie("Misma fecha " + i, 2020, drama); // todas con el mismo año
        }

        Set<Long> ids = new HashSet<>();
        for (int p = 0; p < 4; p++) {
            for (MovieResponse movie : search("sort=releaseYear&size=7&page=" + p).content()) {
                assertTrue(ids.add(movie.id()), "película repetida entre páginas: " + movie.title());
            }
        }
        assertEquals(25, ids.size());
    }

    // --- Dirección de ordenación (parámetro direction) ---

    @Test
    void sinDireccionElOrdenSigueSiendoAscendente() throws Exception {
        saveMovie("B", 2005, drama);
        saveMovie("A", 1999, drama);
        saveMovie("C", 2020, drama);

        assertEquals(List.of(1999, 2005, 2020), years(list("sort=releaseYear")));
        assertEquals(List.of(1999, 2005, 2020), years(search("sort=releaseYear")));
        assertEquals(List.of(1999, 2005, 2020), years(list("sort=releaseYear&direction=asc")));
    }

    @Test
    void ordenarDescendentePorAnioDeEstreno() throws Exception {
        saveMovie("B", 2005, drama);
        saveMovie("A", 1999, drama);
        saveMovie("C", 2020, drama);

        assertEquals(List.of(2020, 2005, 1999), years(list("sort=releaseYear&direction=desc")));
        assertEquals(List.of(2020, 2005, 1999), years(search("sort=releaseYear&direction=desc")));
    }

    @Test
    void ordenarDescendentePorFechaDeCreacionDevuelveLasMasRecientesPrimero() throws Exception {
        List<Long> insertionOrder = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            insertionOrder.add(saveMovie("Reciente " + i, 2000, drama).getId());
        }
        List<Long> newestFirst = new java.util.ArrayList<>(insertionOrder);
        java.util.Collections.reverse(newestFirst);

        assertEquals(newestFirst, ids(list("sort=createdAt&direction=desc")));
        assertEquals(newestFirst, ids(search("sort=createdAt&direction=desc")));
        // Sin dirección: ascendente, es decir, el orden de inserción
        assertEquals(insertionOrder, ids(list("sort=createdAt")));
    }

    @Test
    void laDireccionNoDistingueMayusculas() throws Exception {
        saveMovie("B", 2005, drama);
        saveMovie("A", 1999, drama);

        assertEquals(List.of(2005, 1999), years(list("sort=releaseYear&direction=DESC")));
        assertEquals(List.of(2005, 1999), years(search("sort=releaseYear&direction=Desc")));
        assertEquals(List.of(1999, 2005), years(list("sort=releaseYear&direction=ASC")));
    }

    @Test
    void ordenDescendenteConEmpatesNoRepiteNiPierdePeliculasEntrePaginas() throws Exception {
        for (int i = 0; i < 25; i++) {
            saveMovie("Misma fecha " + i, 2020, drama); // todas con el mismo año
        }

        for (String endpoint : new String[] { "list", "search" }) {
            List<Long> seen = new java.util.ArrayList<>();
            for (int p = 0; p < 4; p++) {
                String query = "sort=releaseYear&direction=desc&size=7&page=" + p;
                var page = "list".equals(endpoint) ? list(query) : search(query);
                seen.addAll(ids(page));
            }
            assertEquals(25, new HashSet<>(seen).size(), "hay repetidas o faltan películas en " + endpoint);
            // El desempate sigue la dirección: con el año empatado, id descendente
            List<Long> expected = new java.util.ArrayList<>(seen);
            expected.sort(java.util.Comparator.reverseOrder());
            assertEquals(expected, seen, "el desempate por id debe ser descendente en " + endpoint);
        }
    }

    @Test
    void ordenarPorIdDescendenteFunciona() throws Exception {
        Long first = saveMovie("Primera", 2000, drama).getId();
        Long second = saveMovie("Segunda", 2000, drama).getId();

        assertEquals(List.of(second, first), ids(list("sort=id&direction=desc")));
    }

    @Test
    void elOrdenDescendenteTampocoEjecutaUnaConsultaPorPelicula() throws Exception {
        for (int i = 0; i < 30; i++) {
            saveMovie(String.format("Desc %02d", i), 2000 + i, action, scifi, drama);
        }
        statistics();

        long small = statementsFor(() -> movieService.getMovies(
                PageRequest.of(0, 5, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")))));
        long large = statementsFor(() -> movieService.getMovies(
                PageRequest.of(0, 25, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")))));
        assertEquals(small, large, "el número de consultas no debe depender del tamaño de página");

        // Y a través del endpoint, con el mismo coste para 5 que para 25 películas
        long viaEndpointSmall = statementsFor(() -> callQuietly(() -> list("sort=createdAt&direction=desc&size=5")));
        long viaEndpointLarge = statementsFor(() -> callQuietly(() -> list("sort=createdAt&direction=desc&size=25")));
        assertEquals(viaEndpointSmall, viaEndpointLarge);
        assertTrue(viaEndpointSmall <= 12, "consultas del endpoint (incluye autenticación): " + viaEndpointSmall);
    }

    // --- Formato de la respuesta ---

    @Test
    void losGenerosVienenOrdenadosPorNombreYLaFechaEsUnInstanteIso() throws Exception {
        Movie movie = saveMovie("Orden", 2000, scifi, drama, action);

        mockMvc.perform(get("/api/movies/" + movie.getId()).header("Authorization", userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.genres[0].name").value("Action"))
                .andExpect(jsonPath("$.genres[1].name").value("Drama"))
                .andExpect(jsonPath("$.genres[2].name").value("Scifi"))
                // Instante ISO-8601 en UTC, por ejemplo 2026-10-01T16:30:00.123Z
                .andExpect(jsonPath("$.createdAt").value(
                        org.hamcrest.Matchers.matchesPattern("\\d{4}-\\d{2}-\\d{2}T.+Z")));
    }

    @Test
    void laFechaDeCreacionLaAsignaHibernateYNoCambiaAlModificar() throws Exception {
        Movie movie = saveMovie("Fecha", 2000, drama);
        assertTrue(movie.getCreatedAt() != null, "createdAt debe asignarse al guardar");
        var created = movieRepository.findById(movie.getId()).orElseThrow().getCreatedAt();

        mockMvc.perform(put("/api/movies/" + movie.getId())
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(movieBody("Fecha editada", drama.getId()))))
                .andExpect(status().isOk());

        var afterUpdate = movieRepository.findById(movie.getId()).orElseThrow().getCreatedAt();
        assertEquals(created, afterUpdate);
    }

    // --- Modificación y géneros ---

    @Test
    void modificarUnaPeliculaSustituyeSusGeneros() throws Exception {
        Movie movie = saveMovie("Cambio", 2000, action, drama);

        mockMvc.perform(put("/api/movies/" + movie.getId())
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(movieBody("Cambio", scifi.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.genres.length()").value(1))
                .andExpect(jsonPath("$.genres[0].name").value("Scifi"));

        mockMvc.perform(get("/api/movies/" + movie.getId()).header("Authorization", userToken))
                .andExpect(jsonPath("$.genres.length()").value(1))
                .andExpect(jsonPath("$.genres[0].name").value("Scifi"));
    }

    @Test
    void ungeneroInexistenteIndicaElDeMenorIdYNoModificaNada() throws Exception {
        Movie movie = saveMovie("Intacta", 2000, action);

        var body = movieBody("Nuevo titulo", action.getId());
        body.put("genreIds", List.of(action.getId(), 987654L, 987653L));

        mockMvc.perform(put("/api/movies/" + movie.getId())
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Género no encontrado: 987653"));

        // La transacción se revirtió: el título no cambió
        assertEquals("Intacta", movieRepository.findById(movie.getId()).orElseThrow().getTitle());
    }

    // --- Utilidades ---

    /**
     * Llama a {@code /api/movies/search} con parámetros {@code clave=valor}
     * separados por {@code &}. Los valores se pasan sin codificar (con
     * {@code .param}) para que caracteres como {@code %} lleguen tal cual al
     * servidor; si fueran parte de la URL, MockMvc volvería a codificarlos.
     */
    private MoviePageResponse search(String query) throws Exception {
        var request = get("/api/movies/search").header("Authorization", userToken);
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            request.param(pair.substring(0, separator), pair.substring(separator + 1));
        }
        String json = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(json, MoviePageResponse.class);
    }

    /** Igual que {@link #search(String)} pero sobre {@code /api/movies}. */
    private MoviePageResponse list(String query) throws Exception {
        var request = get("/api/movies").header("Authorization", userToken);
        for (String pair : query.split("&")) {
            int separator = pair.indexOf('=');
            request.param(pair.substring(0, separator), pair.substring(separator + 1));
        }
        String json = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(json, MoviePageResponse.class);
    }

    private static List<Long> ids(MoviePageResponse page) {
        return page.content().stream().map(MovieResponse::id).toList();
    }

    private static List<Integer> years(MoviePageResponse page) {
        return page.content().stream().map(MovieResponse::releaseYear).toList();
    }

    /** Ejecuta una llamada que lanza excepciones comprobadas dentro de un {@link Runnable}. */
    private static void callQuietly(ThrowingCall call) {
        try {
            call.run();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws Exception;
    }

    private static PageRequest page(int page, int size) {
        return PageRequest.of(page, size, Sort.by("title").ascending().and(Sort.by("id")));
    }

    private Statistics statistics() {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        return statistics;
    }

    private long statementsFor(Runnable operation) {
        Statistics statistics = statistics();
        operation.run();
        return statistics.getPrepareStatementCount();
    }

    private Map<String, Object> movieBody(String title, Long genreId) {
        return new java.util.HashMap<>(Map.of(
                "title", title,
                "description", "Descripción",
                "duration", 100,
                "releaseYear", 2000,
                "imageUrl", "https://example.com/i.jpg",
                "videoUrl", "https://example.com/v.mp4",
                "genreIds", List.of(genreId)));
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
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setDescription("Descripción de " + title);
        movie.setDuration(100);
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
