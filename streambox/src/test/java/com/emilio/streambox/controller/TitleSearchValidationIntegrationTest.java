package com.emilio.streambox.controller;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Validación del parámetro {@code title} de las tres búsquedas por título
 * ({@code /api/movies/search}, {@code /api/series/search} y
 * {@code /api/admin/series}): como mucho 150 caracteres y ningún carácter de
 * control. Lo que no la cumple es un 400 {@code VALIDATION_ERROR} con el motivo
 * en {@code validationErrors.title}, nunca un 500.
 *
 * <p>
 * El caso que la motivó: {@code title=a%00b} llegaba hasta PostgreSQL, que no
 * admite NUL en un texto, y respondía 500 (además de romper una conexión del
 * pool mientras el texto iba como literal; eso se prueba contra PostgreSQL real
 * en {@code PostgresTitleSearchIntegrationTest}). Aquí, con H2, se comprueba el
 * contrato del 400, que no depende del motor.
 * </p>
 *
 * <p>
 * Con {@code @Transactional}: solo crea los usuarios de los tokens y se deshace
 * al terminar.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class TitleSearchValidationIntegrationTest {

    private static final List<String> ENDPOINTS =
            List.of("/api/movies/search", "/api/series/search", "/api/admin/series");

    private static final String CONTROL_MESSAGE = "La búsqueda no puede contener caracteres de control";
    private static final String LENGTH_MESSAGE = "La búsqueda no puede superar los 150 caracteres";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String userToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        userToken = "Bearer " + jwtService.generateToken(saveUser("titlevalidationuser", Role.USER));
        adminToken = "Bearer " + jwtService.generateToken(saveUser("titlevalidationadmin", Role.ADMIN));
    }

    /**
     * Caracteres de control (categoría Unicode {@code Cc}) en cualquier
     * posición: el NUL, el tabulador y los saltos de línea (no se admiten
     * aunque PostgreSQL sí los aceptara: la búsqueda es de una línea), el
     * escape, el {@code DEL} y uno de los de control C1 ({@code U+0085}).
     */
    static Stream<Arguments> textsWithControlCharacters() {
        List<String> texts = List.of("a\u0000b", "\u0000", "interstelar\u0000", "matrix\t", "ma\ntrix",
                "\rmatrix", "a\u001Bb", "matrix\u007F", "ma\u0085trix");
        return forEveryEndpoint(texts);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("textsWithControlCharacters")
    void unCaracterDeControlEsUnErrorDeValidacion(String endpoint, String text) throws Exception {
        assertValidationError(search(endpoint, text), CONTROL_MESSAGE);
    }

    /** Uno más que el límite (y mucho más) es un 400; el propio límite, no. */
    static Stream<Arguments> textsTooLong() {
        return forEveryEndpoint(List.of("x".repeat(151), "alienx".repeat(300)));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("textsTooLong")
    void unTextoDeMasDe150CaracteresEsUnErrorDeValidacion(String endpoint, String text) throws Exception {
        assertValidationError(search(endpoint, text), LENGTH_MESSAGE);
    }

    /**
     * Lo que sí se acepta: el límite exacto, caracteres de formato invisibles
     * ({@code Cf}: espacio de ancho cero, BOM, marca de dirección), que no son
     * de control, y letras con marcas combinantes.
     */
    static Stream<Arguments> validTexts() {
        return forEveryEndpoint(List.of("x".repeat(150), "​matrix", "﻿", "‮alien",
                "mañana", "Ǆǅǆ İı ß ﬁ"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("validTexts")
    void losTextosSinCaracteresDeControlYDentroDelLimiteSeAceptan(String endpoint, String text) throws Exception {
        MockHttpServletResponse response = search(endpoint, text);

        assertEquals(200, response.getStatus(), response.getContentAsString());
    }

    /** Un texto largo <em>y</em> con un NUL es un único 400 con uno de los dos motivos. */
    static Stream<Arguments> endpoints() {
        return ENDPOINTS.stream().map(Arguments::of);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("endpoints")
    void conLosDosProblemasSeRespondeUnSolo400(String endpoint) throws Exception {
        MockHttpServletResponse response = search(endpoint, "x".repeat(200) + "\u0000");

        assertEquals(400, response.getStatus(), response.getContentAsString());
        String message = body(response).path("validationErrors").path("title").asText();
        assertTrue(CONTROL_MESSAGE.equals(message) || LENGTH_MESSAGE.equals(message), message);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static Stream<Arguments> forEveryEndpoint(List<String> texts) {
        List<Arguments> arguments = new ArrayList<>();
        for (String endpoint : ENDPOINTS) {
            for (String text : texts) {
                arguments.add(Arguments.of(endpoint, text));
            }
        }
        return arguments.stream();
    }

    private MockHttpServletResponse search(String endpoint, String text) throws Exception {
        String token = endpoint.startsWith("/api/admin") ? adminToken : userToken;
        return mockMvc.perform(get(endpoint).header("Authorization", token).param("title", text))
                .andReturn().getResponse();
    }

    private void assertValidationError(MockHttpServletResponse response, String message) throws Exception {
        assertEquals(400, response.getStatus(), response.getContentAsString());
        JsonNode body = body(response);
        assertEquals("VALIDATION_ERROR", body.path("code").asText());
        assertEquals(message, body.path("validationErrors").path("title").asText());
    }

    private JsonNode body(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
    }

    private User saveUser(String name, Role role) {
        User user = new User();
        user.setEmailVerifiedAt(Instant.now()); // cuenta verificada (V9)
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(role);
        return userRepository.save(user);
    }
}
