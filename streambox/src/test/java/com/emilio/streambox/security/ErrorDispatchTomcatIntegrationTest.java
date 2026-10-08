package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * El despacho de error de Tomcat ({@code DispatcherType.ERROR}, el reenvío a
 * {@code /error} tras un {@code sendError} o una excepción no controlada) llega
 * al cliente con su código real y en el formato {@code ErrorResponse} de la API,
 * siempre en JSON; y {@code /error} sigue cerrado a quien lo pida directamente
 * sin token. Probado contra el Tomcat embebido real: MockMvc no hace el despacho
 * de error.
 *
 * <p>
 * <b>Bugs que protege.</b>
 * </p>
 * <ol>
 * <li><b>401 falso.</b> Si una excepción escapaba de un filtro (fuera de Spring
 * MVC, donde {@code GlobalExceptionHandler} no llega), Tomcat reenviaba la
 * petición a {@code /error}. Ese reenvío pasaba por la autorización sin el
 * usuario del token ({@code JwtAuthenticationFilter} es un
 * {@code OncePerRequestFilter} y no actúa en el despacho de error), así que
 * {@code anyRequest().authenticated()} lo rechazaba: el cliente veía un 401
 * {@code INVALID_CREDENTIALS} con {@code "path":"/error"} en lugar del 500, y el
 * frontend cerraba la sesión. {@code SecurityConfig} permite ahora el despacho
 * de error; sin ese permiso, los tests del 500 fallan con 401.</li>
 * <li><b>Cuerpo que no era de la API.</b> Con el despacho ya permitido,
 * respondía el {@code BasicErrorController} de Spring Boot: un JSON sin
 * {@code code} ni mensaje en español ({@code timestamp}, {@code status},
 * {@code error}, {@code path}) o, con {@code Accept: text/html}, su página HTML
 * «whitelabel». Lo arregla {@code ApiErrorController}; sin él fallan todos los
 * tests que comprueban {@code code} o {@code message}, el de {@code text/html}
 * y el de {@code GET /error} con token.</li>
 * </ol>
 *
 * <p>
 * <b>Cómo se provocan los fallos.</b> Una configuración solo de este test añade
 * dos filtros de servlet detrás de la cadena de Spring Security: uno lanza una
 * excepción en {@value #BOOM_PATH} y otro hace {@code sendError} con el estado
 * que indica el último tramo de la ruta ({@value #SEND_ERROR_PATH}{@code /404},
 * por ejemplo). Ninguna de esas rutas existe en la aplicación. Como van detrás
 * de la seguridad, la petición ya ha pasado la autorización con el token: es
 * exactamente el caso «token válido y error real enmascarado».
 * </p>
 *
 * <p>
 * Sin {@code @Transactional}: el servidor atiende en otro hilo y solo ve datos
 * confirmados. El usuario de la prueba (dominio {@value #DOMAIN}) se borra
 * antes y después de cada test.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ErrorDispatchTomcatIntegrationTest {

    private static final String DOMAIN = "@error-dispatch-tomcat.test";

    /** Ruta en la que el filtro de prueba lanza la excepción. */
    static final String BOOM_PATH = "/api/prueba-fallo-en-filtro";

    /** Prefijo de las rutas en las que el filtro de prueba hace {@code sendError}. */
    static final String SEND_ERROR_PATH = "/api/prueba-send-error";

    /** Mensaje de la excepción del filtro: no debe llegar nunca al cliente. */
    static final String EXCEPTION_MESSAGE = "Fallo simulado en un filtro (detalle interno que no debe salir)";

    /** Mensaje del {@code sendError} del filtro: tampoco debe llegar al cliente. */
    static final String SEND_ERROR_MESSAGE = "Detalle interno de sendError que no debe salir";

    /** Mensaje genérico de los 5xx de la API ({@code GenericHttpError}). */
    private static final String INTERNAL_ERROR_MESSAGE =
            "Se ha producido un error interno. Inténtalo de nuevo más tarde";

    @Value("${local.server.port}") private int port;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String userToken;

    /**
     * Filtros de servlet solo para este test. El orden 0 los coloca detrás de
     * la cadena de Spring Security (orden -100), como cualquier filtro de la
     * aplicación, y {@code addUrlPatterns} limita cada uno a sus rutas.
     */
    @TestConfiguration
    static class FailingFiltersConfig {

        @Bean
        FilterRegistrationBean<Filter> failingFilter() {
            // Solo se registra para BOOM_PATH, así que no necesita mirar la
            // ruta: siempre falla.
            Filter filter = (ServletRequest request, ServletResponse response, FilterChain chain) -> {
                throw new IllegalStateException(EXCEPTION_MESSAGE);
            };
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
            registration.addUrlPatterns(BOOM_PATH);
            registration.setOrder(0);
            return registration;
        }

        @Bean
        FilterRegistrationBean<Filter> sendErrorFilter() {
            // SEND_ERROR_PATH/<estado>: responde sendError(<estado>, mensaje
            // interno), como haría Tomcat o un filtro de terceros.
            Filter filter = (ServletRequest request, ServletResponse response, FilterChain chain) -> {
                String uri = ((HttpServletRequest) request).getRequestURI();
                int status = Integer.parseInt(uri.substring(uri.lastIndexOf('/') + 1));
                sendError((HttpServletResponse) response, status);
            };
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
            registration.addUrlPatterns(SEND_ERROR_PATH + "/*");
            registration.setOrder(0);
            return registration;
        }

        private static void sendError(HttpServletResponse response, int status) throws IOException {
            response.sendError(status, SEND_ERROR_MESSAGE);
        }
    }

    @BeforeEach
    void createUser() {
        deleteUsersOfThisSuite();
        User user = new User();
        user.setUsername("errordispatchuser");
        user.setEmail("errordispatchuser" + DOMAIN);
        user.setPassword(passwordEncoder.encode("Contraseña-Larga-Correcta-2026"));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        userToken = jwtService.generateToken(userRepository.save(user));
    }

    @AfterEach
    void deleteUsersOfThisSuite() {
        userRepository.deleteAll(userRepository.findAll().stream()
                .filter(user -> user.getEmail().endsWith(DOMAIN))
                .toList());
    }

    /**
     * Con un token válido, una excepción no controlada en un filtro llega al
     * cliente como 500 {@code INTERNAL_ERROR} en el formato de la API, con la
     * ruta original (no {@code /error}) y sin el mensaje ni la traza.
     */
    @Test
    void unaExcepcionEnUnFiltroDa500YNo401() throws Exception {
        HttpResponse<String> response = send(request(BOOM_PATH)
                .header("Authorization", "Bearer " + userToken)
                .GET());

        assertInternalError(response);
    }

    /**
     * Un navegador manda {@code Accept: text/html}. Antes recibía la página
     * «whitelabel» de Spring Boot; ahora, el mismo error JSON que cualquier
     * cliente.
     */
    @Test
    void conAcceptHtmlElErrorSigueSiendoJsonYNoLaPaginaWhitelabel() throws Exception {
        HttpResponse<String> response = send(request(BOOM_PATH)
                .setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Authorization", "Bearer " + userToken)
                .GET());

        assertInternalError(response);
        assertFalse(response.body().contains("<html"), "no debe ser HTML: " + response.body());
        assertFalse(response.body().contains("Whitelabel"), "no debe ser la página whitelabel");
    }

    /**
     * El despacho de error conserva el método de la petición original: el
     * controlador de errores atiende cualquiera de ellos (si no, el propio
     * reenvío daría un 405 en lugar del 500).
     */
    @ParameterizedTest
    @ValueSource(strings = { "POST", "PUT", "PATCH", "DELETE", "OPTIONS" })
    void elErrorEsElMismoConCualquierMetodo(String method) throws Exception {
        HttpResponse<String> response = send(request(BOOM_PATH)
                .header("Authorization", "Bearer " + userToken)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString("{}")));

        assertInternalError(response);
    }

    /** {@code HEAD} no lleva cuerpo, pero el estado y el tipo son los mismos. */
    @Test
    void conHeadDa500EnJsonSinCuerpo() throws Exception {
        HttpResponse<String> response = send(request(BOOM_PATH)
                .header("Authorization", "Bearer " + userToken)
                .method("HEAD", HttpRequest.BodyPublishers.noBody()));

        assertEquals(500, response.statusCode());
        assertJsonContentType(response);
        assertEquals("", response.body());
    }

    /**
     * Un {@code sendError} con cualquier estado llega con ese estado y el
     * {@code code} de la misma tabla que usa {@code GlobalExceptionHandler}, con
     * la ruta original y sin el mensaje que se pasó a {@code sendError}.
     */
    @ParameterizedTest
    @CsvSource({
            "400, MALFORMED_REQUEST",
            "401, INVALID_CREDENTIALS",
            "403, ACCESS_DENIED",
            "404, RESOURCE_NOT_FOUND",
            "405, METHOD_NOT_ALLOWED",
            "406, NOT_ACCEPTABLE",
            "413, MALFORMED_REQUEST",
            "415, UNSUPPORTED_MEDIA_TYPE",
            "503, INTERNAL_ERROR" })
    void unSendErrorLlegaConSuEstadoYSuCode(int status, String code) throws Exception {
        String path = SEND_ERROR_PATH + "/" + status;
        HttpResponse<String> response = send(request(path)
                .header("Authorization", "Bearer " + userToken)
                .GET());

        assertEquals(status, response.statusCode(), "cuerpo: " + response.body());
        assertJsonContentType(response);
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(status, body.path("status").asInt(), response.body());
        assertEquals(code, body.path("code").asText(), response.body());
        assertEquals(path, body.path("path").asText(), response.body());
        assertFalse(body.path("message").asText().isBlank(), response.body());
        assertNoInternalDetails(response);
    }

    /**
     * Permitir el despacho de error no abre la ruta {@code /error}: pedirla
     * directamente es un despacho normal ({@code REQUEST}) y, sin token, sigue
     * siendo el 401 de siempre.
     */
    @Test
    void pedirErrorDirectamenteSinTokenSigueDando401() throws Exception {
        HttpResponse<String> response = send(request("/error").GET());

        assertEquals(401, response.statusCode(), "cuerpo: " + response.body());
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals("INVALID_CREDENTIALS", body.path("code").asText(), response.body());
    }

    /**
     * Con token, pedir {@code /error} directamente no describe ningún error
     * real: es una ruta que no pertenece a la API y da 404, no un 500 (que
     * además llenaría el log de errores falsos).
     */
    @Test
    void pedirErrorDirectamenteConTokenDa404() throws Exception {
        HttpResponse<String> response = send(request("/error")
                .header("Authorization", "Bearer " + userToken)
                .GET());

        assertEquals(404, response.statusCode(), "cuerpo: " + response.body());
        assertJsonContentType(response);
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals("RESOURCE_NOT_FOUND", body.path("code").asText(), response.body());
        assertEquals("/error", body.path("path").asText(), response.body());
    }

    /**
     * El 401 legítimo no cambia: sin token, la ruta del filtro que falla ni
     * siquiera llega a él (la autorización la corta antes).
     */
    @Test
    void sinTokenLaRutaProtegidaSigueDando401() throws Exception {
        HttpResponse<String> response = send(request(BOOM_PATH).GET());

        assertEquals(401, response.statusCode(), "cuerpo: " + response.body());
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals("INVALID_CREDENTIALS", body.path("code").asText(), response.body());
        assertEquals(BOOM_PATH, body.path("path").asText(), response.body());
    }

    /**
     * Un error que nace en Tomcat antes de la seguridad también lleva
     * {@code X-Content-Type-Options: nosniff}, una sola vez.
     *
     * <p>
     * <b>Bug que protege.</b> Tomcat rechaza {@code TRACE} por su cuenta
     * ({@code allowTrace=false}) con un {@code sendError(405)}, sin pasar por los
     * filtros. En el despacho de error que sigue, el {@code HeaderWriterFilter}
     * de Spring Security no actúa (es un {@code OncePerRequestFilter} y no filtra
     * ese despacho), así que el 405 JSON de {@code ApiErrorController} salía sin
     * {@code nosniff}. Sin el arreglo falla con la lista de valores vacía.
     * </p>
     */
    @Test
    void unTraceAnonimoDa405EnJsonConNosniff() throws Exception {
        String path = "/api/genres";
        HttpResponse<String> response = send(request(path)
                .method("TRACE", HttpRequest.BodyPublishers.noBody()));

        assertEquals(405, response.statusCode(), "cuerpo: " + response.body());
        assertJsonContentType(response);
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals("METHOD_NOT_ALLOWED", body.path("code").asText(), response.body());
        assertEquals(path, body.path("path").asText(), response.body());
    }

    /** El controlador de errores no es un endpoint de la API: no sale en el OpenAPI. */
    @Test
    void elControladorDeErroresNoApareceEnLaDocumentacion() throws Exception {
        HttpResponse<String> response = send(request("/v3/api-docs")
                .header("Authorization", "Bearer " + userToken)
                .GET());

        assertEquals(200, response.statusCode(), "cuerpo: " + response.body());
        JsonNode paths = objectMapper.readTree(response.body()).path("paths");
        assertTrue(paths.isObject() && paths.size() > 0, "el OpenAPI debe tener rutas: " + response.body());
        assertFalse(paths.has("/error"), "/error no debe documentarse");
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** 500 {@code INTERNAL_ERROR} de la API, en JSON, con la ruta original y sin detalles internos. */
    private void assertInternalError(HttpResponse<String> response) throws Exception {
        assertEquals(500, response.statusCode(), "cuerpo: " + response.body());
        assertJsonContentType(response);
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(500, body.path("status").asInt(), response.body());
        assertEquals("Internal Server Error", body.path("error").asText(), response.body());
        assertEquals("INTERNAL_ERROR", body.path("code").asText(), response.body());
        assertEquals(INTERNAL_ERROR_MESSAGE, body.path("message").asText(), response.body());
        assertEquals(BOOM_PATH, body.path("path").asText(), response.body());
        assertFalse(body.path("timestamp").asText().isBlank(), response.body());
        // Exactamente los campos de ErrorResponse (los opcionales se omiten
        // aquí): nada de "trace", "exception", "errors"...
        Set<String> fields = new TreeSet<>();
        body.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("timestamp", "status", "error", "code", "message", "path"), fields,
                response.body());
        assertNoInternalDetails(response);
    }

    /**
     * {@code Content-Type} JSON y {@code X-Content-Type-Options: nosniff} (una
     * sola vez): el cuerpo repite la ruta que envió el cliente, así que ningún
     * navegador debe intentar interpretarlo como otra cosa.
     */
    private static void assertJsonContentType(HttpResponse<String> response) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.startsWith("application/json"), "el error debe ir en JSON: " + contentType);
        assertEquals(List.of("nosniff"),response.headers().allValues("X-Content-Type-Options"));
    }

    /** Ni el mensaje de la excepción o del sendError, ni la clase, ni la traza. */
    private static void assertNoInternalDetails(HttpResponse<String> response) {
        String body = response.body();
        assertFalse(body.contains("Fallo simulado"), "no debe filtrar el mensaje de la excepción: " + body);
        assertFalse(body.contains("Detalle interno"), "no debe filtrar el mensaje de sendError: " + body);
        assertFalse(body.contains("IllegalStateException"), "no debe filtrar la excepción: " + body);
        assertFalse(body.contains("trace"), "no debe incluir la traza: " + body);
        assertFalse(body.contains("at com.emilio"), "no debe incluir la traza: " + body);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json");
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
