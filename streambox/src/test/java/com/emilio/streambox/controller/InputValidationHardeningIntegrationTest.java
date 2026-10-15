package com.emilio.streambox.controller;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;

import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.exc.MismatchedInputException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validación de las entradas (punto 14 de la lista de seguridad): longitud del
 * email, ids de género nulos, conversión automática de escalares de Jackson y
 * mensajes en español de {@code MovieRequest}.
 *
 * <p>
 * Cada bloque es la regresión de un fallo concreto y falla sin su arreglo
 * (se indica en cada test qué respondía antes). Los mensajes se escriben
 * literalmente, y no con las constantes de los DTO, porque son el contrato con
 * el frontend ({@code frontend/src/lib/movieValidation.ts}): cambiarlos por
 * descuido debe romper el test. Los cuerpos van como texto JSON literal para
 * controlar el tipo exacto de cada valor ({@code 123} frente a {@code "123"}).
 * </p>
 *
 * <p>
 * Usa {@code @Transactional} (rollback al terminar): nada depende de un commit
 * real.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class InputValidationHardeningIntegrationTest {

    private static final String EMAIL_SIZE = "El correo electrónico no puede tener más de 100 caracteres";
    private static final String MALFORMED_MESSAGE =
            "La petición no se puede interpretar. Revisa el formato de los datos";
    private static final String VALID_PASSWORD = "un paseo por el retiro en otoño";

    @Autowired private MockMvc mockMvc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private String adminToken;
    private Genre drama;

    @BeforeEach
    void setUp() {
        User admin = new User();
        admin.setUsername("inputadmin");
        admin.setEmail("inputadmin@test.com");
        admin.setPassword(passwordEncoder.encode("adminpass1"));
        admin.setRole(Role.ADMIN);
        admin.setCreatedAt(Instant.now());
        adminToken = "Bearer " + jwtService.generateToken(userRepository.save(admin));

        Genre genre = new Genre();
        genre.setName("Drama entradas");
        drama = genreRepository.save(genre);
    }

    // ------------------------------------------------------------------
    // 1. Longitud del email (columna users.email VARCHAR(100))
    // ------------------------------------------------------------------

    /**
     * Un email de formato válido pero de 109 caracteres. {@code @Email} no
     * limita la longitud total: antes llegaba a la base de datos y respondía
     * 409 {@code DATA_INTEGRITY_VIOLATION} («conflicto con datos existentes»).
     */
    @Test
    void unEmailDeMasDe100CaracteresEnElRegistroDa400() throws Exception {
        String email = "x".repeat(64) + "@" + "d".repeat(40) + ".com";
        assertEquals(109, email.length());
        long before = userRepository.count();

        register("emaillargo", email)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.email").value(EMAIL_SIZE));

        assertEquals(before, userRepository.count());
    }

    /** El límite es inclusivo: 100 caracteres justos caben en la columna. */
    @Test
    void unEmailDeExactamente100CaracteresSeAcepta() throws Exception {
        String email = "x".repeat(64) + "@" + "d".repeat(31) + ".com";
        assertEquals(100, email.length());

        register("email100", email).andExpect(status().isCreated());
    }

    /**
     * Un email de 100 caracteres que al pasarlo a minúsculas crece: la
     * {@code İ} (U+0130) se convierte en dos caracteres. Pasa {@code @Size}
     * (mide lo recibido) pero el servicio guarda el normalizado (160
     * caracteres); antes también acababa en el 409 de la base de datos.
     */
    @Test
    void unEmailQueCreceAlNormalizarloDa400ConElMismoMensaje() throws Exception {
        String email = "İ".repeat(60) + "@" + "d".repeat(35) + ".com";
        assertEquals(100, email.length());
        assertEquals(160, email.toLowerCase(Locale.ROOT).length());
        long before = userRepository.count();

        register("emailcrece", email)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.email").value(EMAIL_SIZE));

        assertEquals(before, userRepository.count());
    }

    /**
     * El login aplica el mismo tope: un email más largo que la columna no
     * puede ser de ninguna cuenta. Antes llegaba al servicio (normalización,
     * consulta y contadores de intentos en memoria) y respondía 401.
     */
    @Test
    void unEmailDeMasDe100CaracteresEnElLoginDa400() throws Exception {
        String email = "x".repeat(64) + "@" + "d".repeat(40) + ".com";

        login(email, "cualquiera")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.email").value(EMAIL_SIZE));
    }

    /** Los «obligatorio» del login y del registro, en español (antes, el texto por defecto del validador). */
    @Test
    void losCamposObligatoriosDeLaCuentaTienenMensajeEnEspanol() throws Exception {
        login("", "")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.email").value("El correo electrónico es obligatorio"))
                .andExpect(jsonPath("$.validationErrors.password").value("La contraseña es obligatoria"));

        postJson("/api/users", """
                {"username":"   ","email":"","password":"%s"}
                """.formatted(VALID_PASSWORD), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.username").value("El nombre de usuario es obligatorio"))
                .andExpect(jsonPath("$.validationErrors.email").value("El correo electrónico es obligatorio"));
    }

    // ------------------------------------------------------------------
    // 2. Ids de género nulos en una película
    // ------------------------------------------------------------------

    /**
     * {@code "genreIds": [id, null]}: antes el nulo llegaba al servicio y a
     * {@code findAllById} (que no admite ids nulos). Ahora es un 400
     * {@code VALIDATION_ERROR} como en las series.
     */
    @Test
    void unIdDeGeneroNuloEnUnaPeliculaDa400() throws Exception {
        long before = movieRepository.count();

        postJson("/api/movies", movieJson("\"Con nulo\"", "100", "[%d, null]".formatted(drama.getId())), adminToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.*",
                        hasItem("Los identificadores de género no pueden ser nulos")));

        assertEquals(before, movieRepository.count());
    }

    // ------------------------------------------------------------------
    // 3. Sin conversión automática de escalares
    // ------------------------------------------------------------------

    /**
     * El {@link JsonMapper} de la API (el que lee los {@code @RequestBody}) no
     * convierte entre escalares. Si alguien sustituye el mapper o borra
     * {@code StrictJsonScalarsConfig}, este test señala la causa antes que los
     * de la API.
     */
    @Test
    void elJsonMapperDeLaApiNoConvierteEscalares() {
        assertFalse(jsonMapper.isEnabled(MapperFeature.ALLOW_COERCION_OF_SCALARS));
        assertThrows(MismatchedInputException.class,
                () -> jsonMapper.readValue("{\"text\":123}", Probe.class));
        assertThrows(MismatchedInputException.class,
                () -> jsonMapper.readValue("{\"text\":12.5}", Probe.class));
        assertThrows(MismatchedInputException.class,
                () -> jsonMapper.readValue("{\"text\":true}", Probe.class));
        assertThrows(MismatchedInputException.class,
                () -> jsonMapper.readValue("{\"number\":\"90\"}", Probe.class));
        assertThrows(MismatchedInputException.class,
                () -> jsonMapper.readValue("{\"flag\":1}", Probe.class));
        assertEquals(new Probe("a", 1, true),
                jsonMapper.readValue("{\"text\":\"a\",\"number\":1,\"flag\":true}", Probe.class));
    }

    /**
     * La regresión del encargo: {@code "password": 123456789012.5} (un número)
     * creaba la cuenta con la contraseña {@code "123456789012.5"} (201).
     */
    @Test
    void unaContrasenaNumericaEnElRegistroDa400MalformedRequest() throws Exception {
        long before = userRepository.count();

        postJson("/api/users", """
                {"username":"pwdnumero","email":"pwdnumero@test.com","password":123456789012.5}
                """, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value(MALFORMED_MESSAGE));

        assertEquals(before, userRepository.count());
    }

    /** Un nombre de usuario numérico tampoco se convierte a texto (antes: 201 con «1234567»). */
    @Test
    void unNombreDeUsuarioNumericoDa400MalformedRequest() throws Exception {
        postJson("/api/users", """
                {"username":1234567,"email":"usernumero@test.com","password":"%s"}
                """.formatted(VALID_PASSWORD), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));

        assertFalse(userRepository.existsByEmail("usernumero@test.com"));
    }

    /**
     * En una película: un título booleano o numérico y una duración o un año
     * escritos como texto. Antes todos creaban la película (201) con un valor
     * convertido.
     */
    @ParameterizedTest
    @ValueSource(strings = { "title-true", "title-number", "duration-text", "year-text" })
    void losEscalaresDeUnTipoDistintoEnUnaPeliculaDan400(String variant) throws Exception {
        String title = switch (variant) {
            case "title-true" -> "true";
            case "title-number" -> "2001";
            default -> "\"Tipos\"";
        };
        String duration = "duration-text".equals(variant) ? "\"90\"" : "90";
        String year = "year-text".equals(variant) ? "\"2000\"" : "2000";
        String body = """
                {"title":%s,"description":"d","duration":%s,"releaseYear":%s,
                 "imageUrl":"https://example.com/i.jpg","videoUrl":"https://example.com/v.mp4",
                 "genreIds":[%d]}
                """.formatted(title, duration, year, drama.getId());
        long before = movieRepository.count();

        postJson("/api/movies", body, adminToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.message").value(MALFORMED_MESSAGE));

        assertEquals(before, movieRepository.count());
    }

    /** Un id de género escrito como texto ({@code ["5"]}) tampoco se convierte. */
    @Test
    void unIdDeGeneroComoTextoDa400() throws Exception {
        postJson("/api/movies", movieJson("\"Id texto\"", "100", "[\"%d\"]".formatted(drama.getId())), adminToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    /** Con cada campo de su tipo (lo que envía el frontend), todo sigue funcionando. */
    @Test
    void conLosTiposCorrectosSeCreanCuentaYPelicula() throws Exception {
        register("tiposok", "tiposok@test.com").andExpect(status().isCreated());

        postJson("/api/movies", movieJson("\"Tipos correctos\"", "100", "[%d]".formatted(drama.getId())), adminToken)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Tipos correctos"))
                .andExpect(jsonPath("$.duration").value(100));
    }

    // ------------------------------------------------------------------
    // 4. Mensajes de MovieRequest en español (los del frontend)
    // ------------------------------------------------------------------

    /**
     * Un cuerpo vacío: cada campo obligatorio recibe el mensaje que muestra el
     * formulario del panel. Antes salía el de Hibernate Validator («must not be
     * blank», «must not be null»...).
     */
    @Test
    void losCamposObligatoriosDeUnaPeliculaTienenLosMensajesDelFrontend() throws Exception {
        postJson("/api/movies", "{}", adminToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.validationErrors.title").value("El título es obligatorio"))
                .andExpect(jsonPath("$.validationErrors.description").value("La sinopsis es obligatoria"))
                .andExpect(jsonPath("$.validationErrors.duration").value("Indica la duración en minutos"))
                .andExpect(jsonPath("$.validationErrors.releaseYear").value("Indica el año de estreno"))
                .andExpect(jsonPath("$.validationErrors.imageUrl").value("La URL de la portada es obligatoria"))
                .andExpect(jsonPath("$.validationErrors.videoUrl").value("La URL del vídeo es obligatoria"))
                .andExpect(jsonPath("$.validationErrors.genreIds").value("Elige al menos un género"));
    }

    /**
     * Los rangos de duración y año y la longitud de la sinopsis, también con
     * el texto del frontend (la sinopsis decía antes «La descripción no puede
     * superar...», distinto del formulario de películas).
     */
    @Test
    void losRangosDeUnaPeliculaTienenLosMensajesDelFrontend() throws Exception {
        String body = """
                {"title":"Rangos","description":"%s","duration":0,"releaseYear":1800,
                 "imageUrl":"https://example.com/i.jpg","videoUrl":"https://example.com/v.mp4",
                 "genreIds":[%d]}
                """.formatted("s".repeat(1001), drama.getId());

        postJson("/api/movies", body, adminToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.validationErrors.duration")
                        .value("La duración debe ser un número entero de minutos mayor que 0"))
                .andExpect(jsonPath("$.validationErrors.releaseYear").value("El año debe estar entre 1888 y 2100"))
                .andExpect(jsonPath("$.validationErrors.description")
                        .value("La sinopsis no puede superar los 1000 caracteres"));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Objeto mínimo para probar el {@link JsonMapper} con un campo de cada tipo. */
    record Probe(String text, Integer number, Boolean flag) {
    }

    private ResultActions register(String username, String email) throws Exception {
        String body = jsonMapper.writeValueAsString(
                Map.of("username", username, "email", email, "password", VALID_PASSWORD));
        return postJson("/api/users", body, null);
    }

    private ResultActions login(String email, String password) throws Exception {
        String body = jsonMapper.writeValueAsString(Map.of("email", email, "password", password));
        return postJson("/api/auth/login", body, null);
    }

    private ResultActions postJson(String url, String json, String token) throws Exception {
        var request = post(url).contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            request.header("Authorization", token);
        }
        return mockMvc.perform(request);
    }

    /** Película válida con el título, la duración y los géneros escritos tal cual en el JSON. */
    private static String movieJson(String title, String duration, String genreIds) {
        return """
                {"title":%s,"description":"d","duration":%s,"releaseYear":2000,
                 "imageUrl":"https://example.com/i.jpg","videoUrl":"https://example.com/v.mp4",
                 "genreIds":%s}
                """.formatted(title, duration, genreIds);
    }
}
