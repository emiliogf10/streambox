package com.emilio.streambox.repository;

import java.util.HashSet;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.Episode;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.entity.User;

import jakarta.persistence.EntityManagerFactory;

/**
 * «Me gusta» y recomendaciones ({@code V8}) contra el esquema real de Flyway en
 * <strong>H2</strong> (modo PostgreSQL). Los casos están en
 * {@link LikesQueryContract}; su gemela contra PostgreSQL real es
 * {@code PostgresLikesIntegrationTest}.
 *
 * <p>
 * Mismo estilo y propiedades que {@link SimilarMoviesRepositoryIntegrationTest}
 * (reutiliza su contexto de Spring): datos confirmados de verdad, sin
 * {@code @Transactional}, limpieza antes y después de cada test, y las
 * estadísticas de Hibernate para contar sentencias.
 * </p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LikesRepositoryIntegrationTest implements LikesQueryContract {

    @Autowired private MovieRepository movieRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Override
    public MovieRepository movies() {
        return movieRepository;
    }

    @Override
    public SeriesRepository series() {
        return seriesRepository;
    }

    @Override
    public UserRepository users() {
        return userRepository;
    }

    @Override
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    @Override
    public Genre genre(String name) {
        Genre genre = new Genre();
        genre.setName(name);
        return genreRepository.save(genre);
    }

    @Override
    public Movie movie(String title, Genre... genres) {
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

    @Override
    public Series serie(String title, Genre... genres) {
        Series series = new Series();
        series.setTitle(title);
        series.setDescription("Sinopsis de " + title);
        series.setReleaseYear(2010);
        series.setImageUrl("https://example.com/serie.jpg");
        series.setGenres(new HashSet<>(List.of(genres)));
        return seriesRepository.save(series);
    }

    @Override
    public void episode(Series series, int number) {
        Episode episode = new Episode();
        episode.setSeries(series);
        episode.setSeasonNumber(1);
        episode.setEpisodeNumber(number);
        episode.setTitle("Episodio " + number);
        episode.setDuration(45);
        episode.setVideoUrl("https://example.com/episodio.mp4");
        episodeRepository.save(episode);
    }

    @Override
    public User user(String name) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword("hash-de-prueba");
        user.setRole(Role.USER);
        return userRepository.save(user);
    }

    @Override
    public long statementsFor(Runnable operation) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        operation.run();
        return statistics.getPrepareStatementCount();
    }

    @Override
    public TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * Vacía las tablas respetando las claves foráneas (mismo orden que
     * {@code SeriesRepositoryIntegrationTest}); los «me gusta» se van en
     * cascada con sus series, usuarios y películas.
     */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
