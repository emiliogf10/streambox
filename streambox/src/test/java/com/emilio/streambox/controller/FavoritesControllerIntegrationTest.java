package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.MovieAlreadyInFavoritesException;
import com.emilio.streambox.exception.MovieNotFoundException;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.emilio.streambox.service.FavoriteService;

/**
 * Tests de integración de "Mi lista" ({@code /api/users/me/favorites}).
 *
 * <p>
 * A diferencia de otras suites, esta NO usa {@code @Transactional}: cada
 * petición se confirma (commit) de verdad, de modo que se comprueba también
 * el comportamiento real de la base de datos (claves de la tabla de unión,
 * borrados...). Los datos se limpian explícitamente tras cada test.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class FavoritesControllerIntegrationTest {

    private static final String FAVORITES = "/api/users/me/favorites";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformTransactionManager transactionManager;

    private String aliceToken;
    private String bobToken;
    private String adminToken;
    private Genre genre;
    private Movie dune;
    private Movie alien;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        aliceToken = tokenFor(createUser("alice", Role.USER));
        bobToken = tokenFor(createUser("bob", Role.USER));
        adminToken = tokenFor(createUser("admin", Role.ADMIN));

        Genre g = new Genre();
        g.setName("Scifi");
        genre = genreRepository.save(g);

        dune = createMovie("Dune");
        alien = createMovie("Alien");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // --- Listado ---

    @Test
    void listaVaciaAlPrincipio() throws Exception {
        mockMvc.perform(get(FAVORITES).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void sinTokenRetorna401() throws Exception {
        mockMvc.perform(get(FAVORITES)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(FAVORITES + "/" + dune.getId())).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(FAVORITES)).andExpect(status().isUnauthorized());
    }

    // --- Añadir ---

    @Test
    void anadirPeliculaLaIncluyeEnLaLista() throws Exception {
        mockMvc.perform(post(FAVORITES + "/" + dune.getId()).header("Authorization", aliceToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(FAVORITES).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Dune"))
                .andExpect(jsonPath("$[0].genres[0].name").value("Scifi"));
    }

    @Test
    void anadirDosVecesLaMismaPeliculaRetorna409ConCodigoEspecifico() throws Exception {
        mockMvc.perform(post(FAVORITES + "/" + dune.getId()).header("Authorization", aliceToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(post(FAVORITES + "/" + dune.getId()).header("Authorization", aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MOVIE_ALREADY_IN_FAVORITES"));

        // La lista sigue teniendo una sola entrada
        mockMvc.perform(get(FAVORITES).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void anadirPeliculaInexistenteRetorna404() throws Exception {
        mockMvc.perform(post(FAVORITES + "/999999").header("Authorization", aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // --- Carreras entre la comprobación previa y el INSERT (SQLSTATE reales de H2) ---

    /**
     * Confirma con H2 real los SQLSTATE en los que se apoya
     * {@code FavoriteService.insertFavorite}: la película "desaparece" tras
     * {@code existsById} (repositorio simulado con dato obsoleto) y el INSERT
     * real falla por clave foránea. Debe salir 404 de dominio, no 409.
     */
    @Test
    void siLaPeliculaDesapareceEntreLaComprobacionYElInsertSeTraduceA404() {
        Long aliceId = userRepository.findAll().stream()
                .filter(u -> "alice".equals(u.getUsername())).findFirst().orElseThrow().getId();
        MovieRepository staleMovies = mock(MovieRepository.class);
        when(staleMovies.existsById(987654L)).thenReturn(true);
        FavoriteService service = new FavoriteService(userRepository, staleMovies);

        assertThrows(MovieNotFoundException.class,
                () -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(s -> service.addFavorite(aliceId, 987654L)));
    }

    /**
     * Duplicado real en H2 cuando la comprobación {@code isFavorite} falla en
     * detectarlo (dos peticiones simultáneas): la clave primaria da 23505 y se
     * traduce a 409 de dominio.
     */
    @Test
    void siDosAltasSeCruzanLaClavePrimariaDeH2DaConflicto() {
        Long aliceId = userRepository.findAll().stream()
                .filter(u -> "alice".equals(u.getUsername())).findFirst().orElseThrow().getId();
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(s -> userRepository.addFavorite(aliceId, dune.getId()));

        // Repositorio que delega en el real pero "no ve" el favorito ya insertado
        UserRepository blindRepository = mock(UserRepository.class, delegatesTo(userRepository));
        doReturn(false).when(blindRepository).isFavorite(aliceId, dune.getId());
        FavoriteService service = new FavoriteService(blindRepository, movieRepository);

        assertThrows(MovieAlreadyInFavoritesException.class,
                () -> tx.executeWithoutResult(s -> service.addFavorite(aliceId, dune.getId())));
    }

    // --- Eliminar ---

    @Test
    void eliminarPeliculaDeLaListaLaQuita() throws Exception {
        add(aliceToken, dune);
        add(aliceToken, alien);

        mockMvc.perform(delete(FAVORITES + "/" + dune.getId()).header("Authorization", aliceToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(FAVORITES).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Alien"));
    }

    @Test
    void eliminarPeliculaQueNoEstaEnLaListaRetorna404ConCodigoEspecifico() throws Exception {
        mockMvc.perform(delete(FAVORITES + "/" + dune.getId()).header("Authorization", aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MOVIE_NOT_IN_FAVORITES"));
    }

    @Test
    void eliminarPeliculaInexistenteRetorna404() throws Exception {
        mockMvc.perform(delete(FAVORITES + "/999999").header("Authorization", aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    // --- Vaciar ---

    @Test
    void vaciarListaEliminaTodasLasPeliculas() throws Exception {
        add(aliceToken, dune);
        add(aliceToken, alien);

        mockMvc.perform(delete(FAVORITES).header("Authorization", aliceToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(FAVORITES).header("Authorization", aliceToken))
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void vaciarListaYaVaciaSigueRetornando204() throws Exception {
        mockMvc.perform(delete(FAVORITES).header("Authorization", aliceToken))
                .andExpect(status().isNoContent());
    }

    // --- Por título ---

    @Test
    void anadirPorTituloIgnoraMayusculas() throws Exception {
        mockMvc.perform(post(FAVORITES + "/by-title").param("title", "dUnE")
                        .header("Authorization", aliceToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(FAVORITES).header("Authorization", aliceToken))
                .andExpect(jsonPath("$[0].title").value("Dune"));
    }

    @Test
    void anadirPorTituloDuplicadoRetorna409() throws Exception {
        add(aliceToken, dune);

        mockMvc.perform(post(FAVORITES + "/by-title").param("title", "Dune")
                        .header("Authorization", aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MOVIE_ALREADY_IN_FAVORITES"));
    }

    @Test
    void anadirPorTituloInexistenteRetorna404() throws Exception {
        mockMvc.perform(post(FAVORITES + "/by-title").param("title", "No existe")
                        .header("Authorization", aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void anadirPorTituloAmbiguoRetorna409Ambiguo() throws Exception {
        createMovie("Dune"); // segunda película con el mismo título

        mockMvc.perform(post(FAVORITES + "/by-title").param("title", "Dune")
                        .header("Authorization", aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AMBIGUOUS_TITLE"));
    }

    @Test
    void eliminarPorTituloQuitaLaPelicula() throws Exception {
        add(aliceToken, dune);

        mockMvc.perform(delete(FAVORITES + "/by-title").param("title", "dune")
                        .header("Authorization", aliceToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(FAVORITES).header("Authorization", aliceToken))
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void eliminarPorTituloQueNoEstaEnLaListaRetorna404() throws Exception {
        mockMvc.perform(delete(FAVORITES + "/by-title").param("title", "Dune")
                        .header("Authorization", aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MOVIE_NOT_IN_FAVORITES"));
    }

    @Test
    void porTituloSinParametroRetorna400() throws Exception {
        mockMvc.perform(post(FAVORITES + "/by-title").header("Authorization", aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // --- Aislamiento entre usuarios ---

    @Test
    void cadaUsuarioSoloVeSuPropiaLista() throws Exception {
        add(aliceToken, dune);

        mockMvc.perform(get(FAVORITES).header("Authorization", bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void vaciarMiListaNoAfectaALaDeOtroUsuario() throws Exception {
        add(aliceToken, dune);
        add(bobToken, dune);

        mockMvc.perform(delete(FAVORITES).header("Authorization", bobToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(FAVORITES).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void dosUsuariosPuedenTenerLaMismaPelicula() throws Exception {
        add(aliceToken, dune);
        add(bobToken, dune);

        mockMvc.perform(get(FAVORITES).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get(FAVORITES).header("Authorization", bobToken))
                .andExpect(jsonPath("$.length()").value(1));
    }

    // --- Borrado de película ---

    @Test
    void borrarUnaPeliculaLaRetiraDeLosFavoritosDeTodos() throws Exception {
        add(aliceToken, dune);
        add(bobToken, dune);
        add(bobToken, alien);

        mockMvc.perform(delete("/api/movies/" + dune.getId()).header("Authorization", adminToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(FAVORITES).header("Authorization", aliceToken))
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get(FAVORITES).header("Authorization", bobToken))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Alien"));
    }

    // --- Utilidades ---

    private void add(String token, Movie movie) throws Exception {
        mockMvc.perform(post(FAVORITES + "/" + movie.getId()).header("Authorization", token))
                .andExpect(status().isNoContent());
    }

    private User createUser(String name, Role role) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(role);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user);
    }

    private String tokenFor(User user) {
        return "Bearer " + jwtService.generateToken(user);
    }

    private Movie createMovie(String title) {
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setDescription("Descripción de " + title);
        movie.setDuration(120);
        movie.setReleaseYear(2000);
        movie.setImageUrl("https://example.com/image.jpg");
        movie.setVideoUrl("https://example.com/video.mp4");
        movie.setCreatedAt(Instant.now());
        movie.setGenres(Set.of(genre));
        return movieRepository.save(movie);
    }

    private void cleanDatabase() {
        // Orden: usuarios (limpia user_favorite_movies), películas
        // (limpia movie_genres) y por último géneros.
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
