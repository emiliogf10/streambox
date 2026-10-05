package com.emilio.streambox.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.emilio.streambox.dto.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Las respuestas de error del OpenAPI ({@code /v3/api-docs}) documentan
 * {@code ErrorResponse} y no el tipo de la respuesta correcta.
 *
 * <p>
 * Bug que protege: springdoc rellena cada {@code @ApiResponse} sin
 * {@code content} con el tipo de retorno del método, así que el 401 del login
 * aparecía como un {@code LoginResponse} (el token), el 404 de películas como
 * una {@code MovieResponse}, etc., y {@code ErrorResponse} ni siquiera estaba
 * en {@code components.schemas}. Un cliente generado desde el OpenAPI leería
 * un error como si fuera un token. Lo corrige
 * {@link ErrorResponseOpenApiCustomizer}.
 * </p>
 *
 * <p>
 * Además de casos concretos, {@link #ningunaRespuestaDeErrorDocumentaOtroEsquema()}
 * recorre todas las rutas: protege también los endpoints que se añadan en el
 * futuro sin tener que acordarse de este test.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OpenApiErrorResponseDocumentationIntegrationTest {

    private static final String ERROR_REF = "#/components/schemas/ErrorResponse";
    private static final String JSON = "application/json";

    /** Campos que el JSON de error lleva siempre. */
    private static final Set<String> ALWAYS_PRESENT =
            Set.of("timestamp", "status", "error", "code", "message", "path");

    /** Campos que solo aparecen en algunos errores y, si no aplican, se omiten (no van a null). */
    private static final Set<String> OPTIONAL = Set.of("validationErrors", "remainingAttempts");

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ------------------------------------------------------------------
    // El esquema ErrorResponse
    // ------------------------------------------------------------------

    @Test
    void errorResponseApareceEnLosEsquemasConTodosSusCampos() throws Exception {
        JsonNode schema = apiDocs().path("components").path("schemas").path("ErrorResponse");

        assertFalse(schema.isMissingNode(), "ErrorResponse debe estar en components.schemas");
        assertEquals("object", schema.path("type").asText());

        Set<String> properties = fieldNames(schema.path("properties"));
        Set<String> expected = new HashSet<>(ALWAYS_PRESENT);
        expected.addAll(OPTIONAL);
        assertEquals(expected, properties);

        for (String property : properties) {
            assertFalse(schema.path("properties").path(property).path("description").asText().isBlank(),
                    () -> "El campo " + property + " debe tener descripción");
        }

        assertEquals("date-time", schema.at("/properties/timestamp/format").asText());
        assertEquals("integer", schema.at("/properties/status/type").asText());
        assertEquals("string", schema.at("/properties/validationErrors/additionalProperties/type").asText());
    }

    /**
     * {@code required} refleja el JSON real: los seis campos fijos siempre
     * están y los dos opcionales se omiten cuando no aplican. Que no se
     * documenten como {@code null} (en OpenAPI 3.1, {@code type} sería una
     * lista con {@code "null"}) es lo que hace {@code @JsonInclude(NON_NULL)}.
     */
    @Test
    void losCamposOpcionalesNoSonObligatoriosNiNulables() throws Exception {
        JsonNode schema = apiDocs().at("/components/schemas/ErrorResponse");

        Set<String> required = new HashSet<>();
        schema.path("required").forEach(node -> required.add(node.asText()));
        assertEquals(ALWAYS_PRESENT, required);

        JsonNode remainingAttempts = schema.at("/properties/remainingAttempts");
        assertTrue(remainingAttempts.path("type").isTextual(), remainingAttempts::toString);
        assertEquals("integer", remainingAttempts.path("type").asText());
        assertEquals(1, remainingAttempts.path("minimum").asInt());
        assertTrue(remainingAttempts.path("description").asText().contains("ACCOUNT_LOCKED"));

        JsonNode validationErrors = schema.at("/properties/validationErrors");
        assertTrue(validationErrors.path("type").isTextual(), validationErrors::toString);
        assertEquals("object", validationErrors.path("type").asText());
        assertTrue(validationErrors.path("description").asText().contains("VALIDATION_ERROR"));
    }

    /**
     * El enum de {@code code} sale de {@link ErrorCode}, y su descripción
     * explica cada valor: si alguien añade un código y no lo documenta, este
     * test lo dice.
     */
    @Test
    void elCampoCodeListaYExplicaTodosLosValoresDeErrorCode() throws Exception {
        JsonNode code = apiDocs().at("/components/schemas/ErrorResponse/properties/code");

        Set<String> documented = new TreeSet<>();
        code.path("enum").forEach(node -> documented.add(node.asText()));
        Set<String> expected = Arrays.stream(ErrorCode.values()).map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(expected, documented);

        String description = code.path("description").asText();
        for (ErrorCode value : ErrorCode.values()) {
            assertTrue(description.contains(value.name()),
                    () -> "La descripción de code debe explicar " + value.name());
        }
    }

    // ------------------------------------------------------------------
    // Respuestas concretas
    // ------------------------------------------------------------------

    @Test
    void losErroresDocumentadosApuntanAErrorResponse() throws Exception {
        JsonNode paths = apiDocs().path("paths");

        assertEquals(ERROR_REF, schemaRef(paths, "/api/auth/login", "post", "401"));
        assertEquals(ERROR_REF, schemaRef(paths, "/api/auth/login", "post", "429"));
        assertEquals(ERROR_REF, schemaRef(paths, "/api/auth/login", "post", "400"));
        assertEquals(ERROR_REF, schemaRef(paths, "/api/users", "post", "400"));
        assertEquals(ERROR_REF, schemaRef(paths, "/api/movies/{id}", "get", "404"));
        assertEquals(ERROR_REF, schemaRef(paths, "/api/genres/{id}", "delete", "409"));
        assertEquals(ERROR_REF, schemaRef(paths, "/api/users", "get", "403"));
    }

    /**
     * El arreglo no debe tocar las respuestas correctas: siguen con el tipo de
     * retorno del método y los 204 siguen sin cuerpo.
     */
    @Test
    void lasRespuestasCorrectasConservanSuTipoYLos204SiguenSinCuerpo() throws Exception {
        JsonNode paths = apiDocs().path("paths");

        assertEquals("#/components/schemas/LoginResponse", schemaRef(paths, "/api/auth/login", "post", "200"));
        assertEquals("#/components/schemas/UserResponse", schemaRef(paths, "/api/users", "post", "201"));
        assertEquals("#/components/schemas/MovieResponse", schemaRef(paths, "/api/movies/{id}", "get", "200"));
        assertEquals("#/components/schemas/MoviePageResponse", schemaRef(paths, "/api/movies", "get", "200"));
        assertEquals("#/components/schemas/GenreResponse", paths.path("/api/genres").path("get")
                .path("responses").path("200").path("content").elements().next()
                .at("/schema/items/$ref").asText());

        assertTrue(paths.at("/~1api~1genres~1{id}/delete/responses/204").isObject());
        assertTrue(paths.at("/~1api~1genres~1{id}/delete/responses/204/content").isMissingNode());
        assertTrue(paths.at("/~1api~1movies~1{id}/delete/responses/204/content").isMissingNode());
        assertTrue(paths.at("/~1api~1users~1me~1favorites/delete/responses/204/content").isMissingNode());
    }

    /** Los 429 declaran la cabecera {@code Retry-After} (segundos, entero de al menos 1). */
    @Test
    void los429DocumentanLaCabeceraRetryAfter() throws Exception {
        JsonNode paths = apiDocs().path("paths");

        for (String path : List.of("/api/auth/login", "/api/users")) {
            JsonNode header = paths.path(path).path("post").path("responses").path("429")
                    .path("headers").path("Retry-After");

            assertFalse(header.isMissingNode(), () -> "Falta Retry-After en el 429 de " + path);
            assertTrue(header.path("required").asBoolean(), header::toString);
            assertFalse(header.path("description").asText().isBlank());
            assertEquals("integer", header.at("/schema/type").asText());
            assertEquals(1, header.at("/schema/minimum").asInt());
        }
    }

    // ------------------------------------------------------------------
    // Regla general para todas las rutas (incluidas las futuras)
    // ------------------------------------------------------------------

    /**
     * Recorre todas las operaciones: cada 4xx/5xx debe documentar un cuerpo
     * JSON {@code ErrorResponse} y nada más, y ninguna 2xx debe documentar
     * {@code ErrorResponse}. Si se añade un endpoint nuevo, queda cubierto sin
     * tocar este test.
     */
    @Test
    void ningunaRespuestaDeErrorDocumentaOtroEsquema() throws Exception {
        JsonNode paths = apiDocs().path("paths");
        List<String> problems = new ArrayList<>();
        int errorResponses = 0;

        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                JsonNode responses = operation.getValue().path("responses");
                for (Map.Entry<String, JsonNode> response : responses.properties()) {
                    String where = operation.getKey().toUpperCase() + " " + path.getKey() + " " + response.getKey();
                    JsonNode content = response.getValue().path("content");

                    if (isError(response.getKey())) {
                        errorResponses++;
                        if (!fieldNames(content).equals(Set.of(JSON))) {
                            problems.add(where + ": debe tener solo contenido " + JSON + " y tiene " + content);
                        } else if (!ERROR_REF.equals(content.path(JSON).path("schema").path("$ref").asText())
                                || content.path(JSON).path("schema").size() != 1) {
                            problems.add(where + ": el esquema debe ser solo ErrorResponse y es "
                                    + content.path(JSON).path("schema"));
                        }
                    } else if (content.toString().contains(ERROR_REF)) {
                        problems.add(where + ": una respuesta correcta no debe documentar ErrorResponse");
                    }
                }
            }
        }

        assertTrue(errorResponses > 40, "Se esperaban más de 40 respuestas de error y hay " + errorResponses);
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    // ------------------------------------------------------------------
    // El esquema describe el JSON que se envía de verdad
    // ------------------------------------------------------------------

    /**
     * Compara el esquema documentado con dos errores reales, uno por cada
     * serializador de la API: el 401 sin token lo escribe
     * {@code SecurityErrorResponseWriter} (Jackson 2) y el 400 de validación
     * del login, {@code GlobalExceptionHandler} (Jackson 3). Todo campo del
     * JSON real debe estar documentado y todo campo obligatorio del esquema
     * debe venir en el JSON.
     */
    @Test
    void elEsquemaDescribeElJsonRealDeLosErrores() throws Exception {
        JsonNode schema = apiDocs().at("/components/schemas/ErrorResponse");
        Set<String> documented = fieldNames(schema.path("properties"));
        Set<String> required = new HashSet<>();
        schema.path("required").forEach(node -> required.add(node.asText()));

        JsonNode unauthorized = json(mockMvc.perform(get("/api/users/me")).andReturn().getResponse());
        JsonNode invalidLogin = json(mockMvc.perform(post("/api/auth/login")
                        .with(request -> {
                            request.setRemoteAddr("10.231.0.1");
                            return request;
                        })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"no-es-email\",\"password\":\"\"}"))
                .andReturn().getResponse());

        assertEquals("VALIDATION_ERROR", invalidLogin.path("code").asText());
        assertTrue(invalidLogin.has("validationErrors"), invalidLogin::toString);

        for (JsonNode body : List.of(unauthorized, invalidLogin)) {
            Set<String> sent = fieldNames(body);
            assertTrue(documented.containsAll(sent), () -> "Campos sin documentar en " + body);
            assertTrue(sent.containsAll(required), () -> "Faltan campos obligatorios en " + body);
        }
        assertFalse(unauthorized.has("validationErrors"));
        assertFalse(unauthorized.has("remainingAttempts"));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private JsonNode apiDocs() throws Exception {
        return json(mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse());
    }

    private JsonNode json(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * {@code $ref} del esquema de una respuesta, sea cual sea su tipo de
     * contenido (las correctas usan {@code *}{@code /*}; las de error,
     * {@code application/json}). Cadena vacía si no tiene contenido.
     */
    private static String schemaRef(JsonNode paths, String path, String method, String code) {
        JsonNode content = paths.path(path).path(method).path("responses").path(code).path("content");
        if (!content.elements().hasNext()) {
            return "";
        }
        return content.elements().next().path("schema").path("$ref").asText();
    }

    private static boolean isError(String responseCode) {
        return responseCode.startsWith("4") || responseCode.startsWith("5");
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new TreeSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
