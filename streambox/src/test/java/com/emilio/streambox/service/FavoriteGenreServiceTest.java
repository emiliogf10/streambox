package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.emilio.streambox.dto.FavoriteGenresRequest;
import com.emilio.streambox.dto.GenreResponse;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.exception.InvalidParameterException;
import com.emilio.streambox.exception.UserNotFoundException;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.UserRepository;

/**
 * Tests unitarios de {@link FavoriteGenreService}: el orden de validación (sin
 * tocar la base de datos para lo que no la necesita), el orden obligatorio de
 * la escritura (cerrojo, borrado, inserción) y los casos de carrera que solo se
 * pueden forzar con dobles (cuenta borrada, género borrado entre la validación y
 * el {@code INSERT}). El contrato HTTP está en
 * {@code FavoriteGenresIntegrationTest}.
 */
class FavoriteGenreServiceTest {

    private static final Long USER = 4L;
    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

    private GenreRepository genreRepository;
    private UserRepository userRepository;
    private FavoriteGenreService service;

    @BeforeEach
    void setUp() {
        genreRepository = mock(GenreRepository.class);
        userRepository = mock(UserRepository.class);
        service = new FavoriteGenreService(genreRepository, userRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * Orden obligatorio: primero el cerrojo de la fila del usuario (que cierra
     * la pregunta), después el borrado y por último la inserción, con la hora
     * del {@code Clock}.
     */
    @Test
    void guardaEnElOrdenObligatorio() {
        Set<Long> ids = Set.of(1L, 2L, 3L);
        when(genreRepository.findIdsWithVisibleTitles(anyCollection())).thenReturn(List.of(3L, 1L, 2L));
        when(userRepository.markGenresOnboarded(USER, NOW)).thenReturn(1);
        when(genreRepository.addFavorites(USER, ids, NOW)).thenReturn(3);
        when(genreRepository.findFavoritesByUserId(USER)).thenReturn(List.of(genre(2L, "Acción")));

        List<GenreResponse> result = service.replaceFavoriteGenres(USER, request(1L, 2L, 3L));

        InOrder order = inOrder(genreRepository, userRepository);
        order.verify(genreRepository).findIdsWithVisibleTitles(ids);
        order.verify(userRepository).markGenresOnboarded(USER, NOW);
        order.verify(genreRepository).clearFavorites(USER);
        order.verify(genreRepository).addFavorites(USER, ids, NOW);
        order.verify(genreRepository).findFavoritesByUserId(USER);
        assertEquals(List.of(new GenreResponse(2L, "Acción")), result);
    }

    /** La lista vacía cierra la pregunta y borra, pero no inserta ni valida nada. */
    @Test
    void laListaVaciaCierraLaPreguntaYBorraSinInsertar() {
        when(userRepository.markGenresOnboarded(USER, NOW)).thenReturn(1);

        assertEquals(List.of(), service.replaceFavoriteGenres(USER, request()));

        InOrder order = inOrder(userRepository, genreRepository);
        order.verify(userRepository).markGenresOnboarded(USER, NOW);
        order.verify(genreRepository).clearFavorites(USER);
        verify(genreRepository, never()).findIdsWithVisibleTitles(any());
        verify(genreRepository, never()).addFavorites(anyLong(), any(), any());
    }

    /** El número se comprueba antes que nada: ninguna consulta. */
    @Test
    void unNumeroFueraDeRangoNoConsultaNada() {
        InvalidParameterException error = assertThrows(InvalidParameterException.class,
                () -> service.replaceFavoriteGenres(USER, request(1L, 2L)));

        assertEquals("genreIds", error.getParameter());
        assertEquals(FavoriteGenreService.COUNT_MESSAGE, error.getMessage());
        verifyNoInteractions(genreRepository, userRepository);
    }

    /** Los repetidos se rechazan antes de consultar (y no se «arreglan» quitándolos). */
    @Test
    void losRepetidosNoConsultanNada() {
        InvalidParameterException error = assertThrows(InvalidParameterException.class,
                () -> service.replaceFavoriteGenres(USER, request(1L, 1L, 2L)));

        assertEquals(FavoriteGenreService.DUPLICATES_MESSAGE, error.getMessage());
        verifyNoInteractions(genreRepository, userRepository);
    }

    @Test
    void unIdNuloNoConsultaNada() {
        InvalidParameterException error = assertThrows(InvalidParameterException.class,
                () -> service.replaceFavoriteGenres(USER, new FavoriteGenresRequest(Arrays.asList(1L, null, 2L))));

        assertEquals(FavoriteGenreService.NOT_ELIGIBLE_MESSAGE, error.getMessage());
        verifyNoInteractions(genreRepository, userRepository);
    }

    /** Un género no elegible se rechaza antes de bloquear la fila y de borrar nada. */
    @Test
    void unGeneroNoElegibleNoEscribeNada() {
        when(genreRepository.findIdsWithVisibleTitles(anyCollection())).thenReturn(List.of(1L, 2L));

        InvalidParameterException error = assertThrows(InvalidParameterException.class,
                () -> service.replaceFavoriteGenres(USER, request(1L, 2L, 3L)));

        assertEquals(FavoriteGenreService.NOT_ELIGIBLE_MESSAGE, error.getMessage());
        verifyNoInteractions(userRepository);
        verify(genreRepository, never()).clearFavorites(anyLong());
    }

    /** Si la cuenta ya no existe, 404 sin borrar ni insertar. */
    @Test
    void siLaCuentaNoExisteNoBorraNiInserta() {
        when(genreRepository.findIdsWithVisibleTitles(anyCollection())).thenReturn(List.of(1L, 2L, 3L));
        when(userRepository.markGenresOnboarded(USER, NOW)).thenReturn(0);

        assertThrows(UserNotFoundException.class, () -> service.replaceFavoriteGenres(USER, request(1L, 2L, 3L)));

        verify(genreRepository, never()).clearFavorites(anyLong());
        verify(genreRepository, never()).addFavorites(anyLong(), any(), any());
    }

    /**
     * Si un género se borra entre la validación y el {@code INSERT}, entran
     * menos filas: se lanza la excepción para que la transacción deshaga
     * también el borrado y la lista anterior quede intacta.
     */
    @Test
    void siEntranMenosFilasDeLasValidadasLanzaParaDeshacer() {
        when(genreRepository.findIdsWithVisibleTitles(anyCollection())).thenReturn(List.of(1L, 2L, 3L));
        when(userRepository.markGenresOnboarded(USER, NOW)).thenReturn(1);
        when(genreRepository.addFavorites(USER, Set.of(1L, 2L, 3L), NOW)).thenReturn(2);

        InvalidParameterException error = assertThrows(InvalidParameterException.class,
                () -> service.replaceFavoriteGenres(USER, request(1L, 2L, 3L)));

        assertEquals(FavoriteGenreService.NOT_ELIGIBLE_MESSAGE, error.getMessage());
        verify(genreRepository, never()).findFavoritesByUserId(anyLong());
    }

    /** El GET solo devuelve los favoritos que siguen teniendo títulos visibles, en el orden del repositorio. */
    @Test
    void elGetFiltraLosFavoritosSinTitulosVisibles() {
        when(genreRepository.findFavoritesByUserId(USER))
                .thenReturn(List.of(genre(5L, "Acción"), genre(9L, "Drama"), genre(2L, "Terror")));
        when(genreRepository.findIdsWithVisibleTitles(List.of(5L, 9L, 2L))).thenReturn(List.of(2L, 5L));

        assertEquals(List.of(new GenreResponse(5L, "Acción"), new GenreResponse(2L, "Terror")),
                service.getFavoriteGenres(USER));
    }

    /** Sin favoritos, una sola consulta. */
    @Test
    void elGetSinFavoritosNoCompruebaLaVisibilidad() {
        when(genreRepository.findFavoritesByUserId(USER)).thenReturn(List.of());

        assertEquals(List.of(), service.getFavoriteGenres(USER));
        verify(genreRepository, never()).findIdsWithVisibleTitles(any());
    }

    private static FavoriteGenresRequest request(Long... ids) {
        return new FavoriteGenresRequest(List.of(ids));
    }

    private static Genre genre(Long id, String name) {
        Genre genre = new Genre();
        genre.setId(id);
        genre.setName(name);
        return genre;
    }
}
