package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;

/**
 * {@code HEAD /api/users} con un usuario corriente contra el Tomcat embebido
 * real (hallazgo NV-1 de la auditoría).
 *
 * <p>
 * MockMvc no pasa por el conector HTTP: no aplica las reglas de Tomcat para
 * {@code HEAD} (descartar el cuerpo pero conservar {@code Content-Length}). Esta
 * prueba arranca el servidor en un puerto aleatorio y comprueba lo que ve un
 * cliente de verdad: que el estado es 403 y que el {@code Content-Length} no
 * depende de cuántas cuentas existen (con el fallo, el listado se ejecutaba y
 * su tamaño crecía con cada cuenta: 125 bytes con 1 usuario, 387 con 3).
 * </p>
 *
 * <p>
 * Sin {@code @Transactional}: el servidor atiende en otro hilo y solo ve datos
 * confirmados. Los usuarios (dominio {@value #DOMAIN}) se borran antes y
 * después de cada test.
 * </p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UsersListHeadTomcatIntegrationTest {

    private static final String DOMAIN = "@nv1-tomcat.test";

    /** Valor del campo {@code timestamp} del cuerpo JSON del error. */
    private static final Pattern TIMESTAMP = Pattern.compile("\"timestamp\":\"([^\"]+)\"");

    /**
     * Longitudes posibles de un {@code Instant} ISO-8601 en UTC: sin decimales
     * ({@code 2026-10-08T10:00:00Z}, 20), con 3 (24), con 6 (27) o con 9 (30).
     */
    private static final Set<Integer> LONGITUDES_POSIBLES_DEL_TIMESTAMP = Set.of(20, 24, 27, 30);

    @Value("${local.server.port}") private int port;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    @AfterEach
    void deleteUsersOfThisSuite() {
        userRepository.deleteAll(userRepository.findAll().stream()
                .filter(user -> user.getEmail().endsWith(DOMAIN))
                .toList());
    }

    private User save(String name, Role role) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + DOMAIN);
        user.setPassword(passwordEncoder.encode("Contraseña-Segura-2026"));
        user.setRole(role);
        user.setCreatedAt(Instant.now());
        return userRepository.save(user);
    }

    private HttpResponse<String> call(String method, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/users"));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        request.method(method, HttpRequest.BodyPublishers.noBody());
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Optional<String> contentLength(HttpResponse<String> response) {
        return response.headers().firstValue("Content-Length");
    }

    /**
     * Longitud de la parte del cuerpo del 403 que NO es la marca de tiempo, en
     * bytes UTF-8: el cuerpo completo del {@code GET} menos el valor de su
     * {@code timestamp}. El {@code GET} y el {@code HEAD} salen del mismo
     * handler con la misma ruta, así que comparten esta parte fija.
     */
    private static int bytesFueraDelTimestamp(String cuerpoDelGet) {
        Matcher matcher = TIMESTAMP.matcher(cuerpoDelGet);
        assertTrue(matcher.find(), "el 403 debe llevar un timestamp: " + cuerpoDelGet);
        return cuerpoDelGet.getBytes(StandardCharsets.UTF_8).length - matcher.group(1).length();
    }

    /**
     * Comprueba que el {@code Content-Length} del {@code HEAD} es el de un 403
     * genérico: la parte fija más una marca de tiempo de una longitud posible.
     */
    private static void assertEsLongitudDelError403(HttpResponse<String> head, int parteFija, String contexto) {
        assertEquals(403, head.statusCode(), contexto + ": HEAD con USER debe ser 403, no 200");
        assertEquals(0, head.body().length(), contexto + ": HEAD nunca lleva cuerpo");
        int longitud = Integer.parseInt(contentLength(head).orElseThrow(
                () -> new AssertionError(contexto + ": el HEAD debe llevar Content-Length")));
        assertTrue(LONGITUDES_POSIBLES_DEL_TIMESTAMP.contains(longitud - parteFija),
                contexto + ": Content-Length " + longitud + " no corresponde al 403 genérico (parte fija "
                        + parteFija + " + timestamp de " + LONGITUDES_POSIBLES_DEL_TIMESTAMP + " bytes); "
                        + "si crece con las cuentas, el HEAD está ejecutando el listado");
    }

    /**
     * El {@code Content-Length} de un {@code HEAD} con un USER es el del error
     * 403 genérico con 1, 3 y 33 cuentas, y no el del listado.
     *
     * <p>
     * <b>Por qué no se comparan dos mediciones entre sí.</b> El cuerpo del 403
     * lleva un {@code timestamp} con la hora real, serializado como
     * {@code Instant} ISO-8601, que imprime 0, 3, 6 o 9 decimales según el
     * instante (si los microsegundos acaban en {@code 000}, se omiten tres
     * cifras). La misma petición mide así 191 o 194 bytes sin que haya cambiado
     * nada: la versión anterior de este test comparaba dos mediciones con
     * {@code assertEquals} y fallaba según la hora. Ahora se calcula la parte
     * fija del cuerpo (la del {@code GET}, sin su timestamp) y se exige que
     * {@code Content-Length - parteFija} sea una de las cuatro longitudes que
     * puede tener un timestamp. Es determinista y sigue detectando el fallo: el
     * listado completo mide 125 bytes con 1 usuario y cientos con 33, fuera de
     * ese conjunto, y el estado sería 200.
     * </p>
     */
    @Test
    void headConUserNoDevuelve200NiUnContentLengthQueDependaDelNumeroDeCuentas() throws Exception {
        String userToken = jwtService.generateToken(save("nv1t-user", Role.USER));

        HttpResponse<String> get = call("GET", userToken);
        assertEquals(403, get.statusCode(), "GET con USER debe ser 403");
        int parteFija = bytesFueraDelTimestamp(get.body());

        assertEsLongitudDelError403(call("HEAD", userToken), parteFija, "con 1 cuenta");
        save("nv1t-extra-a", Role.USER);
        save("nv1t-extra-b", Role.USER);
        assertEsLongitudDelError403(call("HEAD", userToken), parteFija, "con 3 cuentas");
        for (int i = 0; i < 30; i++) {
            save("nv1t-relleno-" + i, Role.USER);
        }
        assertEsLongitudDelError403(call("HEAD", userToken), parteFija, "con 33 cuentas");
    }

    @Test
    void headConAdminSiDevuelve200() throws Exception {
        String adminToken = jwtService.generateToken(save("nv1t-admin", Role.ADMIN));

        HttpResponse<String> head = call("HEAD", adminToken);

        assertEquals(200, head.statusCode());
        assertNotEquals(Optional.of("0"), contentLength(head));
    }

    @Test
    void headSinTokenEs401() throws Exception {
        assertEquals(401, call("HEAD", null).statusCode());
    }
}
