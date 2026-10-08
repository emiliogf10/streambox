package com.emilio.streambox.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Los errores de {@link GlobalExceptionHandler} salen siempre en JSON y con su
 * código real, pida el cliente el formato que pida en {@code Accept}, probado
 * contra el Tomcat embebido real.
 *
 * <p>
 * <b>Bug que protege.</b> Con un token válido y {@code Accept: application/yaml}
 * (o XML, o HTML), cualquier error de la API llegaba al cliente como un
 * <b>401 falso</b> con {@code "path":"/error"}: un 404 de película, un 400 de
 * validación, el 406 de un listado y el 415 de un login en YAML. La cadena era
 * esta: el manejador devolvía un {@code ErrorResponse} que Spring intentaba
 * escribir en el tipo pedido; ningún conversor sabía, así que el propio
 * manejador fallaba ({@code Failure in @ExceptionHandler} en el log), Spring
 * acababa en {@code sendError}, Tomcat reenviaba a {@code /error} y allí la
 * seguridad, sin el token ya procesado, respondía 401.
 * </p>
 *
 * <p>
 * <b>Por qué con Tomcat y no con MockMvc.</b> MockMvc no hace el reenvío a
 * {@code /error}: con él se veía un 406 que ningún cliente real recibía. Sin el
 * arreglo, todos los casos con un {@code Accept} que no es JSON reciben 401
 * {@code INVALID_CREDENTIALS} con {@code path} {@code /error}; el caso
 * {@code application/json} es el control y pasa con y sin arreglo.
 * </p>
 *
 * <p>
 * Sin {@code @Transactional}: el servidor atiende en otro hilo y solo ve datos
 * confirmados. El administrador de la prueba (dominio {@value #DOMAIN}) se
 * borra antes y después de cada test; las peticiones no crean nada más (el alta
 * del género es inválida y el login se rechaza antes del controlador).
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ErrorResponseAlwaysJsonTomcatIntegrationTest {

    private static final String DOMAIN = "@accept-json-tomcat.test";

    /** Id que ninguna película de los tests llega a tener. */
    private static final long MISSING_MOVIE_ID = 987_654_321L;

    @Value("${local.server.port}") private int port;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String adminToken;

    @BeforeEach
    void createAdmin() {
        deleteUsersOfThisSuite();
        User admin = new User();
        admin.setUsername("acceptjsonadmin");
        admin.setEmail("acceptjsonadmin" + DOMAIN);
        admin.setPassword(passwordEncoder.encode("Contraseña-Larga-Correcta-2026"));
        admin.setRole(Role.ADMIN);
        admin.setCreatedAt(Instant.now());
        adminToken = jwtService.generateToken(userRepository.save(admin));
    }

    @AfterEach
    void deleteUsersOfThisSuite() {
        userRepository.deleteAll(userRepository.findAll().stream()
                .filter(user -> user.getEmail().endsWith(DOMAIN))
                .toList());
    }

    /** Un 404 de dominio ({@code ResourceNotFoundException}) sigue siendo 404 en JSON. */
    @ParameterizedTest
    @ValueSource(strings = { "application/yaml", "application/xml", "text/html", "application/json" })
    void unaPeliculaInexistenteDa404EnJson(String accept) throws Exception {
        String path = "/api/movies/" + MISSING_MOVIE_ID;

        HttpResponse<String> response = send(authenticated(path, accept).GET());

        assertJsonError(response, 404, "RESOURCE_NOT_FOUND", path);
    }

    /**
     * Una ruta que no existe (la traduce {@code handleExceptionInternal}, el
     * camino de las excepciones estándar de Spring MVC) también es 404 en JSON.
     */
    @ParameterizedTest
    @ValueSource(strings = { "application/yaml", "application/xml", "text/html" })
    void unaRutaInexistenteDa404EnJson(String accept) throws Exception {
        String path = "/api/ruta-que-no-existe";

        HttpResponse<String> response = send(authenticated(path, accept).GET());

        assertJsonError(response, 404, "RESOURCE_NOT_FOUND", path);
    }

    /** Un 400 de validación del cuerpo conserva su código y el detalle por campo. */
    @ParameterizedTest
    @ValueSource(strings = { "application/yaml", "application/xml", "text/html" })
    void unAltaInvalidaDa400EnJson(String accept) throws Exception {
        HttpResponse<String> response = send(authenticated("/api/genres", accept)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"\"}")));

        JsonNode body = assertJsonError(response, 400, "VALIDATION_ERROR", "/api/genres");
        assertTrue(body.path("validationErrors").has("name"), response.body());
    }

    /**
     * Pedir un listado en un formato que la API no produce es un 406 con su
     * propio código ({@code NOT_ACCEPTABLE}), explicado en JSON.
     */
    @ParameterizedTest
    @ValueSource(strings = { "application/yaml", "application/xml", "text/html" })
    void unListadoEnOtroFormatoDa406EnJson(String accept) throws Exception {
        HttpResponse<String> response = send(authenticated("/api/genres", accept).GET());

        assertJsonError(response, 406, "NOT_ACCEPTABLE", "/api/genres");
    }

    /** El login en YAML pidiendo la respuesta en YAML: 415, no un 401 falso. */
    @ParameterizedTest
    @ValueSource(strings = { "application/yaml", "application/xml", "text/html" })
    void elLoginEnYamlDa415EnJson(String accept) throws Exception {
        HttpResponse<String> response = send(request("/api/auth/login", accept)
                .header("Content-Type", "application/yaml")
                .POST(HttpRequest.BodyPublishers.ofString("email: nadie@test.com\npassword: mala\n")));

        assertJsonError(response, 415, "UNSUPPORTED_MEDIA_TYPE", "/api/auth/login");
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private HttpRequest.Builder request(String path, String accept) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", accept);
    }

    /**
     * Petición con el token del administrador. Lleva también la cabecera
     * anti-CSRF por si alguna petición no segura la necesitara; con el token en
     * {@code Authorization} no hace falta, pero así el resultado solo depende
     * del {@code Accept}.
     */
    private HttpRequest.Builder authenticated(String path, String accept) {
        return request(path, accept)
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Requested-With", "StreamBox");
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Comprueba estado, tipo JSON, código y ruta real (no {@code /error}) del
     * error, y devuelve el cuerpo para comprobaciones adicionales.
     */
    private JsonNode assertJsonError(HttpResponse<String> response, int status, String code, String path)
            throws Exception {
        assertEquals(status, response.statusCode(), "cuerpo: " + response.body());
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.startsWith("application/json"), "el error debe ir en JSON: " + contentType);
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(code, body.path("code").asText(), response.body());
        assertEquals(status, body.path("status").asInt(), response.body());
        assertEquals(path, body.path("path").asText(), response.body());
        return body;
    }
}
