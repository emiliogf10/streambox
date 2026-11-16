package com.emilio.streambox.security.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.emilio.streambox.exception.AccountLockedException;
import com.emilio.streambox.security.ratelimit.LoginAttemptService.LoginAttempt;
import com.emilio.streambox.security.ratelimit.SlidingWindowCounterTest.MutableClock;

/**
 * Revisión independiente (QA): la reserva de intentos con cookie de
 * dispositivo sigue siendo <b>atómica</b> bajo concurrencia.
 *
 * <p>
 * <b>Qué protege.</b> Quien tenga una cookie de dispositivo válida (p. ej.
 * robada del disco) y lance muchas peticiones de login a la vez con ella no
 * debe poder comprobar más de {@code maxFailures} contraseñas por ventana: si
 * el contador del dispositivo se consultara y se incrementara en dos pasos,
 * todas las peticiones simultáneas verían «0 fallos» y pasarían. Mezclar
 * peticiones con y sin cookie tampoco debe dar más que la suma de los dos
 * presupuestos (5 del dispositivo + 5 de la cuenta).
 * </p>
 */
class LoginAttemptServiceDeviceConcurrencyTest {

    private static final String OWNER = "titular@device-concurrencia.test";
    private static final String DEVICE = "CCCCCCCCCCCCCCCCCCCCCC";
    private static final int THREADS = 64;

    private final LoginAttemptService service = service();

    private static LoginAttemptService service() {
        RateLimitProperties.Rule rule = new RateLimitProperties.Rule(100, Duration.ofMinutes(1));
        return new LoginAttemptService(new RateLimitProperties(rule, rule, rule, rule, rule, rule, rule,
                new RateLimitProperties.Lockout(5, Duration.ofMinutes(15), 5, Duration.ofDays(30)), 100_000),
                new MutableClock());
    }

    /**
     * 64 reservas simultáneas con el mismo dispositivo: exactamente 5 pasan
     * (las que llegarían a comprobar la contraseña) y el resto recibe
     * {@link AccountLockedException} sin reservar. La cuenta no gasta nada.
     */
    @Test
    void reservasSimultaneasConLaMismaCookieSoloDejanPasarCinco() throws Exception {
        List<Boolean> reserved = runConcurrently(THREADS, i -> () -> {
            try {
                service.reserveAttempt(OWNER, "198.51.100." + (i % 250), DEVICE);
                return true;
            } catch (AccountLockedException e) {
                return false;
            }
        });

        assertEquals(5, reserved.stream().filter(Boolean::booleanValue).count(), reserved::toString);
        // El contador de la cuenta sigue intacto: sin cookie, 4 restantes tras un fallo.
        LoginAttempt withoutCookie = service.reserveAttempt(OWNER, "192.0.2.77");
        assertNull(withoutCookie.deviceId());
        assertEquals(4, service.recordFailure(withoutCookie));
    }

    /**
     * Mitad con cookie y mitad sin ella, a la vez y confirmando cada fallo:
     * como mucho 10 contraseñas comprobadas (5 + 5), con 8 respuestas de
     * «quedan N» y 2 bloqueos por el fallo que agota cada contador.
     */
    @Test
    void mezclarConYSinCookieNoSuperaLaSumaDeLosDosPresupuestos() throws Exception {
        List<String> outcomes = runConcurrently(THREADS, i -> () -> {
            String deviceId = i % 2 == 0 ? DEVICE : null;
            LoginAttempt attempt;
            try {
                attempt = service.reserveAttempt(OWNER, "203.0.113." + (i % 250), deviceId);
            } catch (AccountLockedException e) {
                return "rechazado";
            }
            try {
                return "quedan-" + service.recordFailure(attempt);
            } catch (AccountLockedException e) {
                return "agotado";
            }
        });

        assertEquals(8, outcomes.stream().filter(o -> o.startsWith("quedan-")).count(), outcomes::toString);
        assertEquals(2, outcomes.stream().filter("agotado"::equals).count(), outcomes::toString);
        assertEquals(THREADS - 10, outcomes.stream().filter("rechazado"::equals).count(), outcomes::toString);
    }

    /** Lanza {@code count} tareas que arrancan a la vez y devuelve sus resultados. */
    private static <T> List<T> runConcurrently(int count, java.util.function.IntFunction<Callable<T>> task)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch ready = new CountDownLatch(count);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                Callable<T> body = task.apply(i);
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return body.call();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
