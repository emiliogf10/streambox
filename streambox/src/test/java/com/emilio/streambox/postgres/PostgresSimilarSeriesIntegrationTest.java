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
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.SimilarSeriesQueryContract;

import jakarta.persistence.EntityManagerFactory;

/**
 * Consulta de «series similares» contra <strong>PostgreSQL real</strong>
 * (Testcontainers). Ejecuta los mismos casos que la versión H2
 * ({@code SimilarSeriesRepositoryIntegrationTest}), definidos en
 * {@link SimilarSeriesQueryContract}.
 *
 * <p>
 * Lo que puede cambiar el motor, como en
 * {@link PostgresSimilarMoviesIntegrationTest}: {@code FETCH FIRST :limit ROWS
 * ONLY} con un parámetro enlazado, la validación estricta del
 * {@code GROUP BY}, el tipo que devuelve el driver para {@code series_id}
 * ({@code bigint} → {@link Long}), el orden de {@code TIMESTAMP WITH TIME
 * ZONE} y que el {@code EXISTS} correlacionado sobre {@code episodes} filtre
 * sin duplicar filas.
 * </p>
 */
class PostgresSimilarSeriesIntegrationTest extends PostgresIntegrationTestSupport
        implements SimilarSeriesQueryContract {

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
    public SeriesRepository seriesRepo() {
        return seriesRepository;
    }

    @Override
    public Genre genre(String name) {
        return saveGenre(name);
    }

    @Override
    public Series emptySeries(String title, int year, Genre... genres) {
        return saveSeries(title, year, genres);
    }

    @Override
    public void addEpisode(Series series, int season, int number) {
        saveEpisode(series, season, number);
    }

    @Override
    public void setCreatedAt(Series series, Instant createdAt) {
        jdbc.update("UPDATE series SET created_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC), series.getId());
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
