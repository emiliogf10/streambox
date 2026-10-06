package com.emilio.streambox.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

/**
 * Tests unitarios de las reglas de {@code imageUrl} y {@code videoUrl} (y del
 * tope de {@code genreIds}) de {@link MovieRequest} tal como están anotadas
 * (sin Spring).
 *
 * <p>
 * Comprueban lo que no se ve probando {@code @HttpsUrl} por separado: que cada
 * caso produce <b>exactamente un</b> error por campo (aunque el valor incumpla
 * varias reglas) y que el texto es el acordado con el frontend. Los mensajes
 * se escriben aquí literalmente, y no con las constantes de
 * {@link MovieRequest}, para que cambiar el contrato por descuido rompa el test.
 * </p>
 */
class MovieRequestValidationTest {

    private static final String IMAGE_FORMAT =
            "La URL de la imagen debe empezar por https:// o ser una portada propia (/covers/archivo)";
    private static final String IMAGE_SIZE = "La URL de la imagen no puede superar los 500 caracteres";
    private static final String VIDEO_FORMAT = "La URL del vídeo debe empezar por https://";
    private static final String VIDEO_SIZE = "La URL del vídeo no puede superar los 500 caracteres";
    private static final String GENRES_SIZE = "Una película puede tener como máximo 20 géneros";

    private static final String VALID_IMAGE = "https://image.tmdb.org/t/p/w500/x.jpg";
    private static final String VALID_VIDEO = "https://videos.streambox.example/watch/dune";

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
    void unaPeticionConUrlsValidasNoTieneErrores() {
        assertTrue(validator.validate(request(VALID_IMAGE, VALID_VIDEO)).isEmpty());
        assertTrue(validator.validate(request("/covers/dune-parte-dos.webp", VALID_VIDEO)).isEmpty());
    }

    @Test
    void lasConstantesCoincidenConElContrato() {
        assertEquals(500, MovieRequest.URL_MAX_LENGTH);
        assertEquals(IMAGE_FORMAT, MovieRequest.IMAGE_URL_FORMAT_MESSAGE);
        assertEquals(IMAGE_SIZE, MovieRequest.IMAGE_URL_SIZE_MESSAGE);
        assertEquals(VIDEO_FORMAT, MovieRequest.VIDEO_URL_FORMAT_MESSAGE);
        assertEquals(VIDEO_SIZE, MovieRequest.VIDEO_URL_SIZE_MESSAGE);
    }

    // ------------------------------------------------------------------
    // Formato
    // ------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "http://cdn.example.com/x.jpg", "javascript:alert(1)", "data:image/png;base64,AAAA",
            "file:///etc/passwd", "ftp://cdn.example.com/x.jpg", "//cdn.example.com/x.jpg",
            "HTTPS://cdn.example.com/x.jpg", "https:///ruta", "https://", "https://u:p@cdn.example.com/x.jpg",
            "https://cdn.example.com/a b.jpg", "/covers/../x.webp", "/covers/x.webp?v=1", "/otra/x.webp"
    })
    void unaImagenConFormatoIncorrectoDaSoloElMensajeDeFormato(String url) {
        assertEquals(List.of(IMAGE_FORMAT), messagesFor(request(url, VALID_VIDEO), "imageUrl"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://cdn.example.com/x.mp4", "javascript:alert(1)", "file:///etc/passwd",
            "/covers/dune-parte-dos.webp", "https://u:p@cdn.example.com/x.mp4"
    })
    void unVideoConFormatoIncorrectoDaSoloElMensajeDeFormato(String url) {
        assertEquals(List.of(VIDEO_FORMAT), messagesFor(request(VALID_IMAGE, url), "videoUrl"));
    }

    // ------------------------------------------------------------------
    // Longitud (y prioridad sobre el formato)
    // ------------------------------------------------------------------

    @Test
    void quinientosCaracteresEsElMaximoAdmitido() {
        String url = httpsUrlOfLength(500);

        assertTrue(validator.validate(request(url, url)).isEmpty());
    }

    @Test
    void unaUrlCorrectaDe501CaracteresDaElMensajeDeLongitud() {
        String url = httpsUrlOfLength(501);

        MovieRequest request = request(url, url);

        assertEquals(List.of(IMAGE_SIZE), messagesFor(request, "imageUrl"));
        assertEquals(List.of(VIDEO_SIZE), messagesFor(request, "videoUrl"));
    }

    /**
     * Si la URL incumple a la vez la longitud y el formato, manda la longitud:
     * un solo mensaje, y siempre el mismo (con dos, el manejador de errores se
     * quedaría con uno cualquiera).
     */
    @ParameterizedTest
    @ValueSource(strings = { "javascript:", "http://cdn.example.com/", "/covers/../" })
    void siFallanLongitudYFormatoSoloSeInformaDeLaLongitud(String prefix) {
        String url = prefix + "a".repeat(501 - prefix.length());

        MovieRequest request = request(url, url);

        assertEquals(List.of(IMAGE_SIZE), messagesFor(request, "imageUrl"));
        assertEquals(List.of(VIDEO_SIZE), messagesFor(request, "videoUrl"));
    }

    // ------------------------------------------------------------------
    // Obligatoriedad
    // ------------------------------------------------------------------

    /**
     * Un valor nulo o en blanco solo lo rechaza {@code @NotBlank}. Incluye un
     * espacio Unicode (U+2003, "espacio eme"): comprueba que {@code @HttpsUrl} usa el
     * mismo criterio de "en blanco" que {@code @NotBlank}, de modo que no hay
     * dos errores ni ningún valor que no rechace nadie.
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = { "   ", "\t", " " })
    void unaUrlNulaOEnBlancoSoloLaRechazaNotBlank(String url) {
        MovieRequest request = request(url, url);

        assertOnlyNotBlank(request, "imageUrl");
        assertOnlyNotBlank(request, "videoUrl");
    }

    /**
     * Un carácter de control no cuenta como "en blanco" para
     * {@link String#isBlank()} (ni para {@code @NotBlank}), así que tiene que
     * rechazarlo {@code @HttpsUrl}: nunca puede quedar sin error.
     */
    @Test
    void unCaracterDeControlNoEnBlancoLoRechazaElFormato() {
        MovieRequest request = request("\u0001", "\u0001");

        assertEquals(List.of(IMAGE_FORMAT), messagesFor(request, "imageUrl"));
        assertEquals(List.of(VIDEO_FORMAT), messagesFor(request, "videoUrl"));
    }

    // ------------------------------------------------------------------
    // Número de géneros
    // ------------------------------------------------------------------

    @Test
    void veinteGenerosEsElMaximoAdmitido() {
        assertTrue(validator.validate(withGenres(20)).isEmpty());
        assertEquals(20, MovieRequest.MAX_GENRES);
    }

    @Test
    void veintiunGenerosDaElMensajeDelTope() {
        assertEquals(List.of(GENRES_SIZE), messagesFor(withGenres(21), "genreIds"));
    }

    /** El tope no tapa a {@code @NotEmpty}: una lista vacía sigue dando un único error. */
    @Test
    void sinGenerosSoloInformaNotEmpty() {
        List<ConstraintViolation<MovieRequest>> violations = violationsFor(withGenres(0), "genreIds");

        assertEquals(1, violations.size(), violations::toString);
        assertEquals(NotEmpty.class,
                violations.get(0).getConstraintDescriptor().getAnnotation().annotationType());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static MovieRequest request(String imageUrl, String videoUrl) {
        return new MovieRequest("Dune", "Sinopsis", 155, 2021, imageUrl, videoUrl, Set.of(1L));
    }

    /** Petición válida con {@code count} ids de género distintos (1..count). */
    private static MovieRequest withGenres(int count) {
        Set<Long> genreIds = LongStream.rangeClosed(1, count).boxed().collect(Collectors.toSet());
        return new MovieRequest("Dune", "Sinopsis", 155, 2021, VALID_IMAGE, VALID_VIDEO, genreIds);
    }

    private static String httpsUrlOfLength(int length) {
        String prefix = "https://cdn.example.com/";
        return prefix + "a".repeat(length - prefix.length());
    }

    private static List<ConstraintViolation<MovieRequest>> violationsFor(MovieRequest request, String field) {
        return validator.validate(request).stream()
                .filter(v -> v.getPropertyPath().toString().equals(field))
                .toList();
    }

    private static List<String> messagesFor(MovieRequest request, String field) {
        return violationsFor(request, field).stream().map(ConstraintViolation::getMessage).toList();
    }

    private static void assertOnlyNotBlank(MovieRequest request, String field) {
        List<ConstraintViolation<MovieRequest>> violations = violationsFor(request, field);
        assertEquals(1, violations.size(), () -> field + ": " + violations);
        assertEquals(NotBlank.class,
                violations.get(0).getConstraintDescriptor().getAnnotation().annotationType());
    }
}
