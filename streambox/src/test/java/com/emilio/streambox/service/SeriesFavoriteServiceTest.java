package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.emilio.streambox.exception.SeriesNotFoundException;
import com.emilio.streambox.exception.SeriesNotInFavoritesException;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;

/**
 * Tests unitarios de {@link SeriesFavoriteService#removeFavorite} con
 * repositorios simulados.
 *
 * <p>
 * Fijan el orden de las consultas, que es lo que evita revelar qué series
 * ocultas existen: primero el {@code DELETE} y, solo si no borró nada, la
 * pregunta por la <em>visibilidad</em> ({@code existsBySeriesId}), nunca por
 * la mera existencia ({@code existsById}). La respuesta HTTP completa la
 * comprueba {@code SeriesObjectLevelAuthorizationIntegrationTest}.
 * </p>
 */
class SeriesFavoriteServiceTest {

    private static final Long USER = 1L;
    private static final Long SERIES = 10L;

    private SeriesRepository seriesRepository;
    private EpisodeRepository episodeRepository;
    private SeriesFavoriteService service;

    @BeforeEach
    void setUp() {
        seriesRepository = mock(SeriesRepository.class);
        episodeRepository = mock(EpisodeRepository.class);
        service = new SeriesFavoriteService(seriesRepository, episodeRepository, mock(UserRepository.class));
    }

    /** El caso normal es una sola consulta: el {@code DELETE}. */
    @Test
    void siEstabaEnLaListaSeQuitaConUnaSolaConsulta() {
        when(seriesRepository.removeFavorite(USER, SERIES)).thenReturn(1);

        service.removeFavorite(USER, SERIES);

        verify(seriesRepository).removeFavorite(USER, SERIES);
        verifyNoMoreInteractions(seriesRepository);
        verifyNoInteractions(episodeRepository);
    }

    @Test
    void siNoEstabaYLaSerieEsVisibleDaNoEstaEnLaLista() {
        when(seriesRepository.removeFavorite(USER, SERIES)).thenReturn(0);
        when(episodeRepository.existsBySeriesId(SERIES)).thenReturn(true);

        SeriesNotInFavoritesException error =
                assertThrows(SeriesNotInFavoritesException.class, () -> service.removeFavorite(USER, SERIES));
        assertEquals("La serie no está incluida en tu lista de favoritos", error.getMessage());
    }

    /**
     * Oculta o inexistente: las dos dan "Serie no encontrada", y no se
     * pregunta nunca a {@code existsById}, que distinguiría una de otra.
     */
    @Test
    void siNoEstabaYLaSerieNoEsVisibleDaSerieNoEncontradaSinMirarSiExiste() {
        when(seriesRepository.removeFavorite(USER, SERIES)).thenReturn(0);
        when(episodeRepository.existsBySeriesId(SERIES)).thenReturn(false);

        SeriesNotFoundException error =
                assertThrows(SeriesNotFoundException.class, () -> service.removeFavorite(USER, SERIES));
        assertEquals("Serie no encontrada", error.getMessage());
        verify(seriesRepository).removeFavorite(USER, SERIES);
        verifyNoMoreInteractions(seriesRepository);
    }
}
