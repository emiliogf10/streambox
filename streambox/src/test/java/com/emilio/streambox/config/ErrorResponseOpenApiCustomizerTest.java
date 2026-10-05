package com.emilio.streambox.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.SpecVersion;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;

/**
 * Pruebas unitarias de {@link ErrorResponseOpenApiCustomizer} sobre un
 * documento OpenAPI construido a mano, para cubrir casos que la API real no
 * tiene hoy (rangos {@code 4XX}, {@code default}, respuestas por
 * {@code $ref}, documento sin rutas) y la idempotencia.
 */
class ErrorResponseOpenApiCustomizerTest {

    private static final String ERROR_REF = "#/components/schemas/ErrorResponse";
    private static final String TOKEN_REF = "#/components/schemas/LoginResponse";

    private final ErrorResponseOpenApiCustomizer customizer = new ErrorResponseOpenApiCustomizer();

    @Test
    void sustituyeElCuerpoDeLosErroresYNoTocaLasRespuestasCorrectas() {
        ApiResponses responses = new ApiResponses()
                .addApiResponse("200", response("ok", TOKEN_REF))
                .addApiResponse("204", new ApiResponse().description("sin cuerpo"))
                .addApiResponse("401", response("credenciales", TOKEN_REF))
                .addApiResponse("404", new ApiResponse().description("void sin contenido"))
                .addApiResponse("500", response("interno", TOKEN_REF));
        OpenAPI openApi = document(responses);

        customizer.customise(openApi);

        assertEquals(TOKEN_REF, refOf(responses.get("200"), "*/*"));
        assertNull(responses.get("204").getContent());
        for (String code : Set.of("401", "404", "500")) {
            ApiResponse error = responses.get(code);
            assertEquals(Set.of("application/json"), error.getContent().keySet(), code);
            assertEquals(ERROR_REF, refOf(error, "application/json"), code);
        }
        assertEquals("credenciales", responses.get("401").getDescription());
        assertNull(responses.get("401").getHeaders(), "Solo los 429 llevan Retry-After");
    }

    @Test
    void registraElEsquemaErrorResponseConSusCampos() {
        OpenAPI openApi = document(new ApiResponses().addApiResponse("400", new ApiResponse()));

        customizer.customise(openApi);

        Schema<?> schema = openApi.getComponents().getSchemas().get("ErrorResponse");
        assertNotNull(schema);
        assertEquals(Set.of("timestamp", "status", "error", "code", "message", "path",
                "validationErrors", "remainingAttempts"), schema.getProperties().keySet());
        assertEquals(Set.of("timestamp", "status", "error", "code", "message", "path"),
                Set.copyOf(schema.getRequired()));
    }

    @Test
    void los429LlevanLaCabeceraRetryAfter() {
        ApiResponses responses = new ApiResponses().addApiResponse("429", new ApiResponse().description("límite"));

        customizer.customise(document(responses));

        var header = responses.get("429").getHeaders().get("Retry-After");
        assertNotNull(header);
        assertTrue(header.getRequired());
        assertEquals(BigDecimal.ONE, header.getSchema().getMinimum());
        assertEquals(ERROR_REF, refOf(responses.get("429"), "application/json"));
    }

    @Test
    void cubreLosRangos4XXy5XXPeroNoDefault() {
        ApiResponses responses = new ApiResponses()
                .addApiResponse("4XX", new ApiResponse().description("cliente"))
                .addApiResponse("5XX", new ApiResponse().description("servidor"))
                .addApiResponse("default", response("cualquiera", TOKEN_REF));

        customizer.customise(document(responses));

        assertEquals(ERROR_REF, refOf(responses.get("4XX"), "application/json"));
        assertEquals(ERROR_REF, refOf(responses.get("5XX"), "application/json"));
        assertEquals(TOKEN_REF, refOf(responses.get("default"), "*/*"));
    }

    /** Una respuesta que es un {@code $ref} a {@code components.responses} no admite contenido propio. */
    @Test
    void noTocaLasRespuestasQueSonUnaReferencia() {
        ApiResponse reference = new ApiResponse().$ref("#/components/responses/NotFound");
        ApiResponses responses = new ApiResponses().addApiResponse("404", reference);

        customizer.customise(document(responses));

        assertNull(reference.getContent());
    }

    @Test
    void esIdempotente() {
        ApiResponses responses = new ApiResponses()
                .addApiResponse("401", response("credenciales", TOKEN_REF))
                .addApiResponse("429", new ApiResponse().description("límite"));
        OpenAPI openApi = document(responses);

        customizer.customise(openApi);
        String firstTime = openApi.toString();
        customizer.customise(openApi);

        assertEquals(firstTime, openApi.toString());
        assertEquals(1, responses.get("429").getHeaders().size());
        assertEquals(1, responses.get("401").getContent().size());
    }

    /** Si springdoc ya generó un esquema con ese nombre, se respeta el suyo. */
    @Test
    void noSobrescribeEsquemasQueYaExistian() {
        Schema<?> existing = new ObjectSchema().description("de springdoc");
        OpenAPI openApi = document(new ApiResponses().addApiResponse("400", new ApiResponse()));
        openApi.getComponents().addSchemas("ErrorResponse", existing);

        customizer.customise(openApi);

        assertSame(existing, openApi.getComponents().getSchemas().get("ErrorResponse"));
    }

    @Test
    void unDocumentoSinRutasNoFallaNiCambia() {
        OpenAPI openApi = new OpenAPI(SpecVersion.V31);

        assertDoesNotThrow(() -> customizer.customise(openApi));
        assertNull(openApi.getComponents());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Documento OpenAPI 3.1 (lo que genera springdoc) con una sola operación. */
    private static OpenAPI document(ApiResponses responses) {
        return new OpenAPI(SpecVersion.V31)
                .components(new Components())
                .paths(new Paths().addPathItem("/api/prueba",
                        new PathItem().post(new Operation().responses(responses))));
    }

    /** Respuesta con contenido {@code *}{@code /*}, como las que rellena springdoc. */
    private static ApiResponse response(String description, String ref) {
        return new ApiResponse().description(description)
                .content(new Content().addMediaType("*/*", new MediaType().schema(new Schema<>().$ref(ref))));
    }

    private static String refOf(ApiResponse response, String mediaType) {
        return response.getContent().get(mediaType).getSchema().get$ref();
    }
}
