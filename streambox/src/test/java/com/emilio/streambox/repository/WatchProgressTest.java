package com.emilio.streambox.repository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Reglas de {@link WatchProgress} que añade la migración {@code V15}:
 * «terminado» ({@link WatchProgress#finished()}, posición actual) y «visto»
 * ({@link WatchProgress#watched()}, {@code finishedAt}) son cosas distintas, y
 * el {@code record} rechaza las mismas combinaciones que los {@code CHECK} de
 * la tabla.
 */
class WatchProgressTest {

    private static final Instant AT = Instant.parse("2026-11-24T10:00:00Z");

    /** Visto y ahora a medias: es volver a verlo, y sigue contando como visto. */
    @Test
    void vistoYAMediasEsVolverAVerlo() {
        WatchProgress rewatching = new WatchProgress(120, 6000, AT, AT.minusSeconds(3600));

        assertTrue(rewatching.watched());
        assertFalse(rewatching.finished());
    }

    /** Sin fecha de «visto» y a medias: ni visto ni terminado. */
    @Test
    void sinFechaYAMediasNoEstaVisto() {
        WatchProgress half = new WatchProgress(94, 100, AT, null);

        assertFalse(half.watched());
        assertFalse(half.finished());
    }

    /** Terminado implica visto: un progreso al 95 % sin fecha de «visto» es imposible. */
    @Test
    void rechazaUnProgresoTerminadoSinFechaDeVisto() {
        assertThrows(IllegalArgumentException.class, () -> new WatchProgress(95, 100, AT, null));
        assertThrows(IllegalArgumentException.class, () -> new WatchProgress(100, 100, AT, null));
        WatchProgress done = new WatchProgress(95, 100, AT, AT);
        assertTrue(done.finished());
        assertTrue(done.watched());
    }

    /** Terminarlo no puede ser posterior al último guardado (igual puede, y antes también). */
    @Test
    void rechazaUnaFechaDeVistoPosteriorAlUltimoGuardado() {
        assertThrows(IllegalArgumentException.class, () -> new WatchProgress(10, 100, AT, AT.plusMillis(1)));
        assertDoesNotThrow(() -> new WatchProgress(10, 100, AT, AT));
        assertDoesNotThrow(() -> new WatchProgress(10, 100, AT, AT.minusMillis(1)));
    }
}
