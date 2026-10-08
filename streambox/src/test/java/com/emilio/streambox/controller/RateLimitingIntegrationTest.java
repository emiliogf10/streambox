package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tests de los límites contra fuerza bruta: límite por IP en login y registro
 * y bloqueo temporal de cuentas.
 *
 * <p>
 * Los límites se reducen a valores pequeños para poder alcanzarlos. Los
 * contadores viven en memoria durante todo el contexto, así que cada test usa
 * IPs y emails propios para no interferir con los demás.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "streambox.security.rate-limit.login.max-requests=3",
        "streambox.security.rate-limit.register.max-requests=2",
        "streambox.security.rate-limit.lockout.max-failures=3"
})
class RateLimitingIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Crea un usuario propio para cada test: los contadores de fallos viven en
     * memoria durante todo el contexto, así que compartir emails entre tests
     * los haría depender unos de otros.
     */
    private void createUser(String name) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword(passwordEncoder.encode("correct-password"));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        userRepository.save(user);
    }

    // --- Límite por IP en login ---

    @Test
    void superarElLimiteDeLoginPorIpRetorna429ConRetryAfter() throws Exception {
        createUser("user1");
        String ip = "10.1.0.1";
        for (int i = 0; i < 3; i++) {
            login("nadie" + i + "@test.com", "x", ip).andExpect(status().isUnauthorized());
        }

        login("user1@test.com", "correct-password", ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.remainingAttempts").doesNotExist());
    }

    @Test
    void elLimiteDeLoginEsPorIpYNoAfectaAOtrasIps() throws Exception {
        createUser("user2");
        for (int i = 0; i < 3; i++) {
            login("otro" + i + "@test.com", "x", "10.2.0.1");
        }
        login("user2@test.com", "correct-password", "10.2.0.1")
                .andExpect(status().isTooManyRequests());

        login("user2@test.com", "correct-password", "10.2.0.2")
                .andExpect(status().isNoContent());
    }

    // --- Límite por IP en registro ---

    @Test
    void superarElLimiteDeRegistroPorIpRetorna429() throws Exception {
        createUser("user3");
        String ip = "10.3.0.1";
        register("reg1", ip).andExpect(status().isCreated());
        register("reg2", ip).andExpect(status().isCreated());

        register("reg3", ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }

    @Test
    void elLimiteDeRegistroYElDeLoginSonIndependientes() throws Exception {
        createUser("user4");
        String ip = "10.4.0.1";
        register("indep1", ip).andExpect(status().isCreated());
        register("indep2", ip).andExpect(status().isCreated());

        login("user4@test.com", "correct-password", ip).andExpect(status().isNoContent());
    }

    @Test
    void otrosEndpointsNoTienenLimite() throws Exception {
        createUser("user5");
        String token = "Bearer " + jwtService.generateToken(userRepository.findByEmail("user5@test.com").orElseThrow());
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(get("/api/users/me").header("Authorization", token).with(ip("10.5.0.1")))
                    .andExpect(status().isOk());
        }
    }

    // --- Bloqueo de cuenta ---

    @Test
    void elTercerFalloBloqueaLaCuentaAunqueDespuesLaPasswordSeaCorrecta() throws Exception {
        createUser("user6");
        // IPs distintas: se prueba el bloqueo por cuenta, no el límite por IP
        login("user6@test.com", "mal", "10.6.0.1").andExpect(status().isUnauthorized());
        login("user6@test.com", "mal", "10.6.0.2").andExpect(status().isUnauthorized());
        // El fallo que alcanza el máximo ya responde 429 (antes era un 401 más)
        login("user6@test.com", "mal", "10.6.0.3")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        login("user6@test.com", "correct-password", "10.6.0.4")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void elBloqueoNoDependeDeLasMayusculasDelEmail() throws Exception {
        createUser("user7");
        login("user7@test.com", "mal", "10.7.0.1");
        login("USER7@TEST.com", "mal", "10.7.0.2");
        login("User7@Test.com", "mal", "10.7.0.3");

        login("user7@test.com", "correct-password", "10.7.0.4")
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void unEmailInexistenteSeBloqueaIgualQueUnoExistente() throws Exception {
        // Si solo se bloquearan las cuentas reales, el 429 delataría qué
        // emails están registrados.
        login("fantasma@test.com", "mal", "10.8.0.1").andExpect(status().isUnauthorized());
        login("fantasma@test.com", "mal", "10.8.0.2").andExpect(status().isUnauthorized());
        login("fantasma@test.com", "mal", "10.8.0.3")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));

        login("fantasma@test.com", "mal", "10.8.0.4")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"));
    }

    @Test
    void unLoginCorrectoReiniciaElContadorDeFallos() throws Exception {
        createUser("user8");
        login("user8@test.com", "mal", "10.9.0.1").andExpect(status().isUnauthorized());
        login("user8@test.com", "mal", "10.9.0.2").andExpect(status().isUnauthorized());
        login("user8@test.com", "correct-password", "10.9.0.3").andExpect(status().isNoContent());

        // Vuelve a tener 3 intentos fallidos disponibles
        login("user8@test.com", "mal", "10.9.0.4").andExpect(status().isUnauthorized());
        login("user8@test.com", "mal", "10.9.0.5").andExpect(status().isUnauthorized());
        login("user8@test.com", "correct-password", "10.9.0.6").andExpect(status().isNoContent());
    }

    @Test
    void elBloqueoDeUnaCuentaNoAfectaAOtra() throws Exception {
        createUser("user9");
        User other = new User();
        other.setUsername("other");
        other.setEmail("other@test.com");
        other.setPassword(passwordEncoder.encode("other-password"));
        other.setRole(Role.USER);
        other.setCreatedAt(Instant.now());
        userRepository.save(other);

        login("user9@test.com", "mal", "10.10.0.1");
        login("user9@test.com", "mal", "10.10.0.2");
        login("user9@test.com", "mal", "10.10.0.3");

        login("other@test.com", "other-password", "10.10.0.4").andExpect(status().isNoContent());
    }

    // --- Variantes de la ruta (regresión) ---
    //
    // El filtro comparaba getRequestURI() (la ruta tal como llega, sin
    // decodificar) con "/api/auth/login" y "/api/users". Spring MVC y las
    // reglas de autorización sí decodifican cada segmento, así que
    // "/api/auth/%6cogin" llegaba al login sin pasar por el límite. Se usa
    // URI.create porque post(String) volvería a codificar el '%'.

    /**
     * Agotado el límite de login de una IP, la misma petición con la ruta
     * codificada (que Spring MVC atiende igual) debe recibir también el 429.
     * Sin el arreglo respondía 401 y dejaba probar contraseñas sin límite.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/api/auth/%6cogin", "/api/auth/%6C%6F%67%69%6E", "/%61pi/auth/login", "/api/%61uth/login"
    })
    void elLimiteDeLoginNoSeSaltaCodificandoLaRuta(String encodedPath) throws Exception {
        String ip = nextVariantIp();
        // Emails propios de cada invocación: si se repitieran, el bloqueo por
        // cuenta (3 fallos) daría un 429 que no es el que se quiere probar.
        String prefix = "variante" + VARIANT_SEQUENCE.get() + "-";
        for (int i = 0; i < 3; i++) {
            login(prefix + i + "@test.com", "x", ip).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post(URI.create(encodedPath))
                        .with(ip(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", prefix + "x@test.com", "password", "x"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }

    /**
     * Agotado el límite de registro de una IP, la ruta codificada no debe
     * crear la cuenta. Sin el arreglo respondía 201 y permitía registrar
     * cuentas sin límite.
     */
    @ParameterizedTest
    @ValueSource(strings = { "/api/%75sers", "/api/user%73", "/%61pi/users" })
    void elLimiteDeRegistroNoSeSaltaCodificandoLaRuta(String encodedPath) throws Exception {
        String ip = nextVariantIp();
        String prefix = "codif" + VARIANT_SEQUENCE.get();
        register(prefix + "a", ip).andExpect(status().isCreated());
        register(prefix + "b", ip).andExpect(status().isCreated());

        mockMvc.perform(post(URI.create(encodedPath))
                        .with(ip(ip))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody(prefix + "c")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));

        assertFalse(userRepository.existsByEmail(prefix + "c@test.com"));
    }

    /**
     * Variantes que el filtro no cuenta porque tampoco llegan al registro ni
     * al login: Spring MVC no enruta la barra final ni otras mayúsculas (y las
     * reglas de autorización tampoco las hacen públicas, así que sin token
     * dan 401), y el cortafuegos de Spring Security rechaza el punto y coma
     * de {@code ;jsessionid} con un 400. En ningún caso se crea la cuenta.
     */
    @Test
    void lasVariantesQueSpringNoEnrutaNoCreanCuentasNiDanToken() throws Exception {
        String ip = nextVariantIp();
        register("noenruta1", ip).andExpect(status().isCreated());
        register("noenruta2", ip).andExpect(status().isCreated());

        for (String path : new String[] { "/api/users/", "/api/Users", "/api/USERS" }) {
            mockMvc.perform(post(URI.create(path)).with(ip(ip))
                            .contentType(MediaType.APPLICATION_JSON).content(registerBody("noenruta3")))
                    .andExpect(status().isUnauthorized());
        }
        for (String path : new String[] { "/api/auth/login/", "/api/auth/Login" }) {
            mockMvc.perform(post(URI.create(path)).with(ip(ip))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("email", "noenruta1@test.com", "password", "Secure-Pass-2026"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.token").doesNotExist());
        }
        mockMvc.perform(post(URI.create("/api/users;jsessionid=abc")).with(ip(ip))
                        .contentType(MediaType.APPLICATION_JSON).content(registerBody("noenruta3")))
                .andExpect(status().isBadRequest());

        assertFalse(userRepository.existsByEmail("noenruta3@test.com"));
    }

    /**
     * Con un token válido (el de una cuenta ya registrada) las variantes no
     * enrutadas pasan la autorización, pero Spring MVC no las asocia al
     * registro: no se crea la cuenta, así que no son un atajo para saltarse
     * el límite.
     */
    @Test
    void conTokenLasVariantesNoEnrutadasTampocoRegistran() throws Exception {
        String ip = nextVariantIp();
        register("contoken1", ip).andExpect(status().isCreated());
        register("contoken2", ip).andExpect(status().isCreated());
        String token = "Bearer " + jwtService.generateToken(
                userRepository.findByEmail("contoken1@test.com").orElseThrow());

        for (String path : new String[] { "/api/users/", "/api/Users" }) {
            mockMvc.perform(post(URI.create(path)).with(ip(ip)).header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content(registerBody("contoken3")))
                    .andExpect(status().isNotFound());
        }

        assertFalse(userRepository.existsByEmail("contoken3@test.com"));
    }

    // --- Utilidades ---

    /** Secuencia para las IPs de los tests de variantes (10.11.x.y, sin uso en otros tests). */
    private static final AtomicInteger VARIANT_SEQUENCE = new AtomicInteger();

    private static String nextVariantIp() {
        int n = VARIANT_SEQUENCE.incrementAndGet();
        return "10.11." + (n / 250) + "." + (n % 250 + 1);
    }

    private ResultActions login(String email, String password, String ip) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .with(ip(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private ResultActions register(String name, String ip) throws Exception {
        return mockMvc.perform(post("/api/users")
                .with(ip(ip))
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody(name)));
    }

    private String registerBody(String name) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "username", name,
                "email", name + "@test.com",
                "password", "Secure-Pass-2026"));
    }

    private static RequestPostProcessor ip(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
