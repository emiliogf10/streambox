package com.emilio.streambox.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.Episode;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.entity.User;

import jakarta.persistence.EntityManagerFactory;

/**
 * Tests de las consultas de {@link SeriesRepository} y {@link EpisodeRepository}
 * contra el esquema real de Flyway (H2).
 *
 * <p>
 * Como {@code CatalogIntegrationTest}, los datos se confirman de verdad (sin
 * {@code @Transactional}) y se limpian antes y después de cada test: así se
 * prueba también lo que solo ocurre al cerrar la transacción (carga diferida,
 * cascadas de la base de datos). Las operaciones que modifican datos o que se
 * quieren medir juntas se ejecutan dentro de un {@link TransactionTemplate},
 * igual que lo haría un servicio {@code @Transactional}.
 * </p>
 *
 * <p>
 * Se activan las estadísticas de Hibernate (mismas propiedades que
 * {@code CatalogIntegrationTest}, para reutilizar su contexto de Spring) y se
 * cuentan las sentencias SQL para detectar el problema N+1.
 * </p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SeriesRepositoryIntegrationTest {

    @Autowired private SeriesRepository seriesRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private Genre drama;
    private Genre comedy;
    private Genre scifi;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        cleanDatabase();
        drama = saveGenre("Drama");
        comedy = saveGenre("Comedia");
        scifi = saveGenre("Ciencia ficcion");
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Episodios
    // ------------------------------------------------------------------

    @Test
    void losEpisodiosSeDevuelvenOrdenadosPorTemporadaYNumero() {
        Series series = saveSeries("Ordenada", 2015, drama);
        Series other = saveSeries("Otra", 2015);
        // Se insertan desordenados a propósito
        saveEpisode(series, 2, 2);
        saveEpisode(series, 1, 3);
        saveEpisode(series, 2, 1);
        saveEpisode(series, 1, 1);
        saveEpisode(series, 10, 1); // 10 debe ir después de 2 (orden numérico, no de texto)
        saveEpisode(series, 1, 2);
        saveEpisode(other, 1, 1);

        List<String> order = episodeRepository.findAllBySeriesIdOrdered(series.getId()).stream()
                .map(e -> e.getSeasonNumber() + "x" + e.getEpisodeNumber())
                .toList();

        assertEquals(List.of("1x1", "1x2", "1x3", "2x1", "2x2", "10x1"), order);
    }

    @Test
    void lasComprobacionesDePosicionDeEpisodioFuncionan() {
        Series series = saveSeries("Posiciones", 2015);
        Series other = saveSeries("Ajena", 2015);
        Episode first = saveEpisode(series, 1, 1);
        Episode second = saveEpisode(series, 1, 2);

        assertTrue(episodeRepository.existsBySeriesId(series.getId()));
        assertFalse(episodeRepository.existsBySeriesId(other.getId()));
        assertTrue(episodeRepository.existsBySeriesIdAndSeasonNumberAndEpisodeNumber(series.getId(), 1, 1));
        assertFalse(episodeRepository.existsBySeriesIdAndSeasonNumberAndEpisodeNumber(series.getId(), 2, 1));
        assertFalse(episodeRepository.existsBySeriesIdAndSeasonNumberAndEpisodeNumber(other.getId(), 1, 1));
        // Al editar el episodio 1x1 conservando su posición, no choca consigo mismo...
        assertFalse(episodeRepository.existsBySeriesIdAndSeasonNumberAndEpisodeNumberAndIdNot(
                series.getId(), 1, 1, first.getId()));
        // ...pero sí si intenta ocupar la del 1x2
        assertTrue(episodeRepository.existsBySeriesIdAndSeasonNumberAndEpisodeNumberAndIdNot(
                series.getId(), 1, 2, first.getId()));

        assertTrue(episodeRepository.findByIdAndSeriesId(second.getId(), series.getId()).isPresent());
        assertTrue(episodeRepository.findByIdAndSeriesId(second.getId(), other.getId()).isEmpty(),
                "un episodio no debe encontrarse a través de otra serie");
    }

    @Test
    void elRecuentoDeEpisodiosYTemporadasSeHaceEnUnaSolaConsultaParaVariasSeries() {
        Series twoSeasons = saveSeries("Dos temporadas", 2015);
        Series oneSeason = saveSeries("Una", 2015);
        Series empty = saveSeries("Vacia", 2015);
        saveEpisode(twoSeasons, 1, 1);
        saveEpisode(twoSeasons, 1, 2);
        saveEpisode(twoSeasons, 2, 1);
        saveEpisode(oneSeason, 1, 1);

        long statements = statementsFor(() -> episodeRepository.countBySeriesIds(
                List.of(twoSeasons.getId(), oneSeason.getId(), empty.getId())));
        Map<Long, SeriesEpisodeStats> stats = episodeRepository
                .countBySeriesIds(List.of(twoSeasons.getId(), oneSeason.getId(), empty.getId())).stream()
                .collect(Collectors.toMap(SeriesEpisodeStats::getSeriesId, Function.identity()));

        assertEquals(1, statements);
        assertEquals(3, stats.get(twoSeasons.getId()).getEpisodeCount());
        assertEquals(2, stats.get(twoSeasons.getId()).getSeasonCount());
        assertEquals(1, stats.get(oneSeason.getId()).getEpisodeCount());
        assertEquals(1, stats.get(oneSeason.getId()).getSeasonCount());
        assertFalse(stats.containsKey(empty.getId()), "una serie sin episodios no aparece (cuenta como 0)");
    }

    // ------------------------------------------------------------------
    // Detalle de una serie
    // ------------------------------------------------------------------

    @Test
    void unaSerieSinEpisodiosNoEsVisibleParaLosUsuariosPeroSiParaElAdministrador() {
        Series empty = saveSeries("Vacia", 2015, drama);

        assertTrue(seriesRepository.findVisibleById(empty.getId()).isEmpty());
        assertTrue(seriesRepository.findById(empty.getId()).isPresent());

        saveEpisode(empty, 1, 1);

        assertTrue(seriesRepository.findVisibleById(empty.getId()).isPresent());
        assertTrue(seriesRepository.findVisibleById(987654L).isEmpty());
    }

    @Test
    void elDetalleTraeLosGenerosAunqueSeCierreLaSesion() {
        Series series = saveSeries("Con generos", 2015, drama, comedy);
        saveEpisode(series, 1, 1);

        // Sin transacción alrededor: si los géneros no vinieran en la consulta,
        // tocarlos fuera de la sesión daría LazyInitializationException.
        Series visible = seriesRepository.findVisibleById(series.getId()).orElseThrow();
        Series admin = seriesRepository.findById(series.getId()).orElseThrow();

        assertTrue(Hibernate.isInitialized(visible.getGenres()));
        assertTrue(Hibernate.isInitialized(admin.getGenres()));
        assertEquals(2, visible.getGenres().size());
    }

    /**
     * El detalle completo (serie + géneros + todos sus episodios, tocando la
     * serie de cada episodio) cuesta exactamente dos consultas, con 3 géneros y
     * 24 episodios: no crece con el número de episodios ni de géneros.
     */
    @Test
    void elDetalleCompletoCuestaDosConsultasSinImportarCuantosEpisodiosTenga() {
        Series series = saveSeries("Larga", 2015, drama, comedy, scifi);
        for (int season = 1; season <= 3; season++) {
            for (int number = 1; number <= 8; number++) {
                saveEpisode(series, season, number);
            }
        }

        long statements = statementsFor(() -> tx.executeWithoutResult(status -> {
            Series detail = seriesRepository.findVisibleById(series.getId()).orElseThrow();
            List<Episode> episodes = episodeRepository.findAllBySeriesIdOrdered(series.getId());
            // Lo que haría el mapper: géneros, y para cada episodio sus datos y el id de su serie
            assertEquals(3, detail.getGenres().stream().map(Genre::getName).count());
            assertEquals(24, episodes.size());
            episodes.forEach(e -> {
                assertEquals(series.getId(), e.getSeries().getId());
                e.getTitle();
                e.getDuration();
            });
        }));

        assertEquals(2, statements, "detalle: serie+géneros y episodios");
    }

    // ------------------------------------------------------------------
    // Géneros
    // ------------------------------------------------------------------

    @Test
    void cuentaLasSeriesQueUsanUnGenero() {
        saveSeries("A", 2015, drama, comedy);
        saveSeries("B", 2015, drama);
        saveSeries("C", 2015);

        assertEquals(2, seriesRepository.countByGenres_Id(drama.getId()));
        assertEquals(1, seriesRepository.countByGenres_Id(comedy.getId()));
        assertEquals(0, seriesRepository.countByGenres_Id(scifi.getId()));
    }

    // ------------------------------------------------------------------
    // Favoritos de series
    // ------------------------------------------------------------------

    @Test
    void anadirComprobarYQuitarUnaSerieDeLaLista() {
        User user = saveUser("fan");
        Series series = saveSeries("Favorita", 2015);

        assertFalse(seriesRepository.isFavorite(user.getId(), series.getId()));

        tx.executeWithoutResult(status -> seriesRepository.addFavorite(user.getId(), series.getId()));

        assertTrue(seriesRepository.isFavorite(user.getId(), series.getId()));
        assertEquals(1, (int) tx.execute(status -> seriesRepository.removeFavorite(user.getId(), series.getId())));
        assertEquals(0, (int) tx.execute(status -> seriesRepository.removeFavorite(user.getId(), series.getId())),
                "quitar una serie que no está en la lista no borra nada");
        assertFalse(seriesRepository.isFavorite(user.getId(), series.getId()));
    }

    @Test
    void anadirDosVecesLaMismaSerieLoRechazaLaBaseDeDatos() {
        User user = saveUser("dup");
        Series series = saveSeries("Duplicada", 2015);
        tx.executeWithoutResult(status -> seriesRepository.addFavorite(user.getId(), series.getId()));

        assertThrows(DataIntegrityViolationException.class, () ->
                tx.executeWithoutResult(status -> seriesRepository.addFavorite(user.getId(), series.getId())));
    }

    @Test
    void laListaDeSeriesVaOrdenadaPorTituloYSoloEsDelUsuario() {
        User user = saveUser("fan");
        User other = saveUser("otro");
        Series zeta = saveSeries("Zeta", 2015);
        Series alfa = saveSeries("Alfa", 2015);
        Series media = saveSeries("Media", 2015);
        Series ajena = saveSeries("Ajena", 2015);
        tx.executeWithoutResult(status -> {
            seriesRepository.addFavorite(user.getId(), zeta.getId());
            seriesRepository.addFavorite(user.getId(), alfa.getId());
            seriesRepository.addFavorite(user.getId(), media.getId());
            seriesRepository.addFavorite(other.getId(), ajena.getId());
        });

        List<String> titles = seriesRepository.findFavoritesByUserId(user.getId()).stream()
                .map(Series::getTitle)
                .toList();

        assertEquals(List.of("Alfa", "Media", "Zeta"), titles);
    }

    /**
     * Listar la lista y tocar los géneros de cada serie (lo que hará el mapper)
     * cuesta lo mismo con 3 series que con 15: la consulta de la lista y una
     * consulta por lotes para los géneros.
     */
    @Test
    void laListaDeSeriesNoEjecutaUnaConsultaPorSerie() {
        User few = saveUser("pocas");
        User many = saveUser("muchas");
        for (int i = 0; i < 15; i++) {
            Series series = saveSeries(String.format("Serie %02d", i), 2015, drama, comedy);
            boolean alsoForFew = i < 3;
            tx.executeWithoutResult(status -> {
                seriesRepository.addFavorite(many.getId(), series.getId());
                if (alsoForFew) {
                    seriesRepository.addFavorite(few.getId(), series.getId());
                }
            });
        }

        long small = statementsFor(() -> tx.executeWithoutResult(status ->
                seriesRepository.findFavoritesByUserId(few.getId()).forEach(s -> s.getGenres().size())));
        long large = statementsFor(() -> tx.executeWithoutResult(status ->
                seriesRepository.findFavoritesByUserId(many.getId()).forEach(s -> s.getGenres().size())));

        assertTrue(small <= 2, "lista de 3 series: " + small + " consultas");
        assertEquals(small, large, "el número de consultas no debe depender del tamaño de la lista");
    }

    @Test
    void vaciarLaListaDeSeriesNoTocaLaDeOtrosNiLasPeliculas() {
        User user = saveUser("vacia");
        User other = saveUser("conserva");
        Series a = saveSeries("A", 2015);
        Series b = saveSeries("B", 2015);
        jdbc.update("INSERT INTO movies (title, description, duration, release_year, image_url, video_url, created_at)"
                + " VALUES ('Peli', 'd', 100, 2000, 'u', 'v', CURRENT_TIMESTAMP)");
        long movieId = jdbc.queryForObject("SELECT MAX(id) FROM movies", Long.class);
        tx.executeWithoutResult(status -> {
            seriesRepository.addFavorite(user.getId(), a.getId());
            seriesRepository.addFavorite(user.getId(), b.getId());
            seriesRepository.addFavorite(other.getId(), a.getId());
            userRepository.addFavorite(user.getId(), movieId);
        });

        int removed = tx.execute(status -> seriesRepository.clearFavorites(user.getId()));

        assertEquals(2, removed);
        assertEquals(List.of(), seriesRepository.findFavoritesByUserId(user.getId()));
        assertEquals(1, seriesRepository.findFavoritesByUserId(other.getId()).size());
        assertEquals(1, userRepository.findFavoriteMovies(user.getId()).size(), "las películas siguen en su lista");
    }

    // ------------------------------------------------------------------
    // Borrado
    // ------------------------------------------------------------------

    /**
     * Borrar una serie desde JPA funciona aunque tenga episodios, géneros y
     * esté en listas: Hibernate borra sus filas de {@code series_genres} (es el
     * dueño de la relación) y la base de datos arrastra episodios y favoritos
     * por el {@code ON DELETE CASCADE}. Los géneros y el usuario se conservan.
     */
    @Test
    void borrarUnaSerieDesdeJpaArrastraEpisodiosYFavoritos() {
        User user = saveUser("borra");
        Series series = saveSeries("Borrable", 2015, drama, comedy);
        saveEpisode(series, 1, 1);
        saveEpisode(series, 1, 2);
        tx.executeWithoutResult(status -> seriesRepository.addFavorite(user.getId(), series.getId()));

        seriesRepository.deleteById(series.getId());

        assertFalse(seriesRepository.existsById(series.getId()));
        assertEquals(0, episodeRepository.count());
        assertEquals(0, count("series_genres"));
        assertEquals(0, count("user_favorite_series"));
        assertEquals(3, genreRepository.count());
        assertTrue(userRepository.existsById(user.getId()));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

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

    private Genre saveGenre(String name) {
        Genre genre = new Genre();
        genre.setName(name);
        return genreRepository.save(genre);
    }

    private User saveUser(String name) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword("no-se-usa");
        user.setRole(Role.USER);
        return userRepository.save(user);
    }

    private Series saveSeries(String title, int year, Genre... genres) {
        Series series = new Series();
        series.setTitle(title);
        series.setDescription("Sinopsis de " + title);
        series.setReleaseYear(year);
        series.setImageUrl("https://example.com/serie.jpg");
        series.setGenres(new HashSet<>(List.of(genres)));
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

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    /**
     * Vacía las tablas respetando las claves foráneas: las series primero
     * (arrastran episodios, sus géneros y sus favoritos), luego usuarios,
     * películas y por último los géneros, que ya nadie usa.
     */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
