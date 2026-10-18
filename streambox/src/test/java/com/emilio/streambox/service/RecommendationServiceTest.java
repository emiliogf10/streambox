package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.emilio.streambox.dto.MovieResponse;
import com.emilio.streambox.dto.RecommendationsResponse;
import com.emilio.streambox.dto.SeriesResponse;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.exception.InvalidParameterException;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesEpisodeStats;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.service.recommendation.Recommendations;
import com.emilio.streambox.service.recommendation.Recommender;

/**
 * Tests unitarios de {@link RecommendationService} con un {@link Recommender} y
 * repositorios simulados.
 *
 * <p>
 * Fijan el trabajo que el servicio hace <em>sea cual sea</em> el recomendador
 * (hoy por géneros, mañana quizá una IA): el orden sale de la lista de ids y no
 * del orden en que llega la carga, se descartan los títulos borrados y las
 * series sin episodios, se quitan duplicados y se recorta al límite aunque la
 * implementación no lo haga, y no se lanza ninguna carga con una lista vacía.
 * </p>
 */
class RecommendationServiceTest {

    private static final Long USER = 5L;

    private Recommender recommender;
    private MovieRepository movieRepository;
    private SeriesRepository seriesRepository;
    private EpisodeRepository episodeRepository;
    private RecommendationService service;

    @BeforeEach
    void setUp() {
        recommender = mock(Recommender.class);
        movieRepository = mock(MovieRepository.class);
        seriesRepository = mock(SeriesRepository.class);
        episodeRepository = mock(EpisodeRepository.class);
        service = new RecommendationService(recommender, movieRepository, seriesRepository, episodeRepository);
    }

    @Test
    void sinMeGustaNoCargaNingunTitulo() {
        when(recommender.recommend(USER, 12)).thenReturn(Recommendations.withoutLikes());

        RecommendationsResponse response = service.getRecommendations(USER, 12);

        assertFalse(response.basedOnLikes());
        assertEquals(List.of(), response.movies());
        assertEquals(List.of(), response.series());
        verifyNoInteractions(movieRepository, seriesRepository, episodeRepository);
    }

    /** Con «me gusta» pero sin candidatas: {@code basedOnLikes} sigue siendo {@code true}. */
    @Test
    void conMeGustaPeroSinCandidatasNoCargaNadaYConservaBasedOnLikes() {
        when(recommender.recommend(USER, 12)).thenReturn(new Recommendations(true, List.of(), List.of()));

        RecommendationsResponse response = service.getRecommendations(USER, 12);

        assertTrue(response.basedOnLikes());
        assertEquals(List.of(), response.movies());
        assertEquals(List.of(), response.series());
        verifyNoInteractions(movieRepository, seriesRepository, episodeRepository);
    }

    @Test
    void reconstruyeElOrdenDeLasPeliculasYOmiteLasBorradas() {
        List<Long> ordered = List.of(30L, 10L, 99L, 20L);
        when(recommender.recommend(USER, 12)).thenReturn(new Recommendations(true, ordered, List.of()));
        // La carga las devuelve en otro orden y sin la 99 (borrada entre las dos consultas).
        when(movieRepository.findAllWithGenresByIdIn(ordered)).thenReturn(List.of(movie(10L), movie(20L), movie(30L)));

        RecommendationsResponse response = service.getRecommendations(USER, 12);

        assertEquals(List.of(30L, 10L, 20L), response.movies().stream().map(MovieResponse::id).toList());
        verifyNoInteractions(seriesRepository, episodeRepository);
    }

    /**
     * Las series sin episodios (fuera de los recuentos) no salen nunca, aunque
     * el recomendador las proponga; las demás llegan con sus recuentos y en el
     * orden de los ids.
     */
    @Test
    void reconstruyeElOrdenDeLasSeriesYOmiteLasOcultasYLasBorradas() {
        List<Long> ordered = List.of(3L, 1L, 2L, 4L);
        when(recommender.recommend(USER, 12)).thenReturn(new Recommendations(true, List.of(), ordered));
        // La 4 no se encuentra (borrada); la 2 existe pero no tiene episodios.
        when(seriesRepository.findAllWithGenresByIdIn(ordered)).thenReturn(List.of(series(1L), series(2L), series(3L)));
        when(episodeRepository.countBySeriesIds(ordered)).thenReturn(List.of(stats(1L, 1, 3), stats(3L, 2, 10)));

        List<SeriesResponse> series = service.getRecommendations(USER, 12).series();

        assertEquals(List.of(3L, 1L), series.stream().map(SeriesResponse::id).toList());
        assertEquals(2, series.get(0).seasonCount());
        assertEquals(10, series.get(0).episodeCount());
        verifyNoInteractions(movieRepository);
    }

    /** Si una implementación no cumple su contrato, la API sigue cumpliendo el suyo. */
    @Test
    void quitaIdsRepetidosYRecortaAlLimiteAunqueElRecomendadorNoLoHaga() {
        when(recommender.recommend(USER, 2))
                .thenReturn(new Recommendations(true, List.of(5L, 5L, 6L, 7L), List.of()));
        when(movieRepository.findAllWithGenresByIdIn(List.of(5L, 6L))).thenReturn(List.of(movie(6L), movie(5L)));

        RecommendationsResponse response = service.getRecommendations(USER, 2);

        assertEquals(List.of(5L, 6L), response.movies().stream().map(MovieResponse::id).toList());
        verify(movieRepository).findAllWithGenresByIdIn(List.of(5L, 6L));
    }

    /** El límite acaba en un {@code FETCH FIRST}: se valida antes de llamar al recomendador. */
    @ParameterizedTest
    @ValueSource(ints = { Integer.MIN_VALUE, -1, 0, 25, Integer.MAX_VALUE })
    void unLimiteFueraDeRangoSeRechazaSinLlamarAlRecomendador(int limit) {
        InvalidParameterException error = assertThrows(InvalidParameterException.class,
                () -> service.getRecommendations(USER, limit));

        assertEquals("limit", error.getParameter());
        verifyNoInteractions(recommender, movieRepository, seriesRepository, episodeRepository);
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 12, 24 })
    void losLimitesDelRangoSeAceptan(int limit) {
        when(recommender.recommend(USER, limit)).thenReturn(Recommendations.withoutLikes());

        service.getRecommendations(USER, limit);

        verify(recommender).recommend(USER, limit);
    }

    private static Movie movie(Long id) {
        Movie movie = new Movie();
        movie.setId(id);
        movie.setTitle("Película " + id);
        movie.setGenres(new HashSet<>());
        return movie;
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
            public long getSeasonCount() {
                return seasons;
            }

            @Override
            public long getEpisodeCount() {
                return episodes;
            }
        };
    }
}
