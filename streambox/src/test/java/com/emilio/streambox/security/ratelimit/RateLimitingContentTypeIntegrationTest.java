package com.emilio.streambox.security.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import com.emilio.streambox.dto.CreateUserRequest;
import com.emilio.streambox.dto.LoginRequest;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Regresión de la pista NV-A (auditoría 2): las peticiones de login y registro
 * que el servidor rechaza por su {@code Content-Type} no deben gastar el
 * presupuesto por IP de {@link RateLimitingFilter}.
 *
 * <p>
 * El ataque: una web ajena hace que el navegador de la víctima envíe
 * {@code POST} «simples» (sin preflight de CORS) al login o al registro, con
 * {@code text/plain}, un formulario o sin cuerpo. Spring MVC los rechaza
 * (415/400, porque ningún conversor lee esos tipos), pero antes el filtro
 * ya los había contado: unas pocas peticiones dejaban la IP de la víctima
 * en 429 y no podía iniciar sesión ni registrarse.
 * </p>
 *
 * <p>
 * Los controles comprueban que el arreglo no desactiva el límite: cualquier
 * variante de JSON (con parámetros, mayúsculas o un subtipo {@code +json})
 * sigue contando, también con credenciales incorrectas, y también el YAML,
 * que ya se rechaza con 415 pero no es CORS-safelisted. Dos tests más vigilan
 * la garantía en la que se apoya el arreglo: ningún conversor lee el login o el registro desde un tipo
 * que no cuenta. Los límites se bajan a 2 peticiones; cada test usa una IP y
 * emails propios porque los contadores viven en el contexto.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
        "streambox.security.rate-limit.login.max-requests=2",
        "streambox.security.rate-limit.register.max-requests=2"
})
class RateLimitingContentTypeIntegrationTest {

    private static final int LIMIT = 2;
    private static final String PASSWORD = "Contraseña-Larga-Correcta-2026";

    /** Secuencia para IPs (198.51.100.x, TEST-NET-2) y emails únicos por test. */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private RequestMappingHandlerAdapter handlerAdapter;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Peticiones que una web ajena puede hacer enviar al navegador de la
     * víctima sin preflight: los tres tipos de la lista CORS-safelisted y la
     * petición sin {@code Content-Type} ({@code fetch(url, {method: 'POST',
     * mode: 'no-cors'})}). Cada una lleva el estado con el que la rechaza
     * Spring MVC (sin cuerpo falta el {@code @RequestBody}: 400).
     */
    enum CrossSiteRequest {
        TEXT_PLAIN(415) {
            @Override
            AbstractMockHttpServletRequestBuilder<?> build(String path) {
                return post(path).contentType(MediaType.TEXT_PLAIN).content("{\"email\":\"x\"}");
            }
        },
        /**
         * Lo que envía de verdad el navegador con {@code fetch(url, {method:
         * 'POST', mode: 'no-cors', body: '...'})}: el cuerpo de texto lleva
         * {@code charset}. Protege la comparación por tipo y subtipo, sin
         * parámetros: si el filtro comparara también los parámetros, esta
         * variante contaría.
         */
        TEXT_PLAIN_CON_CHARSET(415) {
            @Override
            AbstractMockHttpServletRequestBuilder<?> build(String path) {
                return post(path).header("Content-Type", "text/plain;charset=UTF-8").content("{\"email\":\"x\"}");
            }
        },
        FORM_URLENCODED(415) {
            @Override
            AbstractMockHttpServletRequestBuilder<?> build(String path) {
                return post(path).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .content("email=x%40test.com&password=x");
            }
        },
        MULTIPART(415) {
            @Override
            AbstractMockHttpServletRequestBuilder<?> build(String path) {
                return multipart(path).file(new MockMultipartFile(
                        "email", null, "text/plain", "x@test.com".getBytes(StandardCharsets.UTF_8)));
            }
        },
        SIN_CONTENT_TYPE_NI_CUERPO(400) {
            @Override
            AbstractMockHttpServletRequestBuilder<?> build(String path) {
                return post(path);
            }
        },
        SIN_CONTENT_TYPE_CON_CUERPO(415) {
            @Override
            AbstractMockHttpServletRequestBuilder<?> build(String path) {
                return post(path).content("{\"email\":\"x\"}");
            }
        };

        private final int expectedStatus;

        CrossSiteRequest(int expectedStatus) {
            this.expectedStatus = expectedStatus;
        }

        abstract AbstractMockHttpServletRequestBuilder<?> build(String path);
    }

    // ------------------------------------------------------------------
    // Regresión: lo que puede enviar otra web no gasta el presupuesto
    // ------------------------------------------------------------------

    /**
     * Sin el arreglo, la tercera petición ya recibía 429 y el login legítimo
     * de la víctima, también.
     */
    @ParameterizedTest
    @EnumSource(CrossSiteRequest.class)
    void lasPeticionesCrossSiteRechazadasNoAgotanElLimiteDeLogin(CrossSiteRequest kind) throws Exception {
        String victimIp = nextIp();
        String email = createUser();

        for (int i = 0; i < LIMIT * 3; i++) {
            mockMvc.perform(kind.build(RateLimitingFilter.LOGIN_PATH)
                            .with(ip(victimIp)).header("Origin", "https://evil.example"))
                    .andExpect(status().is(kind.expectedStatus));
        }

        jsonLogin(email, PASSWORD, victimIp).andExpect(status().isNoContent());
    }

    /** Lo mismo con el registro: la víctima puede seguir creando su cuenta. */
    @ParameterizedTest
    @EnumSource(CrossSiteRequest.class)
    void lasPeticionesCrossSiteRechazadasNoAgotanElLimiteDeRegistro(CrossSiteRequest kind) throws Exception {
        String victimIp = nextIp();

        for (int i = 0; i < LIMIT * 3; i++) {
            mockMvc.perform(kind.build(RateLimitingFilter.REGISTER_PATH)
                            .with(ip(victimIp)).header("Origin", "https://evil.example"))
                    .andExpect(status().is(kind.expectedStatus));
        }

        String name = "ctreg" + SEQUENCE.incrementAndGet();
        register(name, MediaType.APPLICATION_JSON_VALUE, victimIp).andExpect(status().isCreated());
        assertTrue(userRepository.existsByEmail(name + "@test.com"));
    }

    /**
     * Un {@code Content-Type} mal formado tampoco cuenta (el filtro captura el
     * error de análisis) y Spring MVC lo rechaza igual, con 415.
     */
    @ParameterizedTest
    @ValueSource(strings = { "json", "application/", "/json", "application/json/x" })
    void unContentTypeMalFormadoNoCuentaNiLlegaAlLogin(String contentType) throws Exception {
        String ip = nextIp();
        String email = createUser();

        for (int i = 0; i < LIMIT * 2; i++) {
            mockMvc.perform(post(RateLimitingFilter.LOGIN_PATH).with(ip(ip))
                            .header("Content-Type", contentType)
                            .content(loginBody(email, PASSWORD)))
                    .andExpect(status().isUnsupportedMediaType());
        }

        jsonLogin(email, PASSWORD, ip).andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------
    // Controles: el JSON, en cualquiera de sus formas, sigue contando
    // ------------------------------------------------------------------

    /**
     * Cada variante de JSON que acepta Spring MVC gasta el presupuesto, aunque
     * las credenciales sean incorrectas: al superarlo, hasta el login correcto
     * recibe 429.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "application/json", "application/json; charset=UTF-8", "Application/JSON",
            "APPLICATION/JSON;CHARSET=utf-8", "application/vnd.api+json", "application/problem+json"
    })
    void elLoginConCualquierVarianteDeJsonCuenta(String contentType) throws Exception {
        String ip = nextIp();
        String email = createUser();

        for (int i = 0; i < LIMIT; i++) {
            login(loginBody("nadie" + SEQUENCE.incrementAndGet() + "@test.com", "mala"), contentType, ip)
                    .andExpect(status().isUnauthorized());
        }

        jsonLogin(email, PASSWORD, ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }

    /** Los logins correctos seguidos también agotan el límite. */
    @Test
    void variosLoginsJsonCorrectosSeguidosAcabanEn429() throws Exception {
        String ip = nextIp();
        String email = createUser();

        for (int i = 0; i < LIMIT; i++) {
            jsonLogin(email, PASSWORD, ip).andExpect(status().isNoContent());
        }

        jsonLogin(email, PASSWORD, ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }

    /** El registro cuenta con cualquier variante de JSON; la cuenta que sobra no se crea. */
    @ParameterizedTest
    @ValueSource(strings = { "application/json; charset=UTF-8", "Application/JSON", "application/vnd.api+json" })
    void elRegistroConCualquierVarianteDeJsonCuenta(String contentType) throws Exception {
        String ip = nextIp();
        String prefix = "ctjson" + SEQUENCE.incrementAndGet();

        for (int i = 0; i < LIMIT; i++) {
            register(prefix + "n" + i, contentType, ip).andExpect(status().isCreated());
        }

        register(prefix + "x", MediaType.APPLICATION_JSON_VALUE, ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
        assertFalse(userRepository.existsByEmail(prefix + "x@test.com"));
    }

    // ------------------------------------------------------------------
    // Garantía: lo que no se cuenta no puede llegar al controlador
    // ------------------------------------------------------------------

    /**
     * Lo que el filtro deja pasar sin contar solo es seguro si ningún
     * conversor de Spring MVC sabe leer el cuerpo del login o del registro
     * desde esos tipos: si alguno lo hiciera, esa petición llegaría al
     * controlador sin límite. Sin {@code Content-Type}, Spring trata el cuerpo
     * como {@code application/octet-stream}, por eso se incluye ese tipo.
     */
    @ParameterizedTest
    @ValueSource(strings = { "text/plain", "text/plain;charset=UTF-8", "application/x-www-form-urlencoded",
            "multipart/form-data", "application/octet-stream" })
    void ningunConversorLeeElLoginNiElRegistroDesdeUnTipoQueNoCuenta(String contentType) {
        MediaType mediaType = MediaType.parseMediaType(contentType);
        if (!mediaType.equals(MediaType.APPLICATION_OCTET_STREAM)) {
            assertFalse(RateLimitingFilter.countsTowardsLimit(contentType),
                    "el tipo debía ser de los que no cuentan: " + contentType);
        }

        List<String> readers = new ArrayList<>();
        for (Class<?> body : List.of(LoginRequest.class, CreateUserRequest.class)) {
            for (HttpMessageConverter<?> converter : handlerAdapter.getMessageConverters()) {
                if (converter.canRead(body, mediaType)) {
                    readers.add(body.getSimpleName() + " (" + converter.getClass().getSimpleName() + ")");
                }
            }
        }

        assertEquals(List.of(), readers, "un conversor lee " + contentType + " sin pasar por el límite");
    }

    /**
     * El otro lado de la garantía: todo tipo desde el que algún conversor
     * lee el login o el registro cuenta. Hoy solo es JSON (el YAML se quitó en
     * {@code JsonOnlyMessageConvertersConfig}); si mañana se añade otro
     * conversor, también contará.
     */
    @Test
    void todoTipoQueLeeUnConversorCuenta() {
        List<String> uncounted = new ArrayList<>();
        for (Class<?> body : List.of(LoginRequest.class, CreateUserRequest.class)) {
            for (HttpMessageConverter<?> converter : handlerAdapter.getMessageConverters()) {
                for (MediaType mediaType : converter.getSupportedMediaTypes(body)) {
                    if (converter.canRead(body, mediaType)
                            && !RateLimitingFilter.countsTowardsLimit(mediaType.toString())) {
                        uncounted.add(body.getSimpleName() + " <- " + mediaType
                                + " (" + converter.getClass().getSimpleName() + ")");
                    }
                }
            }
        }

        assertEquals(List.of(), uncounted);
    }

    /**
     * El YAML ya no llega al controlador (lo rechaza con 415
     * {@code JsonOnlyMessageConvertersConfig}), pero sigue contando: no es un
     * tipo CORS-safelisted, así que otra web no puede enviarlo sin preflight y
     * no hay motivo para eximirlo. Este test fija las dos cosas: el 415 y que
     * el filtro no se ha relajado para el YAML.
     *
     * <p>
     * Historia: antes Spring MVC sí leía el login en YAML (el conversor YAML
     * de Jackson 2 se registraba porque springdoc trae
     * {@code jackson-dataformat-yaml}); por eso el filtro de NV-A cuenta todo
     * lo que no es CORS-safelisted en lugar de solo el JSON.
     * </p>
     */
    @Test
    void elLoginEnYamlSeRechazaYTambienCuenta() throws Exception {
        String ip = nextIp();
        String email = createUser();

        for (int i = 0; i < LIMIT; i++) {
            login("email: " + email + "\npassword: " + PASSWORD + "\n", "application/yaml", ip)
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        }

        jsonLogin(email, PASSWORD, ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }

    /**
     * Lo mismo con el registro: el YAML da 415, no crea cuentas y gasta el
     * presupuesto, así que el registro JSON siguiente recibe 429.
     */
    @Test
    void elRegistroEnYamlSeRechazaYTambienCuenta() throws Exception {
        String ip = nextIp();
        String prefix = "ctyaml" + SEQUENCE.incrementAndGet();

        for (int i = 0; i < LIMIT; i++) {
            mockMvc.perform(post(RateLimitingFilter.REGISTER_PATH).with(ip(ip))
                            .header("Content-Type", "application/yaml").content(yamlRegisterBody(prefix + "n" + i)))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
            assertFalse(userRepository.existsByEmail(prefix + "n" + i + "@test.com"));
        }

        register(prefix + "x", MediaType.APPLICATION_JSON_VALUE, ip)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
        assertFalse(userRepository.existsByEmail(prefix + "x@test.com"));
    }

    /**
     * Los tipos que no son CORS-safelisted cuentan aunque hoy Spring los
     * rechace con 415: otra web no puede enviarlos sin preflight, y contarlos
     * de más evita que un conversor futuro deje un hueco.
     */
    @ParameterizedTest
    @ValueSource(strings = { "application/xml", "text/xml", "application/cbor", "*/*", "application/*" })
    void losDemasTiposCuentanAunqueSeRechacen(String contentType) throws Exception {
        String ip = nextIp();
        String email = createUser();

        for (int i = 0; i < LIMIT; i++) {
            login(loginBody(email, PASSWORD), contentType, ip).andExpect(status().isUnsupportedMediaType());
        }

        jsonLogin(email, PASSWORD, ip).andExpect(status().isTooManyRequests());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static String nextIp() {
        int n = SEQUENCE.incrementAndGet();
        return "198.51." + (100 + n / 250) + "." + (n % 250 + 1);
    }

    /** Crea un usuario con email propio del test y devuelve ese email. */
    private String createUser() {
        String name = "ctuser" + SEQUENCE.incrementAndGet();
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        userRepository.save(user);
        return user.getEmail();
    }

    private ResultActions jsonLogin(String email, String password, String ip) throws Exception {
        return login(loginBody(email, password), MediaType.APPLICATION_JSON_VALUE, ip);
    }

    private ResultActions login(String body, String contentType, String ip) throws Exception {
        return mockMvc.perform(post(RateLimitingFilter.LOGIN_PATH).with(ip(ip))
                .header("Content-Type", contentType).content(body));
    }

    private ResultActions register(String name, String contentType, String ip) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "username", name, "email", name + "@test.com", "password", "Secure-Pass-2026"));
        return mockMvc.perform(post(RateLimitingFilter.REGISTER_PATH).with(ip(ip))
                .header("Content-Type", contentType).content(body));
    }

    private static String yamlRegisterBody(String name) {
        return "username: " + name + "\nemail: " + name + "@test.com\npassword: Secure-Pass-2026\n";
    }

    private String loginBody(String email, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("email", email, "password", password));
    }

    private static RequestPostProcessor ip(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }
}
