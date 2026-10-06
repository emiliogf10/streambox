package com.emilio.streambox.security;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.UserRepository;

/**
 * Comprueba las reglas de {@link SecurityConfig} para las series
 * ({@code /api/series/**}), las vistas de gestión ({@code /api/admin/**}) y
 * los favoritos de series ({@code /api/users/me/favorites/series}).
 *
 * <p>
 * Las reglas se escribieron <em>antes</em> que los controladores, para que
 * ningún endpoint de series naciera abierto. Por eso estas pruebas no dependen
 * de que el endpoint exista: la autorización la decide la cadena de filtros de
 * Spring Security antes de llegar a Spring MVC. Sin las reglas, la petición de
 * un {@code USER} caería en {@code anyRequest().authenticated()}, pasaría el
 * filtro y recibiría un 404 (o el código del controlador) en lugar de un 403.
 * </p>
 *
 * <p>
 * Igual que {@link CatalogWriteAuthorizationIntegrationTest}, no se comprueban
 * códigos concretos con un {@code ADMIN} ni el 200 de las lecturas de un
 * {@code USER}: dependen de los controladores y los cubren sus propios tests.
 * Lo que sí se comprueba es que las lecturas y los favoritos <em>no</em>
 * reciben un 403, porque una regla amplia mal colocada dejaría a los usuarios
 * sin series y ningún test de "403 para USER" lo detectaría.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class SeriesAuthorizationIntegrationTest {

    private static final String BODY = "{\"title\":\"Serie\"}";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String userToken;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setUsername("seriesauthuser");
        user.setEmail("seriesauthuser@test.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(Role.USER);
        user.setCreatedAt(Instant.now());
        userToken = "Bearer " + jwtService.generateToken(userRepository.save(user));
    }

    /** Escrituras de series y episodios del contrato: solo ADMIN. */
    static Stream<Arguments> escriturasDeSeries() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/api/series"),
                Arguments.of(HttpMethod.PUT, "/api/series/1"),
                Arguments.of(HttpMethod.DELETE, "/api/series/1"),
                Arguments.of(HttpMethod.POST, "/api/series/1/episodes"),
                Arguments.of(HttpMethod.PUT, "/api/series/1/episodes/2"),
                Arguments.of(HttpMethod.DELETE, "/api/series/1/episodes/2"));
    }

    /**
     * Métodos sin endpoint previsto en las series. {@code PATCH} tiene regla
     * propia; {@code OPTIONS} lo atrapa la regla de cierre del catálogo.
     */
    static Stream<Arguments> metodosSinEndpointSobreSeries() {
        return Stream.of(
                Arguments.of(HttpMethod.PATCH, "/api/series/1"),
                Arguments.of(HttpMethod.PATCH, "/api/series/1/episodes/2"),
                Arguments.of(HttpMethod.OPTIONS, "/api/series"),
                Arguments.of(HttpMethod.OPTIONS, "/api/series/1/episodes"));
    }

    /** Vistas de gestión: también la lectura es solo para ADMIN. */
    static Stream<Arguments> vistasDeGestion() {
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/api/admin/series"),
                Arguments.of(HttpMethod.GET, "/api/admin/series/1"),
                Arguments.of(HttpMethod.GET, "/api/admin/series?search=algo&page=0"),
                Arguments.of(HttpMethod.HEAD, "/api/admin/series"),
                Arguments.of(HttpMethod.POST, "/api/admin/series"),
                Arguments.of(HttpMethod.OPTIONS, "/api/admin/series/1"));
    }

    /** Lecturas del contrato de series para cualquier usuario autenticado. */
    static Stream<Arguments> lecturasDeSeries() {
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/api/series"),
                Arguments.of(HttpMethod.GET, "/api/series/search?title=algo"),
                Arguments.of(HttpMethod.GET, "/api/series/1"),
                Arguments.of(HttpMethod.HEAD, "/api/series"),
                Arguments.of(HttpMethod.HEAD, "/api/series/1"));
    }

    /** Favoritos de series: personales, para cualquier usuario autenticado. */
    static Stream<Arguments> favoritosDeSeries() {
        return Stream.of(
                Arguments.of(HttpMethod.GET, "/api/users/me/favorites/series"),
                Arguments.of(HttpMethod.DELETE, "/api/users/me/favorites/series"),
                Arguments.of(HttpMethod.POST, "/api/users/me/favorites/series/1"),
                Arguments.of(HttpMethod.DELETE, "/api/users/me/favorites/series/1"));
    }

    // --- Escritura de series y episodios ---

    @ParameterizedTest(name = "{0} {1} sin token -> 401")
    @MethodSource("escriturasDeSeries")
    void escrituraDeSeriesSinTokenRetorna401(HttpMethod method, String url) throws Exception {
        mockMvc.perform(withBody(request(method, url)))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "{0} {1} con USER -> 403")
    @MethodSource("escriturasDeSeries")
    void escrituraDeSeriesConTokenUserRetorna403(HttpMethod method, String url) throws Exception {
        mockMvc.perform(withBody(request(method, url).header("Authorization", userToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    // --- Red de seguridad: PATCH y métodos sin regla propia ---

    @ParameterizedTest(name = "{0} {1} con USER -> 403")
    @MethodSource("metodosSinEndpointSobreSeries")
    void metodoSinEndpointSobreSeriesConTokenUserRetorna403(HttpMethod method, String url) throws Exception {
        mockMvc.perform(withBody(request(method, url).header("Authorization", userToken)))
                .andExpect(status().isForbidden());
    }

    // --- Vistas de gestión (/api/admin/**) ---

    @ParameterizedTest(name = "{0} {1} sin token -> 401")
    @MethodSource("vistasDeGestion")
    void vistaDeGestionSinTokenRetorna401(HttpMethod method, String url) throws Exception {
        mockMvc.perform(request(method, url))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "{0} {1} con USER -> 403")
    @MethodSource("vistasDeGestion")
    void vistaDeGestionConTokenUserRetorna403(HttpMethod method, String url) throws Exception {
        mockMvc.perform(request(method, url).header("Authorization", userToken))
                .andExpect(status().isForbidden());
    }

    // --- Lecturas y favoritos: autenticados, sin cerrar a un USER ---

    @Test
    void listadoDeSeriesSinTokenRetorna401() throws Exception {
        mockMvc.perform(get("/api/series"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").exists());
    }

    @Test
    void favoritosDeSeriesSinTokenRetorna401() throws Exception {
        mockMvc.perform(get("/api/users/me/favorites/series"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").exists());
    }

    /**
     * La seguridad no corta las lecturas de series a un USER. No se exige un
     * 200 porque el controlador aún puede no existir (daría 404); basta con
     * que la respuesta no sea un 401/403 de la cadena de filtros.
     */
    @ParameterizedTest(name = "{0} {1} con USER no es 401/403")
    @MethodSource("lecturasDeSeries")
    void lecturaDeSeriesNoLaCortaLaSeguridadParaUnUser(HttpMethod method, String url) throws Exception {
        assertNoBloqueadaPorSeguridad(method, url);
    }

    /**
     * Los favoritos de series cuelgan de {@code /api/users/me/**}, no del
     * catálogo: ninguna regla de {@code /api/series/**} ni la de cierre deben
     * capturarlos, así que un USER puede leer y escribir en ellos.
     */
    @ParameterizedTest(name = "{0} {1} con USER no es 401/403")
    @MethodSource("favoritosDeSeries")
    void favoritosDeSeriesNoLosCortaLaSeguridadParaUnUser(HttpMethod method, String url) throws Exception {
        assertNoBloqueadaPorSeguridad(method, url);
    }

    private void assertNoBloqueadaPorSeguridad(HttpMethod method, String url) throws Exception {
        int status = mockMvc.perform(request(method, url).header("Authorization", userToken))
                .andReturn().getResponse().getStatus();
        assertNotEquals(401, status, method + " " + url + " respondió 401");
        assertNotEquals(403, status, method + " " + url + " respondió 403");
    }

    /** Añade un cuerpo JSON, como haría el cliente real en una escritura. */
    private static MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder builder) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(BODY);
    }
}
