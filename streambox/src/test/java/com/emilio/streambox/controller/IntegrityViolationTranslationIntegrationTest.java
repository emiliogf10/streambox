package com.emilio.streambox.controller;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.emilio.streambox.dto.AcceptedResponse;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;
import com.emilio.streambox.security.JwtService;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.json.JsonMapper;

/**
 * Las carreras que antes acababan en el 409 genérico de integridad, de punta a
 * punta y con la violación <b>real</b> de la base de datos (H2).
 *
 * <p>
 * <b>Por qué hace falta.</b> {@code GlobalExceptionHandler} ya no responde 409
 * a una violación sin traducir, sino 500 (es un fallo que nadie esperaba). Las
 * que sí pueden ocurrir con datos correctos las traduce su servicio: un
 * registro simultáneo con el mismo email o nombre (el {@code UNIQUE} de
 * {@code users}) y un género borrado mientras se guarda una película o serie
 * (la clave foránea de {@code movie_genres}/{@code series_genres}). Los tests
 * unitarios ({@code RegistrationServiceTest}, {@code CatalogGenreRaceTest}) prueban la
 * traducción con excepciones simuladas; estos comprueban que H2 entrega de
 * verdad el {@code SQLSTATE} y el nombre de la restricción que esa traducción
 * necesita. Sin el arreglo, el registro respondía 409
 * {@code DATA_INTEGRITY_VIOLATION} (con el manejador nuevo, 500) y el género
 * borrado, igual.
 * </p>
 *
 * <p>
 * <b>Cómo se provoca la carrera sin hilos.</b> Con {@link MockitoSpyBean} sobre
 * el repositorio real, la comprobación previa contesta lo que contestaría si la
 * otra petición aún no hubiera escrito ({@code findByEmail} o
 * {@code findByUsername} → vacío; {@code findAllById} → el género ya
 * borrado). El {@code INSERT} es real y
 * choca con la restricción, como en la carrera.
 * </p>
 *
 * <p>
 * Sin {@code @Transactional}: tras una violación la sesión de Hibernate queda
 * inservible, así que cada petición confirma o deshace su propia transacción, y
 * los datos se borran en {@code @AfterEach}.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class IntegrityViolationTranslationIntegrationTest {

    private static final String PASSWORD = "un paseo por el retiro en otoño";

    @Autowired private MockMvc mockMvc;
    @Autowired private JsonMapper jsonMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private MovieRepository movieRepository;
    @Autowired private SeriesRepository seriesRepository;
    @MockitoSpyBean private UserRepository userRepository;
    @MockitoSpyBean private GenreRepository genreRepository;

    private String adminToken;
    private Genre removedGenre;
    private Genre keptGenre;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        adminToken = "Bearer " + jwtService.generateToken(saveUser("carreraadmin", "carreraadmin@test.com"));

        keptGenre = genreRepository.save(genre("Género que sigue"));
        Genre doomed = genreRepository.save(genre("Género efímero"));
        genreRepository.deleteById(doomed.getId());
        removedGenre = doomed;
        // La comprobación previa "aún ve" el género: el otro administrador lo
        // borra justo después.
        doReturn(List.of(removedGenre)).when(genreRepository).findAllById(Set.of(removedGenre.getId()));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Registro: el UNIQUE de users
    // ------------------------------------------------------------------

    /**
     * Carrera por el email: el segundo registro choca con {@code uk_users_email}
     * y recibe el <b>mismo 202</b> que cualquier registro (antes, 409 «El correo
     * electrónico ya está en uso», que revelaba el email). La cuenta que existe
     * es la primera y el segundo no se guarda, pero su nombre queda reservado
     * como en cualquier registro con un email que ya tiene cuenta
     * ({@code UsernameReservations}): el siguiente registro con ese nombre da
     * 409, igual que si la cuenta se hubiera creado.
     *
     * <p>
     * Cada test de esta clase usa un nombre distinto por eso mismo: la reserva
     * vive en memoria durante todo el contexto de Spring (24 h).
     * </p>
     */
    @Test
    void dosRegistrosConElMismoEmailDanAlSegundoElMismo202() throws Exception {
        User first = saveUser("primera", "carrera@test.com");
        doReturn(Optional.empty()).when(userRepository).findByEmail("carrera@test.com");

        register("segunda", "carrera@test.com")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(AcceptedResponse.REGISTRATION_MESSAGE));
        register("segunda", "otra.carrera@test.com")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("El nombre de usuario ya está en uso"));

        assertEquals(first.getId(), userRepository.findAll().stream()
                .filter(user -> user.getEmail().equals("carrera@test.com")).findFirst().orElseThrow().getId());
        assertTrue(userRepository.findAll().stream().noneMatch(user -> user.getUsername().equals("segunda")));
    }

    @Test
    void dosRegistrosConElMismoNombreDanAlSegundoEl409DeNombreEnUso() throws Exception {
        saveUser("carrera", "primera@test.com");
        doReturn(Optional.empty()).when(userRepository).findByUsername("carrera");

        register("carrera", "segunda@test.com")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value("El nombre de usuario ya está en uso"));
    }

    /**
     * Una carrera de registro no deja el email en <b>ningún</b> log, aunque la
     * respuesta sea el 202 correcto.
     *
     * <p>
     * Hibernate registra por su cuenta (en {@code WARN}, categoría
     * {@code org.hibernate.orm.jdbc.error}) el mensaje de cada error de JDBC
     * antes de que el servicio lo traduzca, y el de H2 trae el valor
     * duplicado ({@code VALUES ( ... 'correo@...' )}). Sin
     * {@code logging.level.org.hibernate.orm.jdbc.error=OFF} en
     * {@code application.properties}, este test falla. Es la versión H2 (corre
     * sin Docker) de
     * {@code PostgresIntegrityViolationLogPrivacyQaIntegrationTest}, que lo
     * comprueba con el {@code Detail} de PostgreSQL.
     * </p>
     */
    @Test
    void unaCarreraDeRegistroNoDejaElEmailEnNingunLog() throws Exception {
        String email = "privada.carrera@test.com";
        saveUser("primera", email);
        // Premisa: el error de H2 sí lleva el email (si no, el test no probaría nada).
        DataIntegrityViolationException error = assertThrows(DataIntegrityViolationException.class,
                () -> saveUser("tercera", email));
        assertTrue(messages(error).contains(email), "H2 debería incluir el valor duplicado en su mensaje");
        doReturn(Optional.empty()).when(userRepository).findByEmail(email);

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            register("segunda_privada", email)
                    .andExpect(status().isAccepted());
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }

        List<String> leaks = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            if ((event.getFormattedMessage() + throwableText(event.getThrowableProxy())).contains(email)) {
                leaks.add(event.getLevel() + " " + event.getLoggerName() + ": " + event.getFormattedMessage());
            }
        }
        assertEquals(List.of(), leaks);
    }

    // ------------------------------------------------------------------
    // Películas y series: la clave foránea del género
    // ------------------------------------------------------------------

    @Test
    void crearUnaPeliculaConUnGeneroBorradoEnMedioDa404DeGeneroYNoGuardaNada() throws Exception {
        long before = movieRepository.count();

        send(post("/api/movies"), movieBody(removedGenre))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Alguno de los géneros indicados ya no existe"));

        assertEquals(before, movieRepository.count());
    }

    @Test
    void editarUnaPeliculaConUnGeneroBorradoEnMedioDa404DeGenero() throws Exception {
        Movie movie = new Movie();
        movie.setTitle("Original");
        movie.setDescription("Sinopsis");
        movie.setDuration(100);
        movie.setReleaseYear(2020);
        movie.setImageUrl("https://cdn.example.com/o.jpg");
        movie.setGenres(new HashSet<>(List.of(keptGenre)));
        long id = movieRepository.save(movie).getId();

        send(put("/api/movies/" + id), movieBody(removedGenre))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        assertEquals("Original", movieRepository.findById(id).orElseThrow().getTitle(),
                "la edición se deshizo entera");
    }

    @Test
    void crearOEditarUnaSerieConUnGeneroBorradoEnMedioDa404DeGenero() throws Exception {
        send(post("/api/series"), seriesBody(removedGenre))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Alguno de los géneros indicados ya no existe"));

        Series series = new Series();
        series.setTitle("Original");
        series.setDescription("Sinopsis");
        series.setReleaseYear(2020);
        series.setImageUrl("https://cdn.example.com/s.jpg");
        series.setGenres(new HashSet<>(List.of(keptGenre)));
        long id = seriesRepository.save(series).getId();

        send(put("/api/series/" + id), seriesBody(removedGenre))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        assertFalse(seriesRepository.findAll().stream().anyMatch(s -> "Fundación".equals(s.getTitle())));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private ResultActions register(String username, String email) throws Exception {
        return mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(
                        Map.of("username", username, "email", email, "password", PASSWORD))));
    }

    private ResultActions send(MockHttpServletRequestBuilder builder, Map<String, Object> body) throws Exception {
        return mockMvc.perform(builder.header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonMapper.writeValueAsString(body)));
    }

    private static Map<String, Object> movieBody(Genre genre) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Duna");
        body.put("description", "Sinopsis");
        body.put("duration", 155);
        body.put("releaseYear", 2021);
        body.put("imageUrl", "https://cdn.example.com/duna.jpg");
        body.put("genreIds", List.of(genre.getId()));
        return body;
    }

    private static Map<String, Object> seriesBody(Genre genre) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "Fundación");
        body.put("description", "Sinopsis");
        body.put("releaseYear", 2021);
        body.put("imageUrl", "https://cdn.example.com/fundacion.jpg");
        body.put("genreIds", List.of(genre.getId()));
        return body;
    }

    private static Genre genre(String name) {
        Genre genre = new Genre();
        genre.setName(name);
        return genre;
    }

    private User saveUser(String username, String email) {
        User user = new User();
        user.setEmailVerifiedAt(Instant.now()); // cuenta verificada (V9)
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRole(Role.ADMIN);
        return userRepository.save(user);
    }

    /** Mensajes de la excepción y de todas sus causas, uno por línea. */
    private static String messages(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            text.append(t.getMessage()).append('\n');
        }
        return text.toString();
    }

    /** Mensajes de la traza adjunta a un evento de log (y de sus causas). */
    private static String throwableText(IThrowableProxy proxy) {
        StringBuilder text = new StringBuilder();
        for (IThrowableProxy p = proxy; p != null; p = p.getCause()) {
            text.append('\n').append(p.getMessage());
        }
        return text.toString();
    }

    private void cleanDatabase() {
        seriesRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
        userRepository.deleteAll();
    }
}
