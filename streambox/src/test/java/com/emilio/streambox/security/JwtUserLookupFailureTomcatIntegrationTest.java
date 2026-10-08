package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Con un token <b>válido</b>, un fallo de infraestructura al cargar el usuario
 * (la base de datos caída) llega al cliente como 500 {@code INTERNAL_ERROR} en
 * JSON, no como 401. Probado contra el Tomcat embebido real porque el 500 sale
 * del despacho de error ({@code /error}, {@code ApiErrorController}), que MockMvc
 * no hace.
 *
 * <p>
 * <b>Bug que protege.</b> {@link JwtAuthenticationFilter} capturaba
 * {@code Exception} también alrededor de {@code userRepository.findByEmail}:
 * la {@code DataAccessException} se registraba como «token inválido», la
 * petición seguía como anónima y la ruta protegida respondía 401
 * {@code INVALID_CREDENTIALS}; el frontend ({@code lib/api.ts}) cierra la
 * sesión ante un 401, así que una caída momentánea de la base de datos echaba a
 * todos los usuarios. Sin el arreglo, los tests {@code conLaBaseDeDatosCaida...}
 * fallan con 401.
 * </p>
 *
 * <p>
 * <b>Cómo se provoca el fallo.</b> {@link UserRepository} se sustituye por un
 * mock ({@code @MockitoBean}) que lanza una
 * {@code DataAccessResourceFailureException}, la que da Spring cuando no hay
 * conexión. El token lo genera el {@link JwtService} real, sin pasar por la
 * base de datos. El resto de tests fija que lo que antes daba 401 lo sigue
 * dando (token no válido, usuario inexistente) y que en una ruta pública un
 * token no válido sigue siendo una petición anónima.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class JwtUserLookupFailureTomcatIntegrationTest {

    private static final String PROTECTED = "/api/users/me";
    private static final String EMAIL = "caida-bd@jwt-lookup-failure.test";

    /** Mensaje de la excepción simulada: no debe llegar nunca al cliente. */
    private static final String DB_ERROR_MESSAGE = "Conexión rechazada por el servidor de base de datos";

    /** Mensaje genérico de los 5xx de la API ({@code GenericHttpError}). */
    private static final String INTERNAL_ERROR_MESSAGE =
            "Se ha producido un error interno. Inténtalo de nuevo más tarde";

    @Value("${local.server.port}") private int port;
    @Autowired private JwtService jwtService;
    @Autowired private JwtProperties jwtProperties;
    @MockitoBean private UserRepository userRepository;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ------------------------------------------------------------------
    // El bug: token válido + BD caída
    // ------------------------------------------------------------------

    @Test
    void conLaBaseDeDatosCaidaUnTokenValidoPorCabeceraDa500YNo401() throws Exception {
        when(userRepository.findByEmail(EMAIL)).thenThrow(new DataAccessResourceFailureException(DB_ERROR_MESSAGE));

        HttpResponse<String> response = send(request(PROTECTED)
                .header("Authorization", "Bearer " + validToken())
                .GET());

        assertInternalError(response, PROTECTED);
    }

    @Test
    void conLaBaseDeDatosCaidaUnTokenValidoPorCookieDa500YNo401() throws Exception {
        when(userRepository.findByEmail(EMAIL)).thenThrow(new DataAccessResourceFailureException(DB_ERROR_MESSAGE));

        HttpResponse<String> response = send(request(PROTECTED)
                .header("Cookie", AuthCookieService.COOKIE_NAME + "=" + validToken())
                .GET());

        assertInternalError(response, PROTECTED);
    }

    /**
     * Petición no segura por cookie con la cabecera CSRF: el navegador de un
     * usuario que añade un favorito con la BD caída recibe el 500, no un 401.
     */
    @Test
    void conLaBaseDeDatosCaidaUnaPeticionNoSeguraPorCookieDa500() throws Exception {
        when(userRepository.findByEmail(EMAIL)).thenThrow(new DataAccessResourceFailureException(DB_ERROR_MESSAGE));
        String path = "/api/users/me/favorites/1";

        HttpResponse<String> response = send(request(path)
                .header("Cookie", AuthCookieService.COOKIE_NAME + "=" + validToken())
                .header(JwtAuthenticationFilter.CSRF_HEADER, JwtAuthenticationFilter.CSRF_HEADER_VALUE)
                .POST(HttpRequest.BodyPublishers.noBody()));

        assertInternalError(response, path);
    }

    // ------------------------------------------------------------------
    // Controles: lo que daba 401 lo sigue dando
    // ------------------------------------------------------------------

    /** Token válido de una cuenta que ya no existe: 401, no un 500. */
    @Test
    void unTokenValidoDeUnUsuarioInexistenteSigueDando401() throws Exception {
        when(userRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertUnauthorized(send(request(PROTECTED)
                .header("Authorization", "Bearer " + validToken())
                .GET()));
        assertUnauthorized(send(request(PROTECTED)
                .header("Cookie", AuthCookieService.COOKIE_NAME + "=" + validToken())
                .GET()));
    }

    /**
     * Token no válido (mal formado, firma ajena, caducado, otro emisor, sin
     * firma, vacío): 401 y ni siquiera se consulta la base de datos, así que
     * la BD caída no lo convierte en un 500.
     */
    @ParameterizedTest
    @ValueSource(strings = { "basura", "firma-ajena", "caducado", "otro-emisor", "sin-firma", "vacio" })
    void unTokenNoValidoSigueDando401SinConsultarLaBaseDeDatos(String kind) throws Exception {
        when(userRepository.findByEmail(anyString()))
                .thenThrow(new DataAccessResourceFailureException(DB_ERROR_MESSAGE));
        String token = invalidToken(kind);

        assertUnauthorized(send(request(PROTECTED).header("Authorization", "Bearer " + token).GET()));
        if (!token.isEmpty()) {
            assertUnauthorized(send(request(PROTECTED)
                    .header("Cookie", AuthCookieService.COOKIE_NAME + "=" + token)
                    .GET()));
        }
        verify(userRepository, never()).findByEmail(anyString());
    }

    /** En una ruta pública, un token no válido sigue siendo una petición anónima. */
    @Test
    void enUnaRutaPublicaUnTokenNoValidoSigueSiendoUnaPeticionAnonima() throws Exception {
        HttpResponse<String> response = send(request("/actuator/health")
                .header("Authorization", "Bearer " + invalidToken("caducado"))
                .GET());

        assertEquals(200, response.statusCode(), "cuerpo: " + response.body());
        verify(userRepository, never()).findByEmail(anyString());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private String validToken() {
        User user = new User();
        user.setEmail(EMAIL);
        return jwtService.generateToken(user);
    }

    private String invalidToken(String kind) {
        long now = System.currentTimeMillis();
        return switch (kind) {
            case "basura" -> "esto-no-es-un-jwt";
            case "firma-ajena" -> Jwts.builder().issuer(JwtService.ISSUER).subject(EMAIL)
                    .expiration(new Date(now + 60_000))
                    .signWith(Keys.hmacShaKeyFor(
                            "otra-clave-distinta-de-al-menos-32-caracteres!!".getBytes(StandardCharsets.UTF_8)))
                    .compact();
            case "caducado" -> Jwts.builder().issuer(JwtService.ISSUER).subject(EMAIL)
                    .issuedAt(new Date(now - 120_000)).expiration(new Date(now - 60_000))
                    .signWith(realKey()).compact();
            case "otro-emisor" -> Jwts.builder().issuer("otro-sistema").subject(EMAIL)
                    .expiration(new Date(now + 60_000)).signWith(realKey()).compact();
            case "sin-firma" -> Jwts.builder().issuer(JwtService.ISSUER).subject(EMAIL)
                    .expiration(new Date(now + 60_000)).compact();
            case "vacio" -> "";
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private SecretKey realKey() {
        return Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
    }

    /** 500 {@code INTERNAL_ERROR} de la API, en JSON, con la ruta original y sin detalles internos. */
    private void assertInternalError(HttpResponse<String> response, String path) throws Exception {
        assertEquals(500, response.statusCode(), "cuerpo: " + response.body());
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue(contentType.startsWith("application/json"), "el error debe ir en JSON: " + contentType);
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals(500, body.path("status").asInt(), response.body());
        assertEquals("INTERNAL_ERROR", body.path("code").asText(), response.body());
        assertEquals(INTERNAL_ERROR_MESSAGE, body.path("message").asText(), response.body());
        assertEquals(path, body.path("path").asText(), response.body());
        Set<String> fields = new TreeSet<>();
        body.fieldNames().forEachRemaining(fields::add);
        assertEquals(Set.of("timestamp", "status", "error", "code", "message", "path"), fields,
                response.body());
        assertFalse(response.body().contains(DB_ERROR_MESSAGE), "no debe filtrar el mensaje: " + response.body());
        assertFalse(response.body().contains("DataAccess"), "no debe filtrar la excepción: " + response.body());
        assertFalse(response.body().contains("Exception"), "no debe filtrar la excepción: " + response.body());
    }

    private void assertUnauthorized(HttpResponse<String> response) throws Exception {
        assertEquals(401, response.statusCode(), "cuerpo: " + response.body());
        JsonNode body = objectMapper.readTree(response.body());
        assertEquals("INVALID_CREDENTIALS", body.path("code").asText(), response.body());
        assertEquals(PROTECTED, body.path("path").asText(), response.body());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Accept", "application/json");
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
