package com.emilio.streambox.postgres;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.emilio.streambox.entity.Episode;
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
import com.emilio.streambox.security.JwtService;

/**
 * Base de los tests que se ejecutan contra un <b>PostgreSQL real</b> (Testcontainers).
 *
 * <h2>Para qué existen</h2>
 * <p>
 * La suite normal usa H2 en modo PostgreSQL: es rápida y no necesita nada
 * instalado, pero H2 no es PostgreSQL. Estos tests son un <b>complemento</b>
 * (no un reemplazo) que repite lo que más depende del motor: migraciones de
 * Flyway, tipos reales de columna, claves foráneas en cascada, restricciones
 * {@code CHECK}, errores reales de unicidad, concurrencia, ordenación por
 * texto (<i>collation</i>) y {@code LIKE}.
 * </p>
 *
 * <h2>Cómo funciona</h2>
 * <ul>
 *   <li><b>Un único contenedor por ejecución</b> de Maven, compartido por todas
 *       las clases que heredan de esta (patrón <i>singleton</i>): arrancar
 *       PostgreSQL cuesta unos segundos y no debe repetirse por clase. Se
 *       arranca de forma <i>perezosa</i>, la primera vez que Spring necesita la
 *       URL de la base de datos, y lo elimina Ryuk (el contenedor "guardián" de
 *       Testcontainers) al terminar la JVM.</li>
 *   <li><b>Sin Docker se omiten, no fallan</b>:
 *       {@code @Testcontainers(disabledWithoutDocker = true)} desactiva estas
 *       clases si no hay un Docker accesible. Por eso el contenedor NO se
 *       arranca en un inicializador estático (se arrancaría aunque la clase
 *       estuviera desactivada): solo se arranca desde el proveedor de
 *       propiedades de Spring, que únicamente se evalúa si la clase se ejecuta.</li>
 *   <li><b>Sobrescribe H2 sin tocar a los demás</b>: {@code @DynamicPropertySource}
 *       tiene más prioridad que {@code application-test.properties}, de modo que
 *       solo las clases que heredan de esta usan PostgreSQL; el resto de la
 *       suite sigue en H2.</li>
 * </ul>
 *
 * <h2>Versión de la imagen</h2>
 * <p>
 * El README declara PostgreSQL 16+ como requisito, así que se fija la 16 (la
 * mayor de la rama que se declara como mínima). Se usa la imagen Debian y NO la
 * {@code -alpine}: Alpine usa musl, cuya <i>collation</i> y funciones de
 * mayúsculas/minúsculas para texto no ASCII se comportan distinto a las de
 * glibc que usa la inmensa mayoría de despliegues (y las imágenes oficiales).
 * Probar con Alpine daría un resultado engañoso en el orden por título.
 * Para subir de versión basta cambiar {@link #POSTGRES_IMAGE}.
 * </p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
public abstract class PostgresIntegrationTestSupport {

    /** Imagen de PostgreSQL usada en todos los tests (ver la nota de la clase). */
    public static final String POSTGRES_IMAGE = "postgres:16";

    private static PostgreSQLContainer container;

    @Autowired protected MovieRepository movieRepository;
    @Autowired protected GenreRepository genreRepository;
    @Autowired protected UserRepository userRepository;
    @Autowired protected SeriesRepository seriesRepository;
    @Autowired protected EpisodeRepository episodeRepository;
    @Autowired protected JwtService jwtService;
    @Autowired protected PasswordEncoder passwordEncoder;

    /**
     * Devuelve el contenedor compartido, arrancándolo la primera vez.
     *
     * <p>
     * Es {@code synchronized} porque Spring puede pedirlo desde distintos
     * hilos de construcción de contexto; {@code start()} sobre un contenedor
     * ya arrancado no hace nada, pero la creación del objeto sí debe ser única.
     * </p>
     *
     * @return el contenedor de PostgreSQL en marcha
     */
    public static synchronized PostgreSQLContainer postgres() {
        if (container == null) {
            container = new PostgreSQLContainer(POSTGRES_IMAGE);
        }
        container.start();
        return container;
    }

    /**
     * Conecta la aplicación al contenedor, sustituyendo la URL de H2 del perfil
     * {@code test}. El driver se indica de nuevo porque
     * {@code application-test.properties} fija el de H2.
     *
     * @param registry registro de propiedades dinámicas de Spring
     */
    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> postgres().getJdbcUrl());
        registry.add("spring.datasource.username", () -> postgres().getUsername());
        registry.add("spring.datasource.password", () -> postgres().getPassword());
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    // ------------------------------------------------------------------
    // Utilidades de datos (con commit real: los tests no usan @Transactional)
    // ------------------------------------------------------------------

    /** Crea un usuario con rol dado; el email es {@code nombre@test.com}. */
    protected User saveUser(String name, Role role) {
        User user = new User();
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(role);
        return userRepository.save(user);
    }

    /** Cabecera {@code Authorization} con un JWT válido para el usuario. */
    protected String tokenFor(User user) {
        return "Bearer " + jwtService.generateToken(user);
    }

    /** Guarda un género. */
    protected Genre saveGenre(String name) {
        Genre genre = new Genre();
        genre.setName(name);
        return genreRepository.save(genre);
    }

    /** Guarda una película de 100 minutos con los géneros indicados. */
    protected Movie saveMovie(String title, int year, Genre... genres) {
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setDescription("Descripción de " + title);
        movie.setDuration(100);
        movie.setReleaseYear(year);
        movie.setImageUrl("https://example.com/image.jpg");
        movie.setVideoUrl("https://example.com/video.mp4");
        movie.setGenres(new HashSet<>(List.of(genres)));
        return movieRepository.save(movie);
    }

    /** Guarda una serie (sin fecha de fin) con los géneros indicados. */
    protected Series saveSeries(String title, int year, Genre... genres) {
        Series series = new Series();
        series.setTitle(title);
        series.setDescription("Sinopsis de " + title);
        series.setReleaseYear(year);
        series.setImageUrl("https://example.com/serie.jpg");
        series.setGenres(new HashSet<>(List.of(genres)));
        return seriesRepository.save(series);
    }

    /** Guarda un episodio de 45 minutos en la posición temporada × número. */
    protected Episode saveEpisode(Series series, int season, int number) {
        Episode episode = new Episode();
        episode.setSeries(series);
        episode.setSeasonNumber(season);
        episode.setEpisodeNumber(number);
        episode.setTitle("Episodio " + season + "x" + number);
        episode.setDuration(45);
        episode.setVideoUrl("https://example.com/episodio.mp4");
        return episodeRepository.save(episode);
    }

    /**
     * Vacía las tablas en el orden que exigen las claves foráneas: series
     * (arrastran episodios, {@code series_genres} y {@code user_favorite_series}),
     * usuarios (arrastra {@code user_favorite_movies}), películas (arrastra
     * {@code movie_genres}) y por último géneros, que ya nadie usa.
     */
    protected void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }

    /**
     * Recorre la cadena de causas y devuelve el {@code SQLSTATE} del primer
     * {@link SQLException} que encuentra: es el código real que PostgreSQL
     * envía ({@code 23505} unicidad, {@code 23503} clave foránea, {@code 23514}
     * {@code CHECK}, {@code 23502} NOT NULL...). Permite comprobar que el
     * rechazo viene de la base de datos y no de otra capa.
     *
     * @param error excepción lanzada por Spring/JDBC
     * @return el SQLSTATE, o {@code null} si no hay ninguna {@link SQLException}
     */
    protected static String sqlState(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return null;
    }
}
