package com.emilio.streambox.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Tests unitarios de las reglas de {@link SeriesRequest} y {@link EpisodeRequest}
 * tal como están anotadas (sin Spring).
 *
 * <p>
 * Se centran en lo que tiene lógica propia: la regla de clase
 * {@code @ValidSeriesYears} (año de finalización no anterior al de estreno),
 * que debe colgar el error de {@code endYear} y dar <b>un único</b> error por
 * campo aunque el valor incumpla varias reglas. Como en
 * {@code MovieRequestValidationTest}, los mensajes se escriben literalmente
 * para que cambiar el contrato por descuido rompa el test.
 * </p>
 */
class SeriesRequestValidationTest {

    private static final String END_BEFORE_RELEASE =
            "El año de finalización no puede ser anterior al año de estreno";
    private static final String END_RANGE = "El año de finalización debe estar entre 1888 y 2100";
    private static final String RELEASE_RANGE = "El año de estreno debe estar entre 1888 y 2100";

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void unaSerieValidaEnEmisionNoTieneErrores() {
        assertEquals(Map.of(), errors(series(2015, null)));
    }

    @ParameterizedTest(name = "estreno {0}, fin {1}")
    @CsvSource({ "2015, 2015", "2015, 2016", "1888, 2100" })
    void unAnioDeFinalizacionIgualOPosteriorEsValido(int releaseYear, int endYear) {
        assertEquals(Map.of(), errors(series(releaseYear, endYear)));
    }

    @Test
    void unAnioDeFinalizacionAnteriorDaUnErrorEnEndYear() {
        Set<ConstraintViolation<SeriesRequest>> violations = validator.validate(series(2015, 2014));

        assertEquals(1, violations.size());
        ConstraintViolation<SeriesRequest> violation = violations.iterator().next();
        assertEquals("endYear", violation.getPropertyPath().toString());
        assertEquals(END_BEFORE_RELEASE, violation.getMessage());
    }

    /**
     * Si el año de finalización está fuera de rango, solo se informa del rango
     * (aunque además sea anterior al de estreno): un único error por campo.
     */
    @ParameterizedTest(name = "estreno {0}, fin {1}")
    @CsvSource({ "2015, 1500", "2015, 2101" })
    void unAnioDeFinalizacionFueraDeRangoSoloDaElErrorDeRango(int releaseYear, int endYear) {
        assertEquals(Map.of("endYear", END_RANGE), errors(series(releaseYear, endYear)));
    }

    /** Con el estreno fuera de rango no se compara: solo se informa del estreno. */
    @Test
    void conElEstrenoFueraDeRangoNoSeComparanLosAnios() {
        assertEquals(Map.of("releaseYear", RELEASE_RANGE), errors(series(2101, 2050)));
    }

    @Test
    void sinAnioDeEstrenoSoloSeInformaDeQueEsObligatorio() {
        assertEquals(Map.of("releaseYear", "El año de estreno es obligatorio"), errors(series(null, 2000)));
    }

    @Test
    void unIdDeGeneroNuloSeRechaza() {
        Set<Long> genreIds = new HashSet<>();
        genreIds.add(null);
        SeriesRequest request = new SeriesRequest("T", "D", 2000, null, "https://e.com/i.jpg", genreIds);

        assertTrue(errors(request).containsValue("Los identificadores de género no pueden ser nulos"));
    }

    @Test
    void veinteGenerosEsElMaximoAdmitido() {
        assertEquals(Map.of(), errors(withGenres(20)));
    }

    @Test
    void veintiunGenerosDaElMensajeDelTope() {
        assertEquals(Map.of("genreIds", "Una serie puede tener como máximo 20 géneros"), errors(withGenres(21)));
    }

    /** El tope no tapa a {@code @NotEmpty}: una lista vacía sigue dando su mensaje. */
    @Test
    void sinGenerosSoloSeInformaDeQueHayQueIndicarAlguno() {
        assertEquals(Map.of("genreIds", "Indica al menos un género"), errors(withGenres(0)));
    }

    @Test
    void unEpisodioConDescripcionNulaEsValido() {
        EpisodeRequest request = new EpisodeRequest(1, 1, "Piloto", null, 45, "https://e.com/v.mp4");

        assertTrue(validator.validate(request).isEmpty());
    }

    @ParameterizedTest(name = "temporada {0}, episodio {1}, duración {2}")
    @CsvSource({ "1, 1, 1", "100, 1000, 600" })
    void losLimitesDelEpisodioSonInclusivos(int season, int number, int duration) {
        EpisodeRequest request = new EpisodeRequest(season, number, "Piloto", null, duration, "https://e.com/v.mp4");

        assertTrue(validator.validate(request).isEmpty());
    }

    private static SeriesRequest series(Integer releaseYear, Integer endYear) {
        return new SeriesRequest("Fargo", "Sinopsis", releaseYear, endYear, "https://e.com/i.jpg", Set.of(1L));
    }

    /** Serie válida con {@code count} ids de género distintos (1..count). */
    private static SeriesRequest withGenres(int count) {
        Set<Long> genreIds = LongStream.rangeClosed(1, count).boxed().collect(Collectors.toSet());
        return new SeriesRequest("Fargo", "Sinopsis", 2014, null, "https://e.com/i.jpg", genreIds);
    }

    private static Map<String, String> errors(SeriesRequest request) {
        return validator.validate(request).stream()
                .collect(Collectors.toMap(v -> v.getPropertyPath().toString(), ConstraintViolation::getMessage));
    }
}
