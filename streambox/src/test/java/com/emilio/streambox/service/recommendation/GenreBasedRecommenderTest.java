package com.emilio.streambox.service.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;

/**
 * Tests unitarios de {@link GenreBasedRecommender} y de {@link Recommendations}.
 *
 * <p>
 * El criterio en sí (pesos, exclusión, desempates) está en SQL y se prueba en
 * H2 y PostgreSQL ({@code LikesQueryContract}) y de punta a punta
 * ({@code RecommendationsIntegrationTest}). Aquí se fija lo que hace la clase:
 * sin «me gusta» visibles no lanza las agregaciones, y con ellos pasa el límite
 * tal cual a las dos consultas.
 * </p>
 */
class GenreBasedRecommenderTest {

    private static final Long USER = 3L;

    private UserRepository userRepository;
    private MovieRepository movieRepository;
    private SeriesRepository seriesRepository;
    private GenreBasedRecommender recommender;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        movieRepository = mock(MovieRepository.class);
        seriesRepository = mock(SeriesRepository.class);
        recommender = new GenreBasedRecommender(userRepository, movieRepository, seriesRepository);
    }

    @Test
    void sinMeGustaVisiblesDevuelveVacioSinMasConsultas() {
        when(userRepository.hasVisibleLikes(USER)).thenReturn(false);

        Recommendations result = recommender.recommend(USER, 12);

        assertFalse(result.basedOnLikes());
        assertEquals(List.of(), result.movieIds());
        assertEquals(List.of(), result.seriesIds());
        verifyNoInteractions(movieRepository, seriesRepository);
    }

    @Test
    void conMeGustaDevuelveLosIdsDeLasDosConsultasConElMismoLimite() {
        when(userRepository.hasVisibleLikes(USER)).thenReturn(true);
        when(movieRepository.findRecommendedMovieIds(USER, 5)).thenReturn(List.of(9L, 4L));
        when(seriesRepository.findRecommendedSeriesIds(USER, 5)).thenReturn(List.of(2L));

        Recommendations result = recommender.recommend(USER, 5);

        assertTrue(result.basedOnLikes());
        assertEquals(List.of(9L, 4L), result.movieIds());
        assertEquals(List.of(2L), result.seriesIds());
    }

    /** Las listas del resultado no se pueden modificar desde fuera ni cambian si cambia la original. */
    @Test
    void elResultadoEsInmutable() {
        List<Long> source = new ArrayList<>(List.of(1L, 2L));
        Recommendations result = new Recommendations(true, source, List.of());

        source.add(3L);

        assertEquals(List.of(1L, 2L), result.movieIds());
        assertThrows(UnsupportedOperationException.class, () -> result.movieIds().add(4L));
    }

    /** Un id nulo es un fallo del recomendador: se rechaza al crear el resultado, no al cargar. */
    @Test
    void elResultadoRechazaIdsNulos() {
        List<Long> withNull = new ArrayList<>();
        withNull.add(null);

        assertThrows(NullPointerException.class, () -> new Recommendations(true, withNull, List.of()));
    }
}
