package com.emilio.streambox.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.emilio.streambox.dto.CreateUserRequest;
import com.emilio.streambox.dto.GenreRequest;
import com.emilio.streambox.dto.LoginRequest;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * Los cuerpos de la API solo se leen en JSON (lo garantiza
 * {@link JsonOnlyMessageConvertersConfig}).
 *
 * <p>
 * Bug que protege: springdoc trae {@code jackson-dataformat-yaml} y Spring
 * registraba por ello un conversor YAML, de modo que cualquier
 * {@code @RequestBody} (el login, el registro, el alta de un género...) se
 * aceptaba también con {@code Content-Type: application/yaml}: un segundo
 * analizador procesando entrada de cualquier cliente sin que nadie lo usara.
 * Sin el arreglo, {@link #ningunCuerpoDeLaApiSeLeeDesdeUnTipoQueNoSeaJson()}
 * encuentra el conversor YAML y los tests de YAML reciben 401/201 en lugar de
 * 415.
 * </p>
 *
 * <p>
 * Los controles comprueban lo que no debe romperse: el JSON sigue entrando y
 * saliendo igual y {@code /v3/api-docs.yaml} sigue sirviendo el OpenAPI en
 * YAML (springdoc lo escribe él mismo, sin el conversor).
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class JsonOnlyRequestBodyIntegrationTest {

    /**
     * Tipos que no son JSON con los que se intenta leer cada cuerpo, además de
     * los que anuncia cada conversor: YAML en sus variantes, XML, formatos
     * binarios de Jackson y los tipos que un formulario puede enviar.
     */
    private static final List<MediaType> NON_JSON_PROBES = List.of(
            MediaType.parseMediaType("application/yaml"),
            MediaType.parseMediaType("application/x-yaml"),
            MediaType.parseMediaType("text/yaml"),
            MediaType.APPLICATION_XML,
            MediaType.TEXT_XML,
            MediaType.parseMediaType("application/cbor"),
            MediaType.parseMediaType("application/x-jackson-smile"),
            MediaType.parseMediaType("application/x-protobuf"),
            MediaType.TEXT_PLAIN,
            MediaType.APPLICATION_FORM_URLENCODED,
            MediaType.MULTIPART_FORM_DATA,
            MediaType.APPLICATION_OCTET_STREAM);

    @Autowired private MockMvc mockMvc;
    @Autowired private RequestMappingHandlerAdapter handlerAdapter;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping handlerMapping;
    @Autowired private UserRepository userRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String adminToken;

    @BeforeEach
    void setUp() {
        User admin = new User();
        admin.setUsername("jsononlyadmin");
        admin.setEmail("jsononlyadmin@test.com");
        admin.setPassword(passwordEncoder.encode("Contraseña-Larga-Correcta-2026"));
        admin.setRole(Role.ADMIN);
        admin.setCreatedAt(Instant.now());
        adminToken = "Bearer " + jwtService.generateToken(userRepository.save(admin));
    }

    // ------------------------------------------------------------------
    // Garantía global: todos los @RequestBody de la aplicación
    // ------------------------------------------------------------------

    /**
     * Recorre todos los endpoints y, para cada tipo de {@code @RequestBody},
     * busca un conversor que lo lea desde un tipo que no sea JSON. No depende
     * de acordarse de los endpoints nuevos: si mañana una dependencia trae un
     * conversor XML, CBOR o YAML capaz de leer los DTO, este test falla.
     */
    @Test
    void ningunCuerpoDeLaApiSeLeeDesdeUnTipoQueNoSeaJson() {
        Set<Class<?>> bodies = requestBodyTypes();

        List<String> nonJsonReaders = new ArrayList<>();
        for (Class<?> body : bodies) {
            for (HttpMessageConverter<?> converter : handlerAdapter.getMessageConverters()) {
                Set<MediaType> candidates = new LinkedHashSet<>(converter.getSupportedMediaTypes(body));
                candidates.addAll(NON_JSON_PROBES);
                for (MediaType mediaType : candidates) {
                    if (!isJson(mediaType) && converter.canRead(body, mediaType)) {
                        nonJsonReaders.add(body.getSimpleName() + " <- " + mediaType
                                + " (" + converter.getClass().getSimpleName() + ")");
                    }
                }
            }
        }

        assertEquals(List.of(), nonJsonReaders, "solo JSON debe poder leer los cuerpos de la API");
    }

    /**
     * Control del test anterior: el recorrido encuentra los cuerpos reales y
     * todos se siguen leyendo en JSON (si la lista saliera vacía, la garantía
     * se cumpliría sin comprobar nada).
     */
    @Test
    void todosLosCuerposDeLaApiSeSiguenLeyendoEnJson() {
        Set<Class<?>> bodies = requestBodyTypes();
        assertTrue(bodies.containsAll(Set.of(LoginRequest.class, CreateUserRequest.class, GenreRequest.class)),
                "el recorrido no encontró los cuerpos esperados: " + bodies);

        for (Class<?> body : bodies) {
            boolean readable = handlerAdapter.getMessageConverters().stream()
                    .anyMatch(converter -> converter.canRead(body, MediaType.APPLICATION_JSON));
            assertTrue(readable, body.getSimpleName() + " debe poder leerse en JSON");
        }
    }

    // ------------------------------------------------------------------
    // Peticiones reales en YAML
    // ------------------------------------------------------------------

    /**
     * El login en YAML ya no llega al controlador: 415 con el formato de error
     * de la API. Sin el arreglo, respondía 401 (credenciales leídas del YAML).
     */
    @ParameterizedTest
    @ValueSource(strings = { "application/yaml", "application/yaml;charset=UTF-8", "Application/YAML" })
    void elLoginEnYamlSeRechazaCon415(String contentType) throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .header("Content-Type", contentType)
                        .content("email: nadie@test.com\npassword: mala\n"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.path").value("/api/auth/login"));
    }

    /**
     * Lo mismo en un endpoint autenticado: el alta de un género en YAML da 415
     * y no crea nada. Sin el arreglo, respondía 201 y el género existía.
     */
    @Test
    void elAltaDeUnGeneroEnYamlSeRechazaCon415YNoCreaNada() throws Exception {
        mockMvc.perform(post("/api/genres")
                        .header("Authorization", adminToken)
                        .header("Content-Type", "application/yaml")
                        .content("name: Desdeyaml\n"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        assertFalse(genreRepository.existsByNameIgnoreCase("Desdeyaml"));
    }

    /**
     * Control: el mismo alta en JSON funciona con el mismo token, así que el
     * 415 anterior se debe al formato y no a la autenticación.
     */
    @Test
    void elAltaDeUnGeneroEnJsonSigueFuncionando() throws Exception {
        mockMvc.perform(post("/api/genres")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Desdejson\"}"))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.name").value("Desdejson"));
    }

    // ------------------------------------------------------------------
    // Respuestas: JSON igual que antes, YAML solo en /v3/api-docs.yaml
    // ------------------------------------------------------------------

    /** Las respuestas siguen en JSON, se pida explícitamente o con {@code *}{@code /*}. */
    @ParameterizedTest
    @ValueSource(strings = { "application/json", "*/*" })
    void lasRespuestasSiguenEnJson(String accept) throws Exception {
        mockMvc.perform(get("/api/genres").header("Authorization", adminToken).header("Accept", accept))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    /**
     * Pedir la respuesta en YAML ya no serializa los DTO en ese formato: 406
     * {@code NOT_ACCEPTABLE}, explicado en JSON. Es la otra mitad de quitar el
     * conversor (no se conserva para escribir porque nadie consume YAML).
     *
     * <p>
     * MockMvc no hace el reenvío a {@code /error} de Tomcat, así que este test
     * no basta para saber que un cliente real recibe el 406: eso lo prueba
     * {@code ErrorResponseAlwaysJsonTomcatIntegrationTest}.
     * </p>
     */
    @Test
    void pedirLaRespuestaEnYamlDa406() throws Exception {
        mockMvc.perform(get("/api/genres").header("Authorization", adminToken).header("Accept", "application/yaml"))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("NOT_ACCEPTABLE"));
    }

    /**
     * {@code /v3/api-docs.yaml} sigue devolviendo el OpenAPI en YAML válido:
     * springdoc lo genera él mismo y lo devuelve como bytes, así que no
     * necesitaba el conversor que se ha quitado. Va con token porque
     * {@code SecurityConfig} solo deja pública {@code /v3/api-docs/**} y esta
     * ruta ({@code .yaml}) no encaja en ese patrón.
     */
    @Test
    void elOpenApiEnYamlSigueFuncionando() throws Exception {
        String yaml = mockMvc.perform(get("/v3/api-docs.yaml").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertTrue(yaml.startsWith("openapi:"), "debe ser YAML, no JSON: " + yaml.substring(0, 40));
        JsonNode document = new ObjectMapper(new YAMLFactory()).readTree(yaml);
        assertFalse(document.path("paths").path("/api/auth/login").isMissingNode(),
                "el OpenAPI en YAML debe documentar el login");
        assertEquals(document.path("info").path("title").asText(),
                objectMapper.readTree(mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse()
                        .getContentAsString(StandardCharsets.UTF_8)).path("info").path("title").asText(),
                "el YAML y el JSON describen la misma API");
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Tipos de todos los parámetros {@code @RequestBody} de los controladores. */
    private Set<Class<?>> requestBodyTypes() {
        Set<Class<?>> bodies = new LinkedHashSet<>();
        for (HandlerMethod method : handlerMapping.getHandlerMethods().values()) {
            for (MethodParameter parameter : method.getMethodParameters()) {
                if (parameter.hasParameterAnnotation(RequestBody.class)) {
                    bodies.add(parameter.getParameterType());
                }
            }
        }
        return bodies;
    }

    /** {@code application/json} o un subtipo estructurado {@code application/*+json}. */
    private static boolean isJson(MediaType mediaType) {
        return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(mediaType)
                || ("application".equals(mediaType.getType()) && "json".equals(mediaType.getSubtypeSuffix()));
    }
}
