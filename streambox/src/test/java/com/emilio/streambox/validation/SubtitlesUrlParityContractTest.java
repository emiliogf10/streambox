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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Paridad cliente/servidor de la URL de los subtítulos (revisión de QA de la
 * {@code V15}): el backend comprueba el MISMO catálogo de casos que el frontend
 * ({@code frontend/src/test/subtitlesUrlCases.json}, que también lee
 * {@code frontend/src/lib/subtitlesUrl.parity.test.ts}).
 *
 * <p>
 * <b>Por qué un archivo compartido.</b> La regla «el texto anterior al primer
 * {@code ?} o {@code #} termina en {@code .vtt}» está escrita dos veces
 * ({@link WebVttUrlValidator} y {@code hasVttPath} de
 * {@code movieValidation.ts}). Cada lado tenía su propia lista de casos y no
 * coincidían: el frontend no probaba {@code https://cdn.example.vtt} ni
 * {@code %2Evtt}, y ninguno probaba un {@code #} dentro de la consulta o un
 * {@code #} antes que la {@code ?}. Con un único archivo, un cambio en un lado
 * que no se replique en el otro rompe la suite del lado que se queda atrás (lo
 * mismo que {@link YouTubeUrlParityContractTest} con los tráileres).
 * </p>
 *
 * <p>
 * Se valida con los DTO reales ({@link MovieRequest} y {@link EpisodeRequest},
 * con {@code @Size}, {@link WebVttUrl} y {@link SubtitlesRequireVideo}), así
 * que también se comprueba el orden de los mensajes (longitud, formato, vídeo)
 * y que el campo reciba un único error. El archivo se lee de la carpeta del
 * frontend del mismo repositorio; si no está, el test falla en lugar de
 * omitirse.
 * </p>
 */
class SubtitlesUrlParityContractTest {

    /** Ruta del contrato compartido, relativa al directorio del módulo ({@code streambox/}). */
    private static final Path CONTRACT = Path.of("..", "frontend", "src", "test", "subtitlesUrlCases.json");

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
    record ContractCase(String nota, String url, String video, String esperado) {
    }

    /** Lee los casos del archivo compartido. */
    static List<ContractCase> contractCases() throws IOException {
        assertTrue(Files.exists(CONTRACT), "No se encuentra el contrato compartido: " + CONTRACT.toAbsolutePath());
        JsonNode root = new ObjectMapper().readTree(Files.readString(CONTRACT));
        List<ContractCase> cases = new ArrayList<>();
        for (JsonNode item : root.get("casos")) {
            JsonNode video = item.get("video");
            cases.add(new ContractCase(item.get("nota").asText(), item.get("url").asText(),
                    video == null || video.isNull() ? null : video.asText(), item.get("esperado").asText()));
        }
        return cases;
    }

    /** Cada caso, en película y en episodio. */
    static Stream<Arguments> casesWithRequests() throws IOException {
        List<Arguments> arguments = new ArrayList<>();
        for (ContractCase item : contractCases()) {
            String name = item.nota() + " → " + item.esperado();
            arguments.add(Arguments.of("película: " + name, item, new MovieRequest("Dune", "Sinopsis", 155, 2021,
                    "https://cdn.example.com/dune.jpg", item.video(), null, item.url(), Set.of(1L))));
            arguments.add(Arguments.of("episodio: " + name, item,
                    new EpisodeRequest(1, 1, "Piloto", null, 45, item.video(), item.url())));
        }
        return arguments.stream();
    }

    /**
     * El contrato tiene de todo (si alguien lo vaciara, los parametrizados
     * pasarían sin comprobar nada) y ningún caso repetido.
     */
    @Test
    void elContratoTieneCasosDeLasCuatroClasesYNingunoRepetido() throws IOException {
        List<ContractCase> cases = contractCases();
        assertTrue(cases.size() >= 40, "El contrato debería tener al menos 40 casos: " + cases.size());
        for (String kind : List.of("valida", "formato", "longitud", "sinVideo")) {
            assertTrue(cases.stream().anyMatch(item -> item.esperado().equals(kind)), "Falta la clase " + kind);
        }
        Set<String> keys = new HashSet<>();
        for (ContractCase item : cases) {
            assertTrue(keys.add(item.url() + " " + item.video()), "Caso repetido: " + item.nota());
        }
    }

    /**
     * El servidor da exactamente lo que dice el contrato, con un único error en
     * {@code subtitlesUrl} cuando lo hay.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("casesWithRequests")
    void elServidorDaLoQueDiceElContrato(String name, ContractCase item, Object request) {
        List<String> messages = validator.validate(request).stream()
                .filter(violation -> violation.getPropertyPath().toString().equals("subtitlesUrl"))
                .map(ConstraintViolation::getMessage)
                .toList();

        assertTrue(messages.size() <= 1, "Más de un error en subtitlesUrl: " + messages);
        assertEquals(item.esperado(), toContractResult(messages.isEmpty() ? null : messages.get(0)), name);
    }

    /** Traduce el mensaje del servidor al vocabulario del contrato. */
    private static String toContractResult(String message) {
        if (message == null) {
            return "valida";
        }
        return switch (message) {
            case MovieRequest.SUBTITLES_URL_SIZE_MESSAGE -> "longitud";
            case MovieRequest.SUBTITLES_URL_FORMAT_MESSAGE -> "formato";
            case MovieRequest.SUBTITLES_REQUIRE_VIDEO_MESSAGE -> "sinVideo";
            default -> "mensaje inesperado: " + message;
        };
    }
}
