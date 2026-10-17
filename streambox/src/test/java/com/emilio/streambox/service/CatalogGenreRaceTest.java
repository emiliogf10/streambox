package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.emilio.streambox.dto.MovieRequest;
import com.emilio.streambox.dto.SeriesRequest;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.exception.GenreNotFoundException;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;

/**
 * Carrera entre guardar una película o una serie y borrar uno de sus géneros
 * ({@code writeTranslatingGenreRace} de {@code MovieService} y de
 * {@code SeriesService}).
 *
 * <p>
 * Los servicios comprueban que los géneros existen, pero otro administrador
 * puede borrar uno antes del {@code INSERT} en {@code movie_genres} o
 * {@code series_genres}, y salta su clave foránea. Antes esa violación llegaba
 * sin traducir a {@code GlobalExceptionHandler} (un 409 genérico; desde que
 * allí responde un 500, sería un error del servidor): debe ser el mismo 404 de
 * género que da la comprobación previa. Sin el arreglo, estos tests reciben la
 * {@link DataIntegrityViolationException} en lugar de
 * {@link GenreNotFoundException}. Que los motores reales entregan el nombre de
 * la restricción así lo comprueba {@code IntegrityViolationTranslationIntegrationTest}.
 * </p>
 */
class CatalogGenreRaceTest {

    private static final long GENRE_ID = 7L;

    private MovieRepository movieRepository;
    private SeriesRepository seriesRepository;
    private MovieService movieService;
    private SeriesService seriesService;

    @BeforeEach
    void setUp() {
        movieRepository = mock(MovieRepository.class);
        seriesRepository = mock(SeriesRepository.class);
        GenreRepository genreRepository = mock(GenreRepository.class);
        movieService = new MovieService(movieRepository, genreRepository);
        seriesService = new SeriesService(seriesRepository, mock(EpisodeRepository.class), genreRepository);
        // save() devuelve la entidad, como el repositorio real; el fallo llega en el flush.
        when(movieRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(seriesRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Genre genre = new Genre();
        genre.setId(GENRE_ID);
        genre.setName("Drama");
        when(genreRepository.findAllById(Set.of(GENRE_ID))).thenReturn(List.of(genre));
    }

    @Test
    void alCrearUnaPeliculaElGeneroBorradoEnMedioDa404DeGenero() {
        doThrow(foreignKey("fk_movie_genres_genre")).when(movieRepository).flush();

        GenreNotFoundException error = assertThrows(GenreNotFoundException.class,
                () -> movieService.createMovie(movieRequest()));

        assertEquals("Alguno de los géneros indicados ya no existe", error.getMessage());
    }

    @Test
    void alEditarUnaPeliculaElGeneroBorradoEnMedioDa404DeGenero() {
        when(movieRepository.findById(1L)).thenReturn(Optional.of(new Movie()));
        // Formato de H2: SQLSTATE 23506 y el nombre en mayúsculas con texto detrás.
        doThrow(violation("23506", "FK_MOVIE_GENRES_GENRE: PUBLIC.MOVIE_GENRES FOREIGN KEY(GENRE_ID)"))
                .when(movieRepository).flush();

        assertThrows(GenreNotFoundException.class, () -> movieService.updateMovie(1L, movieRequest()));
    }

    /**
     * Otra clave foránea (la película borrada mientras se editaba) u otra
     * violación no es un género borrado: se relanza tal cual (500 y log).
     */
    @Test
    void otraViolacionAlGuardarUnaPeliculaSeRelanzaTalCual() {
        DataIntegrityViolationException otherForeignKey = foreignKey("fk_movie_genres_movie");
        doThrow(otherForeignKey).when(movieRepository).flush();

        assertSame(otherForeignKey, assertThrows(DataIntegrityViolationException.class,
                () -> movieService.createMovie(movieRequest())));

        DataIntegrityViolationException check = violation("23514", "ck_movies_duration");
        doThrow(check).when(movieRepository).flush();

        assertSame(check, assertThrows(DataIntegrityViolationException.class,
                () -> movieService.createMovie(movieRequest())));
    }

    @Test
    void alCrearOEditarUnaSerieElGeneroBorradoEnMedioDa404DeGenero() {
        doThrow(foreignKey("fk_series_genres_genre")).when(seriesRepository).flush();
        when(seriesRepository.findById(2L)).thenReturn(Optional.of(new Series()));

        assertEquals("Alguno de los géneros indicados ya no existe",
                assertThrows(GenreNotFoundException.class,
                        () -> seriesService.createSeries(seriesRequest())).getMessage());
        assertThrows(GenreNotFoundException.class, () -> seriesService.updateSeries(2L, seriesRequest()));
    }

    @Test
    void otraViolacionAlGuardarUnaSerieSeRelanzaTalCual() {
        DataIntegrityViolationException otherForeignKey = foreignKey("fk_series_genres_series");
        doThrow(otherForeignKey).when(seriesRepository).flush();

        assertSame(otherForeignKey, assertThrows(DataIntegrityViolationException.class,
                () -> seriesService.createSeries(seriesRequest())));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static MovieRequest movieRequest() {
        return new MovieRequest("Duna", "Sinopsis", 155, 2021, "https://cdn.example.com/duna.jpg",
                null, null, Set.of(GENRE_ID));
    }

    private static SeriesRequest seriesRequest() {
        return new SeriesRequest("Fundación", "Sinopsis", 2021, null, "https://cdn.example.com/f.jpg",
                null, Set.of(GENRE_ID));
    }

    /** Clave foránea con el formato de PostgreSQL ({@code 23503} y el nombre limpio). */
    private static DataIntegrityViolationException foreignKey(String constraintName) {
        return violation("23503", constraintName);
    }

    private static DataIntegrityViolationException violation(String sqlState, String constraintName) {
        SQLException driver = new SQLException("violación de integridad", sqlState);
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", driver, constraintName));
    }
}
