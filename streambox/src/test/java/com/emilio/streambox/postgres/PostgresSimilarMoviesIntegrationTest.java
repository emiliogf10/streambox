package com.emilio.streambox.postgres;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SimilarMoviesQueryContract;

import jakarta.persistence.EntityManagerFactory;

/**
 * Consulta de «películas similares» contra <strong>PostgreSQL real</strong>
 * (Testcontainers). Ejecuta los mismos casos que la versión H2
 * ({@code SimilarMoviesRepositoryIntegrationTest}), definidos en
 * {@link SimilarMoviesQueryContract}.
 *
 * <p>
 * Qué puede cambiar el motor aquí y por eso se repite: la sintaxis
 * {@code FETCH FIRST :limit ROWS ONLY} con un parámetro enlazado, la
 * validación del {@code GROUP BY} (PostgreSQL exige que todo lo que aparece en
 * el {@code ORDER BY} esté agrupado o agregado), el tipo que devuelve el driver
 * para {@code movie_id} ({@code bigint} → {@link Long}), el orden de
 * {@code TIMESTAMP WITH TIME ZONE} y que el {@code JOIN FETCH} con
 * {@code IN} siga siendo una única sentencia.
 * </p>
 */
class PostgresSimilarMoviesIntegrationTest extends PostgresIntegrationTestSupport
        implements SimilarMoviesQueryContract {

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
    public Genre genre(String name) {
        return saveGenre(name);
    }

    @Override
    public Movie movie(String title, int year, Genre... genres) {
        return saveMovie(title, year, genres);
    }

    @Override
    public void setCreatedAt(Movie movie, Instant createdAt) {
        jdbc.update("UPDATE movies SET created_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC), movie.getId());
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
}
