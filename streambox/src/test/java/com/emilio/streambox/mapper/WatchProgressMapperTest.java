package com.emilio.streambox.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.emilio.streambox.dto.ContinueWatchingItemResponse;
import com.emilio.streambox.dto.ContinueWatchingType;
import com.emilio.streambox.dto.EpisodeProgressResponse;
import com.emilio.streambox.dto.WatchHistoryItemResponse;
import com.emilio.streambox.dto.WatchProgressResponse;
import com.emilio.streambox.dto.WatchStatsResponse;
import com.emilio.streambox.repository.ContinueWatchingItem.EpisodeItem;
import com.emilio.streambox.repository.ContinueWatchingItem.MovieItem;
import com.emilio.streambox.repository.WatchHistoryItem.EpisodeEntry;
import com.emilio.streambox.repository.WatchHistoryItem.MovieEntry;
import com.emilio.streambox.repository.WatchProgress;
import com.emilio.streambox.repository.WatchStats;

/**
 * Tests unitarios de {@link WatchProgressMapper} (sin base de datos): las
 * tarjetas de «Seguir viendo» tras el criterio de QA-4 y las entradas y cifras
 * del historial del perfil. El caso que importa es el de la tarjeta «siguiente episodio» sin
 * progreso: antes del arreglo el mapper leía la posición de un progreso nulo
 * y la petición acababa en un 500 ({@link NullPointerException}).
 */
class WatchProgressMapperTest {

    private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

    /** Una película lleva su progreso y nunca es {@code upNext}. */
    @Test
    void unaPeliculaLlevaSuProgresoYUpNextFalse() {
        ContinueWatchingItemResponse card = WatchProgressMapper.toResponse(
                new MovieItem(7, "Dune", "https://example.com/dune.jpg", new WatchProgress(1325, 8160, AT, null)));

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
                episode(new WatchProgress(600, 2700, AT, null), false, AT));

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
                episode(new WatchProgress(30, 100, AT, null), true, finishedAt));

        assertTrue(card.upNext());
        assertEquals(30, card.positionSeconds());
        assertEquals(100, card.durationSeconds());
        assertEquals(finishedAt, card.updatedAt());
    }

    // ------------------------------------------------------------------
    // Historial y cifras del perfil
    // ------------------------------------------------------------------

    /**
     * Una película del historial: sus datos y su progreso, {@code finished}
     * calculado por el 95 % y los cuatro campos de serie y temporada a nulo.
     */
    @Test
    void unaPeliculaDelHistorialLlevaSuProgresoYLosCamposDeSerieANull() {
        WatchHistoryItemResponse entry = WatchProgressMapper.toHistoryResponse(
                new MovieEntry(7, "Dune", "https://example.com/dune.jpg", new WatchProgress(7752, 8160, AT, AT)));

        assertEquals(new WatchHistoryItemResponse(ContinueWatchingType.MOVIE, 7, "Dune",
                "https://example.com/dune.jpg", null, null, null, null, 7752, 8160, true, true, AT), entry);
    }

    /**
     * Un episodio del historial: id y título del episodio, portada, id y
     * título de la serie, temporada y número; a medias, {@code finished} falso.
     */
    @Test
    void unEpisodioDelHistorialLlevaLosDatosDelEpisodioYDeSuSerie() {
        WatchHistoryItemResponse entry = WatchProgressMapper.toHistoryResponse(new EpisodeEntry(11, 2, 3,
                "Episodio 2x3", 4, "Dark", "https://example.com/dark.jpg", new WatchProgress(94, 100, AT, null)));

        assertEquals(new WatchHistoryItemResponse(ContinueWatchingType.EPISODE, 11, "Episodio 2x3",
                "https://example.com/dark.jpg", 2, 3, 4L, "Dark", 94, 100, false, false, AT), entry);
    }

    /** La lista conserva el orden del repositorio. */
    @Test
    void elHistorialConservaElOrden() {
        List<WatchHistoryItemResponse> entries = WatchProgressMapper.toHistoryResponseList(List.of(
                new EpisodeEntry(11, 1, 1, "Ep", 4, "Dark", "https://example.com/dark.jpg",
                        new WatchProgress(1, 100, AT.plusSeconds(1), null)),
                new MovieEntry(7, "Dune", "https://example.com/dune.jpg", new WatchProgress(1, 100, AT, null))));

        assertEquals(List.of(11L, 7L), entries.stream().map(WatchHistoryItemResponse::id).toList());
        assertEquals(List.of(), WatchProgressMapper.toHistoryResponseList(List.of()));
    }

    // ------------------------------------------------------------------
    // «Visto alguna vez» (watched, V15) frente a «terminado» (finished)
    // ------------------------------------------------------------------

    /**
     * Visto y vuelto a ver: la posición actual ya no llega al 95 %
     * ({@code finished} falso), pero {@code watched} sigue a {@code true} en la
     * entrada del historial. Sin el campo, el cliente no tendría forma de
     * mostrar «Vista».
     */
    @Test
    void enElHistorialUnTituloVueltoAVerSigueVistoAunqueNoEsteTerminado() {
        WatchHistoryItemResponse entry = WatchProgressMapper.toHistoryResponse(new MovieEntry(7, "Dune",
                "https://example.com/dune.jpg", new WatchProgress(120, 8160, AT.plusSeconds(60), AT)));

        assertFalse(entry.finished());
        assertTrue(entry.watched());
    }

    /** El progreso para reanudar distingue igual los dos conceptos. */
    @Test
    void elProgresoParaReanudarLlevaWatchedAdemasDeFinished() {
        WatchProgressResponse rewatching = WatchProgressMapper.toResponse(
                new WatchProgress(120, 8160, AT.plusSeconds(60), AT));
        WatchProgressResponse neverFinished = WatchProgressMapper.toResponse(new WatchProgress(120, 8160, AT, null));

        assertEquals(new WatchProgressResponse(120, 8160, false, true, AT.plusSeconds(60)), rewatching);
        assertFalse(neverFinished.watched());
    }

    /** Cada episodio de la serie lleva su propio {@code watched}, en el orden del mapa. */
    @Test
    void elProgresoDeLosEpisodiosLlevaWatchedPorEpisodio() {
        Map<Long, WatchProgress> byEpisode = new LinkedHashMap<>();
        byEpisode.put(11L, new WatchProgress(10, 100, AT.plusSeconds(5), AT));
        byEpisode.put(12L, new WatchProgress(10, 100, AT, null));

        List<EpisodeProgressResponse> episodes = WatchProgressMapper.toEpisodeResponses(byEpisode);

        assertEquals(List.of(new EpisodeProgressResponse(11, 10, 100, false, true, AT.plusSeconds(5)),
                new EpisodeProgressResponse(12, 10, 100, false, false, AT)), episodes);
    }

    /** Las cifras se copian tal cual, también por encima de {@link Integer#MAX_VALUE}. */
    @Test
    void lasCifrasSeCopianTalCual() {
        long big = Integer.MAX_VALUE + 10L;

        assertEquals(new WatchStatsResponse(3, 5, big), WatchProgressMapper.toStatsResponse(new WatchStats(3, 5, big)));
        assertEquals(new WatchStatsResponse(0, 0, 0), WatchProgressMapper.toStatsResponse(WatchStats.EMPTY));
    }

    private static EpisodeItem episode(WatchProgress progress, boolean upNext, Instant updatedAt) {
        return new EpisodeItem(11, 1, 2, "Episodio 1x2", 4, "Dark", "https://example.com/dark.jpg", progress,
                upNext, updatedAt);
    }
}
