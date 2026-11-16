package com.emilio.streambox.security.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.AuthCookieService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Revisión independiente (QA): la cookie de dispositivo con <b>Tomcat real</b>
 * y la cabecera {@code Cookie} escrita a mano.
 *
 * <p>
 * <b>Por qué con Tomcat y no con MockMvc.</b> MockMvc recibe las cookies ya
 * separadas ({@code Cookie[]}) y no pasa por el analizador de Tomcat
 * ({@code Rfc6265CookieProcessor}), que es quien decide qué valores llegan a la
 * aplicación, en qué orden llegan varias cookies con el mismo nombre y qué pasa
 * con comillas, espacios o dos cabeceras {@code Cookie}. Aquí la petición sale
 * por la red con {@link HttpClient}.
 * </p>
 *
 * <p>
 * <b>Contexto compartido.</b> Misma configuración que los demás tests con
 * Tomcat sin ajustes ({@code RANDOM_PORT} + perfil {@code test}): la suite está
 * en el tope de la caché de contextos de Spring y uno nuevo expulsaría el más
 * antiguo (ver el informe de QA). Por eso no se bajan los límites del bloqueo
 * (en el perfil de test son 100000) y, para saber qué contador ha usado una
 * petición, se mira {@code remainingAttempts}: cada contador empieza en el
 * máximo y baja de uno en uno, así que dos peticiones que caen en el mismo
 * contador dan valores consecutivos.
 * </p>
 *
 * <p>
 * Todas las peticiones salen de 127.0.0.1; cada test usa una cuenta nueva.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DeviceCookieTomcatQaIntegrationTest {

    private static final String DOMAIN = "@cookie-tomcat-qa.test";
    private static final String PASSWORD = "Contraseña-Tomcat-QA-2026";
    private static final String DEVICE = AuthCookieService.DEVICE_COOKIE_NAME;
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Value("${local.server.port}") private int port;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
    }

    /**
     * Valores raros escritos a mano en la cabecera (comillas, espacios, un
     * tabulador, coma, sin {@code =}, vacíos, muy largos): con una contraseña
     * incorrecta, el mismo 401 que sin cookie, gastando del mismo contador (el
     * de la cuenta: el segundo fallo deja un intento menos que el primero), y
     * nunca un 500.
     */
    @ParameterizedTest
    @ValueSource(strings = { "streambox_device=\"v1.a.b\"", "streambox_device=v1 . a . b",
            "streambox_device=v1.a.b,streambox_device=v1.c.d", "streambox_device=", "streambox_device",
            "streambox_device=;streambox_device=;", "streambox_device=v1.\t.b",
            "streambox_device=\\\"\\\"", "LONG" })
    void valoresRarosEnLaCabeceraNoDan500(String cookieHeader) throws Exception {
        String email = createUser();
        String header = "LONG".equals(cookieHeader) ? DEVICE + "=" + "v1." + "A".repeat(4000) + ".x" : cookieHeader;

        HttpResponse<String> without = login(email, "mal", null);
        HttpResponse<String> with = login(email, "mal", header);

        assertEquals(401, without.statusCode(), without.body());
        assertEquals(401, with.statusCode(), with.body());
        assertEquals(remaining(without) - 1, remaining(with), with.body());
    }

    /**
     * Dos cabeceras {@code Cookie} con la válida en la segunda, tras otras
     * cookies: Tomcat las une y la aplicación usa la cookie de dispositivo.
     * Prueba: ese fallo cae en el contador del dispositivo (el siguiente con
     * la cookie sola deja uno menos) y no en el de la IP (el siguiente sin
     * cookie empieza de cero).
     */
    @Test
    void laCookieValidaSeLeeAunqueVengaEnOtraCabeceraOTrasOtrasCookies() throws Exception {
        String email = createUser();
        String valid = deviceCookie(login(email, PASSWORD, null));

        HttpResponse<String> split = send(loginRequest(email, "mal")
                .header("Cookie", "otra=1; streambox_token=x")
                .header("Cookie", DEVICE + "=" + valid));

        assertUsedDeviceCounter(email, valid, split);
    }

    /**
     * Un {@code %} en el valor de {@code streambox_device}: Tomcat lo deja
     * pasar (es un carácter válido de cookie según RFC 6265) y el login debe
     * tratarlo como cookie ausente (401), no responder 500.
     *
     * <p>
     * <b>Regresión de la incidencia QA-DC-1:</b> con {@code @CookieValue} daba
     * 500 {@code INTERNAL_ERROR} por la decodificación de URL
     * ({@code UrlPathHelper.decodeRequestString} lanza
     * {@code IllegalArgumentException("Invalid encoded sequence")}).
     * </p>
     */
    @ParameterizedTest
    @ValueSource(strings = { "%", "%zz", "v1.%%.x" })
    void unPorcentajeEnLaCookieDeDispositivoNoDa500(String value) throws Exception {
        String email = createUser();

        HttpResponse<String> response = login(email, "mal", DEVICE + "=" + value);

        assertEquals(401, response.statusCode(), response.body());
    }

    /**
     * Mismo defecto en la cookie {@code streambox_refresh} de
     * {@code POST /api/auth/refresh} y {@code /api/auth/logout} (anterior a la
     * cookie de dispositivo, misma causa): un {@code %} inválido debe dar el
     * 401 / 204 de siempre, no un 500.
     *
     * <p>
     * <b>Regresión de la incidencia QA-DC-1.</b>
     * </p>
     */
    @Test
    void unPorcentajeEnLaCookieDeRefreshNoDa500() throws Exception {
        for (String path : List.of("/api/auth/refresh", "/api/auth/logout")) {
            HttpResponse<String> response = send(HttpRequest.newBuilder(uri(path))
                    .header("X-Requested-With", "StreamBox")
                    .header("Cookie", AuthCookieService.REFRESH_COOKIE_NAME + "=%zz")
                    .POST(HttpRequest.BodyPublishers.noBody()));
            assertTrue(response.statusCode() < 500, path + " -> " + response.statusCode() + " " + response.body());
        }
    }

    /**
     * {@code POST /api/auth/logout} con {@code streambox_refresh=%zz} (y con
     * la cookie de dispositivo también mal formada): el mismo 204 que sin
     * cookie, con las dos cookies de sesión borradas. Falla sin el arreglo de
     * QA-DC-1 (500 sin borrar nada).
     */
    @Test
    void elLogoutConUnPorcentajeInvalidoRespondeComoSinCookie() throws Exception {
        HttpResponse<String> without = send(HttpRequest.newBuilder(uri("/api/auth/logout"))
                .header("X-Requested-With", "StreamBox")
                .POST(HttpRequest.BodyPublishers.noBody()));
        HttpResponse<String> with = send(HttpRequest.newBuilder(uri("/api/auth/logout"))
                .header("X-Requested-With", "StreamBox")
                .header("Cookie", AuthCookieService.REFRESH_COOKIE_NAME + "=%zz; " + DEVICE + "=%zz")
                .POST(HttpRequest.BodyPublishers.noBody()));

        assertEquals(204, without.statusCode(), without.body());
        assertEquals(204, with.statusCode(), with.body());
        assertEquals(without.headers().allValues("Set-Cookie"), with.headers().allValues("Set-Cookie"));
        assertTrue(with.headers().allValues("Set-Cookie").stream()
                .anyMatch(header -> header.startsWith(AuthCookieService.REFRESH_COOKIE_NAME + "=;")),
                with.headers().allValues("Set-Cookie").toString());
    }

    /**
     * Con Tomcat real, dos {@code streambox_device} en la misma cabecera
     * llegan en el orden en que se enviaron: una basura delante (lo que
     * pondría un navegador si alguien plantó una cookie con una ruta más
     * específica) no anula la válida, que se usa.
     *
     * <p>
     * <b>Regresión de la incidencia QA-DC-2 (BAJA):</b> cuando solo se miraba
     * la primera, la basura anulaba la válida.
     * </p>
     */
    @Test
    void conDosCookiesDeDispositivoBastaConQueUnaSeaValida() throws Exception {
        String email = createUser();
        String valid = deviceCookie(login(email, PASSWORD, null));

        HttpResponse<String> response = login(email, "mal", DEVICE + "=plantada; " + DEVICE + "=" + valid);

        assertUsedDeviceCounter(email, valid, response);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /**
     * {@code first} (un fallo) cayó en el contador del dispositivo de
     * {@code valid}: otro fallo con la cookie sola deja un intento menos, y un
     * fallo sin cookie (contador de la IP, ya conocida) no se ve afectado.
     */
    private void assertUsedDeviceCounter(String email, String valid, HttpResponse<String> first) throws Exception {
        assertEquals(401, first.statusCode(), first.body());
        HttpResponse<String> sameDevice = login(email, "mal", DEVICE + "=" + valid);
        HttpResponse<String> noCookie = login(email, "mal", null);

        assertEquals(remaining(first) - 1, remaining(sameDevice), "mismo contador que el dispositivo");
        assertEquals(remaining(first), remaining(noCookie), "el contador de la IP no debía haberse tocado");
    }

    private int remaining(HttpResponse<String> response) throws Exception {
        assertEquals(401, response.statusCode(), response.body());
        return objectMapper.readTree(response.body()).get("remainingAttempts").asInt();
    }

    private HttpResponse<String> login(String email, String password, String cookieHeader) throws Exception {
        HttpRequest.Builder request = loginRequest(email, password);
        if (cookieHeader != null) {
            request.header("Cookie", cookieHeader);
        }
        return send(request);
    }

    private HttpRequest.Builder loginRequest(String email, String password) throws Exception {
        return HttpRequest.newBuilder(uri("/api/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static String deviceCookie(HttpResponse<String> response) {
        assertEquals(204, response.statusCode(), response.body());
        String value = response.headers().allValues("Set-Cookie").stream()
                .filter(header -> header.startsWith(DEVICE + "="))
                .map(header -> header.substring(DEVICE.length() + 1, header.indexOf(';')))
                .findFirst().orElse(null);
        assertNotNull(value, response.headers().allValues("Set-Cookie").toString());
        return value;
    }

    private String createUser() {
        String name = "tomcatqa" + SEQUENCE.getAndIncrement();
        User user = new User();
        user.setEmailVerifiedAt(Instant.now());
        user.setUsername(name);
        user.setEmail(name + DOMAIN);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user).getEmail();
    }
}
