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
 * Reservas de nombre de usuario ({@code V13}) contra el esquema real de Flyway
 * en <strong>H2</strong> (modo PostgreSQL). Los casos están en
 * {@link UsernameReservationQueryContract}; su gemela contra PostgreSQL real
 * (que además prueba la concurrencia) es
 * {@code PostgresUsernameReservationIntegrationTest}.
 *
 * <p>
 * Mismas propiedades que {@link FavoriteGenresRepositoryIntegrationTest}
 * (reutiliza su contexto de Spring): datos confirmados de verdad, sin
 * {@code @Transactional}, y limpieza antes y después de cada test.
 * </p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class UsernameReservationRepositoryIntegrationTest implements UsernameReservationQueryContract {

    @Autowired private UsernameReservationRepository usernameReservationRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach
    void setUp() {
        cleanReservations();
    }

    @AfterEach
    void tearDown() {
        cleanReservations();
    }

    @Override
    public UsernameReservationRepository reservations() {
        return usernameReservationRepository;
    }

    @Override
    public JdbcTemplate jdbc() {
        return jdbc;
    }

    @Override
    public TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    /** Vacía las reservas; sus dueños se van en cascada. */
    private void cleanReservations() {
        jdbc.update("DELETE FROM username_reservations");
    }
}
