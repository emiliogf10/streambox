package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Las peticiones que rechaza el cortafuegos HTTP de Spring Security
 * ({@code StrictHttpFirewall}) responden 400 {@code MALFORMED_REQUEST} en JSON,
 * con o sin token, probado contra el Tomcat embebido real.
 *
 * <p>
 * <b>Bug que protege.</b> Con un token ADMIN válido, {@code GET /api/genres;x=1},
 * {@code /api//genres}, {@code /api/genres/%2e%2e/x} o {@code /api/genres%25}
 * recibían un <b>401 falso</b> {@code INVALID_CREDENTIALS} con
 * {@code "path":"/error"}. El cortafuegos las rechaza (bien), pero el manejador
 * por defecto hacía {@code sendError(400)}; Tomcat reenviaba la petición a
 * {@code /error} y ese reenvío llegaba a la autorización sin autenticar
 * ({@code JwtAuthenticationFilter} no actúa en el despacho de error), así que
 * respondía el punto de entrada de los 401. El frontend, al ver un 401, cierra la
 * sesión: un error de formato echaba al usuario. Lo arregla
 * {@link JsonRequestRejectedHandler}, que escribe el 400 directamente.
 * </p>
 *
 * <p>
 * <b>Por qué con Tomcat y no con MockMvc.</b> MockMvc no hace el reenvío a
 * {@code /error}, y su petición simulada no reproduce la URI sin decodificar que
 * analiza el cortafuegos. Sin el arreglo, los casos con token fallan con 401
 * {@code INVALID_CREDENTIALS} y {@code path} {@code /error}; los casos sin token
 * fallan igual (el reenvío es anónimo en ambos casos).
 * </p>
 *
 * <p>
 * Sin {@code @Transactional}: el servidor atiende en otro hilo y solo ve datos
 * confirmados. El administrador de la prueba (dominio {@value #DOMAIN}) se
 * borra antes y después de cada test.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RequestRejectedTomcatIntegrationTest {

    private static final String DOMAIN = "@firewall-tomcat.test";

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
        admin.setUsername("firewalladmin");
        admin.setEmail("firewalladmin" + DOMAIN);
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

    /**
     * Con un token válido, una URL que el cortafuegos rechaza es un 400 de
     * petición mal formada, no un 401 que cerraría la sesión del usuario.
     */
    @ParameterizedTest
    @ValueSource(strings = { "/api/genres;x=1", "/api//genres", "/api/genres/%2e%2e/x", "/api/genres%25" })
    void conTokenUnaUrlRechazadaDa400EnJson(String path) throws Exception {
        HttpResponse<String> response = send(request(path)
                .header("Authorization", "Bearer " + adminToken)
                .GET());

        assertRejected(response, path);
    }

    /**
     * Sin token, lo mismo: el cortafuegos actúa antes que la autenticación, así
     * que el código depende de la URL y no de quién la pide.
     */
    @ParameterizedTest
    @ValueSource(strings = { "/api/genres;x=1", "/api//genres", "/api/genres/%2e%2e/x", "/api/genres%25" })
    void sinTokenUnaUrlRechazadaDa400EnJson(String path) throws Exception {
        HttpResponse<String> response = send(request(path).GET());

        assertRejected(response, path);
    }

    /**
     * Una ruta pública (el login) con una URL rechazada también es 400: no
     * llega al controlador ni al límite por IP.
     */
    @ParameterizedTest
    @ValueSource(strings = { "/api/auth/login;x=1", "/api/auth//login" })
    void elLoginConUnaUrlRechazadaDa400EnJson(String path) throws Exception {
        HttpResponse<String> response = send(request(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"nadie" + DOMAIN + "\",\"password\":\"mala\"}")));

        assertRejected(response, path);
    }

    /**
     * Rechazo <b>tardío</b>: una cabecera con un carácter de control que el
     * cortafuegos solo descubre cuando Spring MVC la lee. Debe ser el mismo 400
     * {@code MALFORMED_REQUEST} en JSON, no un 500 con la traza en el log.
     *
     * <p>
     * <b>Bug que protege.</b> {@code StrictHttpFirewall} valida las cabeceras
     * de forma perezosa, al leerlas con {@code getHeader}/{@code getHeaders}. El
     * {@code Content-Type} lo lee por primera vez así Spring MVC, al preparar el
     * {@code @RequestBody} del login: la {@code RequestRejectedException} nace
     * dentro de MVC, no llega a {@code FilterChainProxy} (ni, por tanto, a
     * {@link JsonRequestRejectedHandler}) y la atrapaba el
     * {@code @ExceptionHandler(Exception.class)} de
     * {@code GlobalExceptionHandler}: 500 {@code INTERNAL_ERROR} y una línea
     * {@code ERROR} con traza por petición. Como el login es público, cualquiera
     * podía llenar el log de errores. Sin el arreglo falla con 500.
     * </p>
     *
     * <p>
     * <b>Por qué con un socket.</b> Para enviar exactamente el byte
     * {@code 0x85} en la cabecera, sin depender de si {@code HttpClient} acepta
     * o cómo codifica un carácter fuera de ASCII. Tomcat lee las cabeceras en
     * ISO-8859-1, así que ese byte llega como el carácter de control U+0085
     * (NEL), que el cortafuegos rechaza.
     * </p>
     */
    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void unaCabeceraRechazadaAlLeerlaDentroDeSpringMvcDa400YNoUnErrorEnElLog(CapturedOutput output)
            throws Exception {
        String path = "/api/auth/login";
        String raw = "POST " + path + " HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + port + "\r\n"
                + "Content-Type: application/json\u0085\r\n"
                + "Content-Length: 2\r\n"
                + "Connection: close\r\n"
                + "\r\n"
                + "{}";

        RawResponse response = sendRaw(raw.getBytes(StandardCharsets.ISO_8859_1));

        assertEquals(400, response.status(), "respuesta: " + response.body());
        assertTrue(response.header("Content-Type").startsWith("application/json"),
                "el error debe ir en JSON: " + response.header("Content-Type"));
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(400, body.path("status").asInt(), response.body());
        assertEquals("MALFORMED_REQUEST", body.path("code").asText(), response.body());
        assertEquals(path, body.path("path").asText(), response.body());
        assertEquals(JsonRequestRejectedHandler.MESSAGE, body.path("message").asText(), response.body());
        assertFalse(response.body().contains("rejected"), "no debe filtrar el mensaje del cortafuegos");
        // Un 4xx que cualquiera puede provocar no va al log de errores.
        assertFalse(output.getAll().contains("Error no controlado"),
                "el rechazo no debe registrarse como error no controlado");
        assertFalse(output.getAll().contains("RequestRejectedException"),
                "el rechazo no debe registrarse con su traza");
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * Respuesta HTTP leída a mano del socket.
     *
     * @param status  código de estado
     * @param headers cabeceras con el nombre en minúsculas
     * @param body    cuerpo ya sin la codificación por trozos
     */
    private record RawResponse(int status, Map<String, String> headers, String body) {

        /** Valor de la cabecera, o cadena vacía si no viene. */
        String header(String name) {
            return headers.getOrDefault(name.toLowerCase(Locale.ROOT), "");
        }
    }

    /**
     * Envía los bytes tal cual por un socket y lee la respuesta hasta que el
     * servidor cierra la conexión (la petición lleva {@code Connection: close}).
     */
    private RawResponse sendRaw(byte[] request) throws IOException {
        byte[] raw;
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(request);
            out.flush();
            raw = socket.getInputStream().readAllBytes();
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        int headersEnd = text.indexOf("\r\n\r\n");
        assertTrue(headersEnd > 0, "respuesta sin cabeceras: " + text);

        String[] lines = text.substring(0, headersEnd).split("\r\n");
        int status = Integer.parseInt(lines[0].split(" ")[1]);
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT),
                    lines[i].substring(colon + 1).trim());
        }
        String body = text.substring(headersEnd + 4);
        if ("chunked".equalsIgnoreCase(headers.getOrDefault("transfer-encoding", ""))) {
            body = dechunk(body);
        }
        return new RawResponse(status, headers, body);
    }

    /**
     * Quita la codificación por trozos ({@code Transfer-Encoding: chunked}):
     * cada trozo es su tamaño en hexadecimal, {@code CRLF}, los datos y otro
     * {@code CRLF}; el último tiene tamaño 0. El cuerpo de error es ASCII
     * salvo los acentos del mensaje, así que se trabaja sobre bytes UTF-8.
     */
    private static String dechunk(String chunked) {
        byte[] bytes = chunked.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int position = 0;
        while (true) {
            int lineEnd = indexOfCrlf(bytes, position);
            int size = Integer.parseInt(
                    new String(bytes, position, lineEnd - position, StandardCharsets.US_ASCII).trim(), 16);
            if (size == 0) {
                return result.toString(StandardCharsets.UTF_8);
            }
            result.write(bytes, lineEnd + 2, size);
            position = lineEnd + 2 + size + 2;
        }
    }

    private static int indexOfCrlf(byte[] bytes, int from) {
        for (int i = from; i < bytes.length - 1; i++) {
            if (bytes[i] == '\r' && bytes[i + 1] == '\n') {
                return i;
            }
        }
        throw new IllegalStateException("trozo sin fin de línea");
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json");
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Comprueba el 400 en JSON con {@code MALFORMED_REQUEST}, la ruta original
     * (no {@code /error}), un mensaje propio (sin el texto del cortafuegos, que
     * describe qué cadena «maliciosa» encontró) y {@code nosniff}, porque las
     * cabeceras de seguridad de Spring no llegan a escribirse en una petición
     * rechazada.
     */
    private void assertRejected(HttpResponse<String> response, String path) throws Exception {
        assertEquals(400, response.statusCode(), "cuerpo: " + response.body());
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.startsWith("application/json"), "el error debe ir en JSON: " + contentType);
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElse(""));

        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(400, body.path("status").asInt(), response.body());
        assertEquals("MALFORMED_REQUEST", body.path("code").asText(), response.body());
        assertEquals(path, body.path("path").asText(), response.body());
        assertEquals(JsonRequestRejectedHandler.MESSAGE, body.path("message").asText(), response.body());
        assertFalse(response.body().contains("rejected"), "no debe filtrar el mensaje del cortafuegos");
    }
}
