package com.emilio.streambox.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.emilio.streambox.dto.EpisodeRequest;
import com.emilio.streambox.dto.MovieRequest;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Tests unitarios (sin Spring) de {@code subtitlesUrl} en {@link MovieRequest}
 * y {@link EpisodeRequest}: {@link WebVttUrl} más {@link SubtitlesRequireVideo}
 * (migración {@code V15}).
 *
 * <p>
 * Cada caso se comprueba en las <b>dos</b> peticiones, porque comparten
 * constantes y reglas pero cada una lleva sus propias anotaciones: olvidar una
 * en {@link EpisodeRequest} no lo detectaría un test solo de películas. Lo que
 * importa, además de qué se acepta, es que cada caso produzca <b>exactamente
 * un</b> error en el campo con el texto acordado y por este orden: longitud,
 * formato y, solo si lo anterior está bien, que haya vídeo. Los mensajes van
 * escritos literalmente (no con las constantes) para que cambiar el contrato
 * por descuido rompa el test, y otro test los compara con
 * {@code frontend/src/lib/movieValidation.ts}.
 * </p>
 */
class SubtitlesUrlValidationTest {

    private static final String SIZE = "La URL de los subtítulos no puede superar los 500 caracteres";
    private static final String FORMAT =
            "Los subtítulos deben ser un archivo .vtt (WebVTT) con una URL que empiece por https://";
    private static final String REQUIRE_VIDEO = "Los subtítulos necesitan un vídeo: indica también la URL del vídeo";
    private static final String VIDEO_FORMAT = "La URL del vídeo debe empezar por https://";
    private static final String VIDEO_SIZE = "La URL del vídeo no puede superar los 500 caracteres";

    private static final String VIDEO = "https://cdn.example.com/dune.mp4";
    private static final String SUBTITLES = "https://cdn.example.com/subs/dune.es.vtt";

    private static final Path MOVIE_VALIDATION_TS = Path.of("..", "frontend", "src", "lib", "movieValidation.ts");

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

    /**
     * Las dos peticiones con subtítulos: cada test se ejecuta con una y con
     * otra. La función recibe {@code (videoUrl, subtitlesUrl)}.
     */
    static Stream<Arguments> requests() {
        BiFunction<String, String, Object> movie = (video, subtitles) -> new MovieRequest("Dune", "Sinopsis", 155,
                2021, "https://cdn.example.com/dune.jpg", video, null, subtitles, Set.of(1L));
        BiFunction<String, String, Object> episode = (video, subtitles) -> new EpisodeRequest(1, 1, "Piloto", null,
                45, video, subtitles);
        return Stream.of(Arguments.of("película", movie), Arguments.of("episodio", episode));
    }

    /** Combina cada petición con cada URL aceptada. */
    static Stream<Arguments> acceptedWithRequests() {
        return combine(Stream.of(
                SUBTITLES,
                "https://cdn.example.com/subs/dune.es.vtt?token=abc&exp=1",
                "https://cdn.example.com/subs/dune.es.vtt#t=10",
                "https://cdn.example.com/subs/dune.es.vtt?x=1#y",
                "https://cdn.example.com/SUBS/DUNE.ES.VTT",
                "https://cdn.example.com/dune.Vtt",
                "https://cdn.example.com:8443/dune.vtt",
                // El texto completo antes de ?/# termina en .vtt, aunque no haya ruta:
                // el frontend lo acepta y el servidor debe hacer lo mismo (no URI.getPath()).
                "https://cdn.example.vtt",
                "https://cdn.example.vtt?lang=es"));
    }

    /** Combina cada petición con cada URL de formato incorrecto. */
    static Stream<Arguments> rejectedWithRequests() {
        return combine(Stream.of(
                "https://cdn.example.com/dune.srt",
                "https://cdn.example.com/dune.vtt.html",
                "https://cdn.example.com/dune.vtt/",
                "https://cdn.example.com/dunevtt",
                "https://cdn.example.com/descargar?f=dune.vtt",
                "https://cdn.example.com/descargar#dune.vtt",
                "https://cdn.example.com/dune%2Evtt",
                "http://cdn.example.com/dune.vtt",
                "HTTPS://cdn.example.com/dune.vtt",
                "//cdn.example.com/dune.vtt",
                "/subs/dune.vtt",
                "javascript:alert(1)//x.vtt",
                "data:text/vtt,WEBVTT.vtt",
                "https://usuario:clave@cdn.example.com/dune.vtt",
                "https://usuario@cdn.example.com/dune.vtt",
                "https:///dune.vtt",
                " https://cdn.example.com/dune.vtt",
                "https://cdn.example.com/dune.vtt ",
                "https://cdn.example.com/dune​.vtt",
                "https://cdn.example.com/dúne.vtt"));
    }

    private static Stream<Arguments> combine(Stream<String> urls) {
        List<Arguments> requests = requests().toList();
        return urls.flatMap(url -> requests.stream()
                .map(request -> Arguments.of(request.get()[0], request.get()[1], url)));
    }

    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("acceptedWithRequests")
    void unVttPorHttpsConVideoEsValido(String kind, BiFunction<String, String, Object> request, String subtitles) {
        Object valid = request.apply(VIDEO, subtitles);

        assertTrue(validator.validate(valid).isEmpty(), () -> kind + ": " + validator.validate(valid));
    }

    @ParameterizedTest(name = "{0}: {2}")
    @MethodSource("rejectedWithRequests")
    void loQueNoEsUnVttPorHttpsDaUnSoloErrorDeFormato(String kind, BiFunction<String, String, Object> request,
            String subtitles) {
        Object invalid = request.apply(VIDEO, subtitles);

        assertEquals(List.of(FORMAT), messagesFor(invalid, "subtitlesUrl"));
        assertEquals(List.of(), messagesFor(invalid, "videoUrl"));
    }

    /**
     * Sin vídeo y con un formato incorrecto sale el error de formato, no el de
     * vídeo: primero hay que corregir lo que está mal en el propio campo.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("requests")
    void unFormatoIncorrectoSinVideoDaSoloElErrorDeFormato(String kind, BiFunction<String, String, Object> request) {
        assertEquals(List.of(FORMAT), messagesFor(request.apply(null, "https://cdn.example.com/dune.srt"),
                "subtitlesUrl"));
        assertEquals(List.of(FORMAT), messagesFor(request.apply("  ", "http://cdn.example.com/dune.vtt"),
                "subtitlesUrl"));
    }

    /**
     * Más de 500 caracteres: solo el error de longitud, sea cual sea el formato
     * y haya vídeo o no. 500 exactos con formato válido pasan.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("requests")
    void masDe500CaracteresDaSoloElErrorDeLongitud(String kind, BiFunction<String, String, Object> request) {
        String vtt500 = vttUrlOfLength(500);

        assertEquals(List.of(SIZE), messagesFor(request.apply(VIDEO, vttUrlOfLength(501)), "subtitlesUrl"));
        assertEquals(List.of(SIZE), messagesFor(request.apply(VIDEO, "http://" + "a".repeat(494)), "subtitlesUrl"));
        assertEquals(List.of(SIZE), messagesFor(request.apply(null, vttUrlOfLength(501)), "subtitlesUrl"));
        assertTrue(validator.validate(request.apply(VIDEO, vtt500)).isEmpty());
        assertEquals(500, vtt500.length());
    }

    /**
     * Subtítulos válidos sin vídeo (ausente o en blanco): un error, en
     * {@code subtitlesUrl} y no en {@code videoUrl}. Sin
     * {@link SubtitlesRequireVideo} la petición pasaría y el guardado violaría
     * {@code ck_*_subtitles_require_video}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("requests")
    void unosSubtitulosValidosSinVideoDanElErrorEnElCampoDeLosSubtitulos(String kind,
            BiFunction<String, String, Object> request) {
        for (String noVideo : new String[] { null, "", "   ", "\t" }) {
            Object withoutVideo = request.apply(noVideo, SUBTITLES);

            assertEquals(List.of(REQUIRE_VIDEO), messagesFor(withoutVideo, "subtitlesUrl"),
                    () -> "vídeo «" + noVideo + "»");
            assertEquals(List.of(), messagesFor(withoutVideo, "videoUrl"));
        }
    }

    /**
     * Un vídeo con texto pero inválido ya tiene su error en {@code videoUrl}:
     * los subtítulos válidos no añaden otro (sería un aviso falso: «hay» vídeo,
     * solo que mal escrito).
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("requests")
    void unVideoInvalidoConSubtitulosValidosDaUnSoloErrorEnElVideo(String kind,
            BiFunction<String, String, Object> request) {
        Object badVideo = request.apply("http://cdn.example.com/dune.mp4", SUBTITLES);
        Object longVideo = request.apply("https://cdn.example.com/" + "a".repeat(477), SUBTITLES);

        assertEquals(List.of(VIDEO_FORMAT), messagesFor(badVideo, "videoUrl"));
        assertEquals(List.of(), messagesFor(badVideo, "subtitlesUrl"));
        assertEquals(List.of(VIDEO_SIZE), messagesFor(longVideo, "videoUrl"));
        assertEquals(List.of(), messagesFor(longVideo, "subtitlesUrl"));
        assertEquals(1, validator.validate(badVideo).size());
    }

    /** Sin subtítulos (nulos, vacíos o en blanco) no hay ningún error, haya vídeo o no. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("requests")
    void sinSubtitulosNoHayError(String kind, BiFunction<String, String, Object> request) {
        for (String none : new String[] { null, "", "   " }) {
            assertTrue(validator.validate(request.apply(null, none)).isEmpty());
            assertTrue(validator.validate(request.apply(VIDEO, none)).isEmpty());
        }
    }

    /**
     * Las tres constantes del backend coinciden letra a letra con las del
     * formulario ({@code SUBTITLES_URL_*} de {@code movieValidation.ts}): el
     * administrador lee lo mismo lo detecte el cliente o el servidor.
     */
    @Test
    void losMensajesCoincidenConLosDelFrontend() throws IOException {
        assertTrue(Files.isRegularFile(MOVIE_VALIDATION_TS), () -> "No se encuentra "
                + MOVIE_VALIDATION_TS.toAbsolutePath());
        String source = Files.readString(MOVIE_VALIDATION_TS, StandardCharsets.UTF_8);
        String urlMax = constant(source, "URL_MAX", "(\\d+)");

        assertEquals(String.valueOf(MovieRequest.URL_MAX_LENGTH), urlMax);
        assertEquals(MovieRequest.SUBTITLES_URL_SIZE_MESSAGE,
                constant(source, "SUBTITLES_URL_SIZE_MESSAGE", "`([^`]*)`").replace("${URL_MAX}", urlMax));
        assertEquals(MovieRequest.SUBTITLES_URL_FORMAT_MESSAGE,
                constant(source, "SUBTITLES_URL_FORMAT_MESSAGE", "'([^']*)'"));
        assertEquals(MovieRequest.SUBTITLES_REQUIRE_VIDEO_MESSAGE,
                constant(source, "SUBTITLES_REQUIRE_VIDEO_MESSAGE", "'([^']*)'"));
        assertEquals(List.of(SIZE, FORMAT, REQUIRE_VIDEO), List.of(MovieRequest.SUBTITLES_URL_SIZE_MESSAGE,
                MovieRequest.SUBTITLES_URL_FORMAT_MESSAGE, MovieRequest.SUBTITLES_REQUIRE_VIDEO_MESSAGE));
    }

    /** Lee el valor de {@code export const <name> = <valor>;} (el valor puede ir en la línea siguiente). */
    private static String constant(String source, String name, String valuePattern) {
        Matcher matcher = Pattern.compile("export const " + name + "\\s*=\\s*" + valuePattern + "\\s*;")
                .matcher(source);
        assertTrue(matcher.find(), () -> "No se encuentra la constante " + name + " en movieValidation.ts");
        return matcher.group(1);
    }

    /** URL {@code https://} de un {@code .vtt} con la longitud exacta indicada. */
    private static String vttUrlOfLength(int length) {
        String prefix = "https://cdn.example.com/";
        String suffix = ".vtt";
        return prefix + "a".repeat(length - prefix.length() - suffix.length()) + suffix;
    }

    private static List<String> messagesFor(Object request, String field) {
        Set<ConstraintViolation<Object>> violations = validator.validate(request);
        return violations.stream()
                .filter(v -> v.getPropertyPath().toString().equals(field))
                .map(ConstraintViolation::getMessage)
                .toList();
    }
}
