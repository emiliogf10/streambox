package com.emilio.streambox.repository;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.emilio.streambox.repository.ContinueWatchingItem.EpisodeItem;
import com.emilio.streambox.repository.ContinueWatchingItem.MovieItem;

/**
 * Invariantes de {@link ContinueWatchingItem} que garantiza su constructor:
 * el progreso de una tarjeta de serie solo puede faltar si es un «siguiente
 * episodio» ({@code upNext}), nunca está terminado y, si es el episodio a
 * medias, su fecha es la que ordena la fila.
 */
class ContinueWatchingItemTest {

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    private static EpisodeItem episode(WatchProgress progress, boolean upNext, Instant updatedAt) {
        return new EpisodeItem(1, 1, 2, "Episodio", 3, "Serie", "https://example.com/s.jpg", progress, upNext,
                updatedAt);
    }

    /** Las combinaciones que produce la consulta se aceptan. */
    @Test
    void aceptaLasCombinacionesQueProduceLaConsulta() {
        assertDoesNotThrow(() -> episode(new WatchProgress(10, 100, AT, null), false, AT));
        assertDoesNotThrow(() -> episode(null, true, AT));
        assertDoesNotThrow(() -> episode(new WatchProgress(10, 100, AT.minusSeconds(60), null), true, AT));
    }

    /** Sin {@code upNext} el progreso es obligatorio: es el episodio a medias. */
    @Test
    void sinUpNextElProgresoEsObligatorio() {
        assertThrows(IllegalArgumentException.class, () -> episode(null, false, AT));
    }

    /** Ninguna tarjeta lleva un progreso terminado (lo terminado se ve desde el principio). */
    @Test
    void rechazaUnProgresoTerminado() {
        assertThrows(IllegalArgumentException.class, () -> episode(new WatchProgress(95, 100, AT, AT), false, AT));
        assertThrows(IllegalArgumentException.class, () -> episode(new WatchProgress(95, 100, AT, AT), true, AT));
    }

    /** El episodio a medias se ordena por la fecha de su propio progreso. */
    @Test
    void elEpisodioAMediasSeOrdenaPorSuPropiaFecha() {
        assertThrows(IllegalArgumentException.class,
                () -> episode(new WatchProgress(10, 100, AT, null), false, AT.plusSeconds(1)));
    }

    /** La fecha que ordena es obligatoria; en una película, la de su progreso. */
    @Test
    void laFechaQueOrdenaEsObligatoriaYEnUnaPeliculaEsLaDeSuProgreso() {
        assertThrows(NullPointerException.class, () -> episode(null, true, null));
        assertEquals(AT, new MovieItem(1, "Película", "https://example.com/p.jpg",
                new WatchProgress(10, 100, AT, null)).updatedAt());
    }
}
