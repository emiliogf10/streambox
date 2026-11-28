package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.emilio.streambox.dto.SeriesResponse;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.exception.InvalidParameterException;
import com.emilio.streambox.exception.SeriesNotFoundException;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.SeriesEpisodeStats;
import com.emilio.streambox.repository.SeriesRepository;

/**
 * Tests unitarios de {@link SeriesService#getSimilarSeries(Long, int)} con los
 * repositorios simulados (gemelos de {@link MovieServiceSimilarTest}).
 *
 * <p>
 * Fijan lo que una base de datos real no garantiza que se vea: que el orden
 * sale de la lista de ids y no del orden de la carga (con H2 suele coincidir
 * por casualidad), que cada serie recibe <em>sus</em> recuentos, que sin
 * candidatas no se lanzan ni la carga ni los recuentos, que una serie borrada
 * entre consultas se omite y que un origen no visible corta antes de buscar
 * similares.
 * </p>
 */
class SeriesServiceSimilarTest {

    private static final Long ORIGIN = 1L;

    private SeriesRepository seriesRepository;
    private EpisodeRepository episodeRepository;
    private SeriesService service;

    @BeforeEach
    void setUp() {
        seriesRepository = mock(SeriesRepository.class);
        episodeRepository = mock(EpisodeRepository.class);
        service = new SeriesService(seriesRepository, episodeRepository, mock(GenreRepository.class));
        when(episodeRepository.existsBySeriesId(ORIGIN)).thenReturn(true);
    }

    @Test
    void reconstruyeElOrdenDeLosIdsYDaACadaSerieSusRecuentos() {
        List<Long> ordered = List.of(30L, 10L, 20L);
        when(seriesRepository.findSimilarSeriesIds(ORIGIN, 12)).thenReturn(ordered);
        when(seriesRepository.findAllWithGenresByIdIn(ordered))
                .thenReturn(List.of(series(10L), series(20L), series(30L)));
        when(episodeRepository.countBySeriesIds(ordered))
                .thenReturn(List.of(stats(20L, 2, 7), stats(10L, 1, 3), stats(30L, 4, 40)));

        List<SeriesResponse> similar = service.getSimilarSeries(ORIGIN, 12);

        assertEquals(ordered, similar.stream().map(SeriesResponse::id).toList());
        assertEquals(List.of(4L, 1L, 2L), similar.stream().map(SeriesResponse::seasonCount).toList());
        assertEquals(List.of(40L, 3L, 7L), similar.stream().map(SeriesResponse::episodeCount).toList());
    }

    @Test
    void sinCandidatasDevuelveVacioSinCargarSeriesNiRecuentos() {
        when(seriesRepository.findSimilarSeriesIds(ORIGIN, 12)).thenReturn(List.of());

        assertEquals(List.of(), service.getSimilarSeries(ORIGIN, 12));
        verify(seriesRepository, never()).findAllWithGenresByIdIn(any());
        verify(episodeRepository, never()).countBySeriesIds(any());
    }

    @Test
    void unaSerieBorradaEntreLasConsultasSeOmite() {
        List<Long> ordered = List.of(30L, 10L, 20L);
        when(seriesRepository.findSimilarSeriesIds(ORIGIN, 12)).thenReturn(ordered);
        when(seriesRepository.findAllWithGenresByIdIn(ordered)).thenReturn(List.of(series(20L), series(30L)));
        when(episodeRepository.countBySeriesIds(List.of(30L, 20L)))
                .thenReturn(List.of(stats(30L, 1, 1), stats(20L, 1, 2)));

        List<SeriesResponse> similar = service.getSimilarSeries(ORIGIN, 12);

        assertEquals(List.of(30L, 20L), similar.stream().map(SeriesResponse::id).toList());
    }

    /**
     * QA-SS-1: las tres consultas no ven la misma foto de la base de datos
     * (READ COMMITTED). Si el panel borra el último episodio de una candidata
     * después de elegir los ids, la serie ya está oculta: no aparece en los
     * recuentos y no debe salir en la fila (con 0 episodios su tarjeta llevaría
     * a un 404). La lista puede quedar más corta que el límite.
     */
    @Test
    void unaSerieQueSeQuedaSinEpisodiosEntreLasConsultasSeOmite() {
        List<Long> ordered = List.of(1L, 2L);
        when(seriesRepository.findSimilarSeriesIds(ORIGIN, 12)).thenReturn(ordered);
        when(seriesRepository.findAllWithGenresByIdIn(ordered)).thenReturn(List.of(series(1L), series(2L)));
        when(episodeRepository.countBySeriesIds(ordered)).thenReturn(List.of(stats(1L, 1, 3)));

        List<SeriesResponse> similar = service.getSimilarSeries(ORIGIN, 12);

        assertEquals(List.of(1L), similar.stream().map(SeriesResponse::id).toList());
        assertEquals(3L, similar.get(0).episodeCount());
    }

    /**
     * Inexistente y sin episodios son el mismo caso para
     * {@code existsBySeriesId}: misma excepción, mismo mensaje que el detalle
     * y sin llegar a buscar similares.
     */
    @Test
    void unOrigenNoVisibleDa404SinBuscarSimilares() {
        when(episodeRepository.existsBySeriesId(99L)).thenReturn(false);

        SeriesNotFoundException exception =
                assertThrows(SeriesNotFoundException.class, () -> service.getSimilarSeries(99L, 12));

        assertEquals(SeriesService.SERIES_NOT_FOUND, exception.getMessage());
        verify(seriesRepository, never()).findSimilarSeriesIds(anyLong(), anyInt());
    }

    @Test
    void pasaElLimitePedidoALaConsulta() {
        when(seriesRepository.findSimilarSeriesIds(ORIGIN, 24)).thenReturn(List.of());

        service.getSimilarSeries(ORIGIN, 24);

        verify(seriesRepository).findSimilarSeriesIds(ORIGIN, 24);
    }

    /**
     * El controlador ya rechaza estos límites; el servicio también, para que
     * otro llamante no pueda mandar un {@code FETCH FIRST} negativo o enorme.
     */
    @ParameterizedTest
    @ValueSource(ints = { 0, -1, 25, Integer.MAX_VALUE })
    void rechazaUnLimiteFueraDeRangoSinTocarLosRepositorios(int limit) {
        SeriesRepository untouchedSeries = mock(SeriesRepository.class);
        EpisodeRepository untouchedEpisodes = mock(EpisodeRepository.class);
        SeriesService guarded = new SeriesService(untouchedSeries, untouchedEpisodes, mock(GenreRepository.class));

        InvalidParameterException exception =
                assertThrows(InvalidParameterException.class, () -> guarded.getSimilarSeries(ORIGIN, limit));

        assertEquals("limit", exception.getParameter());
        verifyNoInteractions(untouchedSeries, untouchedEpisodes);
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
