package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.exception.AmbiguousTitleException;
import com.emilio.streambox.exception.MovieAlreadyInFavoritesException;
import com.emilio.streambox.exception.MovieNotFoundException;
import com.emilio.streambox.exception.MovieNotInFavoritesException;
import com.emilio.streambox.exception.UserNotFoundException;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.UserRepository;

/**
 * Tests unitarios de {@link FavoriteService} con repositorios simulados.
 *
 * <p>
 * Cubren casos que una petición HTTP normal no puede provocar, como la
 * condición de carrera de dos altas simultáneas de la misma película.
 * </p>
 */
class FavoriteServiceTest {

    private static final Long USER = 1L;
    private static final Long MOVIE = 10L;

    private UserRepository userRepository;
    private MovieRepository movieRepository;
    private FavoriteService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        movieRepository = mock(MovieRepository.class);
        service = new FavoriteService(userRepository, movieRepository);

        when(userRepository.existsById(USER)).thenReturn(true);
        when(movieRepository.existsById(MOVIE)).thenReturn(true);
    }

    private static Movie movie(Long id, String title) {
        Movie movie = new Movie();
        movie.setId(id);
        movie.setTitle(title);
        return movie;
    }

    @Test
    void dosAltasSimultaneasDeLaMismaPeliculaDanConflictoYNoUnErrorInterno() {
        // Ambas peticiones comprueban "no está" a la vez; la segunda inserción
        // choca con la clave primaria de la tabla de unión.
        when(userRepository.isFavorite(USER, MOVIE)).thenReturn(false);
        doThrow(new DataIntegrityViolationException("duplicate key"))
                .when(userRepository).addFavorite(USER, MOVIE);

        assertThrows(MovieAlreadyInFavoritesException.class,
                () -> service.addFavorite(USER, MOVIE));
    }

    @Test
    void siYaEstaEnLaListaNoIntentaInsertar() {
        when(userRepository.isFavorite(USER, MOVIE)).thenReturn(true);

        assertThrows(MovieAlreadyInFavoritesException.class,
                () -> service.addFavorite(USER, MOVIE));

        verify(userRepository, never()).addFavorite(anyLong(), anyLong());
    }

    @Test
    void anadirConUsuarioInexistenteLanzaUsuarioNoEncontrado() {
        when(userRepository.existsById(USER)).thenReturn(false);

        assertThrows(UserNotFoundException.class, () -> service.addFavorite(USER, MOVIE));

        verify(userRepository, never()).addFavorite(anyLong(), anyLong());
    }

    @Test
    void anadirOQuitarPeliculaInexistenteLanzaPeliculaNoEncontrada() {
        when(movieRepository.existsById(99L)).thenReturn(false);

        assertThrows(MovieNotFoundException.class, () -> service.addFavorite(USER, 99L));
        assertThrows(MovieNotFoundException.class, () -> service.removeFavorite(USER, 99L));

        verify(userRepository, never()).removeFavorite(anyLong(), anyLong());
    }

    @Test
    void quitarUnaPeliculaQueNoEstabaLanzaNoEstaEnFavoritos() {
        when(userRepository.removeFavorite(USER, MOVIE)).thenReturn(0);

        assertThrows(MovieNotInFavoritesException.class,
                () -> service.removeFavorite(USER, MOVIE));
    }

    @Test
    void quitarUnaPeliculaQueEstabaNoLanzaNada() {
        when(userRepository.removeFavorite(USER, MOVIE)).thenReturn(1);

        service.removeFavorite(USER, MOVIE);

        verify(userRepository).removeFavorite(USER, MOVIE);
    }

    @Test
    void porTituloInexistenteLanzaPeliculaNoEncontrada() {
        when(movieRepository.findAllByTitleIgnoreCase("nada")).thenReturn(List.of());

        assertThrows(MovieNotFoundException.class, () -> service.addFavoriteByTitle(USER, "nada"));
        assertThrows(MovieNotFoundException.class, () -> service.removeFavoriteByTitle(USER, "nada"));
    }

    @Test
    void porTituloConVariasCoincidenciasLanzaAmbiguo() {
        when(movieRepository.findAllByTitleIgnoreCase("dune"))
                .thenReturn(List.of(movie(1L, "Dune"), movie(2L, "DUNE")));

        assertThrows(AmbiguousTitleException.class, () -> service.addFavoriteByTitle(USER, "dune"));
        assertThrows(AmbiguousTitleException.class, () -> service.removeFavoriteByTitle(USER, "dune"));
    }

    @Test
    void porTituloUsaElIdDeLaPeliculaEncontrada() {
        when(movieRepository.findAllByTitleIgnoreCase("dune")).thenReturn(List.of(movie(MOVIE, "Dune")));
        when(userRepository.isFavorite(USER, MOVIE)).thenReturn(false);

        service.addFavoriteByTitle(USER, "dune");

        verify(userRepository).addFavorite(USER, MOVIE);
    }

    @Test
    void vaciarLaListaDelegaEnElRepositorioAunqueEsteVacia() {
        when(userRepository.clearFavorites(USER)).thenReturn(0);

        service.clearFavorites(USER);

        verify(userRepository).clearFavorites(USER);
    }
}
