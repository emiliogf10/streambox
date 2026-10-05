package com.emilio.streambox.security;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.UserRepository;

/**
 * Regresión de la "regla de cierre" del catálogo de {@link SecurityConfig}
 * ({@code .requestMatchers("/api/movies/**", "/api/genres/**").hasRole("ADMIN")}).
 *
 * <p>
 * {@link CatalogWriteAuthorizationIntegrationTest} comprueba que la regla
 * <em>cierra</em> lo que debe. Esta clase comprueba lo contrario: que no cierra
 * nada legítimo. Una regla amplia mal colocada (antes de las de lectura, o con
 * un patrón demasiado general) dejaría a los usuarios sin catálogo, sin "Mi
 * lista" o sin login, y ningún test de "403 para USER" lo detectaría.
 * También cubre intentos de rodearla con la codificación de la ruta y que un
 * cambio de rol surte efecto con el mismo token sobre los endpoints nuevos.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class CatalogClosureRuleRegressionIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private User user;
    private String userToken;
    private String adminToken;
    private Genre drama;
    private Movie movie;

    @BeforeEach
    void setUp() {
        user = saveUser("closureuser", Role.USER);
        userToken = "Bearer " + jwtService.generateToken(user);
        adminToken = "Bearer " + jwtService.generateToken(saveUser("closureadmin", Role.ADMIN));

        Genre genre = new Genre();
        genre.setName("Drama");
        drama = genreRepository.save(genre);

        Movie m = new Movie();
        m.setTitle("Cierre");
        m.setDescription("Película de prueba de la regla de cierre");
        m.setDuration(90);
        m.setReleaseYear(2010);
        m.setImageUrl("https://cdn.example.com/cierre.jpg");
        m.setVideoUrl("https://cdn.example.com/cierre.mp4");
        m.setCreatedAt(Instant.now());
        m.setGenres(Set.of(drama));
        movie = movieRepository.save(m);
    }

    /** Todas las lecturas del catálogo (GET y HEAD) siguen abiertas a un USER. */
    @Test
    void lasLecturasDelCatalogoSiguenDisponiblesParaUnUser() throws Exception {
        for (String url : new String[] {
                "/api/movies", "/api/movies/" + movie.getId(), "/api/movies/search?title=cierre",
                "/api/genres" }) {
            mockMvc.perform(get(url).header("Authorization", userToken)).andExpect(status().isOk());
            mockMvc.perform(head(url).header("Authorization", userToken)).andExpect(status().isOk());
        }
    }

    /** HEAD es lectura "autenticada", no pública: sin token sigue siendo 401. */
    @Test
    void headSobreElCatalogoSinTokenRetorna401() throws Exception {
        mockMvc.perform(head("/api/genres")).andExpect(status().isUnauthorized());
        mockMvc.perform(head("/api/movies/" + movie.getId())).andExpect(status().isUnauthorized());
    }

    /**
     * Los endpoints personales ({@code /api/users/me/**}) no cuelgan del
     * catálogo: un USER sigue pudiendo leer y escribir en ellos. El 404 del
     * {@code POST} con una película inexistente demuestra que la petición
     * llegó al controlador (no la cortó la seguridad con un 403).
     */
    @Test
    void losEndpointsPersonalesSiguenDisponiblesParaUnUser() throws Exception {
        mockMvc.perform(get("/api/users/me").header("Authorization", userToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/users/me/favorites").header("Authorization", userToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/users/me/favorites/{id}", movie.getId()).header("Authorization", userToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/users/me/favorites/987654").header("Authorization", userToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        mockMvc.perform(delete("/api/users/me/favorites").header("Authorization", userToken))
                .andExpect(status().isNoContent());
    }

    /** Registro e inicio de sesión siguen siendo públicos (sin token). */
    @Test
    void registroYLoginSiguenSiendoPublicos() throws Exception {
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"closurenew\",\"email\":\"closurenew@test.com\","
                                + "\"password\":\"Closure-Pass-2026\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"closurenew@test.com\",\"password\":\"Closure-Pass-2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    /**
     * Actuator y la documentación OpenAPI conservan sus reglas: {@code health}
     * y {@code /v3/api-docs} públicos, {@code info} con token.
     */
    @Test
    void actuatorYDocumentacionMantienenSusReglas() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/info").header("Authorization", userToken)).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    /**
     * La regla de cierre solo restringe el catálogo: un ADMIN sigue pudiendo
     * usar OPTIONS sobre él, y sobre una ruta personal OPTIONS sigue siendo
     * cosa de cualquier usuario autenticado.
     */
    @Test
    void optionsSigueDisponibleParaAdminEnElCatalogoYParaUserFueraDeEl() throws Exception {
        mockMvc.perform(options("/api/genres").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")));
        mockMvc.perform(options("/api/users/me").header("Authorization", userToken))
                .andExpect(status().isOk());
    }

    /**
     * Intento de rodear la regla codificando parte de la ruta
     * ({@code /api/%67enres/...} = {@code /api/genres/...}). Si la seguridad
     * comparara la ruta sin decodificar y Spring MVC la decodificara, un USER
     * llegaría al controlador. Pase lo que pase, no puede ser un 2xx ni
     * modificar el género.
     */
    @Test
    void unaRutaDelCatalogoCodificadaNoSaltaLaAutorizacion() throws Exception {
        URI encoded = URI.create("/api/%67enres/" + drama.getId());

        int putStatus = mockMvc.perform(put(encoded).header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Hackeado\"}"))
                .andReturn().getResponse().getStatus();
        int deleteStatus = mockMvc.perform(delete(encoded).header("Authorization", userToken))
                .andReturn().getResponse().getStatus();

        assertFalse(putStatus >= 200 && putStatus < 300, "PUT codificado respondió " + putStatus);
        assertFalse(deleteStatus >= 200 && deleteStatus < 300, "DELETE codificado respondió " + deleteStatus);
        assertEquals("Drama", genreRepository.findById(drama.getId()).orElseThrow().getName());
    }

    /**
     * El filtro JWT lee el rol de la base de datos en cada petición: ascender
     * a un USER a ADMIN le permite renombrar géneros con el mismo token, y
     * degradarlo se lo vuelve a impedir de inmediato.
     */
    @Test
    void unCambioDeRolSurteEfectoConElMismoTokenEnLaEdicionDeGeneros() throws Exception {
        user.setRole(Role.ADMIN);
        userRepository.saveAndFlush(user);

        mockMvc.perform(put("/api/genres/{id}", drama.getId()).header("Authorization", userToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Melodrama\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Melodrama"));

        user.setRole(Role.USER);
        userRepository.saveAndFlush(user);

        mockMvc.perform(delete("/api/genres/{id}", drama.getId()).header("Authorization", userToken))
                .andExpect(status().isForbidden());
        assertTrue(genreRepository.existsById(drama.getId()));
    }

    private User saveUser(String name, Role role) {
        User u = new User();
        u.setUsername(name);
        u.setEmail(name + "@test.com");
        u.setPassword(passwordEncoder.encode("password123"));
        u.setRole(role);
        u.setCreatedAt(Instant.now());
        return userRepository.save(u);
    }
}
