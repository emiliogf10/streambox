package com.emilio.streambox.security.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.emilio.streambox.exception.AccountLockedException;
import com.emilio.streambox.security.ratelimit.LoginAttemptService.LoginAttempt;
import com.emilio.streambox.security.ratelimit.SlidingWindowCounterTest.MutableClock;

/**
 * Tests unitarios de {@link LoginAttemptService} con un reloj controlado
 * (máximo 5 fallos en 15 minutos, los valores por defecto).
 */
class LoginAttemptServiceTest {

    private static final String EMAIL = "ana@test.com";
    private static final String IP = "203.0.113.5";

    /**
     * Otra IP desconocida, distinta de {@link #IP}. Tras un login correcto
     * desde una IP esta pasa a ser «conocida» y sus fallos posteriores van a su
     * propio contador (que parte vacío), así que un test que quiera comprobar
     * el contador de la CUENTA después de un acierto debe hacer el acierto
     * desde una IP y los fallos posteriores desde la otra, nunca desde la misma.
     */
    private static final String STRANGER_IP = "198.51.100.77";

    private final MutableClock clock = new MutableClock();
    private final LoginAttemptService service = new LoginAttemptService(properties(5, Duration.ofMinutes(15)), clock);

    // ------------------------------------------------------------------
    // Intentos restantes
    // ------------------------------------------------------------------

    @Test
    void cadaFalloDevuelveLosIntentosQueQuedan() {
        assertEquals(4, fail(EMAIL));
        assertEquals(3, fail(EMAIL));
        assertEquals(2, fail(EMAIL));
        assertEquals(1, fail(EMAIL));
    }

    @Test
    void elFalloQueAlcanzaElMaximoBloqueaDurante15Minutos() {
        failTimes(EMAIL, 4);

        AccountLockedException locked = assertThrows(AccountLockedException.class, () -> fail(EMAIL));

        assertEquals(Duration.ofMinutes(15), locked.getRetryAfter());
        assertEquals("Has superado el número máximo de intentos. La cuenta queda bloqueada durante 15 minutos.",
                locked.getMessage());
    }

    @Test
    void duranteElBloqueoNoSeReservanIntentosYSeIndicaElTiempoRestante() {
        failTimes(EMAIL, 4);
        assertThrows(AccountLockedException.class, () -> fail(EMAIL));

        clock.advance(Duration.ofMinutes(10));

        AccountLockedException locked =
                assertThrows(AccountLockedException.class, () -> service.reserveAttempt(EMAIL, IP));
        assertEquals(Duration.ofMinutes(5), locked.getRetryAfter());
        assertEquals("La cuenta está bloqueada temporalmente por demasiados intentos fallidos. "
                + "Inténtalo de nuevo en 5 minutos.", locked.getMessage());
    }

    @Test
    void conLaVentanaDeslizanteElBloqueoDuraHastaQueCaducaElFalloMasAntiguo() {
        fail(EMAIL);                              // t = 0
        clock.advance(Duration.ofMinutes(9));
        failTimes(EMAIL, 3);                      // t = 9

        AccountLockedException locked = assertThrows(AccountLockedException.class, () -> fail(EMAIL));

        // El primer fallo caduca en t = 15: quedan 6 minutos.
        assertEquals(Duration.ofMinutes(6), locked.getRetryAfter());
        assertTrue(locked.getMessage().endsWith("bloqueada durante 6 minutos."));
    }

    @Test
    void alCaducarLosFallosSePuedeVolverAIntentar() {
        failTimes(EMAIL, 4);
        assertThrows(AccountLockedException.class, () -> fail(EMAIL));

        clock.advance(Duration.ofMinutes(15).plusSeconds(1));

        assertEquals(4, fail(EMAIL));
    }

    /**
     * Los fallos salen de una IP desconocida ({@link #IP}, que nunca acierta) y
     * el acierto, de OTRA IP desconocida ({@link #STRANGER_IP}): ambos usan el
     * contador de la cuenta, así que el acierto tiene que borrarlo. Si el
     * acierto viniera de la misma IP que los fallos posteriores, esa IP pasaría
     * a ser «conocida», sus fallos irían a otro contador (vacío) y el test
     * daría 4 restantes aunque el contador de la cuenta no se hubiera
     * reiniciado.
     */
    @Test
    void unLoginCorrectoBorraLosFallos() {
        failTimes(EMAIL, 3);
        service.recordSuccess(service.reserveAttempt(EMAIL, STRANGER_IP));

        assertEquals(4, fail(EMAIL));
    }

    @Test
    void conLaContrasenaCorrectaEnElUltimoIntentoNoSeBloquea() {
        // Bloquea el quinto FALLO, no el quinto intento. El acierto sale de otra
        // IP desconocida (ver unLoginCorrectoBorraLosFallos).
        failTimes(EMAIL, 4);
        LoginAttempt fifth = service.reserveAttempt(EMAIL, STRANGER_IP);
        service.recordSuccess(fifth);

        assertEquals(4, fail(EMAIL));
    }

    /**
     * Caso explícito: fallos desde una IP desconocida A, acierto desde otra IP
     * desconocida B (reserva en el contador de la cuenta y lo reinicia) y
     * fallos posteriores otra vez desde A, que sigue siendo desconocida: empiezan
     * de nuevo en 4 restantes y el quinto fallo bloquea, sin arrastrar ni los
     * fallos anteriores ni la reserva del acierto. Quitar el reinicio del
     * contador de la cuenta en {@code recordSuccess} hace fallar este test.
     */
    @Test
    void fallosDesdeIpDesconocidaAciertoDesdeOtraIpDesconocidaYLosFallosPosterioresEmpiezanEnCuatro() {
        failTimes(EMAIL, 4);                                          // A: 4 fallos de 5

        service.recordSuccess(service.reserveAttempt(EMAIL, STRANGER_IP));   // B acierta

        assertEquals(4, fail(EMAIL));
        assertEquals(3, fail(EMAIL));
        assertEquals(2, fail(EMAIL));
        assertEquals(1, fail(EMAIL));
        assertThrows(AccountLockedException.class, () -> fail(EMAIL));
        // B ahora es conocida: tiene su propio contador y no le afecta el bloqueo de A.
        assertEquals(4, failFrom(EMAIL, STRANGER_IP));
    }

    // ------------------------------------------------------------------
    // Carreras con un login correcto o con la caducidad (regresión)
    // ------------------------------------------------------------------

    /**
     * Regresión: cinco intentos reservados a la vez y el primero acierta. Antes
     * se decidía con el número de reserva (2 a 5): los fallos respondían 3, 2
     * y 1, el último daba 429 {@code ACCOUNT_LOCKED} con la cuenta sin
     * bloquear (el contador estaba a 0 tras el acierto) y ninguno quedaba
     * contado. Ahora cuentan como fallos posteriores al acierto.
     */
    @Test
    void unLoginCorrectoSimultaneoNoProvocaUnFalsoBloqueoYLosFallosEnCursoSeCuentan() {
        LoginAttempt first = service.reserveAttempt(EMAIL, IP);
        List<LoginAttempt> inFlight = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            inFlight.add(service.reserveAttempt(EMAIL, IP));
        }

        service.recordSuccess(first);

        List<Integer> remaining = new ArrayList<>();
        for (LoginAttempt attempt : inFlight) {
            remaining.add(service.recordFailure(attempt));
        }
        assertEquals(List.of(4, 3, 2, 1), remaining);

        // Los cuatro quedaron contados en el contador de la cuenta: el siguiente
        // fallo de una IP desconocida es el quinto y bloquea.
        assertThrows(AccountLockedException.class, () -> failFrom(EMAIL, STRANGER_IP));
    }

    /**
     * Regresión: un fallo antiguo caduca mientras se comprueba la contraseña
     * del quinto intento. En la ventana solo hay cuatro fallos (contando este),
     * así que no se bloquea: se responde 401 con 1 intento. Antes el número de
     * reserva (5) daba un 429 {@code ACCOUNT_LOCKED} con la cuenta sin bloquear.
     */
    @Test
    void siCaducaUnFalloAntiguoMientrasSeCompruebaLaContrasenaNoHayFalsoBloqueo() {
        fail(EMAIL);                                           // t = 0
        clock.advance(Duration.ofMinutes(10));
        failTimes(EMAIL, 3);                                   // t = 10 min
        clock.advance(Duration.ofMinutes(5).minusMillis(100));
        LoginAttempt fifth = service.reserveAttempt(EMAIL, IP);    // t = 15 min - 100 ms
        assertEquals(5, fifth.number());

        clock.advance(Duration.ofMillis(200));                 // caduca el fallo de t = 0

        assertEquals(1, service.recordFailure(fifth));
        assertThrows(AccountLockedException.class, () -> fail(EMAIL));
    }

    /**
     * Carrera extrema: un acierto borra la reserva y, antes de que ese intento
     * termine de fallar, otros cinco fallos bloquean la cuenta. Al volver a
     * apuntarlo no cabe: se responde que la cuenta está bloqueada, nunca un
     * 401 con 0 intentos.
     */
    @Test
    void siAlVolverAApuntarElFalloLaCuentaYaEstaBloqueadaSeRespondeBloqueada() {
        LoginAttempt stale = service.reserveAttempt(EMAIL, IP);
        service.recordSuccess(service.reserveAttempt(EMAIL, IP));
        for (int i = 0; i < 4; i++) {
            failFrom(EMAIL, STRANGER_IP);
        }
        assertThrows(AccountLockedException.class, () -> failFrom(EMAIL, STRANGER_IP));

        AccountLockedException locked = assertThrows(AccountLockedException.class,
                () -> service.recordFailure(stale));

        assertEquals(Duration.ofMinutes(15), locked.getRetryAfter());
        assertTrue(locked.getMessage().startsWith("La cuenta está bloqueada temporalmente"));
    }

    @Test
    void elEmailSeNormaliza() {
        assertEquals(4, fail("  ANA@Test.com "));
        assertEquals(3, fail("ana@test.com"));
    }

    @Test
    void cadaEmailTieneSuPropiaCuenta() {
        failTimes(EMAIL, 4);
        assertThrows(AccountLockedException.class, () -> fail(EMAIL));

        assertEquals(4, fail("otro@test.com"));
    }

    // ------------------------------------------------------------------
    // Redondeo del mensaje
    // ------------------------------------------------------------------

    @Test
    void losMinutosSeRedondeanHaciaArriba() {
        assertEquals("1 minuto", LoginAttemptService.minutes(Duration.ofSeconds(1)));
        assertEquals("1 minuto", LoginAttemptService.minutes(Duration.ofSeconds(60)));
        assertEquals("2 minutos", LoginAttemptService.minutes(Duration.ofSeconds(61)));
        assertEquals("15 minutos", LoginAttemptService.minutes(Duration.ofSeconds(14 * 60 + 1)));
    }

    @Test
    void elRetryAfterSeRedondeaHaciaArribaASegundos() {
        failTimes(EMAIL, 4);
        assertThrows(AccountLockedException.class, () -> fail(EMAIL));
        clock.advance(Duration.ofMillis(14 * 60_000 + 59_500)); // quedan 0,5 s

        AccountLockedException locked =
                assertThrows(AccountLockedException.class, () -> service.reserveAttempt(EMAIL, IP));
        assertEquals(Duration.ofSeconds(1), locked.getRetryAfter());
        assertTrue(locked.getMessage().endsWith("Inténtalo de nuevo en 1 minuto."));
    }

    // ------------------------------------------------------------------
    // Concurrencia
    // ------------------------------------------------------------------

    /**
     * Con la versión anterior (comprobar el bloqueo y, tras BCrypt, apuntar el
     * fallo), todas estas peticiones simultáneas veían "0 fallos" y llegaban a
     * probar su contraseña. Con la reserva atómica solo pasan 5, cada una con
     * un número distinto, y ningún 401 lleva 0 o menos intentos restantes.
     */
    @Test
    void conMuchosIntentosSimultaneosSoloSeReservanLosPermitidos() throws Exception {
        int threads = 64;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        Set<Integer> remainingSeen = ConcurrentHashMap.newKeySet();
        AtomicInteger reserved = new AtomicInteger();
        AtomicInteger lockedAtReservation = new AtomicInteger();
        AtomicInteger lockedAtFailure = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                LoginAttempt attempt;
                try {
                    attempt = service.reserveAttempt(EMAIL, IP);
                } catch (AccountLockedException e) {
                    lockedAtReservation.incrementAndGet();
                    return null;
                }
                reserved.incrementAndGet();
                try {
                    remainingSeen.add(service.recordFailure(attempt));
                } catch (AccountLockedException e) {
                    lockedAtFailure.incrementAndGet();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertEquals(5, reserved.get());
        assertEquals(threads - 5, lockedAtReservation.get());
        assertEquals(1, lockedAtFailure.get());
        assertEquals(Set.of(4, 3, 2, 1), remainingSeen);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Reserva un intento y lo marca como fallido; devuelve los intentos restantes. */
    private int fail(String email) {
        return failFrom(email, IP);
    }

    private int failFrom(String email, String ip) {
        return service.recordFailure(service.reserveAttempt(email, ip));
    }

    private void failTimes(String email, int times) {
        for (int i = 0; i < times; i++) {
            fail(email);
        }
    }

    private static RateLimitProperties properties(int maxFailures, Duration window) {
        RateLimitProperties.Rule rule = new RateLimitProperties.Rule(100, Duration.ofMinutes(1));
        return new RateLimitProperties(rule, rule, new RateLimitProperties.Lockout(maxFailures, window, 5, Duration.ofDays(30)), 100_000);
    }
}
