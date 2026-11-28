package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.emilio.streambox.dto.SeriesResponse;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.repository.SeriesEpisodeStats;

/**
 * Tests unitarios de {@link SeriesEpisodeCounts#toVisibleResponses}, el filtro
 * que impide que una serie vaciada entre consultas salga en una lista pública
 * con 0 episodios (QA-SS-1).
 */
class SeriesEpisodeCountsTest {

    @Test
    void descartaLasSeriesSinRecuentosYConservaElOrden() {
        List<Series> ordered = List.of(series(3L), series(1L), series(2L));
        Map<Long, SeriesEpisodeStats> stats = Map.of(3L, stats(3L, 2, 10), 2L, stats(2L, 1, 4));

        List<SeriesResponse> visible = SeriesEpisodeCounts.toVisibleResponses(ordered, stats);

        assertEquals(List.of(3L, 2L), visible.stream().map(SeriesResponse::id).toList());
        assertEquals(List.of(10L, 4L), visible.stream().map(SeriesResponse::episodeCount).toList());
        assertEquals(List.of(2L, 1L), visible.stream().map(SeriesResponse::seasonCount).toList());
    }

    @Test
    void sinRecuentosNoQuedaNinguna() {
        assertEquals(List.of(), SeriesEpisodeCounts.toVisibleResponses(List.of(series(1L)), Map.of()));
    }

    private static Series series(Long id) {
        Series series = new Series();
        series.setId(id);
        series.setTitle("Serie " + id);
        series.setGenres(new HashSet<>());
        return series;
    }

    private static SeriesEpisodeStats stats(Long seriesId, long seasons, long episodes) {
        return new SeriesEpisodeStats() {
            @Override
            public Long getSeriesId() {
                return seriesId;
            }

            @Override
            public long getEpisodeCount() {
                return episodes;
            }

            @Override
            public long getSeasonCount() {
                return seasons;
            }
        };
    }
}
