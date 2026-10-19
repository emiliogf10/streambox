package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;

/**
 * {@link EmailVerificationService#lockForUpdate} contra la base de datos: lo
 * que pasa cuando otra transacción cambia o borra la fila <b>entre la lectura
 * y el bloqueo</b> (hallazgo B2 de la revisión de seguridad).
 *
 * <p>
 * El cambio se confirma en otra transacción ({@code REQUIRES_NEW}) antes de
 * pedir el bloqueo, así que no hace falta concurrencia real: es la misma
 * secuencia que con dos peticiones, sin depender de tiempos. La espera real
 * del bloqueo entre dos conexiones se prueba mejor contra PostgreSQL
 * (pendiente para {@code qa}).
 * </p>
 *
 * <ul>
 *   <li><b>Borrada</b>: vacío. Antes, {@code EntityManager.refresh(user,
 *       PESSIMISTIC_WRITE)} lanzaba {@code EntityNotFoundException} y el
 *       registro o el reenvío respondían 500.</li>
 *   <li><b>Cambiada</b> (verificada entre medias): devuelve el estado nuevo.
 *       Sin desacoplar la entidad antes de la consulta, Hibernate devolvería
 *       la instancia ya cargada con el estado de antes.</li>
 * </ul>
 *
 * <p>
 * Sin {@code @Transactional}: los cambios de la otra transacción tienen que
 * confirmarse de verdad. Las cuentas del dominio {@value #DOMAIN} se borran
 * antes y después de cada test.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountLockIntegrationTest {

    private static final String DOMAIN = "@bloqueo-cuenta.test";

    @Autowired private EmailVerificationService emailVerificationService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private TransactionTemplate otherTransaction;

    @BeforeEach
    void setUp() {
        cleanUp();
        transaction = new TransactionTemplate(transactionManager);
        otherTransaction = new TransactionTemplate(transactionManager);
        otherTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
    }

    @Test
    void siLaCuentaSeBorraAntesDelBloqueoDevuelveVacioEnLugarDeFallar() {
        createPendingUser("borrada");

        Optional<User> locked = transaction.execute(status -> {
            User read = userRepository.findByEmail("borrada" + DOMAIN).orElseThrow();
            otherTransaction.executeWithoutResult(other ->
                    jdbc.update("DELETE FROM users WHERE id = ?", read.getId()));
            return emailVerificationService.lockForUpdate(read);
        });

        assertTrue(locked.isEmpty());
    }

    @Test
    void siLaCuentaCambiaAntesDelBloqueoDevuelveElEstadoNuevo() {
        createPendingUser("verificada");

        User locked = transaction.execute(status -> {
            User read = userRepository.findByEmail("verificada" + DOMAIN).orElseThrow();
            otherTransaction.executeWithoutResult(other -> jdbc.update(
                    "UPDATE users SET email_verified_at = ? WHERE id = ?",
                    java.sql.Timestamp.from(Instant.now()), read.getId()));
            User fresh = emailVerificationService.lockForUpdate(read).orElseThrow();
            assertNotSame(read, fresh);
            return fresh;
        });

        assertNotNull(locked.getEmailVerifiedAt());
    }

    private void createPendingUser(String name) {
        User user = new User();
        user.setUsername(name + "_bloqueo");
        user.setEmail(name + DOMAIN);
        user.setPassword("hash-de-prueba");
        user.setRole(Role.USER);
        userRepository.saveAndFlush(user);
    }
}
