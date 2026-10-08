package com.emilio.streambox.security.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.emilio.streambox.security.ratelimit.SlidingWindowCounter.Reservation;

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
            new SlidingWindowCounter(Duration.ofMinutes(1), clock, 100_000);

    @Test
    void permiteHastaElMaximoYRechazaElSiguiente() {
        assertTrue(counter.tryAcquire("ip", 3));
        assertTrue(counter.tryAcquire("ip", 3));
        assertTrue(counter.tryAcquire("ip", 3));

        assertFalse(counter.tryAcquire("ip", 3));
    }

    @Test
    void acquireDevuelveElNumeroDeOrdenOCeroSiSeRechaza() {
        assertEquals(1, counter.acquire("email", 3));
        assertEquals(2, counter.acquire("email", 3));
        assertEquals(3, counter.acquire("email", 3));

        assertEquals(0, counter.acquire("email", 3));
        assertEquals(3, counter.count("email"));
    }

    @Test
    void acquireVuelveAEmpezarCuandoCaducanLosEventos() {
        counter.acquire("email", 2);
        clock.advance(Duration.ofSeconds(30));
        counter.acquire("email", 2);
        assertEquals(0, counter.acquire("email", 2));

        clock.advance(Duration.ofSeconds(31)); // caduca solo el primero
        assertEquals(2, counter.acquire("email", 2));
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

    // ------------------------------------------------------------------
    // reserve / confirm
    // ------------------------------------------------------------------

    @Test
    void reserveDevuelveElNumeroDeOrdenYUnIdentificadorDistinto() {
        Reservation first = counter.reserve("email", 2).orElseThrow();
        Reservation second = counter.reserve("email", 2).orElseThrow();

        assertEquals(1, first.number());
        assertEquals(2, second.number());
        assertNotEquals(first.id(), second.id());
        assertTrue(counter.reserve("email", 2).isEmpty());
    }

    @Test
    void confirmDevuelveLaPosicionActualQueBajaSiCaducanEventosAnteriores() {
        Reservation first = counter.reserve("email", 3).orElseThrow();   // t = 0
        clock.advance(Duration.ofSeconds(30));
        Reservation second = counter.reserve("email", 3).orElseThrow();  // t = 30

        assertEquals(1, counter.confirm("email", first, 3));
        assertEquals(2, counter.confirm("email", second, 3));

        clock.advance(Duration.ofSeconds(31));                          // caduca el primero
        assertEquals(1, counter.confirm("email", second, 3));
        assertEquals(1, counter.count("email"));
    }

    @Test
    void confirmVuelveARegistrarElEventoSiUnResetLoBorro() {
        counter.reserve("email", 3);
        Reservation second = counter.reserve("email", 3).orElseThrow();
        counter.reset("email");

        assertEquals(1, counter.confirm("email", second, 3));
        assertEquals(1, counter.count("email"));
        // Confirmarlo otra vez no lo duplica: ya está en la ventana.
        assertEquals(1, counter.confirm("email", second, 3));
        assertEquals(1, counter.count("email"));
    }

    @Test
    void confirmDevuelveCeroSiElEventoYaNoEstaYNoCabe() {
        Reservation stale = counter.reserve("email", 2).orElseThrow();
        counter.reset("email");
        counter.acquire("email", 2);
        counter.acquire("email", 2);

        assertEquals(0, counter.confirm("email", stale, 2));
        assertEquals(2, counter.count("email"));
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

    // ------------------------------------------------------------------
    // Tope de claves (hallazgo NV-5)
    // ------------------------------------------------------------------

    /**
     * Sin tope, un atacante que mande N claves distintas (IPs rotadas, emails
     * inventados) dejaba N entradas vivas durante toda la ventana. Con el tope,
     * el mapa nunca lo supera, entren las claves que entren.
     */
    @Test
    void elMapaNuncaSuperaElTopeAunqueEntrenMuchasClavesDistintas() {
        SlidingWindowCounter capped = new SlidingWindowCounter(Duration.ofHours(1), clock, 100);

        for (int i = 0; i < 10_000; i++) {
            capped.record("ip-" + i);
            assertTrue(capped.size() <= 100, "Tope superado con la clave " + i + ": " + capped.size());
        }

        assertEquals(100, capped.size());
    }

    /** Todas las operaciones que insertan claves respetan el tope, no solo {@code record}. */
    @Test
    void todasLasOperacionesQueInsertanRespetanElTope() {
        SlidingWindowCounter capped = new SlidingWindowCounter(Duration.ofHours(1), clock, 10);

        for (int i = 0; i < 100; i++) {
            capped.tryAcquire("a-" + i, 5);
            capped.reserve("b-" + i, 5);
            Reservation reservation = new Reservation(1_000_000L + i, 1);
            capped.confirm("c-" + i, reservation, 5);
            assertTrue(capped.size() <= 10);
        }
    }

    /** Al llenarse se expulsa la clave con la actividad más antigua, no la nueva. */
    @Test
    void alLlenarseExpulsaLaClaveMasAntiguaYAdmiteLaNueva() {
        SlidingWindowCounter capped = new SlidingWindowCounter(Duration.ofHours(1), clock, 3);
        capped.record("vieja");
        clock.advance(Duration.ofSeconds(1));
        capped.record("media");
        clock.advance(Duration.ofSeconds(1));
        capped.record("reciente");
        clock.advance(Duration.ofSeconds(1));

        capped.record("nueva");

        assertEquals(3, capped.size());
        assertEquals(0, capped.count("vieja"), "La más antigua se olvidó");
        assertEquals(1, capped.count("media"));
        assertEquals(1, capped.count("reciente"));
        assertEquals(1, capped.count("nueva"), "La clave nueva se admite: no se rechazan claves nuevas");
    }

    /** La antigüedad es la del último evento: una clave activa no es la próxima en salir. */
    @Test
    void unaClaveConActividadRecienteNoEsLaProximaEnSerExpulsada() {
        SlidingWindowCounter capped = new SlidingWindowCounter(Duration.ofHours(1), clock, 3);
        capped.record("a");
        clock.advance(Duration.ofSeconds(1));
        capped.record("b");
        clock.advance(Duration.ofSeconds(1));
        capped.record("c");
        clock.advance(Duration.ofSeconds(1));
        capped.record("a");                      // «a» vuelve a tener actividad: ahora «b» es la más antigua

        capped.record("d");

        assertEquals(2, capped.count("a"));
        assertEquals(0, capped.count("b"));
        assertEquals(1, capped.count("c"));
        assertEquals(1, capped.count("d"));
    }

    /** Leer o rechazar no cuenta como actividad: un atacante no puede «refrescar» claves leyéndolas. */
    @Test
    void lecturasYRechazosNoReordenanLasClaves() {
        SlidingWindowCounter capped = new SlidingWindowCounter(Duration.ofHours(1), clock, 2);
        capped.tryAcquire("a", 1);
        clock.advance(Duration.ofSeconds(1));
        capped.tryAcquire("b", 1);

        capped.count("a");
        capped.retryAfter("a");
        assertFalse(capped.tryAcquire("a", 1));    // rechazado: no registra nada

        capped.record("c");

        assertEquals(0, capped.count("a"), "«a» seguía siendo la más antigua");
        assertEquals(1, capped.count("b"));
    }

    /** Antes de expulsar claves vivas se purgan las caducadas. */
    @Test
    void alLlenarsePrimeroSePurganLasCaducadasYNoSeExpulsaNingunaViva() {
        SlidingWindowCounter capped = new SlidingWindowCounter(Duration.ofMinutes(1), clock, 3);
        capped.record("caducada-1");
        capped.record("caducada-2");
        clock.advance(Duration.ofSeconds(50));
        capped.record("viva");
        clock.advance(Duration.ofSeconds(20));    // las dos primeras caducaron; «viva» tiene 20 s

        capped.record("nueva-1");
        capped.record("nueva-2");

        assertEquals(3, capped.size());
        assertEquals(1, capped.count("viva"), "Se purgaron las caducadas en lugar de expulsar una viva");
        assertEquals(1, capped.count("nueva-1"));
        assertEquals(1, capped.count("nueva-2"));
    }

    /** Reiniciar una clave libera su hueco. */
    @Test
    void resetLiberaElHuecoDeLaClave() {
        SlidingWindowCounter capped = new SlidingWindowCounter(Duration.ofHours(1), clock, 2);
        capped.record("a");
        capped.record("b");
        capped.reset("a");

        capped.record("c");

        assertEquals(1, capped.count("b"), "Con hueco libre no se expulsa nada");
        assertEquals(1, capped.count("c"));
    }

    /** Un tope no positivo es un error de configuración que no se debe aceptar. */
    @Test
    void unTopeNoPositivoSeRechaza() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowCounter(Duration.ofMinutes(1), clock, 0));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new SlidingWindowCounter(Duration.ofMinutes(1), clock, -5));
    }

    /**
     * La purga ya no recorre todo el mapa en cada operación: las claves
     * caducadas se retiran desde el principio del orden y la primera viva
     * detiene la purga. Prueba de cordura (no un benchmark): llenar de claves
     * un contador grande y seguir operando mientras caducan por tandas termina
     * en un tiempo muy inferior al de recorrer todo el mapa en cada operación
     * (con 200 000 claves serían miles de millones de pasos).
     */
    @Test
    void laPurgaNoDependeDeRecorrerElMapaEnteroEnCadaOperacion() {
        int keys = 200_000;
        SlidingWindowCounter big = new SlidingWindowCounter(Duration.ofSeconds(10), clock, keys);

        long start = System.nanoTime();
        for (int i = 0; i < keys; i++) {
            big.record("k-" + i);
            if (i % 1_000 == 999) {
                clock.advance(Duration.ofSeconds(1));    // las claves van caducando por tandas
            }
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(big.size() <= keys);
        assertTrue(big.size() <= 11_000, "Solo deben quedar las claves de la última ventana: " + big.size());
        assertTrue(elapsedMillis < 5_000, "Demasiado lento para una purga incremental: " + elapsedMillis + " ms");
    }
}
