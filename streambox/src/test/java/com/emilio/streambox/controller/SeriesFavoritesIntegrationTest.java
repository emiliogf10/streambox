package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.Set;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.Episode;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.exception.SeriesAlreadyInFavoritesException;
import com.emilio.streambox.exception.SeriesNotFoundException;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.emilio.streambox.service.SeriesFavoriteService;

import jakarta.persistence.EntityManagerFactory;

/**
 * Tests de integración de las series de "Mi lista"
 * ({@code /api/users/me/favorites/series}).
 *
 * <p>
 * Sin {@code @Transactional}, como {@link FavoritesControllerIntegrationTest}:
 * cada petición se confirma y se comprueba el comportamiento real de la tabla
 * {@code user_favorite_series} (clave primaria, claves foráneas). Incluye
 * pruebas explícitas de que estas rutas no las captura
 * {@link FavoriteController} ({@code /api/users/me/favorites/{movieId}}).
 * </p>
 *
 * <p>
 * Usa las mismas propiedades que {@link CatalogIntegrationTest} (estadísticas
 * de Hibernate) para compartir su contexto de Spring y contar consultas.
 * </p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SeriesFavoritesIntegrationTest {

    private static final String FAVORITES = "/api/users/me/favorites";
    private static final String SERIES_FAVORITES = FAVORITES + "/series";

    @Autowired private MockMvc mockMvc;
    @Autowired private SeriesFavoriteService seriesFavoriteService;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private User alice;
    private String aliceToken;
    private String bobToken;
    private Genre drama;
    private Series fargo;
    private Series dark;
    private Series empty;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        alice = saveUser("seriesalice");
        aliceToken = tokenFor(alice);
        bobToken = tokenFor(saveUser("seriesbob"));

        drama = saveGenre("Drama");
        fargo = saveSeries("Fargo");
        saveEpisode(fargo, 1, 1);
        saveEpisode(fargo, 2, 1);
        dark = saveSeries("Dark");
        saveEpisode(dark, 1, 1);
        empty = saveSeries("Vacia");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Listado
    // ------------------------------------------------------------------

    @Test
    void laListaEmpiezaVaciaYSinTokenDa401() throws Exception {
        mockMvc.perform(get(SERIES_FAVORITES).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        mockMvc.perform(get(SERIES_FAVORITES)).andExpect(status().isUnauthorized());
        mockMvc.perform(post(SERIES_FAVORITES + "/" + fargo.getId())).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(SERIES_FAVORITES + "/" + fargo.getId())).andExpect(status().isUnauthorized());
        mockMvc.perform(delete(SERIES_FAVORITES)).andExpect(status().isUnauthorized());
    }

    @Test
    void anadirSeriesLasMuestraOrdenadasPorTituloConSusRecuentos() throws Exception {
        add(aliceToken, fargo);
        add(aliceToken, dark);

        mockMvc.perform(get(SERIES_FAVORITES).header("Authorization", aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("Dark"))
                .andExpect(jsonPath("$[1].title").value("Fargo"))
                .andExpect(jsonPath("$[1].seasonCount").value(2))
                .andExpect(jsonPath("$[1].episodeCount").value(2))
                .andExpect(jsonPath("$[1].genres[0].name").value("Drama"))
                .andExpect(jsonPath("$[1].seasons").doesNotExist());
    }

    @Test
    void cadaUsuarioVeSoloSuLista() throws Exception {
        add(aliceToken, fargo);

        mockMvc.perform(get(SERIES_FAVORITES).header("Authorization", bobToken))
                .andExpect(jsonPath("$").isEmpty());

        // Bob no puede quitar de su lista algo que solo está en la de Alice
        mockMvc.perform(delete(SERIES_FAVORITES + "/" + fargo.getId()).header("Authorization", bobToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SERIES_NOT_IN_FAVORITES"));
        mockMvc.perform(get(SERIES_FAVORITES).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.length()").value(1));
    }

    /**
     * Si una serie de la lista se queda sin episodios deja de mostrarse, pero
     * la fila no se borra: al volver a tener episodios reaparece sola.
     */
    @Test
    void unaSerieQueSeQuedaSinEpisodiosDesapareceDeLaListaYReapareceSiVuelveATenerlos() throws Exception {
        add(aliceToken, dark);
        Long onlyEpisode = episodeRepository.findAllBySeriesIdOrdered(dark.getId()).get(0).getId();

        episodeRepository.deleteById(onlyEpisode);
        mockMvc.perform(get(SERIES_FAVORITES).header("Authorization", aliceToken))
                .andExpect(jsonPath("$").isEmpty());

        saveEpisode(dark, 1, 1);
        mockMvc.perform(get(SERIES_FAVORITES).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Dark"));
    }

    /** Tres consultas (lista, recuentos, géneros por lotes), tenga las series que tenga. */
    @Test
    void elListadoNoEjecutaUnaConsultaPorSerie() {
        for (int i = 0; i < 12; i++) {
            Series series = saveSeries(String.format("Muchas %02d", i));
            saveEpisode(series, 1, 1);
            favorite(alice, series);
        }
        favorite(alice, empty);

        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        int size = seriesFavoriteService.getFavorites(alice.getId()).size();

        assertEquals(12, size, "la serie vacía no debe aparecer");
        assertTrue(statistics.getPrepareStatementCount() <= 3,
                "consultas para 13 favoritos: " + statistics.getPrepareStatementCount());
    }

    // ------------------------------------------------------------------
    // Añadir
    // ------------------------------------------------------------------

    @Test
    void anadirDosVecesLaMismaSerieDa409ConSuCodigo() throws Exception {
        add(aliceToken, fargo);

        mockMvc.perform(post(SERIES_FAVORITES + "/" + fargo.getId()).header("Authorization", aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERIES_ALREADY_IN_FAVORITES"))
                .andExpect(jsonPath("$.message").value("La serie ya está incluida en tu lista de favoritos"));

        assertEquals(1, favoritesOf(alice));
    }

    @Test
    void anadirUnaSerieInexistenteOSinEpisodiosDa404Igual() throws Exception {
        mockMvc.perform(post(SERIES_FAVORITES + "/987654").header("Authorization", aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Serie no encontrada"));
        mockMvc.perform(post(SERIES_FAVORITES + "/" + empty.getId()).header("Authorization", aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Serie no encontrada"));

        assertEquals(0, favoritesOf(alice));
    }

    // ------------------------------------------------------------------
    // Quitar y vaciar
    // ------------------------------------------------------------------

    @Test
    void quitarUnaSerieDeLaListaLaQuita() throws Exception {
        add(aliceToken, fargo);
        add(aliceToken, dark);

        mockMvc.perform(delete(SERIES_FAVORITES + "/" + fargo.getId()).header("Authorization", aliceToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(SERIES_FAVORITES).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Dark"));
    }

    @Test
    void quitarUnaSerieQueNoEstabaDa404ConCodigoEspecificoYSiNoExisteRecursoNoEncontrado() throws Exception {
        mockMvc.perform(delete(SERIES_FAVORITES + "/" + fargo.getId()).header("Authorization", aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SERIES_NOT_IN_FAVORITES"))
                .andExpect(jsonPath("$.message").value("La serie no está incluida en tu lista de favoritos"));
        mockMvc.perform(delete(SERIES_FAVORITES + "/987654").header("Authorization", aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Serie no encontrada"));
    }

    /** Una serie que se quedó vacía estando en la lista se puede quitar igualmente. */
    @Test
    void sePuedeQuitarUnaSerieQueSeQuedoSinEpisodios() throws Exception {
        favorite(alice, empty);

        mockMvc.perform(delete(SERIES_FAVORITES + "/" + empty.getId()).header("Authorization", aliceToken))
                .andExpect(status().isNoContent());

        assertEquals(0, favoritesOf(alice));
    }

    // ------------------------------------------------------------------
    // Convivencia con las rutas de películas (/favorites/{movieId})
    // ------------------------------------------------------------------

    /**
     * {@code POST .../favorites/series/{id}} llega al controlador de series:
     * se añade una serie y la lista de películas no cambia, aunque exista una
     * película con el mismo id.
     */
    @Test
    void anadirUnaSerieNoTocaLaListaDePeliculas() throws Exception {
        // Película y serie con el mismo id, insertadas con un id alto que los
        // contadores de identidad de los tests no alcanzan.
        long sharedId = 777_777L;
        jdbc.update("INSERT INTO movies (id, title, description, duration, release_year, image_url, video_url,"
                + " created_at) VALUES (?, 'Gemela', 'd', 100, 2000, 'https://e.com/i.jpg', 'https://e.com/v.mp4',"
                + " CURRENT_TIMESTAMP)", sharedId);
        jdbc.update("INSERT INTO series (id, title, description, release_year, image_url, created_at)"
                + " VALUES (?, 'Gemela', 'd', 2000, 'https://e.com/i.jpg', CURRENT_TIMESTAMP)", sharedId);
        jdbc.update("INSERT INTO episodes (series_id, season_number, episode_number, title, duration, video_url,"
                + " created_at) VALUES (?, 1, 1, 't', 40, 'https://e.com/v.mp4', CURRENT_TIMESTAMP)", sharedId);

        mockMvc.perform(post(SERIES_FAVORITES + "/" + sharedId).header("Authorization", aliceToken))
                .andExpect(status().isNoContent());

        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_favorite_movies WHERE user_id = ?", Integer.class, alice.getId()));
        mockMvc.perform(get(FAVORITES).header("Authorization", aliceToken))
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get(SERIES_FAVORITES).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.length()").value(1));
    }

    /**
     * {@code DELETE .../favorites/series} vacía solo las series. Si la ruta la
     * capturase el borrado de una película ({@code /{movieId}} con
     * {@code movieId="series"}), respondería 400 por el tipo del id.
     */
    @Test
    void vaciarLasSeriesNoTocaLasPeliculasNiLaListaDeOtros() throws Exception {
        Movie movie = saveMovie("Peli favorita");
        mockMvc.perform(post(FAVORITES + "/" + movie.getId()).header("Authorization", aliceToken))
                .andExpect(status().isNoContent());
        add(aliceToken, fargo);
        add(aliceToken, dark);
        add(bobToken, fargo);

        mockMvc.perform(delete(SERIES_FAVORITES).header("Authorization", aliceToken))
                .andExpect(status().isNoContent());

        assertEquals(0, favoritesOf(alice));
        mockMvc.perform(get(FAVORITES).header("Authorization", aliceToken))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].title").value("Peli favorita"));
        mockMvc.perform(get(SERIES_FAVORITES).header("Authorization", bobToken))
                .andExpect(jsonPath("$.length()").value(1));

        // Vaciar una lista ya vacía tampoco es un error
        mockMvc.perform(delete(SERIES_FAVORITES).header("Authorization", aliceToken))
                .andExpect(status().isNoContent());
    }

    /** Y al revés: vaciar las películas no toca las series. */
    @Test
    void vaciarLasPeliculasNoTocaLasSeries() throws Exception {
        add(aliceToken, fargo);

        mockMvc.perform(delete(FAVORITES).header("Authorization", aliceToken))
                .andExpect(status().isNoContent());

        assertEquals(1, favoritesOf(alice));
    }

    // ------------------------------------------------------------------
    // Carreras entre la comprobación previa y el INSERT (SQLSTATE reales de H2)
    // ------------------------------------------------------------------

    /**
     * Dos altas simultáneas: la comprobación {@code isFavorite} no ve la otra
     * y la clave primaria de H2 da 23505, que se traduce al mismo 409.
     */
    @Test
    void siDosAltasSeCruzanLaClavePrimariaDaConflicto() {
        favorite(alice, fargo);
        SeriesRepository blind = mock(SeriesRepository.class, delegatesTo(seriesRepository));
        doReturn(false).when(blind).isFavorite(alice.getId(), fargo.getId());
        SeriesFavoriteService service = new SeriesFavoriteService(blind, episodeRepository, userRepository);

        assertThrows(SeriesAlreadyInFavoritesException.class,
                () -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(s -> service.addFavorite(alice.getId(), fargo.getId())));
    }

    /**
     * La serie "desaparece" tras la comprobación de visibilidad (repositorio
     * con dato obsoleto) y el {@code INSERT} real falla por clave foránea:
     * debe salir 404, no 409.
     */
    @Test
    void siLaSerieDesapareceEntreLaComprobacionYElInsertSeTraduceA404() {
        EpisodeRepository stale = mock(EpisodeRepository.class, delegatesTo(episodeRepository));
        when(stale.existsBySeriesId(987654L)).thenReturn(true);
        SeriesFavoriteService service = new SeriesFavoriteService(seriesRepository, stale, userRepository);

        assertThrows(SeriesNotFoundException.class,
                () -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(s -> service.addFavorite(alice.getId(), 987654L)));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private void add(String token, Series series) throws Exception {
        mockMvc.perform(post(SERIES_FAVORITES + "/" + series.getId()).header("Authorization", token))
                .andExpect(status().isNoContent());
    }

    /** Inserta un favorito directamente (también de series vacías, que la API no deja añadir). */
    private void favorite(User user, Series series) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(s -> seriesRepository.addFavorite(user.getId(), series.getId()));
    }

    private int favoritesOf(User user) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM user_favorite_series WHERE user_id = ?",
                Integer.class, user.getId());
    }

    private String tokenFor(User user) {
        return "Bearer " + jwtService.generateToken(user);
    }

    private User saveUser(String name) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(Role.USER);
        return userRepository.save(user);
    }

    private Genre saveGenre(String name) {
        Genre genre = new Genre();
        genre.setName(name);
        return genreRepository.save(genre);
    }

    private Movie saveMovie(String title) {
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setDescription("Descripción");
        movie.setDuration(100);
        movie.setReleaseYear(2000);
        movie.setImageUrl("https://example.com/i.jpg");
        movie.setVideoUrl("https://example.com/v.mp4");
        movie.setGenres(new HashSet<>(Set.of(drama)));
        return movieRepository.save(movie);
    }

    private Series saveSeries(String title) {
        Series series = new Series();
        series.setTitle(title);
        series.setDescription("Sinopsis de " + title);
        series.setReleaseYear(2015);
        series.setImageUrl("https://example.com/serie.jpg");
        series.setGenres(new HashSet<>(Set.of(drama)));
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

    /** Series primero (sus favoritos y episodios caen con ellas), luego el resto. */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
