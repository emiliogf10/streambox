package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Savepoint;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.repository.UsernameReservationRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * {@link TransactionSavepoints} contra la base de datos de los tests (H2),
 * dentro de transacciones reales del {@code JpaTransactionManager} de la
 * aplicación: que el punto de guardado se toma en la <b>misma conexión</b> que
 * usan JPA y {@code JdbcClient} (si no, volver a él no desharía nada), que
 * vuelve a valer después de usarlo, que vacía la sesión de Hibernate y que
 * exige una transacción.
 *
 * <p>
 * Que en PostgreSQL además suelta los bloqueos de filas tomados después del
 * punto lo prueban las carreras de {@code PostgresUsernameRaceIntegrationTest}
 * (MEDIA-A): sin eso, el registro repetido se quedaría esperando su propia
 * fila... o la de otro, con riesgo de interbloqueo.
 * </p>
 *
 * <p>
 * Sin {@code @Transactional}: cada test abre y confirma la suya con
 * {@link TransactionTemplate}, y los datos se borran en {@code @AfterEach}.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
class TransactionSavepointsIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-05-01T10:00:00Z");
    private static final Duration TTL = Duration.ofHours(24);

    @Autowired private TransactionSavepoints savepoints;
    @Autowired private UsernameReservationRepository reservations;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM username_reservations WHERE username LIKE 'sp\\_%' ESCAPE '\\'");
        jdbc.update("DELETE FROM users WHERE email LIKE '%@savepoint.test'");
    }

    /**
     * Lo hecho antes del punto se confirma; lo hecho después se deshace al
     * volver, también la segunda vez que se vuelve al mismo punto; lo hecho
     * tras la última vuelta se confirma.
     */
    @Test
    void volverAlPuntoDeshaceSoloLoPosteriorYElPuntoSigueValiendo() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            reservations.reserve("sp_antes", "a@savepoint.test", NOW, TTL);
            Savepoint savepoint = savepoints.create();

            reservations.reserve("sp_primer_intento", "a@savepoint.test", NOW, TTL);
            savepoints.rollbackTo(savepoint);
            reservations.reserve("sp_segundo_intento", "a@savepoint.test", NOW, TTL);
            savepoints.rollbackTo(savepoint);
            reservations.reserve("sp_final", "a@savepoint.test", NOW, TTL);
        });

        assertEquals(List.of("sp_antes", "sp_final"), jdbc.queryForList(
                "SELECT username FROM username_reservations WHERE username LIKE 'sp\\_%' ESCAPE '\\'"
                        + " ORDER BY username", String.class));
    }

    /**
     * Las escrituras de JPA también vuelven atrás (misma conexión), y la
     * sesión de Hibernate queda vacía: una entidad cargada antes ya no está en
     * ella, así que la siguiente lectura sale de la base de datos.
     */
    @Test
    void lasEscriturasDeJpaTambienSeDeshacenYLaSesionQuedaVacia() {
        Long keptId = new TransactionTemplate(transactionManager).execute(status -> {
            User kept = userRepository.save(user("sp_conservada"));
            Savepoint savepoint = savepoints.create();
            userRepository.save(user("sp_deshecha"));

            savepoints.rollbackTo(savepoint);

            assertFalse(entityManager.contains(kept), "la sesión de Hibernate se vacía");
            assertTrue(userRepository.findByEmail("sp_deshecha@savepoint.test").isEmpty());
            return kept.getId();
        });

        assertTrue(userRepository.findById(keptId).isPresent());
        assertTrue(userRepository.findByEmail("sp_deshecha@savepoint.test").isEmpty());
    }

    /** Sin transacción no hay punto que tomar: es un error de programación. */
    @Test
    void sinTransaccionFalla() {
        assertThrows(IllegalTransactionStateException.class, () -> savepoints.create());
    }

    private static User user(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@savepoint.test");
        user.setPassword("no-se-usa");
        user.setRole(Role.USER);
        return user;
    }
}
