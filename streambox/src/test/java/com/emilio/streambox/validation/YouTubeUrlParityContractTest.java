package com.emilio.streambox.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.emilio.streambox.dto.MovieRequest;
import com.emilio.streambox.dto.SeriesRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Paridad cliente/servidor del enlace del tráiler (revisión de QA): el backend
 * comprueba el MISMO catálogo de casos que el frontend
 * ({@code frontend/src/test/youtubeTrailerUrlCases.json}, que también lee
 * {@code frontend/src/lib/youtube.parity.test.ts}).
 *
 * <p>
 * <b>Por qué un archivo compartido y no dos listas.</b> {@code YouTubeUrls} y
 * {@code youtube.ts} implementan la misma regla con técnicas distintas
 * ({@code java.net.URI} frente a expresiones regulares). Con una lista en cada
 * lado, nada impide que se separen: la revisión encontró precisamente dos
 * diferencias (un {@code %} mal formado en los parámetros y los corchetes
 * {@code [ ]}) que ninguna de las dos listas tenía. Con un único archivo, un
 * cambio en un lado que no se replica en el otro rompe la suite del lado que
 * se queda atrás.
 * </p>
 *
 * <p>
 * Se valida con los DTO reales ({@link MovieRequest} y {@link SeriesRequest},
 * con sus {@code @Size} y {@code @YouTubeUrl}), así que también se comprueba
 * el reparto de mensajes entre longitud y formato. Este lado es la referencia:
 * todos los casos deben pasar aquí y, desde la revisión de QA del 2026-10-16,
 * también en el frontend ({@code youtube.parity.test.ts}), sin excepciones.
 * </p>
 *
 * <p>
 * El archivo se lee de la carpeta del frontend del mismo repositorio (Maven
 * ejecuta los tests desde {@code streambox/}; el CI hace checkout del
 * repositorio entero). Si no está, el test falla en lugar de omitirse: un
 * contrato que se salta en silencio no protege nada.
 * </p>
 */
class YouTubeUrlParityContractTest {

    /** Ruta del contrato compartido, relativa al directorio del módulo ({@code streambox/}). */
    private static final Path CONTRACT = Path.of("..", "frontend", "src", "test", "youtubeTrailerUrlCases.json");

    private static final String FORMAT = "formato";
    private static final String SIZE = "longitud";

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

    /** Casos del contrato: nota, url y resultado esperado. */
    static Stream<Arguments> contractCases() throws IOException {
        assertTrue(Files.isRegularFile(CONTRACT), "No se encuentra el contrato compartido " + CONTRACT.toAbsolutePath());
        JsonNode root = new ObjectMapper().readTree(Files.readString(CONTRACT));
        List<Arguments> cases = new ArrayList<>();
        for (JsonNode item : root.get("casos")) {
            cases.add(Arguments.of(item.get("nota").asText(), item.get("url").asText(), item.get("esperado").asText()));
        }
        return cases.stream();
    }

    /**
     * Cada caso del contrato da en la API el resultado esperado, en películas
     * y en series: la forma normalizada (que cumple el {@code CHECK}),
     * el error de formato o el de longitud, y nunca los dos a la vez.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("contractCases")
    void elServidorCumpleElContratoCompartido(String note, String url, String expected) {
        assertEquals(expected, serverResult(url, trailerErrors(validator.validate(movie(url)))), "película: " + note);
        assertEquals(expected, serverResult(url, trailerErrors(validator.validate(series(url)))), "serie: " + note);
        if (!expected.equals(FORMAT) && !expected.equals(SIZE)) {
            assertEquals(expected, YouTubeUrls.normalize(url), "normalización: " + note);
            assertTrue(expected.matches("https://www\\.youtube\\.com/watch\\?v=[A-Za-z0-9_-]{11}"),
                    "lo aceptado debe cumplir el CHECK de la base de datos: " + note);
        }
    }

    /** El contrato no puede quedarse vacío ni perder alguna de las tres clases de resultado. */
    @Test
    void elContratoCubreLasTresClases() throws IOException {
        List<String> expected = contractCases().map(arguments -> (String) arguments.get()[2]).toList();
        assertTrue(expected.size() > 50, "el contrato debe tener casos suficientes");
        assertTrue(expected.contains(FORMAT));
        assertTrue(expected.contains(SIZE));
        assertTrue(expected.stream().anyMatch(value -> value.startsWith("https://")));
    }

    /**
     * Traduce lo que haría la API al vocabulario del contrato: sin errores, la
     * forma que se guardaría ({@link YouTubeUrls#normalize(String)}, lo que
     * hace el mapper); con un error, su clase.
     */
    private static String serverResult(String url, List<String> messages) {
        if (messages.isEmpty()) {
            return YouTubeUrls.normalize(url);
        }
        if (messages.equals(List.of(MovieRequest.TRAILER_URL_FORMAT_MESSAGE))) {
            return FORMAT;
        }
        if (messages.equals(List.of(MovieRequest.TRAILER_URL_SIZE_MESSAGE))) {
            return SIZE;
        }
        return "mensajes inesperados: " + messages;
    }

    /** Mensajes de error del campo {@code trailerUrl}, ordenados. */
    private static <T> List<String> trailerErrors(Set<ConstraintViolation<T>> violations) {
        return violations.stream()
                .filter(violation -> violation.getPropertyPath().toString().equals("trailerUrl"))
                .map(ConstraintViolation::getMessage)
                .sorted()
                .toList();
    }

    private static MovieRequest movie(String trailerUrl) {
        return new MovieRequest("Dune", "Sinopsis", 155, 2021, "https://e.com/i.jpg", null, trailerUrl, Set.of(1L));
    }

    private static SeriesRequest series(String trailerUrl) {
        return new SeriesRequest("Fargo", "Sinopsis", 2014, null, "https://e.com/i.jpg", trailerUrl, Set.of(1L));
    }
}
