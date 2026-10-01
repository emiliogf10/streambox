package com.emilio.streambox.security.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/** Tests unitarios de {@link SlidingWindowCounter} con un reloj controlado. */
class SlidingWindowCounterTest {

    /** Reloj manual: el tiempo solo avanza cuando el test lo indica. */
    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private final SlidingWindowCounter counter =
            new SlidingWindowCounter(Duration.ofMinutes(1), clock);

    @Test
    void permiteHastaElMaximoYRechazaElSiguiente() {
        assertTrue(counter.tryAcquire("ip", 3));
        assertTrue(counter.tryAcquire("ip", 3));
        assertTrue(counter.tryAcquire("ip", 3));

        assertFalse(counter.tryAcquire("ip", 3));
    }

    @Test
    void loRechazadoNoSeContabiliza() {
        for (int i = 0; i < 3; i++) {
            counter.tryAcquire("ip", 3);
        }
        for (int i = 0; i < 50; i++) {
            counter.tryAcquire("ip", 3); // rechazados
        }

        assertEquals(3, counter.count("ip"));
    }

    @Test
    void cadaClaveTieneSuPropiaCuenta() {
        counter.tryAcquire("a", 1);

        assertFalse(counter.tryAcquire("a", 1));
        assertTrue(counter.tryAcquire("b", 1));
    }

    @Test
    void losEventosCaducanAlPasarLaVentana() {
        counter.tryAcquire("ip", 1);
        assertFalse(counter.tryAcquire("ip", 1));

        clock.advance(Duration.ofSeconds(59));
        assertFalse(counter.tryAcquire("ip", 1));

        clock.advance(Duration.ofSeconds(2));
        assertTrue(counter.tryAcquire("ip", 1));
    }

    @Test
    void laVentanaEsDeslizanteNoPorBloques() {
        counter.tryAcquire("ip", 2);            // t = 0
        clock.advance(Duration.ofSeconds(40));
        counter.tryAcquire("ip", 2);            // t = 40
        assertFalse(counter.tryAcquire("ip", 2));

        clock.advance(Duration.ofSeconds(21));  // t = 61: caduca solo el primero
        assertTrue(counter.tryAcquire("ip", 2));
        assertFalse(counter.tryAcquire("ip", 2));
    }

    @Test
    void retryAfterIndicaCuantoFaltaParaQueSeLibereUnHueco() {
        counter.tryAcquire("ip", 1);
        clock.advance(Duration.ofSeconds(20));

        assertEquals(Duration.ofSeconds(40), counter.retryAfter("ip"));
        assertEquals(Duration.ZERO, counter.retryAfter("desconocida"));
    }

    @Test
    void resetOlvidaLosEventosDeLaClave() {
        counter.record("email");
        counter.record("email");
        counter.reset("email");

        assertEquals(0, counter.count("email"));
    }

    @Test
    void lasClavesCaducadasSeLimpianSinQueNadieLasConsulte() {
        // Un atacante que use muchas claves distintas no debe llenar la memoria:
        // tras caducar y acumular operaciones, las claves antiguas se purgan.
        for (int i = 0; i < 1000; i++) {
            counter.record("spam-" + i);
        }
        clock.advance(Duration.ofMinutes(2));
        for (int i = 0; i < 600; i++) {
            counter.count("otra-clave");
        }

        assertEquals(0, counter.count("spam-0"));
        assertEquals(0, counter.count("spam-999"));
    }
}
