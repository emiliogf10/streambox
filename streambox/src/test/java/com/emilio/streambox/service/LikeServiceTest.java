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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import com.emilio.streambox.exception.MovieNotFoundException;
import com.emilio.streambox.exception.SeriesNotFoundException;
import com.emilio.streambox.exception.UserNotFoundException;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;

/**
 * Tests unitarios de {@link LikeService} con los repositorios simulados.
 *
 * <p>
 * Fijan lo que con una base de datos real es difícil de provocar: la
 * traducción de cada violación de integridad del {@code INSERT} (la del usuario
 * borrado durante la petición, la del título y una inesperada), que la fecha
 * sale del {@link Clock} y que sin título visible no se intenta el
 * {@code INSERT}. La carrera real contra PostgreSQL está en
 * {@code PostgresIntegrityRaceTranslationQaIntegrationTest}.
 * </p>
 */
class LikeServiceTest {

    private static final Long USER = 7L;
    private static final Long TITLE = 42L;
    private static final Instant NOW = Instant.parse("2026-10-17T10:15:30Z");

    private MovieRepository movieRepository;
    private SeriesRepository seriesRepository;
    private EpisodeRepository episodeRepository;
    private LikeService service;

    @BeforeEach
    void setUp() {
        movieRepository = mock(MovieRepository.class);
        seriesRepository = mock(SeriesRepository.class);
        episodeRepository = mock(EpisodeRepository.class);
        service = new LikeService(movieRepository, seriesRepository, episodeRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // --- Películas ---

    @Test
    void darMeGustaAUnaPeliculaGuardaLaHoraDelReloj() {
        when(movieRepository.existsById(TITLE)).thenReturn(true);

        service.likeMovie(USER, TITLE);

        verify(movieRepository).addLike(USER, TITLE, NOW);
    }

    /** Que ya le gustara (0 filas) no es un error: el servicio no mira el resultado. */
    @Test
    void repetirElMeGustaDeUnaPeliculaNoEsUnError() {
        when(movieRepository.existsById(TITLE)).thenReturn(true);
        when(movieRepository.addLike(USER, TITLE, NOW)).thenReturn(0);

        service.likeMovie(USER, TITLE);

        verify(movieRepository).addLike(USER, TITLE, NOW);
    }

    @Test
    void unaPeliculaInexistenteDa404SinIntentarElInsert() {
        MovieNotFoundException error = assertThrows(MovieNotFoundException.class,
                () -> service.likeMovie(USER, TITLE));

        assertEquals("Película no encontrada", error.getMessage());
        verify(movieRepository, never()).addLike(anyLong(), anyLong(), any());
    }

    /** La película se borra entre la comprobación y el {@code INSERT} (PostgreSQL y H2). */
    @ParameterizedTest
    @ValueSource(strings = { "23503", "23506" })
    void laClaveForaneaDeLaPeliculaSeTraduceA404(String sqlState) {
        when(movieRepository.existsById(TITLE)).thenReturn(true);
        when(movieRepository.addLike(USER, TITLE, NOW))
                .thenThrow(violation(sqlState, "fk_liked_movies_movie"));

        MovieNotFoundException error = assertThrows(MovieNotFoundException.class,
                () -> service.likeMovie(USER, TITLE));
        assertEquals("Película no encontrada", error.getMessage());
    }

    /** Sin nombre de restricción (no se sabe cuál): se asume la del título, el caso esperable. */
    @Test
    void unaClaveForaneaSinNombreSeTraduceAlNotFoundDelTitulo() {
        when(movieRepository.existsById(TITLE)).thenReturn(true);
        when(movieRepository.addLike(USER, TITLE, NOW)).thenThrow(violation("23503", null));

        assertThrows(MovieNotFoundException.class, () -> service.likeMovie(USER, TITLE));
    }

    /** La cuenta se borró durante la petición: decir «Película no encontrada» sería falso. */
    @ParameterizedTest
    @ValueSource(strings = { "23503", "23506" })
    void laClaveForaneaDelUsuarioSeTraduceAUsuarioNoEncontrado(String sqlState) {
        when(movieRepository.existsById(TITLE)).thenReturn(true);
        when(movieRepository.addLike(USER, TITLE, NOW))
                .thenThrow(violation(sqlState, "fk_liked_movies_user"));

        UserNotFoundException error = assertThrows(UserNotFoundException.class,
                () -> service.likeMovie(USER, TITLE));
        assertEquals("Usuario no encontrado", error.getMessage());
    }

    /** Una violación inesperada (aquí un CHECK) no se disfraza de 404: sale tal cual (500). */
    @Test
    void otraViolacionSeRelanzaTalCual() {
        DataIntegrityViolationException unexpected = violation("23514", "otra_restriccion");
        when(movieRepository.existsById(TITLE)).thenReturn(true);
        when(movieRepository.addLike(USER, TITLE, NOW)).thenThrow(unexpected);

        assertSame(unexpected, assertThrows(DataIntegrityViolationException.class,
                () -> service.likeMovie(USER, TITLE)));
    }

    /** Quitar no comprueba nada antes ni falla si no había fila. */
    @Test
    void quitarElMeGustaDeUnaPeliculaEsUnSoloDeleteSinComprobaciones() {
        when(movieRepository.removeLike(USER, TITLE)).thenReturn(0);

        service.unlikeMovie(USER, TITLE);

        verify(movieRepository).removeLike(USER, TITLE);
        verify(movieRepository, never()).existsById(anyLong());
    }

    // --- Series ---

    @Test
    void darMeGustaAUnaSerieVisibleGuardaLaHoraDelReloj() {
        when(episodeRepository.existsBySeriesId(TITLE)).thenReturn(true);

        service.likeSeries(USER, TITLE);

        verify(seriesRepository).addLike(USER, TITLE, NOW);
    }

    /** Sin episodios = inexistente: mismo mensaje que el detalle, sin {@code INSERT}. */
    @Test
    void unaSerieSinEpisodiosOInexistenteDa404SinIntentarElInsert() {
        SeriesNotFoundException error = assertThrows(SeriesNotFoundException.class,
                () -> service.likeSeries(USER, TITLE));

        assertEquals(SeriesService.SERIES_NOT_FOUND, error.getMessage());
        verify(seriesRepository, never()).addLike(anyLong(), anyLong(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = { "23503", "23506" })
    void laClaveForaneaDeLaSerieSeTraduceA404(String sqlState) {
        when(episodeRepository.existsBySeriesId(TITLE)).thenReturn(true);
        when(seriesRepository.addLike(USER, TITLE, NOW))
                .thenThrow(violation(sqlState, "fk_liked_series_series"));

        assertThrows(SeriesNotFoundException.class, () -> service.likeSeries(USER, TITLE));
    }

    @Test
    void laClaveForaneaDelUsuarioEnSeriesSeTraduceAUsuarioNoEncontrado() {
        when(episodeRepository.existsBySeriesId(TITLE)).thenReturn(true);
        when(seriesRepository.addLike(USER, TITLE, NOW))
                .thenThrow(violation("23503", "fk_liked_series_user"));

        assertThrows(UserNotFoundException.class, () -> service.likeSeries(USER, TITLE));
    }

    /** Quitar el de una serie no consulta los episodios: la respuesta no revela si está oculta. */
    @Test
    void quitarElMeGustaDeUnaSerieNoConsultaSiEsVisible() {
        service.unlikeSeries(USER, TITLE);

        verify(seriesRepository).removeLike(USER, TITLE);
        verifyNoInteractions(episodeRepository);
    }

    /**
     * Fabrica la violación como la entrega Spring: envuelve la de Hibernate (con
     * el nombre de la restricción, si se conoce), que envuelve la del driver
     * (con el SQLSTATE).
     */
    private static DataIntegrityViolationException violation(String sqlState, String constraintName) {
        SQLException driver = new SQLException("violación de integridad", sqlState);
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", driver, constraintName));
    }
}
