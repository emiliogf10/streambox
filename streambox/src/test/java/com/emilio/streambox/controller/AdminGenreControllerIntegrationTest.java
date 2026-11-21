package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.emilio.streambox.dto.AdminGenreResponse;
import com.emilio.streambox.entity.Episode;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.emilio.streambox.service.GenreService;

import jakarta.persistence.EntityManagerFactory;

/**
 * Tests de la vista de gestión de géneros ({@code GET /api/admin/genres}).
 *
 * <p>
 * Qué protege: que solo un ADMIN ve los recuentos (incluyen las series sin
 * episodios, ocultas para los usuarios), que esos recuentos son
 * <strong>exactamente</strong> los del 409 {@code GENRE_IN_USE} (si
 * divergieran, el panel desactivaría «Borrar» en un género que sí se puede
 * borrar, o lo dejaría activo en uno que no) y que se obtienen en una sola
 * consulta, tenga el catálogo los géneros que tenga.
 * </p>
 *
 * <p>
 * Sin {@code @Transactional}, con datos confirmados y limpieza antes y después:
 * el 409 deja la transacción del borrado marcada para deshacer y, dentro de
 * la transacción del test, las peticiones siguientes heredarían ese estado.
 * Usa las mismas propiedades que {@code FavoriteGenresIntegrationTest} (las
 * estadísticas de Hibernate, para contar sentencias) y así comparte su
 * contexto de Spring.
 * </p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AdminGenreControllerIntegrationTest {

    private static final String ADMIN_GENRES = "/api/admin/genres";

    @Autowired private MockMvc mockMvc;
    @Autowired private GenreService genreService;
    @Autowired private GenreRepository genreRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private String adminToken;
    private String userToken;

    /** 3 películas y 2 series (una sin episodios). */
    private Genre drama;

    /** 1 película (que también es de Drama). */
    private Genre comedia;

    /** Solo 1 serie, sin episodios: invisible para los usuarios, pero impide el borrado. */
    private Genre western;

    /** Ningún título: el único que se puede borrar. */
    private Genre vacio;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        adminToken = bearer(saveUser("admgenadmin", Role.ADMIN));
        userToken = bearer(saveUser("admgenuser", Role.USER));

        // Creados en un orden distinto del alfabético, para comprobar el orden.
        western = saveGenre("Western");
        drama = saveGenre("Drama");
        vacio = saveGenre("Vacío");
        comedia = saveGenre("Comedia");

        saveMovie("Uno", drama);
        saveMovie("Dos", drama, comedia);
        saveMovie("Tres", drama);
        saveEpisode(saveSeries("Serie con episodios", drama));
        saveSeries("Serie vacía de drama", drama);
        saveSeries("Serie vacía del oeste", western);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Seguridad
    // ------------------------------------------------------------------

    @Test
    void sinTokenDa401() throws Exception {
        mockMvc.perform(get(ADMIN_GENRES))
                .andExpect(status().isUnauthorized());
    }

    /** Un USER no puede ver los recuentos: delatarían las series sin episodios. */
    @Test
    void unUsuarioRecibe403() throws Exception {
        mockMvc.perform(get(ADMIN_GENRES).header("Authorization", userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    /**
     * {@code HEAD} cae en el {@code @GetMapping}: la regla de
     * {@code /api/admin/**} es por ruta (cualquier método), así que también se
     * rechaza.
     */
    @Test
    void unUsuarioRecibe403TambienConHead() throws Exception {
        mockMvc.perform(head(ADMIN_GENRES).header("Authorization", userToken))
                .andExpect(status().isForbidden());
    }

    /** {@code HEAD} sin token tampoco pasa: 401, como el {@code GET}. */
    @Test
    void sinTokenDa401TambienConHead() throws Exception {
        mockMvc.perform(head(ADMIN_GENRES))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Un USER recibe 403 con cualquier método, no 405: la regla de
     * {@code /api/admin/**} se aplica antes de buscar el controlador, así que
     * ni siquiera sabe qué métodos admite la ruta.
     */
    @Test
    void unUsuarioRecibe403ConCualquierMetodo() throws Exception {
        mockMvc.perform(post(ADMIN_GENRES).header("Authorization", userToken)
                        .header("X-Requested-With", "StreamBox")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Terror\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(ADMIN_GENRES + "/{id}", vacio.getId()).header("Authorization", userToken)
                        .header("X-Requested-With", "StreamBox"))
                .andExpect(status().isForbidden());

        assertEquals(4, genreRepository.count());
    }

    /**
     * Degradar a un ADMIN surte efecto con el mismo token: el filtro lee el
     * rol de la base de datos en cada petición, así que el token antiguo ya
     * no ve los recuentos.
     */
    @Test
    void unAdminDegradadoPierdeElAccesoConElMismoToken() throws Exception {
        mockMvc.perform(get(ADMIN_GENRES).header("Authorization", adminToken))
                .andExpect(status().isOk());

        User admin = userRepository.findByEmail("admgenadmin@test.com").orElseThrow();
        admin.setRole(Role.USER);
        userRepository.save(admin);

        mockMvc.perform(get(ADMIN_GENRES).header("Authorization", adminToken))
                .andExpect(status().isForbidden());
    }

    /** La vista es de solo lectura: las escrituras siguen en {@code /api/genres}. */
    @Test
    void noAdmiteEscrituras() throws Exception {
        mockMvc.perform(post(ADMIN_GENRES).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Terror\"}"))
                .andExpect(status().isMethodNotAllowed());

        assertEquals(4, genreRepository.count());
    }

    // ------------------------------------------------------------------
    // Contenido
    // ------------------------------------------------------------------

    /**
     * Todos los géneros, por nombre, con sus recuentos: las series sin
     * episodios cuentan (Western tiene 1), una película con dos géneros cuenta
     * en los dos y un género sin títulos sale con 0 y 0.
     */
    @Test
    void unAdminVeTodosLosGenerosPorNombreConSusRecuentos() throws Exception {
        mockMvc.perform(get(ADMIN_GENRES).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].id").value(comedia.getId()))
                .andExpect(jsonPath("$[0].name").value("Comedia"))
                .andExpect(jsonPath("$[0].movieCount").value(1))
                .andExpect(jsonPath("$[0].seriesCount").value(0))
                .andExpect(jsonPath("$[1].id").value(drama.getId()))
                .andExpect(jsonPath("$[1].name").value("Drama"))
                .andExpect(jsonPath("$[1].movieCount").value(3))
                .andExpect(jsonPath("$[1].seriesCount").value(2))
                .andExpect(jsonPath("$[2].id").value(vacio.getId()))
                .andExpect(jsonPath("$[2].name").value("Vacío"))
                .andExpect(jsonPath("$[2].movieCount").value(0))
                .andExpect(jsonPath("$[2].seriesCount").value(0))
                .andExpect(jsonPath("$[3].id").value(western.getId()))
                .andExpect(jsonPath("$[3].name").value("Western"))
                .andExpect(jsonPath("$[3].movieCount").value(0))
                .andExpect(jsonPath("$[3].seriesCount").value(1));
    }

    @Test
    void sinGenerosDevuelveUnaListaVacia() throws Exception {
        seriesRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();

        mockMvc.perform(get(ADMIN_GENRES).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * Los recuentos son los del 409: cada género en uso se intenta borrar y el
     * mensaje dice exactamente las cifras de la vista; el que tiene 0 y 0 se
     * borra (204). Si la vista y el borrado contaran distinto, el panel
     * desactivaría «Borrar» donde no debe (o al revés).
     */
    @Test
    void losRecuentosCoincidenConLosDelConflictoAlBorrar() throws Exception {
        List<AdminGenreResponse> view = genreService.getAdminGenres();

        expectInUse(find(view, comedia), 1, 0, "lo usa 1 película. Quítalo de esa película");
        expectInUse(find(view, drama), 3, 2, "lo usan 3 películas y 2 series. "
                + "Quítalo de esas películas y de esas series");
        expectInUse(find(view, western), 0, 1, "lo usa 1 serie. Quítalo de esa serie");

        AdminGenreResponse empty = find(view, vacio);
        assertEquals(0, empty.movieCount());
        assertEquals(0, empty.seriesCount());
        mockMvc.perform(delete("/api/genres/{id}", empty.id()).header("Authorization", adminToken))
                .andExpect(status().isNoContent());
    }

    /**
     * Sin N+1: una sola sentencia, tenga el catálogo 4 géneros o 24 (cada
     * género de más no añade consultas).
     */
    @Test
    void seObtieneEnUnaSolaConsulta() {
        assertEquals(1, statementsFor(genreService::getAdminGenres));

        for (int i = 0; i < 20; i++) {
            saveMovie("Película extra " + i, saveGenre("Extra " + i));
        }
        assertEquals(1, statementsFor(genreService::getAdminGenres));
        assertEquals(24, genreService.getAdminGenres().size());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * Comprueba que la vista da {@code movies} y {@code series} y que borrar
     * el género da el 409 con esas mismas cifras. El mensaje se escribe a mano
     * (no se calcula con las cifras) para que el test no repita la lógica del
     * servicio.
     */
    private void expectInUse(AdminGenreResponse genre, long movies, long series, String usage) throws Exception {
        assertEquals(movies, genre.movieCount());
        assertEquals(series, genre.seriesCount());

        mockMvc.perform(delete("/api/genres/{id}", genre.id()).header("Authorization", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENRE_IN_USE"))
                .andExpect(jsonPath("$.message").value("No se puede eliminar el género «" + genre.name()
                        + "»: " + usage + " antes de borrarlo."));
    }

    private static AdminGenreResponse find(List<AdminGenreResponse> view, Genre genre) {
        return view.stream().filter(g -> g.id().equals(genre.getId())).findFirst().orElseThrow();
    }

    /** Cuenta las sentencias JDBC que prepara {@code operation} (estadísticas de Hibernate). */
    private long statementsFor(Runnable operation) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        operation.run();
        return statistics.getPrepareStatementCount();
    }

    private User saveUser(String name, Role role) {
        User user = new User();
        user.setEmailVerifiedAt(Instant.now()); // cuenta verificada (V9)
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(role);
        return userRepository.save(user);
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateToken(user);
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

    /** Vacía las tablas respetando las claves foráneas (los episodios caen con su serie). */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
