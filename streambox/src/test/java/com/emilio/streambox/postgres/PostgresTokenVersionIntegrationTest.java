package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;

/**
 * Versión de los tokens de acceso ({@code users.token_version}, V12) contra
 * <b>PostgreSQL real</b>.
 *
 * <p>
 * El riesgo que cubre: si dos revocaciones simultáneas de la misma cuenta
 * (por ejemplo, «cerrar sesión en todos los dispositivos» en dos pestañas, o
 * un cambio de contraseña a la vez que un restablecimiento) leyeran la versión
 * y escribieran «leída + 1», una de las dos se perdería y ambas dejarían la
 * misma versión. Aquí muchas transacciones incrementan a la vez y se comprueba
 * que no se pierde ninguna suma: {@code token_version = token_version + 1} lo
 * calcula PostgreSQL con la fila bloqueada, y la segunda espera al bloqueo y
 * vuelve a leer el valor confirmado (READ COMMITTED). El comportamiento sin
 * concurrencia (incrementa, 0 filas si no existe, la entidad en memoria queda
 * desfasada) se prueba en {@code repository.UserTokenVersionRepositoryIntegrationTest}.
 * </p>
 */
class PostgresTokenVersionIntegrationTest extends PostgresIntegrationTestSupport {

    /** Transacciones simultáneas sobre la misma cuenta (menos que el pool de conexiones de Hikari, 10). */
    private static final int CONCURRENT = 8;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        pool = Executors.newFixedThreadPool(CONCURRENT);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        cleanDatabase();
    }

    /**
     * {@value #CONCURRENT} transacciones que arrancan a la vez e incrementan la
     * versión de la misma cuenta: todas afectan a una fila, la versión final es
     * exactamente {@value #CONCURRENT} y cada una, al leer dentro de su
     * transacción con {@code findTokenVersionById}, ve un valor distinto (el
     * suyo, que nadie puede cambiar hasta que confirme: la fila sigue
     * bloqueada). Es la versión que llevaría el JWT que emitiera cada una.
     */
    @Test
    void lasRevocacionesSimultaneasNoPierdenNingunIncremento() throws Exception {
        User user = saveUser("pgversion", Role.USER);
        User other = saveUser("pgversionotro", Role.USER);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch start = new CountDownLatch(1);

        List<Future<int[]>> results = new ArrayList<>();
        for (int i = 0; i < CONCURRENT; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return tx.execute(status -> new int[] {
                        userRepository.incrementTokenVersion(user.getId()),
                        userRepository.findTokenVersionById(user.getId()).orElseThrow() });
            }));
        }
        start.countDown();

        List<Integer> seenVersions = new ArrayList<>();
        for (Future<int[]> result : results) {
            int[] value = result.get(30, TimeUnit.SECONDS);
            assertEquals(1, value[0], "cada incremento afecta a la fila de la cuenta");
            seenVersions.add(value[1]);
        }
        assertEquals(CONCURRENT, jdbc.queryForObject("SELECT token_version FROM users WHERE id = ?",
                Integer.class, user.getId()));
        assertEquals(CONCURRENT, seenVersions.stream().distinct().count(),
                "cada transacción ve su propia versión: " + seenVersions);
        assertTrue(seenVersions.stream().allMatch(v -> v >= 1 && v <= CONCURRENT), "" + seenVersions);
        assertEquals(Optional.of(0), userRepository.findTokenVersionById(other.getId()),
                "las demás cuentas no cambian");
    }
}
