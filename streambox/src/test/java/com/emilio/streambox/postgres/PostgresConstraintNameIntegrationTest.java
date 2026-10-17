package com.emilio.streambox.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.service.SqlStates;

/**
 * Lo que necesitan de PostgreSQL las traducciones de carreras que antes
 * acababan en el 409 genérico de integridad: el {@code SQLSTATE} y el nombre de
 * la restricción ({@link SqlStates#constraintName}), sacados de la excepción
 * <b>real</b> del motor.
 *
 * <p>
 * {@code UserService.registerUser} decide entre «email en uso» y «nombre en
 * uso» por el nombre del {@code UNIQUE}, y {@code MovieService}/{@code
 * SeriesService} reconocen un género borrado a medio guardar por el de su clave
 * foránea. Si PostgreSQL (o la extracción de Hibernate) entregara otro nombre,
 * la carrera acabaría en un 500. Lo mismo en H2 lo prueba
 * {@code IntegrityViolationTranslationIntegrationTest}, de punta a punta.
 * </p>
 */
class PostgresConstraintNameIntegrationTest extends PostgresIntegrationTestSupport {

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    @Test
    void elEmailYElNombreRepetidosTraenSuRestriccion() {
        saveUser("ana", Role.USER);

        DataIntegrityViolationException byEmail = assertThrows(DataIntegrityViolationException.class,
                () -> userRepository.saveAndFlush(user("otra", "ana@test.com")));
        DataIntegrityViolationException byUsername = assertThrows(DataIntegrityViolationException.class,
                () -> userRepository.saveAndFlush(user("ana", "otra@test.com")));

        assertEquals("23505", SqlStates.find(byEmail));
        assertEquals("uk_users_email", SqlStates.constraintName(byEmail));
        assertEquals("23505", SqlStates.find(byUsername));
        assertEquals("uk_users_username", SqlStates.constraintName(byUsername));
    }

    /**
     * La carrera del género: la entidad se resolvió cuando aún existía y se
     * borra antes del {@code INSERT} en la tabla de unión.
     */
    @Test
    void unGeneroBorradoAMedioGuardarTraeLaClaveForaneaDeSuTablaDeUnion() {
        Genre removed = saveGenre("Efímero");
        genreRepository.deleteById(removed.getId());

        Movie movie = new Movie();
        movie.setTitle("Duna");
        movie.setDescription("Sinopsis");
        movie.setDuration(155);
        movie.setReleaseYear(2021);
        movie.setImageUrl("https://example.com/image.jpg");
        movie.setGenres(new HashSet<>(List.of(removed)));
        DataIntegrityViolationException movieError = assertThrows(DataIntegrityViolationException.class,
                () -> movieRepository.saveAndFlush(movie));

        Series series = new Series();
        series.setTitle("Fundación");
        series.setDescription("Sinopsis");
        series.setReleaseYear(2021);
        series.setImageUrl("https://example.com/serie.jpg");
        series.setGenres(new HashSet<>(List.of(removed)));
        DataIntegrityViolationException seriesError = assertThrows(DataIntegrityViolationException.class,
                () -> seriesRepository.saveAndFlush(series));

        assertEquals("23503", SqlStates.find(movieError));
        assertEquals("fk_movie_genres_genre", SqlStates.constraintName(movieError));
        assertEquals("23503", SqlStates.find(seriesError));
        assertEquals("fk_series_genres_genre", SqlStates.constraintName(seriesError));
    }

    private User user(String username, String email) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(Role.USER);
        return user;
    }
}
