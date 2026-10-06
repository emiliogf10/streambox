package com.emilio.streambox.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import com.emilio.streambox.dto.GenreRequest;
import com.emilio.streambox.dto.GenreResponse;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.exception.GenreAlreadyExistsException;
import com.emilio.streambox.exception.GenreInUseException;
import com.emilio.streambox.exception.InvalidParameterException;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;

/**
 * Tests unitarios de {@link GenreService} con repositorios simulados.
 *
 * <p>
 * Cubren las condiciones de carrera que una petición HTTP normal no puede
 * provocar: otra petición asigna el género a una película (o crea un género
 * con el mismo nombre) entre la comprobación del servicio y la escritura, y
 * es la restricción de la base de datos la que lo detecta.
 * </p>
 *
 * <p>
 * También fijan la normalización del nombre y que su longitud se valide sobre
 * el resultado normalizado, sin llegar a consultar los repositorios.
 * </p>
 */
class GenreServiceTest {

    private static final Long GENRE = 7L;

    private GenreRepository genreRepository;
    private MovieRepository movieRepository;
    private SeriesRepository seriesRepository;
    private GenreService service;
    private Genre drama;

    @BeforeEach
    void setUp() {
        genreRepository = mock(GenreRepository.class);
        movieRepository = mock(MovieRepository.class);
        seriesRepository = mock(SeriesRepository.class);
        service = new GenreService(genreRepository, movieRepository, seriesRepository);

        drama = new Genre();
        drama.setId(GENRE);
        drama.setName("Drama");
        when(genreRepository.findById(GENRE)).thenReturn(Optional.of(drama));
    }

    @Test
    void siAlguienAsignaElGeneroEntreElRecuentoYElBorradoDa409EnUso() {
        // Los recuentos dijeron "0 películas y 0 series", pero al hacer flush
        // la clave foránea de movie_genres (o de series_genres) rechaza el DELETE.
        when(movieRepository.countByGenres_Id(GENRE)).thenReturn(0L);
        when(seriesRepository.countByGenres_Id(GENRE)).thenReturn(0L);
        doThrow(violation("23503")).when(genreRepository).flush();

        GenreInUseException thrown = assertThrows(GenreInUseException.class,
                () -> service.deleteGenre(GENRE));

        assertEquals("No se puede eliminar el género \"Drama\": alguna película o serie lo tiene asignado. "
                + "Quítalo de esas películas o series antes de borrarlo.", thrown.getMessage());
    }

    @Test
    void laCarreraEnElBorradoSeTraduceAunqueLaViolacionNoTraigaSqlState() {
        // Un DELETE sobre genres solo puede violar una clave foránea, así que
        // no hace falta mirar el SQLSTATE para saber qué ha pasado.
        when(movieRepository.countByGenres_Id(GENRE)).thenReturn(0L);
        doThrow(new DataIntegrityViolationException("sin causa"))
                .when(genreRepository).delete(drama);

        assertThrows(GenreInUseException.class, () -> service.deleteGenre(GENRE));
    }

    @Test
    void unGeneroEnUsoNoLlegaABorrarse() {
        when(movieRepository.countByGenres_Id(GENRE)).thenReturn(2L);

        GenreInUseException thrown = assertThrows(GenreInUseException.class,
                () -> service.deleteGenre(GENRE));

        assertEquals("No se puede eliminar el género \"Drama\": lo usan 2 películas. "
                + "Quítalo de esas películas antes de borrarlo.", thrown.getMessage());
        verify(genreRepository, never()).delete(any());
    }

    /**
     * Un género que solo usan series tampoco se borra: antes de las series el
     * servicio solo contaba películas, y con 0 películas intentaba el
     * {@code DELETE} (que fallaba por la clave foránea de {@code series_genres}
     * con un mensaje sin cifra).
     */
    @Test
    void unGeneroQueSoloUsanSeriesNoLlegaABorrarse() {
        when(movieRepository.countByGenres_Id(GENRE)).thenReturn(0L);
        when(seriesRepository.countByGenres_Id(GENRE)).thenReturn(2L);

        GenreInUseException thrown = assertThrows(GenreInUseException.class,
                () -> service.deleteGenre(GENRE));

        assertEquals("No se puede eliminar el género \"Drama\": lo usan 2 series. "
                + "Quítalo de esas series antes de borrarlo.", thrown.getMessage());
        verify(genreRepository, never()).delete(any());
    }

    /**
     * Mensaje del 409 según los recuentos: singular o plural en cada parte,
     * la parte a 0 se omite y el verbo va en singular solo si en total es
     * una única película o serie.
     */
    @ParameterizedTest
    @MethodSource("recuentosYMensajes")
    void elMensajeDeGeneroEnUsoConcuerdaConLosRecuentos(long movies, long series, String usage) {
        when(movieRepository.countByGenres_Id(GENRE)).thenReturn(movies);
        when(seriesRepository.countByGenres_Id(GENRE)).thenReturn(series);

        GenreInUseException thrown = assertThrows(GenreInUseException.class,
                () -> service.deleteGenre(GENRE));

        assertEquals("No se puede eliminar el género \"Drama\": " + usage + " antes de borrarlo.",
                thrown.getMessage());
    }

    static Stream<Arguments> recuentosYMensajes() {
        return Stream.of(
                Arguments.of(1L, 0L,
                        "lo usa 1 película. Quítalo de esa película"),
                Arguments.of(3L, 0L,
                        "lo usan 3 películas. Quítalo de esas películas"),
                Arguments.of(0L, 1L,
                        "lo usa 1 serie. Quítalo de esa serie"),
                Arguments.of(3L, 2L,
                        "lo usan 3 películas y 2 series. Quítalo de esas películas y de esas series"),
                Arguments.of(1L, 1L,
                        "lo usan 1 película y 1 serie. Quítalo de esa película y de esa serie"),
                Arguments.of(2L, 1L,
                        "lo usan 2 películas y 1 serie. Quítalo de esas películas y de esa serie"));
    }

    @Test
    void dosAltasSimultaneasConElMismoNombreDan409YNoUnErrorDeIntegridadGenerico() {
        when(genreRepository.existsByNameIgnoreCase("Drama")).thenReturn(false);
        when(genreRepository.saveAndFlush(any())).thenThrow(violation("23505"));

        assertThrows(GenreAlreadyExistsException.class,
                () -> service.createGenre(new GenreRequest("drama")));
    }

    @Test
    void unRenombradoQueChocaConLaUnicidadPorUnaCarreraDa409() {
        when(genreRepository.existsByNameIgnoreCaseAndIdNot("Comedia", GENRE)).thenReturn(false);
        when(genreRepository.saveAndFlush(any())).thenThrow(violation("23505"));

        assertThrows(GenreAlreadyExistsException.class,
                () -> service.updateGenre(GENRE, new GenreRequest("comedia")));
    }

    @Test
    void otraViolacionAlGuardarNoSeDisfrazaDeNombreRepetido() {
        when(genreRepository.existsByNameIgnoreCase("Drama")).thenReturn(false);
        DataIntegrityViolationException tooLong = violation("22001");
        when(genreRepository.saveAndFlush(any())).thenThrow(tooLong);

        DataIntegrityViolationException thrown = assertThrows(DataIntegrityViolationException.class,
                () -> service.createGenre(new GenreRequest("drama")));

        assertSame(tooLong, thrown);
    }

    // ------------------------------------------------------------------
    // Normalización y validación del nombre ya normalizado
    // ------------------------------------------------------------------

    /**
     * Nombres que pasan {@code @NotBlank}/{@code @Size} tal como llegan pero
     * que, normalizados, no miden entre 2 y 50: recortados a 1 o 0 caracteres
     * (espacios normales, espacios duros U+00A0, tabuladores, espacio de ancho
     * cero U+200B) o alargados a 99 por la «İ» (U+0130). El servicio los
     * rechaza como error de validación de {@code name} antes de tocar ningún
     * repositorio, así que no pueden acabar en 409 ni en la base de datos.
     */
    @ParameterizedTest
    @MethodSource("nombresInvalidosTrasNormalizar")
    void unNombreInvalidoTrasNormalizarSeRechazaSinLlegarAlRepositorio(String name) {
        InvalidParameterException create = assertThrows(InvalidParameterException.class,
                () -> service.createGenre(new GenreRequest(name)));
        InvalidParameterException update = assertThrows(InvalidParameterException.class,
                () -> service.updateGenre(GENRE, new GenreRequest(name)));

        for (InvalidParameterException thrown : List.of(create, update)) {
            assertEquals("name", thrown.getParameter());
            assertEquals(GenreRequest.NAME_SIZE_MESSAGE, thrown.getMessage());
        }
        verifyNoInteractions(genreRepository, movieRepository, seriesRepository);
    }

    static Stream<String> nombresInvalidosTrasNormalizar() {
        return Stream.of(
                " a",
                "a ",
                "  x  ",
                "  ",
                "\t\n",
                "​x​",
                "A" + "İ".repeat(49));
    }

    /**
     * El nombre se valida antes de buscar el género: con un id inexistente y un
     * nombre inválido la respuesta es el 400 de validación, no el 404.
     */
    @Test
    void enLaEdicionElNombreInvalidoSeDetectaAntesQueElIdInexistente() {
        assertThrows(InvalidParameterException.class,
                () -> service.updateGenre(999L, new GenreRequest(" a")));

        verify(genreRepository, never()).findById(any());
    }

    /**
     * Lo invisible de los extremos se quita (también espacios duros y de ancho
     * cero) y los espacios interiores repetidos o especiales se colapsan en uno
     * normal: el resultado es el que se busca como duplicado y el que se guarda.
     */
    @Test
    void elNombreSeRecortaYColapsaSusEspaciosAntesDeBuscarDuplicadosYGuardar() {
        when(genreRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        GenreResponse created = service.createGenre(
                new GenreRequest("  ciencia  \t FICCIÓN​ "));

        assertEquals("Ciencia ficción", created.name());
        verify(genreRepository).existsByNameIgnoreCase("Ciencia ficción");
    }

    /**
     * Los límites cuentan sobre el texto normalizado: 2 y 50 caracteres son
     * válidos, aunque el texto recibido midiera más por los espacios.
     */
    @ParameterizedTest
    @ValueSource(ints = { 2, 50 })
    void losLimitesDeLongitudSeAplicanAlNombreNormalizado(int length) {
        when(genreRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        String name = "a".repeat(length);

        GenreResponse created = service.createGenre(new GenreRequest("   " + name + "  "));

        assertEquals(length, created.name().length());
    }

    /**
     * Reproduce la forma real de la excepción: Spring envuelve a Hibernate y este
     * al {@link SQLException} del driver, que es quien lleva el SQLSTATE.
     */
    private static DataIntegrityViolationException violation(String sqlState) {
        SQLException driver = new SQLException("violación de integridad", sqlState);
        return new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("hibernate", driver));
    }
}
