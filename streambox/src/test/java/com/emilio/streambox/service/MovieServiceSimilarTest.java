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

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.emilio.streambox.dto.MovieResponse;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.exception.InvalidParameterException;
import com.emilio.streambox.exception.MovieNotFoundException;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;

/**
 * Tests unitarios de {@link MovieService#getSimilarMovies(Long, int)} con el
 * repositorio simulado.
 *
 * <p>
 * Fijan lo que una base de datos real no garantiza que se vea: que el orden
 * del resultado sale de la lista de ids y no del orden en que la segunda
 * consulta devuelve las películas (con H2 suele coincidir por casualidad), que
 * sin candidatas no se lanza la segunda consulta y que una película borrada
 * entre las dos consultas se omite en vez de romper el mapeo.
 * </p>
 */
class MovieServiceSimilarTest {

    private static final Long ORIGIN = 1L;

    private MovieRepository movieRepository;
    private MovieService service;

    @BeforeEach
    void setUp() {
        movieRepository = mock(MovieRepository.class);
        service = new MovieService(movieRepository, mock(GenreRepository.class));
        when(movieRepository.existsById(ORIGIN)).thenReturn(true);
    }

    @Test
    void reconstruyeElOrdenDeLosIdsAunqueLaCargaLosDevuelvaDesordenados() {
        List<Long> ordered = List.of(30L, 10L, 20L);
        when(movieRepository.findSimilarMovieIds(ORIGIN, 12)).thenReturn(ordered);
        when(movieRepository.findAllWithGenresByIdIn(ordered))
                .thenReturn(List.of(movie(10L), movie(20L), movie(30L)));

        List<MovieResponse> similar = service.getSimilarMovies(ORIGIN, 12);

        assertEquals(ordered, similar.stream().map(MovieResponse::id).toList());
    }

    @Test
    void sinCandidatasDevuelveVacioSinLanzarLaSegundaConsulta() {
        when(movieRepository.findSimilarMovieIds(ORIGIN, 12)).thenReturn(List.of());

        assertEquals(List.of(), service.getSimilarMovies(ORIGIN, 12));
        verify(movieRepository, never()).findAllWithGenresByIdIn(any());
    }

    @Test
    void unaPeliculaBorradaEntreLasDosConsultasSeOmite() {
        List<Long> ordered = List.of(30L, 10L, 20L);
        when(movieRepository.findSimilarMovieIds(ORIGIN, 12)).thenReturn(ordered);
        when(movieRepository.findAllWithGenresByIdIn(ordered)).thenReturn(List.of(movie(20L), movie(30L)));

        List<MovieResponse> similar = service.getSimilarMovies(ORIGIN, 12);

        assertEquals(List.of(30L, 20L), similar.stream().map(MovieResponse::id).toList());
    }

    @Test
    void unaPeliculaInexistenteDa404SinBuscarSimilares() {
        when(movieRepository.existsById(99L)).thenReturn(false);

        MovieNotFoundException exception =
                assertThrows(MovieNotFoundException.class, () -> service.getSimilarMovies(99L, 12));

        assertEquals("Película no encontrada", exception.getMessage());
        verify(movieRepository, never()).findSimilarMovieIds(anyLong(), anyInt());
    }

    @Test
    void pasaElLimitePedidoALaConsulta() {
        when(movieRepository.findSimilarMovieIds(ORIGIN, 24)).thenReturn(List.of());

        service.getSimilarMovies(ORIGIN, 24);

        verify(movieRepository).findSimilarMovieIds(ORIGIN, 24);
    }

    /**
     * El controlador ya rechaza estos límites; el servicio también, para que
     * otro llamante no pueda mandar un {@code FETCH FIRST} negativo o enorme.
     */
    @ParameterizedTest
    @ValueSource(ints = { 0, -1, 25, Integer.MAX_VALUE })
    void rechazaUnLimiteFueraDeRangoSinTocarElRepositorio(int limit) {
        MovieRepository untouched = mock(MovieRepository.class);
        MovieService guarded = new MovieService(untouched, mock(GenreRepository.class));

        InvalidParameterException exception =
                assertThrows(InvalidParameterException.class, () -> guarded.getSimilarMovies(ORIGIN, limit));

        assertEquals("limit", exception.getParameter());
        verifyNoInteractions(untouched);
    }

    private static Movie movie(Long id) {
        Movie movie = new Movie();
        movie.setId(id);
        movie.setTitle("Película " + id);
        return movie;
    }
}
