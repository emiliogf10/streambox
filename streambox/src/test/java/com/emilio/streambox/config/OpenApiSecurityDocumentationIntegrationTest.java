package com.emilio.streambox.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * La seguridad documentada en el OpenAPI ({@code /v3/api-docs}) coincide con
 * la real: cookie {@code streambox_token} o Bearer, y 403
 * {@code CSRF_REJECTED} en las operaciones no seguras autenticadas.
 *
 * <p>
 * Desajuste que protege: el documento solo declaraba {@code bearerAuth}, así
 * que no reflejaba la autenticación principal (la cookie del navegador), y el
 * 403 {@code CSRF_REJECTED} de {@code JwtAuthenticationFilter} solo aparecía
 * en refresh y logout. Lo corrigen {@link OpenApiConfig} y
 * {@link CookieAuthOperationCustomizer}. Las comprobaciones recorren todas las
 * rutas, así que cubren también los endpoints futuros.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class OpenApiSecurityDocumentationIntegrationTest {

    private static final String ERROR_REF = "#/components/schemas/ErrorResponse";

    /** Métodos que la defensa CSRF deja pasar. */
    private static final Set<String> SAFE_METHODS = Set.of("get", "head", "options", "trace");

    /** Operaciones públicas: no declaran seguridad ni deben recibir el 403 CSRF global. */
    private static final List<String[]> PUBLIC_OPERATIONS = List.of(
            new String[] {"/api/auth/login", "post"},
            new String[] {"/api/users", "post"},
            new String[] {"/api/auth/refresh", "post"},
            new String[] {"/api/auth/logout", "post"});

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void declaraLaCookieDeSesionComoEsquemaApiKeyYConservaElBearer() throws Exception {
        JsonNode schemes = apiDocs().at("/components/securitySchemes");

        JsonNode cookie = schemes.path("cookieAuth");
        assertEquals("apiKey", cookie.path("type").asText(), cookie::toString);
        assertEquals("cookie", cookie.path("in").asText());
        assertEquals("streambox_token", cookie.path("name").asText());
        String description = cookie.path("description").asText();
        assertTrue(description.contains("X-Requested-With: StreamBox"), description);
        assertTrue(description.contains("CSRF_REJECTED"), description);

        JsonNode bearer = schemes.path("bearerAuth");
        assertEquals("http", bearer.path("type").asText(), bearer::toString);
        assertEquals("bearer", bearer.path("scheme").asText());
        assertEquals("JWT", bearer.path("bearerFormat").asText());
    }

    /**
     * Toda operación autenticada acepta cualquiera de las dos formas: dos
     * requisitos alternativos, la cookie primero (es la principal).
     */
    @Test
    void lasOperacionesAutenticadasAceptanCookieOBearer() throws Exception {
        List<String> problems = new ArrayList<>();
        int secured = 0;

        for (Operation operation : operations(apiDocs())) {
            JsonNode security = operation.node().path("security");
            if (security.isMissingNode()) {
                continue;
            }
            secured++;
            String alternatives = security.toString();
            if (!"[{\"cookieAuth\":[]},{\"bearerAuth\":[]}]".equals(alternatives)) {
                problems.add(operation + ": " + alternatives);
            }
        }

        assertTrue(secured > 25, "Se esperaban más de 25 operaciones autenticadas y hay " + secured);
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    /**
     * Las operaciones no seguras autenticadas documentan el 403
     * {@code CSRF_REJECTED} con el cuerpo {@code ErrorResponse}; las seguras
     * no. Si ya tenían un 403 (falta de permisos), conservan su texto y se les
     * añade el caso CSRF.
     */
    @Test
    void lasOperacionesNoSegurasAutenticadasDocumentanElRechazoCsrf() throws Exception {
        List<String> problems = new ArrayList<>();
        int unsafe = 0;

        for (Operation operation : operations(apiDocs())) {
            if (operation.node().path("security").isMissingNode()) {
                continue;
            }
            JsonNode forbidden = operation.node().at("/responses/403");
            String description = forbidden.path("description").asText();

            if (SAFE_METHODS.contains(operation.method())) {
                if (description.contains("CSRF_REJECTED")) {
                    problems.add(operation + ": una operación segura no exige la cabecera CSRF");
                }
                continue;
            }
            unsafe++;
            if (!description.contains("CSRF_REJECTED") || !description.contains("X-Requested-With: StreamBox")) {
                problems.add(operation + ": falta el 403 CSRF_REJECTED (" + description + ")");
            } else if (description.indexOf("CSRF_REJECTED") != description.lastIndexOf("CSRF_REJECTED")) {
                problems.add(operation + ": el caso CSRF está repetido (" + description + ")");
            } else if (!ERROR_REF.equals(forbidden.at("/content/application~1json/schema/$ref").asText())) {
                problems.add(operation + ": el 403 debe documentar ErrorResponse");
            }
        }

        assertTrue(unsafe > 15, "Se esperaban más de 15 operaciones no seguras autenticadas y hay " + unsafe);
        assertTrue(problems.isEmpty(), () -> String.join("\n", problems));
    }

    /** Un 403 que ya existía (falta de permisos de ADMIN) conserva su descripción original. */
    @Test
    void el403DeAdminConservaSuTextoYAnadeElCasoCsrf() throws Exception {
        String description = apiDocs().at("/paths/~1api~1movies/post/responses/403/description").asText();

        assertTrue(description.startsWith("El usuario no tiene permisos de administrador"), description);
        assertTrue(description.contains("También 403 con código CSRF_REJECTED"), description);
    }

    /** Un endpoint de USER sin 403 propio (favoritos) recibe uno nuevo, solo con el caso CSRF. */
    @Test
    void unEndpointDeUsuarioSin403RecibeElDeCsrf() throws Exception {
        JsonNode responses = apiDocs().at("/paths/~1api~1users~1me~1favorites~1{movieId}/post/responses");

        assertTrue(responses.path("403").path("description").asText().startsWith("Código CSRF_REJECTED"),
                responses::toString);
        // Queda en su sitio (401, 403, 404), no al final del documento.
        List<String> codes = new ArrayList<>();
        responses.fieldNames().forEachRemaining(codes::add);
        assertEquals(List.of("204", "401", "403", "404", "409"), codes);
    }

    /**
     * Login y registro no exigen la cabecera (no tienen sesión que proteger) y
     * refresh y logout ya documentan su propio 403: ninguno declara seguridad
     * ni recibe el texto global.
     */
    @Test
    void lasOperacionesPublicasNoRecibenNiSeguridadNiElTextoGlobal() throws Exception {
        JsonNode paths = apiDocs().path("paths");

        for (String[] publicOperation : PUBLIC_OPERATIONS) {
            JsonNode operation = paths.path(publicOperation[0]).path(publicOperation[1]);
            String where = publicOperation[1].toUpperCase() + " " + publicOperation[0];

            assertFalse(operation.isMissingNode(), where);
            assertTrue(operation.path("security").isMissingNode(), () -> where + " no debe declarar seguridad");
            assertFalse(operation.at("/responses/403/description").asText()
                    .contains(CookieAuthOperationCustomizer.CSRF_APPENDIX), where);
            assertFalse(operation.at("/responses/403/description").asText()
                    .contains(CookieAuthOperationCustomizer.CSRF_DESCRIPTION), where);
        }
        assertTrue(paths.at("/~1api~1auth~1login/post/responses/403").isMissingNode());
        assertTrue(paths.at("/~1api~1users/post/responses/403").isMissingNode());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Una operación del documento: ruta, método HTTP (en minúsculas) y su nodo JSON. */
    private record Operation(String path, String method, JsonNode node) {

        @Override
        public String toString() {
            return method.toUpperCase() + " " + path;
        }
    }

    private static List<Operation> operations(JsonNode apiDocs) {
        List<Operation> operations = new ArrayList<>();
        for (Map.Entry<String, JsonNode> path : apiDocs.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> operation : path.getValue().properties()) {
                operations.add(new Operation(path.getKey(), operation.getKey(), operation.getValue()));
            }
        }
        return operations;
    }

    private JsonNode apiDocs() throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
    }
}
