package com.emilio.streambox.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.emilio.streambox.dto.EpisodeRequest;
import com.emilio.streambox.dto.GenreResponse;
import com.emilio.streambox.dto.SeasonResponse;
import com.emilio.streambox.dto.SeriesDetailResponse;
import com.emilio.streambox.dto.SeriesRequest;
import com.emilio.streambox.entity.Episode;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Series;

/**
 * Tests unitarios de {@link SeriesMapper} y {@link EpisodeMapper} (sin base de
 * datos): agrupación de episodios en temporadas, orden y recuentos del
 * detalle, y normalización de la sinopsis del episodio.
 */
class SeriesMapperTest {

    @Test
    void elDetalleAgrupaPorTemporadaYOrdenaAunqueLaListaLlegueDesordenada() {
        Series series = series();
        List<Episode> episodes = List.of(
                episode(3, 2), episode(1, 2), episode(10, 1), episode(3, 1), episode(1, 1));

        SeriesDetailResponse detail = SeriesMapper.toDetailResponse(series, episodes);

        assertEquals(List.of(1, 3, 10), detail.seasons().stream().map(SeasonResponse::seasonNumber).toList());
        assertEquals(List.of(1, 2), numbers(detail.seasons().get(0)));
        assertEquals(List.of(1, 2), numbers(detail.seasons().get(1)));
        assertEquals(3, detail.seasonCount());
        assertEquals(5, detail.episodeCount());
    }

    @Test
    void sinEpisodiosElDetalleTieneTemporadasVaciasYRecuentosACero() {
        SeriesDetailResponse detail = SeriesMapper.toDetailResponse(series(), List.of());

        assertEquals(List.of(), detail.seasons());
        assertEquals(0, detail.seasonCount());
        assertEquals(0, detail.episodeCount());
    }

    @Test
    void losGenerosSeOrdenanPorNombre() {
        SeriesDetailResponse detail = SeriesMapper.toDetailResponse(series(), List.of());

        assertEquals(List.of("Comedia", "Drama", "Scifi"), detail.genres().stream().map(GenreResponse::name).toList());
    }

    @Test
    void unaSinopsisDeEpisodioEnBlancoSeGuardaComoNull() {
        Episode blank = EpisodeMapper.toEntity(
                new EpisodeRequest(1, 1, "Piloto", "  \t ", 45, "https://e.com/v.mp4"), series());
        Episode kept = EpisodeMapper.toEntity(
                new EpisodeRequest(1, 1, "Piloto", " Texto ", 45, "https://e.com/v.mp4"), series());

        assertNull(blank.getDescription());
        assertEquals(" Texto ", kept.getDescription());
    }

    /** El vídeo del episodio es opcional desde la V6: en blanco se guarda como {@code null}. */
    @Test
    void unVideoDeEpisodioEnBlancoSeGuardaComoNull() {
        Episode blank = EpisodeMapper.toEntity(new EpisodeRequest(1, 1, "Piloto", null, 45, "   "), series());
        Episode none = EpisodeMapper.toEntity(new EpisodeRequest(1, 1, "Piloto", null, 45, null), series());

        assertNull(blank.getVideoUrl());
        assertNull(none.getVideoUrl());
        assertNull(EpisodeMapper.toResponse(none).videoUrl());
    }

    /** El tráiler de la serie se guarda normalizado y sale igual en el resumen y en el detalle. */
    @Test
    void elTrailerDeLaSerieSeGuardaNormalizadoYSaleEnResumenYDetalle() {
        Series series = SeriesMapper.toEntity(new SeriesRequest("Serie", "Sinopsis", 2020, null,
                "https://e.com/i.jpg", "https://www.youtube-nocookie.com/embed/aB3_-eFgHiJ", Set.of(1L)));
        String normalized = "https://www.youtube.com/watch?v=aB3_-eFgHiJ";

        assertEquals(normalized, series.getTrailerUrl());
        assertEquals(normalized, SeriesMapper.toResponse(series, 0, 0).trailerUrl());
        assertEquals(normalized, SeriesMapper.toDetailResponse(series, List.of()).trailerUrl());

        SeriesMapper.updateEntity(new SeriesRequest("Serie", "Sinopsis", 2020, null,
                "https://e.com/i.jpg", "  ", Set.of(1L)), series);
        assertNull(series.getTrailerUrl());
    }

    private static List<Integer> numbers(SeasonResponse season) {
        return season.episodes().stream().map(e -> e.episodeNumber()).toList();
    }

    private static Series series() {
        Series series = new Series();
        series.setId(1L);
        series.setTitle("Serie");
        series.setGenres(new java.util.HashSet<>(Set.of(genre(3L, "Scifi"), genre(1L, "Drama"), genre(2L, "Comedia"))));
        return series;
    }

    private static Genre genre(Long id, String name) {
        Genre genre = new Genre();
        genre.setId(id);
        genre.setName(name);
        return genre;
    }

    private static Episode episode(int season, int number) {
        Episode episode = new Episode();
        episode.setId((long) (season * 100 + number));
        episode.setSeasonNumber(season);
        episode.setEpisodeNumber(number);
        episode.setTitle(season + "x" + number);
        return episode;
    }
}
