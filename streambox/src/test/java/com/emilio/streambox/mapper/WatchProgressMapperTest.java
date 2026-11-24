package com.emilio.streambox.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.emilio.streambox.dto.ContinueWatchingItemResponse;
import com.emilio.streambox.dto.ContinueWatchingType;
import com.emilio.streambox.repository.ContinueWatchingItem.EpisodeItem;
import com.emilio.streambox.repository.ContinueWatchingItem.MovieItem;
import com.emilio.streambox.repository.WatchProgress;

/**
 * Tests unitarios de {@link WatchProgressMapper#toResponse(com.emilio.streambox.repository.ContinueWatchingItem)}
 * (sin base de datos): las tarjetas de «Seguir viendo» tras el criterio de
 * QA-4. El caso que importa es el de la tarjeta «siguiente episodio» sin
 * progreso: antes del arreglo el mapper leía la posición de un progreso nulo
 * y la petición acababa en un 500 ({@link NullPointerException}).
 */
class WatchProgressMapperTest {

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    /** Una película lleva su progreso y nunca es {@code upNext}. */
    @Test
    void unaPeliculaLlevaSuProgresoYUpNextFalse() {
        ContinueWatchingItemResponse card = WatchProgressMapper.toResponse(
                new MovieItem(7, "Dune", "https://example.com/dune.jpg", new WatchProgress(1325, 8160, AT)));

        assertEquals(ContinueWatchingType.MOVIE, card.type());
        assertEquals(1325, card.positionSeconds());
        assertEquals(8160, card.durationSeconds());
        assertFalse(card.upNext());
        assertEquals(AT, card.updatedAt());
        assertNull(card.seriesId());
    }

    /** El episodio a medias: su progreso, {@code upNext = false} y su propia fecha. */
    @Test
    void unEpisodioAMediasLlevaSuProgreso() {
        ContinueWatchingItemResponse card = WatchProgressMapper.toResponse(
                episode(new WatchProgress(600, 2700, AT), false, AT));

        assertEquals(ContinueWatchingType.EPISODE, card.type());
        assertEquals(600, card.positionSeconds());
        assertEquals(2700, card.durationSeconds());
        assertFalse(card.upNext());
        assertEquals(AT, card.updatedAt());
    }

    /**
     * El siguiente episodio sin empezar: posición y duración nulas, sin
     * excepción, y la fecha del episodio terminado (no hay otra).
     */
    @Test
    void elSiguienteSinProgresoDaPosicionYDuracionNulas() {
        Instant finishedAt = AT.plusSeconds(60);

        ContinueWatchingItemResponse card = WatchProgressMapper.toResponse(episode(null, true, finishedAt));

        assertTrue(card.upNext());
        assertNull(card.positionSeconds());
        assertNull(card.durationSeconds());
        assertEquals(finishedAt, card.updatedAt());
        assertEquals(11, card.id());
        assertEquals(2, card.episodeNumber());
    }

    /**
     * El siguiente con progreso propio a medias lo trae, pero la fecha es la
     * del episodio terminado, no la de ese progreso.
     */
    @Test
    void elSiguienteConProgresoLoTraeYSeFechaPorElTerminado() {
        Instant finishedAt = AT.plusSeconds(60);

        ContinueWatchingItemResponse card = WatchProgressMapper.toResponse(
                episode(new WatchProgress(30, 100, AT), true, finishedAt));

        assertTrue(card.upNext());
        assertEquals(30, card.positionSeconds());
        assertEquals(100, card.durationSeconds());
        assertEquals(finishedAt, card.updatedAt());
    }

    private static EpisodeItem episode(WatchProgress progress, boolean upNext, Instant updatedAt) {
        return new EpisodeItem(11, 1, 2, "Episodio 1x2", 4, "Dark", "https://example.com/dark.jpg", progress,
                upNext, updatedAt);
    }
}
