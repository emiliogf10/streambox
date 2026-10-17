package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mockingDetails;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;

/**
 * Revisión de QA: ninguna de las violaciones de integridad que antes eran un
 * 409 «genérico» acaba hoy en un 500, con los {@code SQLSTATE} reales de
 * PostgreSQL.
 *
 * <p>
 * <b>Por qué.</b> Desde la tanda del 2026-10-16,
 * {@code GlobalExceptionHandler} responde 500 a toda violación que un
 * servicio no haya traducido. Cada carrera conocida (dos peticiones que pasan
 * a la vez la comprobación previa, o un borrado entre la comprobación y la
 * escritura) debe seguir dando su 409 o su 404 de dominio. Los tests
 * unitarios de cada servicio lo comprueban con excepciones fabricadas; aquí se
 * comprueba de punta a punta con el error que manda PostgreSQL ({@code 23505},
 * {@code 23503}), que no es el de H2 ({@code 23506} para la clave foránea).
 * </p>
 *
 * <p>
 * <b>Cómo se provoca la carrera sin hilos</b> (como
 * {@code IntegrityViolationTranslationIntegrationTest}): con
 * {@link MockitoSpyBean} la comprobación previa contesta lo que contestaría si
 * la otra petición aún no hubiera escrito; la escritura es real y choca con la
 * restricción.
 * </p>
 *
 * <p>
 * Al final, una carrera que <b>no</b> viola ninguna restricción y por eso no
 * da ningún error: la actualización perdida de la contraseña cuando un cambio
 * de perfil se cruza con un cambio de contraseña (incidencia de QA del
 * 2026-10-17, corregida con {@code @DynamicUpdate} en {@code User}).
 * </p>
 */
class PostgresIntegrityRaceTranslationQaIntegrationTest extends PostgresIntegrationTestSupport {

    @Autowired private MockMvc mockMvc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private UserRepository spiedUsers;
    @MockitoSpyBean private GenreRepository spiedGenres;
    @MockitoSpyBean private EpisodeRepository spiedEpisodes;
    @MockitoSpyBean private MovieRepository spiedMovies;
    @MockitoSpyBean private SeriesRepository spiedSeries;

    private String adminToken;
    private User user;
    private Genre drama;
    private Movie movie;
    private Series series;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        adminToken = tokenFor(saveUser("pgraceadmin", Role.ADMIN));
        user = saveUser("pgraceuser", Role.USER);
        drama = saveGenre("Drama");
        movie = saveMovie("Película de la carrera", 2020, drama);
        series = saveSeries("Serie de la carrera", 2020, drama);
        saveEpisode(series, 1, 1);
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // --- 23505: unicidad ---

    /** Dos cambios de nombre al mismo: el segundo recibe el 409 de nombre en uso (no un 500). */
    @Test
    void cambioDeNombreEnCarreraDa409() throws Exception {
        saveUser("nombre_disputado", Role.USER);
        doReturn(false).when(spiedUsers).existsByUsername("nombre_disputado");

        mockMvc.perform(json(patch("/api/users/me"), "{\"username\":\"nombre_disputado\"}", tokenFor(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_EXISTS"));
    }

    /** Dos altas del mismo género: 409 {@code GENRE_ALREADY_EXISTS}. */
    @Test
    void altaDeGeneroEnCarreraDa409() throws Exception {
        doReturn(false).when(spiedGenres).existsByNameIgnoreCase("Drama");

        mockMvc.perform(json(post("/api/genres"), "{\"name\":\"drama\"}", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENRE_ALREADY_EXISTS"));
    }

    /** Renombrar un género al nombre que otro acaba de ocupar: 409. */
    @Test
    void edicionDeGeneroEnCarreraDa409() throws Exception {
        Genre other = saveGenre("Comedia");
        doReturn(false).when(spiedGenres).existsByNameIgnoreCaseAndIdNot("Drama", other.getId());

        mockMvc.perform(json(put("/api/genres/" + other.getId()), "{\"name\":\"Drama\"}", adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GENRE_ALREADY_EXISTS"));
    }

    /** Dos altas del mismo episodio (temporada × número): 409 {@code EPISODE_ALREADY_EXISTS}. */
    @Test
    void altaDeEpisodioEnCarreraDa409() throws Exception {
        doReturn(false).when(spiedEpisodes).existsBySeriesIdAndSeasonNumberAndEpisodeNumber(series.getId(), 1, 1);

        mockMvc.perform(json(post("/api/series/" + series.getId() + "/episodes"), episodeJson(1, 1), adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EPISODE_ALREADY_EXISTS"));
    }

    /** Mover un episodio a una posición que otro acaba de ocupar: 409. */
    @Test
    void edicionDeEpisodioEnCarreraDa409() throws Exception {
        Long second = saveEpisode(series, 1, 2).getId();
        doReturn(false).when(spiedEpisodes)
                .existsBySeriesIdAndSeasonNumberAndEpisodeNumberAndIdNot(series.getId(), 1, 1, second);

        mockMvc.perform(json(put("/api/series/" + series.getId() + "/episodes/" + second), episodeJson(1, 1),
                        adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EPISODE_ALREADY_EXISTS"));
    }

    /** Doble clic en «Mi lista» (película): el segundo {@code INSERT} da 409, no 500. */
    @Test
    void favoritoDePeliculaEnCarreraDa409() throws Exception {
        // Las consultas @Modifying exigen transacción: la «otra petición» hace commit antes.
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(tx -> userRepository.addFavorite(user.getId(), movie.getId()));
        doReturn(false).when(spiedUsers).isFavorite(user.getId(), movie.getId());

        mockMvc.perform(json(post("/api/users/me/favorites/" + movie.getId()), null, tokenFor(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MOVIE_ALREADY_IN_FAVORITES"));
    }

    /** Doble clic en «Mi lista» (serie): 409. */
    @Test
    void favoritoDeSerieEnCarreraDa409() throws Exception {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(tx -> seriesRepository.addFavorite(user.getId(), series.getId()));
        doReturn(false).when(spiedSeries).isFavorite(user.getId(), series.getId());

        mockMvc.perform(json(post("/api/users/me/favorites/series/" + series.getId()), null, tokenFor(user)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SERIES_ALREADY_IN_FAVORITES"));
    }

    /**
     * Dos registros con el mismo email: el segundo recibe el 409 con el mensaje
     * del <b>email</b>. El servicio elige el mensaje por el nombre de la
     * restricción ({@code uk_users_email}), que en PostgreSQL llega sin el
     * prefijo de esquema ni el sufijo de índice de H2: si la extracción fallara,
     * el mensaje sería el genérico «nombre o email».
     */
    @Test
    void registroConEmailEnCarreraDa409DeEmail() throws Exception {
        saveUser("primera", Role.USER);
        doReturn(false).when(spiedUsers).existsByEmail("primera@test.com");

        mockMvc.perform(json(post("/api/users"), registration("segunda", "primera@test.com"), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value("El correo electrónico ya está en uso"));
    }

    /** Dos registros con el mismo nombre: 409 con el mensaje del <b>nombre</b> ({@code uk_users_username}). */
    @Test
    void registroConNombreEnCarreraDa409DeNombre() throws Exception {
        saveUser("disputado", Role.USER);
        doReturn(false).when(spiedUsers).existsByUsername("disputado");

        mockMvc.perform(json(post("/api/users"), registration("disputado", "otra@test.com"), null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.message").value("El nombre de usuario ya está en uso"));
    }

    // --- 23503: clave foránea (algo se borró entre la comprobación y la escritura) ---

    /** La película se borra justo antes del {@code INSERT} del favorito: 404 de película. */
    @Test
    void favoritoDePeliculaBorradaEnMedioDa404() throws Exception {
        Long gone = movie.getId();
        movieRepository.deleteById(gone);
        doReturn(true).when(spiedMovies).existsById(gone);

        mockMvc.perform(json(post("/api/users/me/favorites/" + gone), null, tokenFor(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /** La serie se borra justo antes del {@code INSERT} del favorito: 404. */
    @Test
    void favoritoDeSerieBorradaEnMedioDa404() throws Exception {
        Long gone = series.getId();
        seriesRepository.deleteById(gone);
        doReturn(true).when(spiedEpisodes).existsBySeriesId(gone);

        mockMvc.perform(json(post("/api/users/me/favorites/series/" + gone), null, tokenFor(user)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /** La serie se borra justo antes del {@code INSERT} del episodio: 404 de serie. */
    @Test
    void episodioDeSerieBorradaEnMedioDa404() throws Exception {
        Long gone = series.getId();
        seriesRepository.deleteById(gone);
        doReturn(true).when(spiedSeries).existsById(gone);
        doReturn(false).when(spiedEpisodes)
                .existsBySeriesIdAndSeasonNumberAndEpisodeNumber(eq(gone), eq(1), eq(5));

        mockMvc.perform(json(post("/api/series/" + gone + "/episodes"), episodeJson(1, 5), adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    /**
     * El género se borra después de comprobarlo y antes del {@code INSERT} en
     * {@code movie_genres}: 404 de género (no un 500) y no queda la película a
     * medias. En PostgreSQL es un {@code 23503} de {@code fk_movie_genres_genre};
     * en H2, {@code 23506} (lo cubre {@code IntegrityViolationTranslationIntegrationTest}).
     */
    @Test
    void peliculaConGeneroBorradoEnMedioDa404() throws Exception {
        Genre doomed = saveGenre("Efímero");
        genreRepository.deleteById(doomed.getId());
        doReturn(List.of(doomed)).when(spiedGenres).findAllById(Set.of(doomed.getId()));
        long before = movieRepository.count();

        mockMvc.perform(json(post("/api/movies"), "{\"title\":\"Duna\",\"description\":\"Sinopsis\","
                        + "\"duration\":155,\"releaseYear\":2021,\"imageUrl\":\"https://cdn.example.com/d.jpg\","
                        + "\"genreIds\":[" + doomed.getId() + "]}", adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Alguno de los géneros indicados ya no existe"));

        assertEquals(before, movieRepository.count());
    }

    /** Lo mismo al crear una serie ({@code fk_series_genres_genre}). */
    @Test
    void serieConGeneroBorradoEnMedioDa404() throws Exception {
        Genre doomed = saveGenre("Efímero");
        genreRepository.deleteById(doomed.getId());
        doReturn(List.of(doomed)).when(spiedGenres).findAllById(Set.of(doomed.getId()));

        mockMvc.perform(json(post("/api/series"), "{\"title\":\"Fundación\",\"description\":\"Sinopsis\","
                        + "\"releaseYear\":2021,\"imageUrl\":\"https://cdn.example.com/f.jpg\","
                        + "\"genreIds\":[" + doomed.getId() + "]}", adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Alguno de los géneros indicados ya no existe"));
    }

    // --- Carrera sin violación: la actualización perdida ---

    /**
     * Un {@code PATCH /api/users/me} (avatar) que coincide con un cambio de
     * contraseña confirmado no debe deshacer la contraseña nueva.
     *
     * <p>
     * <b>Cómo se reproduce.</b> {@code UserService.updateProfile} lee la fila
     * con {@code findById} (sin bloqueo) y la escribe al final con
     * {@code saveAndFlush}. El espía confirma, entre esa lectura y esa
     * escritura y en otra transacción ({@code REQUIRES_NEW}, otra conexión), lo
     * que hace {@code PasswordChangeService}: un {@code UPDATE} del hash. Es la
     * intercalación que ocurre si el usuario cambia la contraseña en una pestaña
     * y el avatar en otra, o si quien tiene un token de acceso robado (vale
     * hasta 15 min tras el cambio) lanza {@code PATCH} en bucle.
     * </p>
     */
    @Test
    void unCambioDePerfilSimultaneoNoDeshaceUnCambioDeContrasena() throws Exception {
        String newHash = passwordEncoder.encode("una contraseña nueva bastante larga");
        TransactionTemplate concurrent = new TransactionTemplate(transactionManager);
        concurrent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // El espía envuelve un proxy de Spring Data (una interfaz): callRealMethod
        // no sirve; la respuesta por defecto del espía es la que delega en el
        // repositorio real.
        Answer<?> realRepository = mockingDetails(spiedUsers).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            Object loaded = realRepository.answer(invocation);
            concurrent.executeWithoutResult(tx -> jdbcTemplate.update(
                    "UPDATE users SET password = ? WHERE id = ?", newHash, user.getId()));
            // Premisa: el cambio de contraseña ya está confirmado (otra conexión lo ve).
            assertEquals(newHash, concurrent.execute(tx -> storedHash()));
            return loaded;
        }).when(spiedUsers).findById(user.getId());

        mockMvc.perform(json(patch("/api/users/me"), "{\"avatar\":\"cat\"}", tokenFor(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.avatar").value("cat"));

        assertEquals(newHash, storedHash(), "el UPDATE del perfil ha vuelto a escribir el hash antiguo");
    }

    private String storedHash() {
        return jdbcTemplate.queryForObject("SELECT password FROM users WHERE id = ?", String.class, user.getId());
    }

    // --- Utilidades ---

    private static String episodeJson(int season, int number) {
        return "{\"seasonNumber\":" + season + ",\"episodeNumber\":" + number
                + ",\"title\":\"Episodio en carrera\",\"duration\":40}";
    }

    private static String registration(String username, String email) {
        return "{\"username\":\"" + username + "\",\"email\":\"" + email
                + "\",\"password\":\"tres gatos pardos en el tejado\"}";
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String body, String token) {
        if (token != null) {
            builder.header("Authorization", token);
        }
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(body.getBytes(StandardCharsets.UTF_8));
        }
        return builder;
    }
}
