package com.emilio.streambox.service.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import com.emilio.streambox.dto.RecommendationBasis;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;

/**
 * Tests unitarios de {@link GenreBasedRecommender}, de {@link Recommendations}
 * y de {@link RecommendationBasis}.
 *
 * <p>
 * El criterio en sí (pesos, exclusión, desempates, el +2 de los favoritos) está
 * en SQL y se prueba en H2 y PostgreSQL ({@code LikesQueryContract},
 * {@code FavoriteGenresQueryContract}) y de punta a punta
 * ({@code RecommendationsIntegrationTest}). Aquí se fija lo que hace la clase:
 * qué {@code basis} sale de cada combinación de señales, que un favorito sin
 * títulos no cuenta, que sin señales no se lanzan las agregaciones y que con
 * ellas el límite pasa tal cual a las dos consultas.
 * </p>
 */
class GenreBasedRecommenderTest {

    private static final Long USER = 3L;

    private UserRepository userRepository;
    private GenreRepository genreRepository;
    private MovieRepository movieRepository;
    private SeriesRepository seriesRepository;
    private GenreBasedRecommender recommender;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        genreRepository = mock(GenreRepository.class);
        movieRepository = mock(MovieRepository.class);
        seriesRepository = mock(SeriesRepository.class);
        recommender = new GenreBasedRecommender(userRepository, genreRepository, movieRepository, seriesRepository);
    }

    /** Sin «me gusta» ni favoritos: {@code NONE}, sin ninguna consulta más que los dos {@code EXISTS}. */
    @Test
    void sinSenalesDevuelveNoneSinMasConsultas() {
        when(userRepository.hasVisibleLikes(USER)).thenReturn(false);
        when(userRepository.hasFavoriteGenres(USER)).thenReturn(false);

        Recommendations result = recommender.recommend(USER, 12);

        assertEquals(RecommendationBasis.NONE, result.basis());
        assertEquals(List.of(), result.movieIds());
        assertEquals(List.of(), result.seriesIds());
        verifyNoInteractions(genreRepository, movieRepository, seriesRepository);
    }

    /**
     * Cada combinación de señales da su {@code basis} y, si hay alguna, lanza
     * las dos consultas con el mismo límite. Antes de los géneros favoritos,
     * con solo favoritos el recomendador cortaba como si no hubiera nada.
     */
    @ParameterizedTest
    @CsvSource({
            "true,  false, GENRES",
            "false, true,  LIKES",
            "true,  true,  GENRES_AND_LIKES"
    })
    void cadaCombinacionDeSenalesDaSuBaseYLanzaLasConsultas(boolean genres, boolean likes,
            RecommendationBasis expected) {
        when(userRepository.hasVisibleLikes(USER)).thenReturn(likes);
        favoritesWithVisibleTitles(genres);
        when(movieRepository.findRecommendedMovieIds(USER, 5)).thenReturn(List.of(9L, 4L));
        when(seriesRepository.findRecommendedSeriesIds(USER, 5)).thenReturn(List.of(2L));

        Recommendations result = recommender.recommend(USER, 5);

        assertEquals(expected, result.basis());
        assertEquals(List.of(9L, 4L), result.movieIds());
        assertEquals(List.of(2L), result.seriesIds());
    }

    /**
     * Un favorito que se ha quedado sin títulos visibles no cuenta: si es la
     * única señal, la base es {@code NONE} (el inicio invita a elegir otros
     * gustos en vez de decir «Porque te gustan…» sin poder nombrar ninguno) y
     * no se lanzan las agregaciones.
     */
    @Test
    void unFavoritoSinTitulosVisiblesNoCuenta() {
        when(userRepository.hasVisibleLikes(USER)).thenReturn(false);
        when(userRepository.hasFavoriteGenres(USER)).thenReturn(true);
        when(genreRepository.findFavoritesByUserId(USER)).thenReturn(List.of(genre(7L)));
        when(genreRepository.findIdsWithVisibleTitles(List.of(7L))).thenReturn(List.of());

        Recommendations result = recommender.recommend(USER, 12);

        assertEquals(RecommendationBasis.NONE, result.basis());
        verifyNoInteractions(movieRepository, seriesRepository);
    }

    /** Con «me gusta» y un favorito vacío, la base es solo {@code LIKES}. */
    @Test
    void conMeGustaYUnFavoritoSinTitulosLaBaseEsSoloLikes() {
        when(userRepository.hasVisibleLikes(USER)).thenReturn(true);
        when(userRepository.hasFavoriteGenres(USER)).thenReturn(true);
        when(genreRepository.findFavoritesByUserId(USER)).thenReturn(List.of(genre(7L), genre(8L)));
        when(genreRepository.findIdsWithVisibleTitles(List.of(7L, 8L))).thenReturn(List.of());
        when(movieRepository.findRecommendedMovieIds(USER, 12)).thenReturn(List.of(1L));
        when(seriesRepository.findRecommendedSeriesIds(USER, 12)).thenReturn(List.of());

        assertEquals(RecommendationBasis.LIKES, recommender.recommend(USER, 12).basis());
    }

    /** Basta con que uno de los favoritos conserve títulos. */
    @Test
    void bastaConUnFavoritoConTitulos() {
        when(userRepository.hasVisibleLikes(USER)).thenReturn(false);
        when(userRepository.hasFavoriteGenres(USER)).thenReturn(true);
        when(genreRepository.findFavoritesByUserId(USER)).thenReturn(List.of(genre(7L), genre(8L), genre(9L)));
        when(genreRepository.findIdsWithVisibleTitles(List.of(7L, 8L, 9L))).thenReturn(List.of(8L));
        when(movieRepository.findRecommendedMovieIds(USER, 12)).thenReturn(List.of());
        when(seriesRepository.findRecommendedSeriesIds(USER, 12)).thenReturn(List.of());

        assertEquals(RecommendationBasis.GENRES, recommender.recommend(USER, 12).basis());
    }

    /** Sin favoritos, el {@code EXISTS} basta: no se cargan géneros. */
    @Test
    void sinFavoritosNoSeCarganGeneros() {
        when(userRepository.hasVisibleLikes(USER)).thenReturn(true);
        when(userRepository.hasFavoriteGenres(USER)).thenReturn(false);
        when(movieRepository.findRecommendedMovieIds(USER, 12)).thenReturn(List.of());
        when(seriesRepository.findRecommendedSeriesIds(USER, 12)).thenReturn(List.of());

        recommender.recommend(USER, 12);

        verify(genreRepository, never()).findFavoritesByUserId(anyLong());
        verify(genreRepository, never()).findIdsWithVisibleTitles(any());
    }

    // ------------------------------------------------------------------
    // Recommendations y RecommendationBasis
    // ------------------------------------------------------------------

    /** Las listas del resultado no se pueden modificar desde fuera ni cambian si cambia la original. */
    @Test
    void elResultadoEsInmutable() {
        List<Long> source = new ArrayList<>(List.of(1L, 2L));
        Recommendations result = new Recommendations(RecommendationBasis.LIKES, source, List.of());

        source.add(3L);

        assertEquals(List.of(1L, 2L), result.movieIds());
        assertThrows(UnsupportedOperationException.class, () -> result.movieIds().add(4L));
    }

    /** Un id nulo es un fallo del recomendador: se rechaza al crear el resultado, no al cargar. */
    @Test
    void elResultadoRechazaIdsNulos() {
        List<Long> withNull = new ArrayList<>();
        withNull.add(null);

        assertThrows(NullPointerException.class,
                () -> new Recommendations(RecommendationBasis.LIKES, withNull, List.of()));
    }

    /** Sin base, un recomendador no puede devolver títulos: el inicio se contradiría. */
    @Test
    void elResultadoRechazaTitulosSinBase() {
        assertThrows(IllegalArgumentException.class,
                () -> new Recommendations(RecommendationBasis.NONE, List.of(1L), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new Recommendations(RecommendationBasis.NONE, List.of(), List.of(1L)));
        assertThrows(NullPointerException.class, () -> new Recommendations(null, List.of(), List.of()));
    }

    /** Con base, unas listas vacías son válidas (no queda nada nuevo que proponer). */
    @ParameterizedTest
    @EnumSource(RecommendationBasis.class)
    void cualquierBaseAdmiteListasVacias(RecommendationBasis basis) {
        assertEquals(basis, new Recommendations(basis, List.of(), List.of()).basis());
    }

    @ParameterizedTest
    @CsvSource({
            "false, false, NONE",
            "true,  false, GENRES",
            "false, true,  LIKES",
            "true,  true,  GENRES_AND_LIKES"
    })
    void laBaseCombinaLasDosSenales(boolean genres, boolean likes, RecommendationBasis expected) {
        assertEquals(expected, RecommendationBasis.of(genres, likes));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Simula que el usuario tiene (o no) un favorito con títulos visibles. */
    private void favoritesWithVisibleTitles(boolean has) {
        when(userRepository.hasFavoriteGenres(USER)).thenReturn(has);
        if (has) {
            when(genreRepository.findFavoritesByUserId(USER)).thenReturn(List.of(genre(7L)));
            when(genreRepository.findIdsWithVisibleTitles(List.of(7L))).thenReturn(List.of(7L));
        }
    }

    private static Genre genre(Long id) {
        Genre genre = new Genre();
        genre.setId(id);
        genre.setName("Género " + id);
        return genre;
    }
}
