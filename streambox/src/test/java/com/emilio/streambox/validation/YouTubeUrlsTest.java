package com.emilio.streambox.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Tests unitarios de {@link YouTubeUrls} (análisis y normalización del enlace
 * del tráiler) y de {@link YouTubeUrl} / {@link YouTubeUrlValidator}, sin Spring.
 *
 * <p>
 * Los casos salen de {@link YouTubeUrlSamples}, el mismo catálogo que usan los
 * tests de la API y de PostgreSQL. Además de "acepta / rechaza", comprueban la
 * propiedad que evita un 500: lo normalizado cumple exactamente la forma que
 * exige el {@code CHECK} de la base de datos ({@code https://www.youtube.com/watch?v=}
 * + 11 caracteres de {@code [A-Za-z0-9_-]}, 43 en total). La anotación se
 * prueba con el {@link Validator} real sobre registros anotados, como en
 * {@code HttpsUrlValidatorTest}.
 * </p>
 */
class YouTubeUrlsTest {

    /** La misma regla que los {@code CHECK} {@code ck_movies_trailer_url} y {@code ck_series_trailer_url}. */
    private static final String CHECK_REGEX = "https://www\\.youtube\\.com/watch\\?v=[A-Za-z0-9_-]{11}";

    private static final String MESSAGE = "El tráiler debe ser un enlace de un vídeo de YouTube";

    private static ValidatorFactory factory;
    private static Validator validator;

    /** Como {@code MovieRequest.trailerUrl}, con su máximo de 500. */
    record TrailerField(@YouTubeUrl(maxLength = 500) String url) {
    }

    /** Con un máximo pequeño, para probar el reparto de errores con {@code @Size}. */
    record ShortTrailerField(@YouTubeUrl(maxLength = 50) String url) {
    }

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    static Stream<Arguments> accepted() {
        return YouTubeUrlSamples.ACCEPTED.entrySet().stream()
                .map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    static Stream<String> rejected() {
        return YouTubeUrlSamples.REJECTED.stream();
    }

    // ------------------------------------------------------------------
    // Formas aceptadas
    // ------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("accepted")
    void extraeElIdDeCadaFormaAceptada(String url, String id) {
        assertEquals(Optional.of(id), YouTubeUrls.videoId(url));
        assertTrue(YouTubeUrls.isValid(url));
        assertEquals(List.of(), messages(new TrailerField(url)));
    }

    /** Lo normalizado siempre cumple el {@code CHECK}: nunca puede acabar en un 500. */
    @ParameterizedTest
    @MethodSource("accepted")
    void normalizaCadaFormaAceptadaALaFormaQueExigeLaBaseDeDatos(String url, String id) {
        String normalized = YouTubeUrls.normalize(url);

        assertEquals("https://www.youtube.com/watch?v=" + id, normalized);
        assertEquals(43, normalized.length());
        assertTrue(normalized.matches(CHECK_REGEX), normalized);
    }

    /** Normalizar dos veces da lo mismo: la forma normalizada también es una forma aceptada. */
    @ParameterizedTest
    @MethodSource("accepted")
    void laNormalizacionEsIdempotente(String url, String id) {
        String normalized = YouTubeUrls.normalize(url);

        assertEquals(normalized, YouTubeUrls.normalize(normalized));
    }

    // ------------------------------------------------------------------
    // Formas rechazadas
    // ------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("rejected")
    void rechazaTodoLoQueNoEsUnaFormaAceptada(String url) {
        assertEquals(Optional.empty(), YouTubeUrls.videoId(url), url);
        assertFalse(YouTubeUrls.isValid(url), url);
        assertEquals(List.of(MESSAGE), messages(new TrailerField(url)), url);
    }

    /**
     * Normalizar algo no validado es un error de programación (el servicio
     * siempre recibe el DTO ya validado): falla con un mensaje claro en lugar
     * de llegar al {@code CHECK} de la base de datos.
     */
    @ParameterizedTest
    @MethodSource("rejected")
    void normalizarUnEnlaceNoAceptadoLanzaIllegalArgumentException(String url) {
        assertThrows(IllegalArgumentException.class, () -> YouTubeUrls.normalize(url), url);
    }

    @Test
    void elCatalogoDeEjemplosNoSeContradice() {
        for (String url : YouTubeUrlSamples.REJECTED) {
            assertFalse(YouTubeUrlSamples.ACCEPTED.containsKey(url), url);
            assertFalse(url.isBlank(), "Los textos en blanco no son un rechazo: son «sin tráiler»");
        }
    }

    // ------------------------------------------------------------------
    // Vacío = sin tráiler, y reparto de errores con @Size
    // ------------------------------------------------------------------

    /** {@code null} o en blanco (también un espacio Unicode) = sin tráiler: válido y se guarda {@code null}. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "\t", "\n", " " })
    void nuloOEnBlancoEsSinTrailer(String value) {
        assertEquals(List.of(), messages(new TrailerField(value)));
        assertNull(YouTubeUrls.normalize(value));
        assertFalse(YouTubeUrls.isValid(value), "No es un enlace, aunque la anotación lo deje pasar");
    }

    /**
     * Más largo que {@code maxLength}: la anotación no opina (lo rechaza
     * {@code @Size} con su mensaje), aunque además tenga un formato incorrecto.
     */
    @Test
    void porEncimaDeMaxLengthNoOpina() {
        String longAndWrong = "javascript:" + "a".repeat(60);
        String exactlyAtLimit = "https://www.youtube.com/watch?v=" + YouTubeUrlSamples.ID + "&x=aaaa";
        assertEquals(50, exactlyAtLimit.length());

        assertEquals(List.of(), messages(new ShortTrailerField(longAndWrong)));
        assertEquals(List.of(), messages(new ShortTrailerField(exactlyAtLimit)));
        assertEquals(List.of(MESSAGE), messages(new ShortTrailerField("javascript:alert(1)")),
                "Por debajo del límite sí opina");
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static <T> List<String> messages(T bean) {
        return validator.validate(bean).stream().map(ConstraintViolation::getMessage).toList();
    }
}
