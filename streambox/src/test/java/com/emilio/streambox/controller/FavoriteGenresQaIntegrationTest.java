package com.emilio.streambox.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

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
import com.jayway.jsonpath.JsonPath;

/**
 * Revisión independiente de QA de los géneros favoritos («Tus gustos») y de su
 * efecto en las recomendaciones, siempre por HTTP (como lo usa el frontend).
 *
 * <p>
 * Completa {@link FavoriteGenresIntegrationTest} y
 * {@link RecommendationsIntegrationTest} con lo que esas suites no cubren de punta
 * a punta:
 * </p>
 * <ul>
 * <li>el ejemplo literal del autor (un género con 1 «me gusta» + favorito pesa
 * 3 y gana a uno con 2 «me gusta»), con los gustos guardados por el
 * {@code PUT} real (3 géneros, no uno insertado a mano);</li>
 * <li>que {@code basis} y los nombres de {@code GET /me/genres} cuentan la misma
 * historia (lo que pinta «Porque te gustan…» en el inicio);</li>
 * <li>que borrar un género favorito por la API del panel no se bloquea y lo
 * saca de los gustos;</li>
 * <li>cuerpos raros del {@code PUT}: ninguno acaba en 500.</li>
 * </ul>
 *
 * <p>
 * Sin {@code @Transactional} (el {@code PUT} cierra la pregunta con un
 * {@code UPDATE} masivo; ver {@link FavoriteGenresIntegrationTest}); limpia antes
 * y después. Mismas propiedades que esa suite para compartir el contexto.
 * </p>
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class FavoriteGenresQaIntegrationTest {

    private static final String GENRES = "/api/users/me/genres";
    private static final String OPTIONS = "/api/users/me/genres/options";
    private static final String RECOMMENDATIONS = "/api/users/me/recommendations";

    private static final Instant BASE = Instant.parse("2024-01-01T00:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private MovieRepository movieRepository;
    @Autowired private SeriesRepository seriesRepository;
    @Autowired private EpisodeRepository episodeRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    private String token;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        token = bearer(saveUser("qafavgen", Role.USER));
    }

    @AfterEach
    void tearDown() {
        cleanDatabase();
    }

    // ------------------------------------------------------------------
    // Peso del favorito (decisión 4 del autor), de punta a punta
    // ------------------------------------------------------------------

    /**
     * Ejemplo literal del autor: Acción tiene 1 «me gusta» y es favorito (1 + 2 =
     * 3); Comedia tiene 2 «me gusta» y no es favorito (2). La candidata de acción
     * es la MÁS ANTIGUA de todas, así que solo sale primera si pesa más de verdad:
     * si el favorito valiera 1, Acción empataría con Comedia (2 = 2) y saldría
     * antes la comedia, más nueva. Los otros dos favoritos (necesarios: el
     * {@code PUT} exige 3) pesan 2 y quedan detrás de Acción.
     */
    @Test
    void unGeneroConUnMeGustaYFavoritoPesaTresYGanaAUnoConDosMeGusta() throws Exception {
        Genre accion = saveGenre("Acción");
        Genre comedia = saveGenre("Comedia");
        Genre western = saveGenre("Western");
        Genre musical = saveGenre("Musical");

        Movie candidataAccion = saveMovie("Candidata de acción", accion);
        Movie candidataComedia = saveMovie("Candidata de comedia", comedia);
        Movie candidataWestern = saveMovie("Candidata del oeste", western);
        Movie candidataMusical = saveMovie("Candidata musical", musical);
        setCreatedAt(candidataAccion, BASE);
        setCreatedAt(candidataComedia, BASE.plusSeconds(60));
        setCreatedAt(candidataWestern, BASE.plusSeconds(120));
        setCreatedAt(candidataMusical, BASE.plusSeconds(180));

        like(saveMovie("Acción que me gusta", accion));
        like(saveMovie("Comedia que me gusta 1", comedia));
        like(saveMovie("Comedia que me gusta 2", comedia));
        putGenres(ids(accion, western, musical)).andExpect(status().isOk());

        String json = recommendationsJson();

        assertEquals("GENRES_AND_LIKES", JsonPath.read(json, "$.basis"));
        // Acción 3; Comedia, Western y Musical 2 (desempate: la más nueva primero).
        assertEquals(List.of(candidataAccion.getId().intValue(), candidataMusical.getId().intValue(),
                candidataWestern.getId().intValue(), candidataComedia.getId().intValue()),
                JsonPath.read(json, "$.movies[*].id"));
    }

    /**
     * El otro extremo: 1 «me gusta» + favorito (3) empata con 3 «me gusta» (3) y
     * gana la más nueva. Si el favorito valiera 3 (o más), Acción seguiría
     * ganando: con el test anterior, fija el peso en exactamente 2.
     */
    @Test
    void unMeGustaMasFavoritoEmpataConTresMeGusta() throws Exception {
        Genre accion = saveGenre("Acción");
        Genre comedia = saveGenre("Comedia");
        Genre western = saveGenre("Western");
        Genre musical = saveGenre("Musical");
        saveMovie("Del oeste", western);
        saveMovie("Musical", musical);

        Movie candidataAccion = saveMovie("Candidata de acción", accion);
        Movie candidataComedia = saveMovie("Candidata de comedia", comedia);
        setCreatedAt(candidataAccion, BASE);
        setCreatedAt(candidataComedia, BASE.plusSeconds(60));

        like(saveMovie("Acción que me gusta", accion));
        like(saveMovie("Comedia que me gusta 1", comedia));
        like(saveMovie("Comedia que me gusta 2", comedia));
        like(saveMovie("Comedia que me gusta 3", comedia));
        putGenres(ids(accion, western, musical)).andExpect(status().isOk());

        List<Integer> movies = JsonPath.read(recommendationsJson(), "$.movies[*].id");

        assertEquals(List.of(candidataComedia.getId().intValue(), candidataAccion.getId().intValue()),
                movies.subList(0, 2), "3 = 3: empate, primero la más nueva");
    }

    // ------------------------------------------------------------------
    // basis y «Porque te gustan…»: la misma historia
    // ------------------------------------------------------------------

    /**
     * Sin ningún «me gusta», con gustos guardados: llegan títulos de esos
     * géneros, solo visibles (la serie sin episodios no sale), y los nombres de
     * {@code GET /me/genres} son exactamente los que el inicio puede citar.
     * Uno de los tres favoritos se queda sin títulos: ni se nombra ni aporta.
     */
    @Test
    void conSoloGustosHayTitulosVisiblesYLosNombresCoincidenConLaBase() throws Exception {
        Genre accion = saveGenre("Acción");
        Genre drama = saveGenre("Drama");
        Genre western = saveGenre("Western");
        Genre comedia = saveGenre("Comedia");
        Movie pelicula = saveMovie("Película de acción", accion);
        Movie delOeste = saveMovie("Película del oeste", western);
        Series serie = saveSeries("Serie de drama", drama);
        saveEpisode(serie);
        saveSeries("Serie de acción oculta", accion);
        saveMovie("Comedia que no es favorita", comedia);
        putGenres(ids(accion, drama, western)).andExpect(status().isOk());

        movieRepository.delete(delOeste); // Western se queda sin títulos

        String json = recommendationsJson();
        assertEquals("GENRES", JsonPath.read(json, "$.basis"));
        assertEquals(List.of(pelicula.getId().intValue()), JsonPath.read(json, "$.movies[*].id"));
        assertEquals(List.of(serie.getId().intValue()), JsonPath.read(json, "$.series[*].id"));
        assertEquals(List.of("Acción", "Drama"), names(mockMvc.perform(get(GENRES).header("Authorization", token))));
    }

    /**
     * Con un «me gusta» y unos gustos que se han quedado TODOS sin títulos, la
     * base es solo {@code LIKES}: si dijera {@code GENRES_AND_LIKES}, el inicio
     * escribiría «Porque te gustan…» sin ningún género que nombrar
     * ({@code GET /me/genres} va vacío).
     */
    @Test
    void conGustosSinTitulosYUnMeGustaLaBaseEsSoloLikes() throws Exception {
        Genre accion = saveGenre("Acción");
        Genre drama = saveGenre("Drama");
        Genre western = saveGenre("Western");
        Genre comedia = saveGenre("Comedia");
        Movie a = saveMovie("Acción", accion);
        Movie d = saveMovie("Drama", drama);
        Movie w = saveMovie("Western", western);
        putGenres(ids(accion, drama, western)).andExpect(status().isOk());
        movieRepository.deleteAll(List.of(a, d, w));
        like(saveMovie("Comedia que me gusta", comedia));
        Movie candidata = saveMovie("Otra comedia", comedia);

        String json = recommendationsJson();

        assertEquals("LIKES", JsonPath.read(json, "$.basis"));
        assertEquals(List.of(candidata.getId().intValue()), JsonPath.read(json, "$.movies[*].id"));
        assertEquals(List.of(), names(mockMvc.perform(get(GENRES).header("Authorization", token))));
    }

    /**
     * Lo que ya te gusta nunca se recomienda, aunque sea de un género favorito;
     * y lo que está en «Mi lista» sí puede recomendarse (el contrato solo
     * excluye los «me gusta»).
     */
    @Test
    void seExcluyeLoQueYaTeGustaAunqueSeaDeUnGeneroFavorito() throws Exception {
        Genre accion = saveGenre("Acción");
        Genre drama = saveGenre("Drama");
        Genre western = saveGenre("Western");
        Movie meGusta = saveMovie("Acción que me gusta", accion);
        Movie otra = saveMovie("Otra de acción", accion);
        saveMovie("Drama", drama);
        saveMovie("Western", western);
        putGenres(ids(accion, drama, western)).andExpect(status().isOk());
        like(meGusta);

        List<Integer> movies = JsonPath.read(recommendationsJson(), "$.movies[*].id");

        assertEquals(false, movies.contains(meGusta.getId().intValue()));
        assertEquals(true, movies.contains(otra.getId().intValue()));
    }

    // ------------------------------------------------------------------
    // Borrar un género favorito (ON DELETE CASCADE)
    // ------------------------------------------------------------------

    /**
     * El ADMIN borra por la API un género que alguien tiene entre sus gustos
     * (ya sin títulos, porque con títulos el borrado da 409): 204, sale de los
     * gustos de esa persona y su siguiente {@code PUT} funciona.
     */
    @Test
    void borrarUnGeneroFavoritoNoSeBloqueaYLoSacaDeLosGustos() throws Exception {
        Genre accion = saveGenre("Acción");
        Genre drama = saveGenre("Drama");
        Genre western = saveGenre("Western");
        Genre comedia = saveGenre("Comedia");
        saveMovie("Acción", accion);
        saveMovie("Drama", drama);
        Movie delOeste = saveMovie("Western", western);
        saveMovie("Comedia", comedia);
        putGenres(ids(accion, drama, western)).andExpect(status().isOk());
        movieRepository.delete(delOeste);
        String admin = bearer(saveUser("qafavgenadmin", Role.ADMIN));

        mockMvc.perform(delete("/api/genres/" + western.getId()).header("Authorization", admin))
                .andExpect(status().isNoContent());

        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM user_favorite_genres", Integer.class));
        assertEquals(List.of("Acción", "Drama"), names(mockMvc.perform(get(GENRES).header("Authorization", token))));
        putGenres(ids(accion, drama, comedia)).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Cuerpos raros del PUT y métodos: nunca 500
    // ------------------------------------------------------------------

    /**
     * Cuerpos que un cliente roto podría enviar: todos 400 (nunca 500 ni un
     * guardado a medias). Un decimal no se trunca a entero
     * ({@code accept-float-as-int=false}) ni un texto numérico se convierte, y un
     * número suelto no se toma como lista de uno.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "[1,2,3]",
            "{\"genreIds\":5}",
            "{\"genreIds\":[1.5,2,3]}",
            "{\"genreIds\":[\"1\",\"2\",\"3\"]}",
            "{\"genreIds\":{\"a\":1}}",
            "{\"genreIds\":[9223372036854775808,2,3]}",
            "{\"genreIds\":[true,false,true]}",
            "",
            "{\"genreIds\":[1,2,3]"
    })
    void unCuerpoMalFormadoDa400SinGuardarNada(String body) throws Exception {
        Genre accion = saveGenre("Acción");
        saveMovie("Acción", accion);

        mockMvc.perform(put(GENRES).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value(GENRES));

        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM user_favorite_genres", Integer.class));
    }

    /** Un {@code Content-Type} que no es JSON: 415 (no 400 ni 500). */
    @Test
    void unCuerpoQueNoEsJsonDa415() throws Exception {
        mockMvc.perform(put(GENRES).header("Authorization", token)
                        .contentType(MediaType.TEXT_PLAIN).content("{\"genreIds\":[]}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    /** {@code POST}/{@code DELETE} sobre la lista no existen: 405 (la lista se sustituye con {@code PUT}). */
    @Test
    void otrosMetodosDan405() throws Exception {
        mockMvc.perform(delete(GENRES).header("Authorization", token))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put(OPTIONS).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"genreIds\":[]}"))
                .andExpect(status().isMethodNotAllowed());
    }

    /** {@code HEAD} y {@code OPTIONS} sin sesión también dan 401: los gustos son personales. */
    @Test
    void headYOptionsSinSesionDan401() throws Exception {
        mockMvc.perform(head(GENRES)).andExpect(status().isUnauthorized());
        mockMvc.perform(head(OPTIONS)).andExpect(status().isUnauthorized());
        mockMvc.perform(options(GENRES)).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private ResultActions putGenres(String idsJson) throws Exception {
        return mockMvc.perform(put(GENRES).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"genreIds\":" + idsJson + "}"));
    }

    private void like(Movie movie) throws Exception {
        mockMvc.perform(put("/api/users/me/likes/movies/" + movie.getId()).header("Authorization", token))
                .andExpect(status().isNoContent());
    }

    private String recommendationsJson() throws Exception {
        return mockMvc.perform(get(RECOMMENDATIONS).header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static List<String> names(ResultActions result) throws Exception {
        return JsonPath.read(result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$[*].name");
    }

    private static String ids(Genre... genres) {
        return Stream.of(genres).map(g -> String.valueOf(g.getId())).collect(Collectors.joining(",", "[", "]"));
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateToken(user);
    }

    private void setCreatedAt(Movie movie, Instant createdAt) {
        jdbc.update("UPDATE movies SET created_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC), movie.getId());
    }

    private User saveUser(String name, Role role) {
        User user = new User();
        user.setEmailVerifiedAt(Instant.now());
        user.setUsername(name);
        user.setEmail(name + "@test.com");
        user.setPassword(passwordEncoder.encode("password123"));
        user.setRole(role);
        return userRepository.save(user);
    }

    private Genre saveGenre(String name) {
        Genre genre = new Genre();
        genre.setName(name);
        return genreRepository.save(genre);
    }

    private Movie saveMovie(String title, Genre... genres) {
        Movie movie = new Movie();
        movie.setTitle(title);
        movie.setDescription("Descripción de " + title);
        movie.setDuration(100);
        movie.setReleaseYear(2000);
        movie.setImageUrl("https://example.com/image.jpg");
        movie.setVideoUrl("https://example.com/video.mp4");
        movie.setGenres(new HashSet<>(List.of(genres)));
        return movieRepository.save(movie);
    }

    private Series saveSeries(String title, Genre... genres) {
        Series series = new Series();
        series.setTitle(title);
        series.setDescription("Sinopsis de " + title);
        series.setReleaseYear(2010);
        series.setImageUrl("https://example.com/serie.jpg");
        series.setGenres(new HashSet<>(List.of(genres)));
        return seriesRepository.save(series);
    }

    private void saveEpisode(Series series) {
        Episode episode = new Episode();
        episode.setSeries(series);
        episode.setSeasonNumber(1);
        episode.setEpisodeNumber(1);
        episode.setTitle("Episodio 1x1");
        episode.setDuration(45);
        episode.setVideoUrl("https://example.com/episodio.mp4");
        episodeRepository.save(episode);
    }

    /** Vacía las tablas respetando las claves foráneas (gustos y «me gusta» caen en cascada). */
    private void cleanDatabase() {
        seriesRepository.deleteAll();
        userRepository.deleteAll();
        movieRepository.deleteAll();
        genreRepository.deleteAll();
    }
}
