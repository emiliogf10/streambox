package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import com.emilio.streambox.dto.EpisodeRequest;
import com.emilio.streambox.entity.Episode;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.exception.EpisodeAlreadyExistsException;
import com.emilio.streambox.exception.EpisodeNotFoundException;
import com.emilio.streambox.exception.SeriesNotFoundException;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.SeriesRepository;

/**
 * Tests unitarios de {@link EpisodeService} con repositorios simulados (revisión
 * de QA, fase 5 de series).
 *
 * <p>
 * Los tests de integración ya prueban las carreras con los {@code SQLSTATE}
 * reales de H2 para la unicidad y la clave foránea. Aquí se fijan las ramas que
 * allí no se pueden provocar y el orden de las comprobaciones:
 * </p>
 * <ul>
 * <li>Una violación de integridad que <em>no</em> es de unicidad ni de clave
 * foránea (p. ej. un {@code CHECK}, 23514) se relanza tal cual: disfrazarla de
 * 409 "ya existe el episodio" ocultaría un fallo de programación.</li>
 * <li>Un {@link DuplicateKeyException} sin {@code SQLSTATE} también es 409.</li>
 * <li>Si la serie no existe no se consulta nada sobre episodios, y si la
 * posición está ocupada al editar no se toca la entidad ni se guarda.</li>
 * </ul>
 */
class EpisodeServiceTest {

    private static final Long SERIES_ID = 1L;
    private static final Long EPISODE_ID = 7L;

    private EpisodeRepository episodeRepository;
    private SeriesRepository seriesRepository;
    private EpisodeService service;

    @BeforeEach
    void setUp() {
        episodeRepository = mock(EpisodeRepository.class);
        seriesRepository = mock(SeriesRepository.class);
        service = new EpisodeService(episodeRepository, seriesRepository);
        when(seriesRepository.existsById(SERIES_ID)).thenReturn(true);
        when(seriesRepository.getReferenceById(SERIES_ID)).thenReturn(series());
    }

    /** Un CHECK u otra violación no prevista no se convierte en un 409 ni en un 404. */
    @Test
    void unaViolacionQueNoEsDeUnicidadNiDeClaveForaneaSeRelanzaTalCual() {
        DataIntegrityViolationException check = violation("23514");
        when(episodeRepository.saveAndFlush(any())).thenThrow(check);

        DataIntegrityViolationException thrown = assertThrows(DataIntegrityViolationException.class,
                () -> service.createEpisode(SERIES_ID, request(1, 1)));

        assertSame(check, thrown);
    }

    /** Sin {@code SQLSTATE} en la cadena de causas, el tipo {@link DuplicateKeyException} basta para el 409. */
    @Test
    void unDuplicateKeyExceptionSinSqlStateTambienEsConflicto() {
        when(episodeRepository.saveAndFlush(any())).thenThrow(new DuplicateKeyException("duplicado"));

        EpisodeAlreadyExistsException thrown = assertThrows(EpisodeAlreadyExistsException.class,
                () -> service.createEpisode(SERIES_ID, request(2, 5)));

        assertEquals("Ya existe el episodio 5 de la temporada 2", thrown.getMessage());
    }

    /** Clave foránea de PostgreSQL (23503) y de H2 (23506): la serie desapareció entre medias. */
    @ParameterizedTest
    @ValueSource(strings = { "23503", "23506" })
    void unaViolacionDeClaveForaneaEsSerieNoEncontrada(String sqlState) {
        when(episodeRepository.saveAndFlush(any())).thenThrow(violation(sqlState));

        SeriesNotFoundException thrown = assertThrows(SeriesNotFoundException.class,
                () -> service.createEpisode(SERIES_ID, request(1, 1)));

        assertEquals("Serie no encontrada", thrown.getMessage());
    }

    /** La unicidad (23505) también al editar, envuelta en varias causas. */
    @Test
    void unaViolacionDeUnicidadAlEditarEsConflicto() {
        Episode episode = episode(1, 2);
        when(episodeRepository.findByIdAndSeriesId(EPISODE_ID, SERIES_ID)).thenReturn(Optional.of(episode));
        when(episodeRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("envuelta",
                new RuntimeException("intermedia", new SQLException("duplicado", "23505"))));

        assertThrows(EpisodeAlreadyExistsException.class,
                () -> service.updateEpisode(SERIES_ID, EPISODE_ID, request(1, 1)));
    }

    /**
     * Con la posición ocupada al editar, la entidad no se modifica ni se guarda:
     * si se copiaran antes los datos, el vaciado automático de Hibernate podría
     * escribirlos fuera del {@code try} que traduce la violación.
     */
    @Test
    void siLaPosicionEstaOcupadaAlEditarNoSeTocaLaEntidadNiSeGuarda() {
        Episode episode = episode(1, 2);
        when(episodeRepository.findByIdAndSeriesId(EPISODE_ID, SERIES_ID)).thenReturn(Optional.of(episode));
        when(episodeRepository.existsBySeriesIdAndSeasonNumberAndEpisodeNumberAndIdNot(SERIES_ID, 1, 1, EPISODE_ID))
                .thenReturn(true);

        assertThrows(EpisodeAlreadyExistsException.class,
                () -> service.updateEpisode(SERIES_ID, EPISODE_ID, request(1, 1)));

        assertEquals(1, episode.getSeasonNumber());
        assertEquals(2, episode.getEpisodeNumber());
        assertEquals("Original", episode.getTitle());
        verify(episodeRepository, never()).saveAndFlush(any());
    }

    /** Serie inexistente: 404 de serie sin consultar episodios. */
    @Test
    void siLaSerieNoExisteNoSeConsultaNingunEpisodio() {
        when(seriesRepository.existsById(99L)).thenReturn(false);

        assertThrows(SeriesNotFoundException.class, () -> service.createEpisode(99L, request(1, 1)));
        assertThrows(SeriesNotFoundException.class, () -> service.updateEpisode(99L, EPISODE_ID, request(1, 1)));
        assertThrows(SeriesNotFoundException.class, () -> service.deleteEpisode(99L, EPISODE_ID));

        verifyNoInteractions(episodeRepository);
    }

    /**
     * El episodio se busca siempre <em>dentro</em> de la serie de la ruta
     * ({@code findByIdAndSeriesId}), nunca solo por su id.
     */
    @Test
    void elEpisodioSeBuscaDentroDeLaSerieDeLaRuta() {
        when(episodeRepository.findByIdAndSeriesId(EPISODE_ID, SERIES_ID)).thenReturn(Optional.empty());

        EpisodeNotFoundException thrown = assertThrows(EpisodeNotFoundException.class,
                () -> service.deleteEpisode(SERIES_ID, EPISODE_ID));

        assertEquals("Episodio no encontrado", thrown.getMessage());
        verify(episodeRepository, never()).findById(anyLong());
        verify(episodeRepository, never()).delete(any());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static DataIntegrityViolationException violation(String sqlState) {
        return new DataIntegrityViolationException("violación", new SQLException("violación", sqlState));
    }

    private static EpisodeRequest request(int season, int number) {
        return new EpisodeRequest(season, number, "Nuevo", null, 40, "https://example.com/v.mp4");
    }

    private static Series series() {
        Series series = new Series();
        series.setId(SERIES_ID);
        return series;
    }

    private static Episode episode(int season, int number) {
        Episode episode = new Episode();
        episode.setId(EPISODE_ID);
        episode.setSeries(series());
        episode.setSeasonNumber(season);
        episode.setEpisodeNumber(number);
        episode.setTitle("Original");
        episode.setDuration(45);
        episode.setVideoUrl("https://example.com/e.mp4");
        return episode;
    }
}
