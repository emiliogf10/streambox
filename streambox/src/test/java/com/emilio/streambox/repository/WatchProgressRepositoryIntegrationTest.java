package com.emilio.streambox.repository;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Progreso de visionado ({@code V14}) contra el esquema real de Flyway en
 * <strong>H2</strong> (modo PostgreSQL). Los casos están en
 * {@link WatchProgressQueryContract}; su gemela contra PostgreSQL real (que
 * además prueba la carrera de dos primeros guardados) es
 * {@code PostgresWatchProgressIntegrationTest}.
 *
 * <p>
 * Mismas propiedades que {@link LikesRepositoryIntegrationTest} (reutiliza su
 * contexto de Spring): datos confirmados de verdad, sin {@code @Transactional},
 * y limpieza antes y después de cada test.
 * </p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class WatchProgressRepositoryIntegrationTest implements WatchProgressQueryContract {

    /** H2: {@code CHECK_CONSTRAINT_VIOLATED_1} (PostgreSQL usa {@code 23514}). */
    private static final String H2_CHECK_VIOLATION = "23513";

    /** H2: {@code REFERENTIAL_INTEGRITY_VIOLATED_PARENT_MISSING_1} (PostgreSQL usa {@code 23503}). */
    private static final String H2_FOREIGN_KEY_PARENT_MISSING = "23506";

    @Autowired private WatchProgressRepository watchProgressRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private SeriesRepository seriesRepository;
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
    public WatchProgressRepository progress() {
        return watchProgressRepository;
    }

    @Override
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    @Override
    public TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @Override
    public String checkViolationState() {
        return H2_CHECK_VIOLATION;
    }

    @Override
    public String foreignKeyViolationState() {
        return H2_FOREIGN_KEY_PARENT_MISSING;
    }

    /**
     * Vacía las tablas respetando las claves foráneas (mismo orden que
     * {@link LikesRepositoryIntegrationTest}); el progreso se va en cascada con
     * sus series (y episodios), usuarios y películas.
     */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
    }
}
