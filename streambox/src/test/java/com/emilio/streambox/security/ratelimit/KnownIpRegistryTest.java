package com.emilio.streambox.security.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.emilio.streambox.security.ratelimit.SlidingWindowCounterTest.MutableClock;

/** Tests unitarios de {@link KnownIpRegistry}: IPs conocidas por cuenta, acotadas y con caducidad. */
class KnownIpRegistryTest {

    private final MutableClock clock = new MutableClock();
    private final KnownIpRegistry registry = new KnownIpRegistry(3, 2, Duration.ofDays(30), clock);

    @Test
    void unaIpSoloEsConocidaTrasRecordarla() {
        assertFalse(registry.isKnown("ana@test.com", "203.0.113.1"));

        registry.remember("ana@test.com", "203.0.113.1");

        assertTrue(registry.isKnown("ana@test.com", "203.0.113.1"));
        assertFalse(registry.isKnown("ana@test.com", "203.0.113.2"));
        assertFalse(registry.isKnown("otra@test.com", "203.0.113.1"), "Las IPs conocidas son por cuenta");
    }

    @Test
    void sinIpNoSeRecuerdaNiSeConoceNada() {
        registry.remember("ana@test.com", null);

        assertFalse(registry.isKnown("ana@test.com", null));
        assertEquals(0, registry.accounts(), "Sin IP no se crea ninguna entrada");
    }

    @Test
    void consultarNoCreaEntradas() {
        for (int i = 0; i < 1_000; i++) {
            registry.isKnown("cuenta" + i + "@test.com", "203.0.113." + (i % 250));
        }

        assertEquals(0, registry.accounts());
    }

    @Test
    void caducaAlCumplirseElPlazoDesdeElUltimoLoginCorrecto() {
        registry.remember("ana@test.com", "203.0.113.1");

        clock.advance(Duration.ofDays(30).minusMillis(1));
        assertTrue(registry.isKnown("ana@test.com", "203.0.113.1"));

        clock.advance(Duration.ofMillis(1));
        assertFalse(registry.isKnown("ana@test.com", "203.0.113.1"));
        assertEquals(0, registry.accounts(), "La cuenta sin IPs vivas se elimina");
    }

    @Test
    void recordarDeNuevoRenuevaElPlazo() {
        registry.remember("ana@test.com", "203.0.113.1");
        clock.advance(Duration.ofDays(29));
        registry.remember("ana@test.com", "203.0.113.1");
        clock.advance(Duration.ofDays(29));

        assertTrue(registry.isKnown("ana@test.com", "203.0.113.1"));
    }

    @Test
    void lasCuentasCaducadasSeLimpianSinQueNadieLasConsulte() {
        registry.remember("ana@test.com", "203.0.113.1");
        registry.remember("eva@test.com", "203.0.113.2");
        clock.advance(Duration.ofDays(31));

        registry.remember("luis@test.com", "203.0.113.3");

        assertEquals(1, registry.accounts());
    }

    @Test
    void porCuentaSeGuardaElMaximoDeIpsYSeExpulsaLaMasAntigua() {
        registry.remember("ana@test.com", "203.0.113.1");
        clock.advance(Duration.ofMinutes(1));
        registry.remember("ana@test.com", "203.0.113.2");
        clock.advance(Duration.ofMinutes(1));

        registry.remember("ana@test.com", "203.0.113.3");

        assertFalse(registry.isKnown("ana@test.com", "203.0.113.1"));
        assertTrue(registry.isKnown("ana@test.com", "203.0.113.2"));
        assertTrue(registry.isKnown("ana@test.com", "203.0.113.3"));
    }

    @Test
    void elNumeroDeCuentasRespetaElTopeYSeOlvidaLaMasAntigua() {
        registry.remember("a@test.com", "203.0.113.1");
        clock.advance(Duration.ofMinutes(1));
        registry.remember("b@test.com", "203.0.113.1");
        clock.advance(Duration.ofMinutes(1));
        registry.remember("c@test.com", "203.0.113.1");
        clock.advance(Duration.ofMinutes(1));

        registry.remember("d@test.com", "203.0.113.1");

        assertEquals(3, registry.accounts());
        assertFalse(registry.isKnown("a@test.com", "203.0.113.1"));
        assertTrue(registry.isKnown("b@test.com", "203.0.113.1"));
        assertTrue(registry.isKnown("d@test.com", "203.0.113.1"));
    }

    @Test
    void recordarUnaCuentaYaPresenteNoOcupaUnHuecoNuevo() {
        registry.remember("a@test.com", "203.0.113.1");
        registry.remember("b@test.com", "203.0.113.1");
        registry.remember("c@test.com", "203.0.113.1");

        registry.remember("a@test.com", "203.0.113.2");

        assertEquals(3, registry.accounts());
        assertTrue(registry.isKnown("b@test.com", "203.0.113.1"));
    }

    @Test
    void unosLimitesOUnPlazoNoPositivosSeRechazan() {
        assertThrows(IllegalArgumentException.class, () -> new KnownIpRegistry(0, 2, Duration.ofDays(1), clock));
        assertThrows(IllegalArgumentException.class, () -> new KnownIpRegistry(3, 0, Duration.ofDays(1), clock));
        assertThrows(IllegalArgumentException.class, () -> new KnownIpRegistry(3, 2, Duration.ZERO, clock));
        assertThrows(IllegalArgumentException.class, () -> new KnownIpRegistry(3, 2, Duration.ofDays(-1), clock));
    }
}
