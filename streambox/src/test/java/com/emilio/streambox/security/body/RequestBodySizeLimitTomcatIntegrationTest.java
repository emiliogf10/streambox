package com.emilio.streambox.security.body;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.header.HeaderWriterFilter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtAuthenticationFilter;
import com.emilio.streambox.security.JwtService;
import com.emilio.streambox.security.ratelimit.RateLimitingFilter;
import com.emilio.streambox.service.AuthenticationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Tope del cuerpo de las peticiones (BAJA-2): 413 {@code PAYLOAD_TOO_LARGE}
 * por encima de {@code streambox.http.max-request-body} (64 KB), con Tomcat
 * real.
 *
 * <p>
 * <b>Por qué con Tomcat y no con MockMvc.</b> Lo que se prueba vive en la
 * cadena de filtros y en cómo llega el cuerpo: MockMvc no manda nunca un
 * cuerpo por trozos ({@code Transfer-Encoding: chunked}), no aplica las
 * cabeceras como el servidor real y no hace el despacho de error. Aquí la
 * petición sale por la red con {@link HttpClient}, con y sin
 * {@code Content-Length}.
 * </p>
 *
 * <p>
 * <b>Bug que protege.</b> Sin {@link RequestBodySizeLimitFilter}, Jackson leía
 * el cuerpo entero sin límite (el 1 MB de nginx solo existe en Docker): los
 * tests del 413 recibirían un 200 o un 401 tras leer todo. Sin el
 * reconocimiento de la causa en {@code GlobalExceptionHandler}, el caso por
 * trozos daría 400 {@code MALFORMED_REQUEST}. Si el filtro fuera después del
 * límite por IP, el test del login vería un 429 donde espera un 401.
 * </p>
 *
 * <p>
 * El límite del login se baja a 2 ({@code @TestPropertySource}, contexto
 * propio) y solo lo usa {@link #unCuerpoDemasiadoGrandeEnElLoginNoGastaElLimitePorIpNiLlegaAlServicio()}:
 * aquí todas las peticiones salen de 127.0.0.1. El resto usa
 * {@code PUT /api/users/me/genres} (autenticado, sin límite por IP, y su cuerpo
 * lo lee Jackson).
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = "streambox.security.rate-limit.login.max-requests=2")
@ExtendWith(OutputCaptureExtension.class)
class RequestBodySizeLimitTomcatIntegrationTest {

    /** 64 KB: el valor de {@code application.properties}. */
    private static final int LIMIT = 64 * 1024;

    private static final String DOMAIN = "@limite-cuerpo.test";
    private static final String GENRES = "/api/users/me/genres";
    private static final String LOGIN = "/api/auth/login";

    @Value("${local.server.port}") private int port;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private FilterChainProxy filterChainProxy;
    @Autowired private RequestBodyLimitProperties properties;
    @MockitoSpyBean private AuthenticationService authenticationService;

    /** HTTP/1.1 explícito: con HTTP/2 no habría {@code Transfer-Encoding: chunked}. */
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String userToken;

    /**
     * Anota cómo llegó cada petición al servidor ({@code Content-Length} y
     * {@code Transfer-Encoding}), antes de cualquier otro filtro: así el test
     * comprueba que la petición «por trozos» llegó de verdad sin
     * {@code Content-Length}, y no lo da por supuesto.
     */
    @TestConfiguration
    static class RecordingFilterConfig {

        static final Map<String, String> FRAMING = new ConcurrentHashMap<>();

        @Bean
        FilterRegistrationBean<Filter> framingRecorder() {
            Filter filter = (request, response, chain) -> {
                HttpServletRequest http = (HttpServletRequest) request;
                String marker = http.getHeader("X-Test-Id");
                if (marker != null) {
                    FRAMING.put(marker, "length=" + http.getContentLengthLong() + ";te="
                            + http.getHeader("Transfer-Encoding"));
                }
                chain.doFilter(request, response);
            };
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }

    @BeforeEach
    void createUser() {
        deleteUsersOfThisSuite();
        User user = new User();
        user.setEmailVerifiedAt(Instant.now());
        user.setUsername("limitecuerpo");
        user.setEmail("limitecuerpo" + DOMAIN);
        user.setPassword(passwordEncoder.encode("Contraseña-Larga-Correcta-2026"));
        user.setRole(Role.USER);
        userToken = jwtService.generateToken(userRepository.save(user));
        clearInvocations(authenticationService);
    }

    @AfterEach
    void deleteUsersOfThisSuite() {
        userRepository.deleteAll(userRepository.findAll().stream()
                .filter(user -> user.getEmail().endsWith(DOMAIN))
                .toList());
    }

    // ------------------------------------------------------------------
    // El límite, con y sin Content-Length
    // ------------------------------------------------------------------

    /** La propiedad por defecto es la que dicen los tests y la documentación. */
    @Test
    void elLimitePorDefectoEsDe64Kb() {
        assertEquals(LIMIT, properties.maxRequestBody().toBytes());
    }

    /**
     * Justo en el límite pasa (y el controlador responde), un byte más da 413,
     * con {@code Content-Length} (se rechaza sin leer) y sin él (se corta al
     * leer). El 413 es el mismo por los dos caminos.
     */
    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void justoEnElLimitePasaYUnByteMasDa413(boolean chunked) throws Exception {
        HttpResponse<String> atLimit = send(put(GENRES, json("{\"genreIds\":[]}", LIMIT), chunked)
                .header("Authorization", "Bearer " + userToken));
        assertEquals(200, atLimit.statusCode(), "cuerpo: " + atLimit.body());

        HttpResponse<String> overLimit = send(put(GENRES, json("{\"genreIds\":[]}", LIMIT + 1), chunked)
                .header("Authorization", "Bearer " + userToken));
        assertPayloadTooLarge(overLimit, GENRES);
    }

    /** Muy por encima del límite: el mismo 413 (y el servidor no se lo guarda todo). */
    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void unCuerpoMuchoMayorDa413(boolean chunked) throws Exception {
        HttpResponse<String> response = send(put(GENRES, json("{\"genreIds\":[]}", 1024 * 1024), chunked)
                .header("Authorization", "Bearer " + userToken));

        assertPayloadTooLarge(response, GENRES);
    }

    /**
     * Control de los tests anteriores: la petición «por trozos» llega de verdad
     * sin {@code Content-Length} y con {@code Transfer-Encoding: chunked}, y la
     * otra con su {@code Content-Length}. Si no, el caso sin cabecera estaría
     * probando el otro camino.
     */
    @Test
    void lasPeticionesLleganConElFormatoQueDiceElTest() throws Exception {
        send(put(GENRES, json("{\"genreIds\":[]}", 100), true).header("Authorization", "Bearer " + userToken)
                .header("X-Test-Id", "trozos"));
        send(put(GENRES, json("{\"genreIds\":[]}", 100), false).header("Authorization", "Bearer " + userToken)
                .header("X-Test-Id", "longitud"));

        assertEquals("length=-1;te=chunked", RecordingFilterConfig.FRAMING.get("trozos"));
        assertEquals("length=100;te=null", RecordingFilterConfig.FRAMING.get("longitud"));
    }

    /**
     * El 413 sale en JSON pida lo que pida el {@code Accept} (un navegador
     * manda {@code text/html}), por los dos caminos.
     */
    @ParameterizedTest
    @ValueSource(strings = { "text/html", "application/xml", "*/*", "text/plain" })
    void el413SaleEnJsonPidaLoQuePidaElAccept(String accept) throws Exception {
        for (boolean chunked : new boolean[] { true, false }) {
            HttpResponse<String> response = send(put(GENRES, json("{\"genreIds\":[]}", LIMIT + 1), chunked)
                    .header("Authorization", "Bearer " + userToken)
                    .setHeader("Accept", accept));
            assertPayloadTooLarge(response, GENRES);
        }
    }

    // ------------------------------------------------------------------
    // Formularios grandes a un endpoint JSON (INFO 4)
    // ------------------------------------------------------------------

    /**
     * Un formulario ({@code application/x-www-form-urlencoded}) mayor que el
     * límite al login, por trozos o con {@code Content-Length}: el mismo 413
     * en JSON, sin llegar al servicio y sin un {@code ERROR} en el log.
     *
     * <p>
     * <b>Bug que protege (INFO 4).</b> El formulario no pasa por el flujo
     * vigilado: Spring reconstruye el cuerpo de un {@code POST} de formulario
     * con {@code getParameterMap()} y es Tomcat quien lo lee, con su
     * {@code max-http-form-post-size} (igualado a 64 KB). Por trozos, Tomcat
     * lanzaba {@code InvalidParameterException} y
     * {@code GlobalExceptionHandler} respondía 500 con la traza en el log, a
     * voluntad de cualquiera y sin token. Con {@code Content-Length} ya daba
     * 413 (lo corta el filtro sin leer).
     * </p>
     *
     * <p>
     * 128 KB a propósito: por encima del límite, pero por debajo de los 2 MB
     * que Tomcat descarta del cuerpo sin leer antes de cerrar la conexión
     * ({@code maxSwallowSize}). Con un cuerpo mucho mayor la respuesta es la
     * misma, pero Tomcat corta la conexión y el cliente puede no llegar a
     * leerla.
     * </p>
     */
    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void unFormularioMayorQueElLimiteDa413SinErrorEnElLog(boolean chunked, CapturedOutput output) throws Exception {
        String form = "email=nadie%40limite-cuerpo.test&password=" + "a".repeat(LIMIT * 2);
        int logStart = output.getOut().length();

        HttpResponse<String> response = send(form(LOGIN, form, chunked));

        assertPayloadTooLarge(response, LOGIN);
        assertNoErrorLogged(output, logStart);
        verify(authenticationService, never()).login(any(), any(), any());
    }

    /**
     * Formularios que Tomcat no puede analizar aunque sean pequeños (un
     * {@code %} mal codificado, más de los 1000 parámetros que admite): 400
     * {@code MALFORMED_REQUEST} en JSON, por los dos caminos, sin
     * {@code ERROR} en el log. Antes, el mismo 500 con traza que el caso
     * anterior.
     */
    @ParameterizedTest
    @ValueSource(strings = { "malcodificado-trozos", "malcodificado-longitud", "muchos-trozos", "muchos-longitud" })
    void unFormularioQueTomcatNoPuedeAnalizarDa400SinErrorEnElLog(String scenario, CapturedOutput output)
            throws Exception {
        String form = scenario.startsWith("malcodificado") ? "email=%zz&password=%" : "a=1&".repeat(1500);
        int logStart = output.getOut().length();

        HttpResponse<String> response = send(form(LOGIN, form, scenario.endsWith("trozos")));

        assertEquals(400, response.statusCode(), "cuerpo: " + response.body());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals("MALFORMED_REQUEST", body.path("code").asText(), response.body());
        assertEquals(LOGIN, body.path("path").asText(), response.body());
        assertNoErrorLogged(output, logStart);
        verify(authenticationService, never()).login(any(), any(), any());
    }

    /**
     * Control: un formulario pequeño y bien formado sigue dando el 415 de
     * siempre (la API solo acepta JSON), también por trozos.
     */
    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void unFormularioPequenoSigueDando415(boolean chunked) throws Exception {
        HttpResponse<String> response = send(form(LOGIN, "email=nadie%40limite-cuerpo.test&password=abc", chunked));

        assertEquals(415, response.statusCode(), "cuerpo: " + response.body());
        assertEquals("UNSUPPORTED_MEDIA_TYPE", objectMapper.readTree(response.body()).path("code").asText());
    }

    // ------------------------------------------------------------------
    // Antes del límite por IP y de la base de datos
    // ------------------------------------------------------------------

    /**
     * Un anónimo que manda al login cuerpos enormes con {@code Content-Length}
     * recibe 413 sin llegar al servicio (ni a la base de datos) y sin gastar el
     * límite por IP (aquí 2): después, dos logins normales siguen recibiendo su
     * 401 y solo el tercero el 429. Si el 413 contara, el primero ya sería un
     * 429.
     */
    @Test
    void unCuerpoDemasiadoGrandeEnElLoginNoGastaElLimitePorIpNiLlegaAlServicio() throws Exception {
        String oversized = json("{\"email\":\"nadie" + DOMAIN + "\",\"password\":\"x\"}", LIMIT + 1);
        for (int i = 0; i < 5; i++) {
            assertPayloadTooLarge(send(post(LOGIN, oversized, false)), LOGIN);
        }
        verify(authenticationService, never()).login(any(), any(), any());

        String normal = "{\"email\":\"nadie" + DOMAIN + "\",\"password\":\"Una-Contraseña-Cualquiera\"}";
        assertEquals(401, send(post(LOGIN, normal, false)).statusCode());
        assertEquals(401, send(post(LOGIN, normal, false)).statusCode());
        HttpResponse<String> limited = send(post(LOGIN, normal, false));
        assertEquals(429, limited.statusCode(), "el límite sigue activo: " + limited.body());
    }

    /**
     * El orden de la cadena que garantiza lo anterior: el tope del cuerpo va
     * después de {@link HeaderWriterFilter} (para que el 413 lleve las
     * cabeceras de seguridad) y antes del límite por IP y del filtro JWT (que
     * consulta la base de datos).
     */
    @Test
    void elFiltroVaDespuesDeLasCabecerasYAntesDelLimitePorIpYDelJwt() {
        List<Filter> filters = filterChainProxy.getFilterChains().get(0).getFilters();
        int headers = indexOf(filters, HeaderWriterFilter.class);
        int body = indexOf(filters, RequestBodySizeLimitFilter.class);
        int rateLimit = indexOf(filters, RateLimitingFilter.class);
        int jwt = indexOf(filters, JwtAuthenticationFilter.class);

        assertTrue(headers >= 0 && headers < body, filters.toString());
        assertTrue(body < rateLimit && rateLimit < jwt, filters.toString());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * 413 {@code PAYLOAD_TOO_LARGE} en el formato de la API, en JSON, con la
     * ruta y con las cabeceras de seguridad de Spring Security.
     */
    private void assertPayloadTooLarge(HttpResponse<String> response, String path) throws Exception {
        assertEquals(413, response.statusCode(), "cuerpo: " + response.body());
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.startsWith("application/json"), "el 413 debe ir en JSON: " + contentType);
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(413, body.path("status").asInt(), response.body());
        assertEquals("PAYLOAD_TOO_LARGE", body.path("code").asText(), response.body());
        assertEquals(RequestBodySizeLimitFilter.MESSAGE, body.path("message").asText(), response.body());
        assertEquals(path, body.path("path").asText(), response.body());
        assertEquals(List.of("nosniff"), response.headers().allValues("X-Content-Type-Options"));
        assertEquals(List.of("DENY"), response.headers().allValues("X-Frame-Options"));
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"),
                response.headers().map().toString());
    }

    /** JSON {@code base} con espacios al final hasta medir exactamente {@code size} bytes. */
    private static String json(String base, int size) {
        int length = base.getBytes(StandardCharsets.UTF_8).length;
        return base + " ".repeat(size - length);
    }

    private HttpRequest.Builder put(String path, String body, boolean chunked) {
        return request(path).header("Content-Type", "application/json").PUT(publisher(body, chunked));
    }

    private HttpRequest.Builder post(String path, String body, boolean chunked) {
        return request(path).header("Content-Type", "application/json").POST(publisher(body, chunked));
    }

    private HttpRequest.Builder form(String path, String body, boolean chunked) {
        return request(path).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(publisher(body, chunked));
    }

    /** Ninguna línea {@code ERROR} en el log desde {@code logStart} (un 500 la deja, con su traza). */
    private static void assertNoErrorLogged(CapturedOutput output, int logStart) {
        String log = output.getOut().substring(logStart);
        assertTrue(!log.contains(" ERROR "), "no debe registrarse un error del servidor:\n" + log);
    }

    /**
     * Con {@code chunked}, un publicador sin longitud conocida: HttpClient
     * envía el cuerpo por trozos y sin {@code Content-Length}.
     */
    private static HttpRequest.BodyPublisher publisher(String body, boolean chunked) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return chunked
                ? HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes))
                : HttpRequest.BodyPublishers.ofByteArray(bytes);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int indexOf(List<Filter> filters, Class<?> type) {
        for (int i = 0; i < filters.size(); i++) {
            if (type.isInstance(filters.get(i))) {
                return i;
            }
        }
        return -1;
    }
}
