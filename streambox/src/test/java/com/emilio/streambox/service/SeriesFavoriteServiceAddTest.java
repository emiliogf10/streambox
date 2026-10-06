package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import com.emilio.streambox.exception.SeriesAlreadyInFavoritesException;
import com.emilio.streambox.exception.SeriesNotFoundException;
import com.emilio.streambox.exception.UserNotFoundException;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;

/**
 * Tests unitarios de {@link SeriesFavoriteService#addFavorite} con
 * repositorios simulados (revisión de QA, fase 5 de series). Complementan a
 * {@link SeriesFavoriteServiceTest}, que cubre el borrado.
 *
 * <p>
 * Fijan el orden de las comprobaciones (primero la visibilidad, que no revela
 * si la serie existe; después el usuario y el duplicado; por último el
 * {@code INSERT}) y la traducción de las violaciones que el test de
 * integración no puede provocar: un {@code CHECK} u otra violación inesperada
 * se relanza tal cual, sin disfrazarla de 409.
 * </p>
 */
class SeriesFavoriteServiceAddTest {

    private static final Long USER = 1L;
    private static final Long SERIES = 10L;

    private SeriesRepository seriesRepository;
    private EpisodeRepository episodeRepository;
    private UserRepository userRepository;
    private SeriesFavoriteService service;

    @BeforeEach
    void setUp() {
        seriesRepository = mock(SeriesRepository.class);
        episodeRepository = mock(EpisodeRepository.class);
        userRepository = mock(UserRepository.class);
        service = new SeriesFavoriteService(seriesRepository, episodeRepository, userRepository);
        when(episodeRepository.existsBySeriesId(SERIES)).thenReturn(true);
        when(userRepository.existsById(USER)).thenReturn(true);
    }

    /** Una serie oculta o inexistente da 404 antes de mirar nada más (ni existencia, ni lista). */
    @Test
    void unaSerieNoVisibleDa404SinConsultarNadaMas() {
        when(episodeRepository.existsBySeriesId(SERIES)).thenReturn(false);

        assertThrows(SeriesNotFoundException.class, () -> service.addFavorite(USER, SERIES));

        verifyNoInteractions(seriesRepository);
        verifyNoInteractions(userRepository);
    }

    @Test
    void unUsuarioInexistenteNoLlegaAlInsert() {
        when(userRepository.existsById(USER)).thenReturn(false);

        assertThrows(UserNotFoundException.class, () -> service.addFavorite(USER, SERIES));

        verify(seriesRepository, never()).addFavorite(anyLong(), anyLong());
    }

    /** Si ya estaba, 409 sin intentar el {@code INSERT} (en PostgreSQL abortaría la transacción). */
    @Test
    void siYaEstabaNoSeIntentaElInsert() {
        when(seriesRepository.isFavorite(USER, SERIES)).thenReturn(true);

        assertThrows(SeriesAlreadyInFavoritesException.class, () -> service.addFavorite(USER, SERIES));

        verify(seriesRepository, never()).addFavorite(anyLong(), anyLong());
    }

    @Test
    void unDuplicateKeyExceptionSinSqlStateEsConflicto() {
        doThrow(new DuplicateKeyException("duplicado")).when(seriesRepository).addFavorite(USER, SERIES);

        assertThrows(SeriesAlreadyInFavoritesException.class, () -> service.addFavorite(USER, SERIES));
    }

    /** Un {@code CHECK} (23514) u otra violación no prevista se relanza tal cual: acabará en 500 y en el log. */
    @Test
    void unaViolacionInesperadaSeRelanzaTalCual() {
        DataIntegrityViolationException check =
                new DataIntegrityViolationException("check", new SQLException("check", "23514"));
        doThrow(check).when(seriesRepository).addFavorite(USER, SERIES);

        DataIntegrityViolationException thrown =
                assertThrows(DataIntegrityViolationException.class, () -> service.addFavorite(USER, SERIES));

        assertSame(check, thrown);
    }
}
