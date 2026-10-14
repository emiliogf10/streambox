package com.emilio.streambox.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * El OpenAPI documenta la búsqueda tolerante a erratas: el campo
 * {@code approximate} de las páginas y el parámetro {@code title} de las tres
 * búsquedas. El frontend (y quien lea {@code /swagger-ui}) depende de esa
 * descripción para saber que, con {@code approximate: true}, el orden es por
 * parecido y no el pedido.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OpenApiTitleSearchDocumentationIntegrationTest {

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** {@code approximate} es un booleano obligatorio y documentado en los dos esquemas de página. */
    @Test
    void lasPaginasDocumentanElCampoApproximate() throws Exception {
        JsonNode schemas = apiDocs().path("components").path("schemas");

        for (String schema : List.of("MoviePageResponse", "SeriesPageResponse")) {
            JsonNode field = schemas.path(schema).path("properties").path("approximate");

            assertEquals("boolean", field.path("type").asText(), schema);
            assertTrue(field.path("description").asText().contains("parecido"), schema);
            assertTrue(schemas.path(schema).path("required").toString().contains("\"approximate\""), schema);
        }
    }

    /** El parámetro {@code title} explica las dos reglas: sin acentos y, si no hay nada, parecidos. */
    @Test
    void elParametroTitleExplicaLaToleranciaAErratas() throws Exception {
        JsonNode paths = apiDocs().path("paths");

        for (String path : List.of("/api/movies/search", "/api/series/search", "/api/admin/series")) {
            String description = "";
            for (JsonNode parameter : paths.path(path).path("get").path("parameters")) {
                if ("title".equals(parameter.path("name").asText())) {
                    description = parameter.path("description").asText();
                }
            }

            assertTrue(description.contains("acentos"), path + ": " + description);
            assertTrue(description.contains("5 letras"), path + ": " + description);
            assertTrue(description.contains("approximate: true"), path + ": " + description);
        }
    }

    /**
     * Las restricciones del parámetro {@code title} (como mucho 150 caracteres
     * y sin caracteres de control) aparecen en su esquema, y el 400 de las
     * tres búsquedas las menciona: el cliente puede replicarlas sin leer el
     * código.
     */
    @Test
    void elParametroTitleDocumentaSuLongitudMaximaYEl400() throws Exception {
        JsonNode paths = apiDocs().path("paths");

        for (String path : List.of("/api/movies/search", "/api/series/search", "/api/admin/series")) {
            JsonNode schema = null;
            for (JsonNode parameter : paths.path(path).path("get").path("parameters")) {
                if ("title".equals(parameter.path("name").asText())) {
                    schema = parameter.path("schema");
                }
            }
            String badRequest = paths.path(path).path("get").path("responses").path("400")
                    .path("description").asText();

            assertTrue(schema != null, path);
            assertEquals(150, schema.path("maxLength").asInt(), path + ": " + schema);
            assertTrue(schema.path("pattern").asText().contains("\\p{Cc}"), path + ": " + schema);
            assertTrue(badRequest.contains("150 caracteres") && badRequest.contains("caracteres de control"),
                    path + ": " + badRequest);
        }
    }

    private JsonNode apiDocs() throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }
}
