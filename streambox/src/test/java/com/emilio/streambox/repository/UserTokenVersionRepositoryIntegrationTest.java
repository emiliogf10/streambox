package com.emilio.streambox.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/**
 * Versión de los tokens de acceso ({@code users.token_version}, V12):
 * {@link UserRepository#incrementTokenVersion(Long)},
 * {@link UserRepository#findTokenVersionById(Long)} y las garantías del campo
 * {@link User#getTokenVersion()} contra el esquema real de Flyway (H2).
 *
 * <p>
 * Como {@link AccountTokenRepositoryIntegrationTest}: los datos se confirman de
 * verdad (sin {@code @Transactional} en la clase) y se limpian antes y después
 * de cada test; lo que debe ocurrir dentro de una transacción (como en un
 * servicio) va en un {@link TransactionTemplate}. Que el incremento no pierde
 * sumas con peticiones simultáneas se prueba contra PostgreSQL en
 * {@code postgres.PostgresTokenVersionIntegrationTest}.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
class UserTokenVersionRepositoryIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        userRepository.deleteAll();
        alice = userRepository.save(newUser("alice"));
        bob = userRepository.save(newUser("bob"));
    }

    @AfterEach
    void tearDown() {
        userRepository.deleteAll();
    }

    /** Una cuenta nueva nace en la versión 0: en la entidad guardada, en la consulta y en la fila. */
    @Test
    void unaCuentaNuevaNaceEnLaVersionCero() {
        assertEquals(0, alice.getTokenVersion());
        assertEquals(Optional.of(0), userRepository.findTokenVersionById(alice.getId()));
        assertEquals(0, rowVersion(alice));
        assertEquals(0, userRepository.findById(alice.getId()).orElseThrow().getTokenVersion());
    }

    /** Cada llamada suma exactamente 1, solo a esa cuenta, y devuelve 1 (una fila). */
    @Test
    void incrementarSumaUnoSoloALaCuentaIndicada() {
        assertEquals(1, incrementInTransaction(alice.getId()));
        assertEquals(1, incrementInTransaction(alice.getId()));

        assertEquals(2, rowVersion(alice));
        assertEquals(Optional.of(2), userRepository.findTokenVersionById(alice.getId()));
        assertEquals(2, userRepository.findById(alice.getId()).orElseThrow().getTokenVersion());
        assertEquals(0, rowVersion(bob), "la versión de las demás cuentas no cambia");
    }

    /** Una cuenta inexistente (borrada durante la petición): 0 filas, nada que leer y ninguna fila tocada. */
    @Test
    void conUnaCuentaInexistenteDevuelveCeroYLaLecturaVacio() {
        assertEquals(0, incrementInTransaction(Long.MAX_VALUE));

        assertEquals(Optional.empty(), userRepository.findTokenVersionById(Long.MAX_VALUE));
        assertEquals(0, rowVersion(alice));
        assertEquals(0, rowVersion(bob));
    }

    /**
     * El caso del cambio de contraseña: en una transacción con el usuario
     * bloqueado y cargado en la sesión ({@code PESSIMISTIC_WRITE}), con un
     * cambio pendiente. Al incrementar, {@code flushAutomatically} escribe antes
     * el cambio pendiente; la entidad en memoria <b>conserva la versión
     * antigua</b> (no se vacía la sesión), mientras que
     * {@code findTokenVersionById} ya devuelve la nueva: es la que debe llevar
     * el JWT que se emita después. Los cambios hechos sobre la entidad tras el
     * incremento se siguen guardando (sigue gestionada) y no devuelven la
     * versión a la antigua.
     */
    @Test
    void enLaMismaTransaccionLaEntidadQuedaDesfasadaYLaConsultaVeLaVersionNueva() {
        int[] seen = tx.execute(status -> {
            User locked = entityManager.find(User.class, alice.getId(), LockModeType.PESSIMISTIC_WRITE);
            locked.setPassword("hash-nuevo");

            int rows = userRepository.incrementTokenVersion(alice.getId());
            int inMemory = locked.getTokenVersion();
            int fromQuery = userRepository.findTokenVersionById(alice.getId()).orElseThrow();
            boolean stillManaged = entityManager.contains(locked);
            locked.setAvatar("rocket");
            return new int[] { rows, inMemory, fromQuery, stillManaged ? 1 : 0 };
        });

        assertEquals(1, seen[0]);
        assertEquals(0, seen[1], "la entidad de la sesión conserva la versión antigua");
        assertEquals(1, seen[2], "la consulta lee la versión nueva de la base de datos");
        assertEquals(1, seen[3], "sin clearAutomatically la entidad sigue gestionada");
        assertEquals(1, rowVersion(alice), "guardar la entidad desfasada no devuelve la versión a la antigua");
        assertEquals("hash-nuevo", jdbc.queryForObject("SELECT password FROM users WHERE id = ?",
                String.class, alice.getId()), "el cambio pendiente se escribió (flushAutomatically)");
        assertEquals("rocket", jdbc.queryForObject("SELECT avatar FROM users WHERE id = ?",
                String.class, alice.getId()), "los cambios posteriores al incremento también se guardan");
    }

    /**
     * Guardar una copia antigua de la cuenta (leída antes de una revocación,
     * por ejemplo por un {@code PATCH /api/users/me} simultáneo) no deshace la
     * revocación: {@code token_version} es {@code updatable = false}, así que
     * nunca va en el {@code UPDATE} que genera Hibernate.
     */
    @Test
    void guardarUnaCopiaAntiguaDeLaCuentaNoDeshaceLaRevocacion() {
        User stale = userRepository.findById(alice.getId()).orElseThrow();
        tx.executeWithoutResult(status -> userRepository.incrementTokenVersion(alice.getId()));

        stale.setUsername("alice_renombrada");
        userRepository.save(stale);

        assertEquals(1, rowVersion(alice));
        assertEquals("alice_renombrada", jdbc.queryForObject("SELECT username FROM users WHERE id = ?",
                String.class, alice.getId()));
    }

    /**
     * La versión no tiene setter: ningún DTO, mapper ni servicio puede
     * asignarla (bajarla volvería a dar por buenos tokens revocados). Si
     * alguien quitara {@code @Setter(AccessLevel.NONE)}, este test lo detecta.
     */
    @Test
    void laVersionNoTieneSetter() {
        assertTrue(Arrays.stream(User.class.getMethods()).noneMatch(m -> m.getName().equals("setTokenVersion")));
    }

    /** Incrementa en su propia transacción confirmada, como haría un servicio. */
    private int incrementInTransaction(Long userId) {
        return tx.execute(status -> userRepository.incrementTokenVersion(userId));
    }

    private int rowVersion(User user) {
        return jdbc.queryForObject("SELECT token_version FROM users WHERE id = ?", Integer.class, user.getId());
    }

    private static User newUser(String name) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword("hash-no-usado");
        user.setRole(Role.USER);
        return user;
    }
}
