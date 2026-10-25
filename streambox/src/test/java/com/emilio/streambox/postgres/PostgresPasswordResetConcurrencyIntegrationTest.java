package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.email.AccountEmails;
import com.emilio.streambox.email.MemoryEmailSender;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.service.PasswordResetService;

/**
 * Dos confirmaciones de «He olvidado mi contraseña» <b>a la vez con el mismo
 * token</b> (un doble clic, el enlace abierto en dos pestañas) contra un
 * PostgreSQL real: una responde 204 y la otra el 400
 * {@code INVALID_RESET_TOKEN}, sin ningún 500, y la contraseña que queda es la
 * de la que ganó.
 *
 * <p>
 * <b>Por qué en PostgreSQL.</b> El servicio lee el token sin gastarlo, calcula
 * BCrypt y luego, en una transacción, bloquea la fila del usuario
 * ({@code SELECT ... FOR UPDATE}) y consume el token con un {@code UPDATE}
 * condicional. Cómo espera ese bloqueo detrás de otra transacción y qué ve el
 * {@code UPDATE} cuando la otra confirma depende del motor; H2 no bloquea igual.
 * </p>
 *
 * <p>
 * <b>Cómo se fuerza la carrera.</b> Una transacción «árbitro» bloquea antes la
 * fila del usuario; las dos peticiones se lanzan en hilos, pasan las dos la
 * lectura inicial (el token aún está vigente) y el test espera a verlas
 * <b>esperando ese bloqueo</b> en {@code pg_stat_activity}; solo entonces el
 * árbitro confirma y las dos compiten de verdad por el token. Sin el árbitro,
 * una podría terminar antes de que la otra empezara y el test no probaría la
 * carrera. Datos con commit real, limpiados antes y después; sin Docker la
 * clase se omite.
 * </p>
 */
class PostgresPasswordResetConcurrencyIntegrationTest extends PostgresIntegrationTestSupport {

    private static final Pattern LINK = Pattern.compile("#token=([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])");

    @Autowired private MockMvc mockMvc;
    @Autowired private PasswordResetService passwordResetService;
    @Autowired private MemoryEmailSender inbox;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private ExecutorService pool;
    private CountDownLatch releaseArbiter;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        inbox.clear();
        pool = Executors.newFixedThreadPool(3);
        releaseArbiter = new CountDownLatch(1);
    }

    @AfterEach
    void tearDown() {
        releaseArbiter.countDown();
        pool.shutdownNow();
        cleanDatabase();
    }

    @Test
    void dosConfirmacionesSimultaneasConElMismoTokenDanUn204YUn400YNingun500() throws Exception {
        User user = saveUser("restablece_pg", Role.USER);
        passwordResetService.request(user.getEmail());
        String token = tokenSentTo(user.getEmail());
        Future<?> arbiter = holdUserRowLock(user.getId());

        Future<MockHttpServletResponse> first = pool.submit(() -> confirm(token, "Primera-Clave-Nueva-2026"));
        Future<MockHttpServletResponse> second = pool.submit(() -> confirm(token, "Segunda-Clave-Nueva-2026"));
        awaitWaitingForLock(2);
        releaseArbiter.countDown();
        arbiter.get(10, TimeUnit.SECONDS);

        MockHttpServletResponse a = result(first);
        MockHttpServletResponse b = result(second);
        List<Integer> statuses = List.of(a.getStatus(), b.getStatus());
        assertTrue(statuses.contains(204) && statuses.contains(400), "Estados: " + statuses);
        MockHttpServletResponse loser = a.getStatus() == 400 ? a : b;
        assertTrue(loser.getContentAsString(StandardCharsets.UTF_8).contains("\"code\":\"INVALID_RESET_TOKEN\""),
                loser.getContentAsString(StandardCharsets.UTF_8));

        String winnerPassword = a.getStatus() == 204 ? "Primera-Clave-Nueva-2026" : "Segunda-Clave-Nueva-2026";
        String storedHash = jdbc.queryForObject("SELECT password FROM users WHERE id = ?", String.class, user.getId());
        assertTrue(passwordEncoder.matches(winnerPassword, storedHash), "Queda la contraseña de la que ganó");
        assertEquals(1, inbox.sentTo(user.getEmail()).stream()
                .filter(sent -> sent.subject().equals(AccountEmails.PASSWORD_CHANGED_SUBJECT)).count(),
                "Un solo aviso de contraseña cambiada");
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private MockHttpServletResponse confirm(String token, String newPassword) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + token + "\",\"newPassword\":\"" + newPassword + "\"}"))
                .andReturn().getResponse();
    }

    private String tokenSentTo(String address) {
        Matcher matcher = LINK.matcher(inbox.sentTo(address).get(0).body());
        assertTrue(matcher.find(), "El correo debía traer el enlace");
        return matcher.group(1);
    }

    /** Bloquea la fila del usuario en otra transacción hasta que el test la suelte. */
    private Future<?> holdUserRowLock(Long id) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        Future<?> arbiter = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, id);
            locked.countDown();
            await(releaseArbiter);
        }));
        assertTrue(locked.await(10, TimeUnit.SECONDS), "El árbitro no llegó a bloquear la fila");
        return arbiter;
    }

    /** Espera (máx. 20 s: antes, cada petición calcula BCrypt) a ver {@code expected} conexiones esperando el bloqueo. */
    private void awaitWaitingForLock(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        int waiting = 0;
        while (System.nanoTime() < deadline) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM pg_stat_activity"
                    + " WHERE datname = current_database() AND wait_event_type = 'Lock'"
                    + " AND query ILIKE ?", Integer.class, "%users%");
            waiting = count == null ? 0 : count;
            if (waiting >= expected) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Se esperaban " + expected + " conexiones esperando el bloqueo de users y hay "
                + waiting);
    }

    /** Resultado de un hilo; si lanzó una excepción, el test falla con ella. */
    private static <T> T result(Future<T> future) throws Exception {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw new AssertionError("La petición concurrente terminó con un error: " + e.getCause(), e.getCause());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("tiempo de espera agotado");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
