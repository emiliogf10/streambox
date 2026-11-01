package com.emilio.streambox.repository;

import java.util.HashSet;
import java.util.List;

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

/**
 * Géneros favoritos y su peso en las recomendaciones ({@code V11}) contra el
 * esquema real de Flyway en <strong>H2</strong> (modo PostgreSQL). Los casos
 * están en {@link FavoriteGenresQueryContract}; su gemela contra PostgreSQL
 * real es {@code PostgresFavoriteGenresIntegrationTest}.
 *
 * <p>
 * Mismo estilo y propiedades que {@link LikesRepositoryIntegrationTest}
 * (reutiliza su contexto de Spring): datos confirmados de verdad, sin
 * {@code @Transactional}, y limpieza antes y después de cada test.
 * </p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class FavoriteGenresRepositoryIntegrationTest implements FavoriteGenresQueryContract {

    @Autowired private MovieRepository movieRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
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
    public GenreRepository genres() {
        return genreRepository;
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
    public TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * Vacía las tablas respetando las claves foráneas (mismo orden que
     * {@link LikesRepositoryIntegrationTest}); los gustos y los «me gusta» se
     * van en cascada con sus usuarios, géneros y títulos.
     */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
