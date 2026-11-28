package com.emilio.streambox.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.emilio.streambox.dto.EpisodeRequest;
import com.emilio.streambox.dto.MovieRequest;
import com.emilio.streambox.dto.SeriesRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Paridad cliente/servidor de las URL de imagen con la regla de la portada
 * ({@link HttpsUrl} con {@code allowLocalCovers}): el backend comprueba el
 * MISMO catálogo de casos que el frontend
 * ({@code frontend/src/test/imageUrlCases.json}, que también lee
 * {@code frontend/src/lib/imageUrl.parity.test.ts}).
 *
 * <p>
 * <b>Por qué.</b> La regla está escrita dos veces ({@link HttpsUrlValidator}
 * y la validación de los formularios del panel, en {@code movieValidation.ts},
 * {@code seriesValidation.ts} y {@code episodeValidation.ts}) y, desde la
 * migración {@code V16}, se aplica a cinco campos: la portada
 * ({@code imageUrl}) y la imagen de fondo ({@code backdropUrl}) de películas
 * y series, y la imagen del episodio ({@code imageUrl} de
 * {@link EpisodeRequest}). Con un único archivo de casos, un cambio en un lado
 * que no se replique en el otro rompe la suite del lado que se queda atrás
 * (como {@link SubtitlesUrlParityContractTest} y
 * {@link YouTubeUrlParityContractTest}).
 * </p>
 *
 * <p>
 * Se valida con los DTO reales, así que también se comprueba que la longitud
 * gane al formato y que cada campo reciba un único error, con el mensaje de
 * <b>su</b> campo (un mensaje de la portada en {@code backdropUrl} sería un
 * fallo). El archivo se lee de la carpeta del frontend del mismo repositorio;
 * si no está, el test falla en lugar de omitirse.
 * </p>
 */
class ImageUrlParityContractTest {

    /** Ruta del contrato compartido, relativa al directorio del módulo ({@code streambox/}). */
    private static final Path CONTRACT = Path.of("..", "frontend", "src", "test", "imageUrlCases.json");

    /** Portada válida para las peticiones en las que se prueba otro campo. */
    private static final String POSTER = "https://cdn.example.com/poster.jpg";

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

    /** Un caso del contrato. */
    record ContractCase(String nota, String url, String esperado) {
    }

    /**
     * Un campo con la regla de la portada: la petición que lo contiene y los
     * mensajes que le corresponden.
     */
    record ImageField(String name, String property, String formatMessage, String sizeMessage) {
    }

    private static final ImageField MOVIE_POSTER = new ImageField("película: imageUrl", "imageUrl",
            MovieRequest.IMAGE_URL_FORMAT_MESSAGE, MovieRequest.IMAGE_URL_SIZE_MESSAGE);
    private static final ImageField MOVIE_BACKDROP = new ImageField("película: backdropUrl", "backdropUrl",
            MovieRequest.BACKDROP_URL_FORMAT_MESSAGE, MovieRequest.BACKDROP_URL_SIZE_MESSAGE);
    private static final ImageField SERIES_POSTER = new ImageField("serie: imageUrl", "imageUrl",
            MovieRequest.IMAGE_URL_FORMAT_MESSAGE, MovieRequest.IMAGE_URL_SIZE_MESSAGE);
    private static final ImageField SERIES_BACKDROP = new ImageField("serie: backdropUrl", "backdropUrl",
            MovieRequest.BACKDROP_URL_FORMAT_MESSAGE, MovieRequest.BACKDROP_URL_SIZE_MESSAGE);
    private static final ImageField EPISODE_IMAGE = new ImageField("episodio: imageUrl", "imageUrl",
            EpisodeRequest.IMAGE_URL_FORMAT_MESSAGE, EpisodeRequest.IMAGE_URL_SIZE_MESSAGE);

    /** Lee los casos del archivo compartido. */
    static List<ContractCase> contractCases() throws IOException {
        assertTrue(Files.exists(CONTRACT), "No se encuentra el contrato compartido: " + CONTRACT.toAbsolutePath());
        JsonNode root = new ObjectMapper().readTree(Files.readString(CONTRACT));
        List<ContractCase> cases = new ArrayList<>();
        for (JsonNode item : root.get("casos")) {
            cases.add(new ContractCase(item.get("nota").asText(), item.get("url").asText(),
                    item.get("esperado").asText()));
        }
        return cases;
    }

    /** Cada caso, en los cinco campos. */
    static Stream<Arguments> casesWithRequests() throws IOException {
        List<Arguments> arguments = new ArrayList<>();
        for (ContractCase item : contractCases()) {
            String url = item.url();
            String suffix = ": " + item.nota() + " → " + item.esperado();
            arguments.add(Arguments.of(MOVIE_POSTER.name() + suffix, item, MOVIE_POSTER,
                    movie(url, null)));
            arguments.add(Arguments.of(MOVIE_BACKDROP.name() + suffix, item, MOVIE_BACKDROP,
                    movie(POSTER, url)));
            arguments.add(Arguments.of(SERIES_POSTER.name() + suffix, item, SERIES_POSTER,
                    series(url, null)));
            arguments.add(Arguments.of(SERIES_BACKDROP.name() + suffix, item, SERIES_BACKDROP,
                    series(POSTER, url)));
            arguments.add(Arguments.of(EPISODE_IMAGE.name() + suffix, item, EPISODE_IMAGE,
                    new EpisodeRequest(1, 1, "Piloto", null, 45, null, null, url)));
        }
        return arguments.stream();
    }

    /**
     * El contrato tiene de todo (si alguien lo vaciara, los parametrizados
     * pasarían sin comprobar nada) y ningún caso repetido.
     */
    @Test
    void elContratoTieneCasosDeLasTresClasesYNingunoRepetido() throws IOException {
        List<ContractCase> cases = contractCases();
        assertTrue(cases.size() >= 30, "El contrato debería tener al menos 30 casos: " + cases.size());
        for (String kind : List.of("valida", "formato", "longitud")) {
            assertTrue(cases.stream().anyMatch(item -> item.esperado().equals(kind)), "Falta la clase " + kind);
        }
        Set<String> urls = new HashSet<>();
        for (ContractCase item : cases) {
            assertTrue(urls.add(item.url()), "Caso repetido: " + item.nota());
        }
    }

    /**
     * El servidor da exactamente lo que dice el contrato en cada campo, con un
     * único error y el mensaje propio del campo cuando lo hay. Además, la
     * petición no tiene errores en ningún otro campo: así un caso «valida» no
     * pasa por un error que haya caído en otro sitio.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("casesWithRequests")
    void elServidorDaLoQueDiceElContrato(String name, ContractCase item, ImageField field, Object request) {
        Set<ConstraintViolation<Object>> violations = validator.validate(request);
        List<String> messages = violations.stream()
                .filter(violation -> violation.getPropertyPath().toString().equals(field.property()))
                .map(ConstraintViolation::getMessage)
                .toList();

        assertEquals(messages.size(), violations.size(), "Errores en otros campos: " + violations);
        assertTrue(messages.size() <= 1, "Más de un error en " + field.property() + ": " + messages);
        assertEquals(item.esperado(), toContractResult(field, messages.isEmpty() ? null : messages.get(0)), name);
    }

    /** Traduce el mensaje del servidor al vocabulario del contrato. */
    private static String toContractResult(ImageField field, String message) {
        if (message == null) {
            return "valida";
        }
        if (message.equals(field.formatMessage())) {
            return "formato";
        }
        if (message.equals(field.sizeMessage())) {
            return "longitud";
        }
        return "mensaje inesperado: " + message;
    }

    private static MovieRequest movie(String imageUrl, String backdropUrl) {
        return new MovieRequest("Dune", "Sinopsis", 155, 2021, imageUrl, null, null, null, backdropUrl, Set.of(1L));
    }

    private static SeriesRequest series(String imageUrl, String backdropUrl) {
        return new SeriesRequest("Fargo", "Sinopsis", 2014, null, imageUrl, null, backdropUrl, Set.of(1L));
    }
}
