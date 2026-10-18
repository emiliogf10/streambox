package com.emilio.streambox.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.entity.User;

/**
 * Casos de prueba de los «me gusta» y de las recomendaciones por géneros
 * ({@code V8}: {@code user_liked_movies} y {@code user_liked_series}), escritos
 * <strong>una sola vez</strong> y ejecutados en los dos motores: H2
 * ({@link LikesRepositoryIntegrationTest}) y PostgreSQL real
 * ({@code PostgresLikesIntegrationTest}). Mismo patrón que
 * {@link SimilarMoviesQueryContract}.
 *
 * <p>
 * Qué se prueba: {@code addLike}/{@code removeLike} idempotentes (con
 * {@code ON CONFLICT DO NOTHING}, que no existe en SQL estándar y por eso se
 * comprueba en los dos motores), los listados por fecha del «me gusta», la
 * ocultación de las series sin episodios, el borrado en cascada y la
 * puntuación, exclusión, desempate y límite de
 * {@link MovieRepository#findRecommendedMovieIds(Long, int)} y
 * {@link SeriesRepository#findRecommendedSeriesIds(Long, int)}, además del
 * número de sentencias (sin N+1).
 * </p>
 *
 * <p>
 * Las escrituras van dentro de {@link #tx()}: las consultas
 * {@code @Modifying} exigen una transacción, como la que abrirá el servicio.
 * Las fechas de los «me gusta» se pasan explícitas (el servicio las tomará de
 * su {@code Clock}) para que el orden no dependa de la velocidad del test.
 * </p>
 */
public interface LikesQueryContract {

    /** Instante base de las fechas fijadas a mano. */
    Instant BASE = Instant.parse("2024-01-01T00:00:00Z");

    // ------------------------------------------------------------------
    // Lo que aporta cada motor
    // ------------------------------------------------------------------

    /** @return repositorio de películas */
    MovieRepository movies();

    /** @return repositorio de series */
    SeriesRepository series();

    /** @return repositorio de usuarios */
    UserRepository users();

    /** @return acceso JDBC directo, para fijar fechas y contar filas */
    JdbcTemplate jdbc();

    /** Guarda un género. */
    Genre genre(String name);

    /** Guarda una película con esos géneros. */
    Movie movie(String title, Genre... genres);

    /** Guarda una serie (sin episodios) con esos géneros. */
    Series serie(String title, Genre... genres);

    /** Añade a la serie el episodio 1x{@code number}. */
    void episode(Series series, int number);

    /** Guarda un usuario con rol {@code USER}. */
    User user(String name);

    /** Número de sentencias SQL preparadas mientras se ejecuta la operación. */
    long statementsFor(Runnable operation);

    /** Plantilla de transacción, como la que abrirá el servicio. */
    TransactionTemplate tx();

    // ------------------------------------------------------------------
    // Utilidades comunes
    // ------------------------------------------------------------------

    /** Da «me gusta» a una película en su propia transacción; devuelve las filas insertadas. */
    default int likeMovie(User user, Movie movie, Instant at) {
        return tx().execute(status -> movies().addLike(user.getId(), movie.getId(), at));
    }

    /** Da «me gusta» a una serie en su propia transacción; devuelve las filas insertadas. */
    default int likeSeries(User user, Series series, Instant at) {
        return tx().execute(status -> series().addLike(user.getId(), series.getId(), at));
    }

    /** Quita el «me gusta» de una película en su propia transacción; devuelve las filas borradas. */
    default int unlikeMovie(User user, long movieId) {
        return tx().execute(status -> movies().removeLike(user.getId(), movieId));
    }

    /** Quita el «me gusta» de una serie en su propia transacción; devuelve las filas borradas. */
    default int unlikeSeries(User user, long seriesId) {
        return tx().execute(status -> series().removeLike(user.getId(), seriesId));
    }

    /** Serie con un episodio (visible para los usuarios). */
    default Series visibleSerie(String title, Genre... genres) {
        Series series = serie(title, genres);
        episode(series, 1);
        return series;
    }

    /** Sobrescribe {@code created_at} de una película (la entidad no deja cambiarlo). */
    default void setCreatedAt(Movie movie, Instant createdAt) {
        jdbc().update("UPDATE movies SET created_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC), movie.getId());
    }

    /** Sobrescribe {@code created_at} de una serie. */
    default void setCreatedAt(Series series, Instant createdAt) {
        jdbc().update("UPDATE series SET created_at = ? WHERE id = ?",
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC), series.getId());
    }

    /** Filas de una tabla de «me gusta» que cumplen {@code column = value}. */
    default int likeRows(String table, String column, long value) {
        return jdbc().queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, value);
    }

    /** Recomendaciones de películas con un límite amplio. */
    default List<Long> recommendedMovies(User user) {
        return movies().findRecommendedMovieIds(user.getId(), 24);
    }

    /** Recomendaciones de series con un límite amplio. */
    default List<Long> recommendedSeries(User user) {
        return series().findRecommendedSeriesIds(user.getId(), 24);
    }

    /** Busca el {@code SQLSTATE} en la cadena de causas. */
    private static String sqlState(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Dar y quitar «me gusta»
    // ------------------------------------------------------------------

    /**
     * Dar «me gusta» dos veces deja <strong>un</strong> registro: la segunda
     * vez devuelve 0 filas, no lanza la violación de la clave primaria y
     * conserva la fecha original (repetirlo no lo sube al principio).
     */
    @Test
    default void darMeGustaDosVecesDejaUnRegistroConLaFechaOriginal() {
        User ana = user("ana");
        Movie movie = movie("Pelicula");
        Series serie = visibleSerie("Serie");

        assertEquals(1, likeMovie(ana, movie, BASE));
        assertEquals(0, likeMovie(ana, movie, BASE.plusSeconds(60)));
        assertEquals(1, likeSeries(ana, serie, BASE));
        assertEquals(0, likeSeries(ana, serie, BASE.plusSeconds(60)));

        assertEquals(1, likeRows("user_liked_movies", "user_id", ana.getId()));
        assertEquals(1, likeRows("user_liked_series", "user_id", ana.getId()));
        assertEquals(BASE, jdbc().queryForObject("SELECT created_at FROM user_liked_movies WHERE user_id = ?",
                OffsetDateTime.class, ana.getId()).toInstant());
        assertEquals(BASE, jdbc().queryForObject("SELECT created_at FROM user_liked_series WHERE user_id = ?",
                OffsetDateTime.class, ana.getId()).toInstant());
    }

    /**
     * Quitar un «me gusta» que no existe (de un título que no le gustaba o que
     * ni siquiera existe) no falla: devuelve 0. Quitar uno que existe devuelve
     * 1 y no toca los de otros usuarios.
     */
    @Test
    default void quitarMeGustaEsIdempotenteYSoloTocaElDelUsuario() {
        User ana = user("ana");
        User bea = user("bea");
        Movie movie = movie("Pelicula");
        Series serie = visibleSerie("Serie");
        likeMovie(ana, movie, BASE);
        likeMovie(bea, movie, BASE);
        likeSeries(bea, serie, BASE);

        assertEquals(0, unlikeSeries(ana, serie.getId()));
        assertEquals(0, unlikeMovie(ana, Long.MAX_VALUE));
        assertEquals(1, unlikeMovie(ana, movie.getId()));
        assertEquals(0, unlikeMovie(ana, movie.getId()));

        assertEquals(0, likeRows("user_liked_movies", "user_id", ana.getId()));
        assertEquals(1, likeRows("user_liked_movies", "user_id", bea.getId()));
        assertEquals(1, likeRows("user_liked_series", "user_id", bea.getId()));
    }

    /**
     * {@code ON CONFLICT DO NOTHING} solo ignora el duplicado: un «me gusta» a
     * un título inexistente sigue fallando con la violación de clave foránea
     * ({@code 23503} en PostgreSQL, {@code 23506} en H2), que el servicio
     * traducirá a 404 (la carrera «se borró entre la comprobación y el
     * {@code INSERT}»).
     */
    @Test
    default void darMeGustaAUnTituloInexistenteFallaPorLaClaveForanea() {
        User ana = user("ana");

        DataIntegrityViolationException movieError = assertThrows(DataIntegrityViolationException.class,
                () -> tx().execute(status -> movies().addLike(ana.getId(), Long.MAX_VALUE, BASE)));
        DataIntegrityViolationException seriesError = assertThrows(DataIntegrityViolationException.class,
                () -> tx().execute(status -> series().addLike(ana.getId(), Long.MAX_VALUE, BASE)));

        assertTrue(Set.of("23503", "23506").contains(sqlState(movieError)), sqlState(movieError));
        assertTrue(Set.of("23503", "23506").contains(sqlState(seriesError)), sqlState(seriesError));
        assertEquals(0, likeRows("user_liked_movies", "user_id", ana.getId()));
    }

    // ------------------------------------------------------------------
    // Listados de la sección «Te gusta»
    // ------------------------------------------------------------------

    /**
     * Las películas que te gustan salen de la que te gustó más recientemente a
     * la más antigua (fecha del «me gusta», no de la película) y, con la misma
     * fecha, por id descendente. Los «me gusta» se dan en un orden distinto del
     * de creación de las películas para que ninguno de los dos explique el
     * resultado por casualidad. Los de otro usuario no aparecen.
     */
    @Test
    default void lasPeliculasQueTeGustanSalenDeLaMasRecienteALaMasAntigua() {
        User ana = user("ana");
        User bea = user("bea");
        Movie first = movie("Primera creada");
        Movie second = movie("Segunda creada");
        Movie third = movie("Tercera creada");
        Movie fourth = movie("Cuarta creada");
        Movie other = movie("Solo de Bea");
        likeMovie(ana, second, BASE.plusSeconds(30));
        likeMovie(ana, first, BASE.plusSeconds(20));
        likeMovie(ana, fourth, BASE.plusSeconds(10));
        likeMovie(ana, third, BASE.plusSeconds(10));
        likeMovie(bea, other, BASE.plusSeconds(99));

        List<Long> liked = movies().findLikedByUserId(ana.getId()).stream().map(Movie::getId).toList();

        assertEquals(List.of(second.getId(), first.getId(), fourth.getId(), third.getId()), liked);
        assertEquals(List.of(), movies().findLikedByUserId(user("singustos").getId()));
    }

    /**
     * Las series que te gustan, en el mismo orden, pero <strong>solo las
     * visibles</strong>: una serie que te gustó y se quedó sin episodios no
     * sale (como en «Mi lista»), aunque su «me gusta» se conserva y reaparece
     * cuando vuelve a tener episodios.
     */
    @Test
    default void lasSeriesQueTeGustanSalenPorFechaYSoloLasQueTienenEpisodios() {
        User ana = user("ana");
        Series older = visibleSerie("Antigua");
        Series newer = visibleSerie("Nueva");
        Series hidden = serie("Sin episodios");
        likeSeries(ana, older, BASE.plusSeconds(10));
        likeSeries(ana, newer, BASE.plusSeconds(20));
        likeSeries(ana, hidden, BASE.plusSeconds(30));

        assertEquals(List.of(newer.getId(), older.getId()),
                series().findVisibleLikedByUserId(ana.getId()).stream().map(Series::getId).toList());
        assertEquals(3, likeRows("user_liked_series", "user_id", ana.getId()), "el «me gusta» oculto se conserva");

        episode(hidden, 1);

        assertEquals(List.of(hidden.getId(), newer.getId(), older.getId()),
                series().findVisibleLikedByUserId(ana.getId()).stream().map(Series::getId).toList());
    }

    /**
     * Sin N+1: el listado de 12 películas que te gustan más sus géneros son dos
     * sentencias (la consulta y una carga por lotes de los géneros), igual con
     * las series.
     */
    @Test
    default void listarLoQueTeGustaConSusGenerosSonDosSentencias() {
        Genre drama = genre("Drama");
        Genre comedy = genre("Comedia");
        User ana = user("ana");
        for (int i = 0; i < 12; i++) {
            likeMovie(ana, movie("Pelicula " + i, drama, comedy), BASE.plusSeconds(i));
            likeSeries(ana, visibleSerie("Serie " + i, drama, comedy), BASE.plusSeconds(i));
        }

        long movieStatements = statementsFor(() -> tx().executeWithoutResult(status -> {
            List<Movie> liked = movies().findLikedByUserId(ana.getId());
            assertEquals(12, liked.size());
            liked.forEach(movie -> assertEquals(2, movie.getGenres().size()));
        }));
        long seriesStatements = statementsFor(() -> tx().executeWithoutResult(status -> {
            List<Series> liked = series().findVisibleLikedByUserId(ana.getId());
            assertEquals(12, liked.size());
            liked.forEach(series -> assertEquals(2, series.getGenres().size()));
        }));

        assertEquals(2, movieStatements, "listado + una carga por lotes de los géneros");
        assertEquals(2, seriesStatements, "listado + una carga por lotes de los géneros");
    }

    /**
     * {@code hasVisibleLikes} (lo que decide {@code basedOnLikes}): falso sin
     * «me gusta» y con solo el de una serie oculta; verdadero con el de una
     * película o el de una serie con episodios.
     */
    @Test
    default void tieneMeGustaVisiblesSoloCuentaPeliculasYSeriesConEpisodios() {
        User ana = user("ana");
        User bea = user("bea");
        Series hidden = serie("Oculta");
        Series visible = visibleSerie("Visible");

        assertFalse(users().hasVisibleLikes(ana.getId()));
        likeSeries(ana, hidden, BASE);
        assertFalse(users().hasVisibleLikes(ana.getId()), "una serie oculta no cuenta");
        likeSeries(ana, visible, BASE);
        assertTrue(users().hasVisibleLikes(ana.getId()));

        likeMovie(bea, movie("Pelicula"), BASE);
        assertTrue(users().hasVisibleLikes(bea.getId()));
        assertFalse(users().hasVisibleLikes(Long.MAX_VALUE));
    }

    // ------------------------------------------------------------------
    // Borrado en cascada
    // ------------------------------------------------------------------

    /**
     * Borrar una película o una serie con los repositorios (como hace el panel
     * de administración) borra sus «me gusta» por la cascada de la base de
     * datos, sin tocar los de otros títulos; borrar un usuario borra todos los
     * suyos y ninguno de otro usuario.
     */
    @Test
    default void borrarUnTituloOUnUsuarioBorraSusMeGusta() {
        User ana = user("ana");
        User bea = user("bea");
        Movie keptMovie = movie("Se queda");
        Movie doomedMovie = movie("Se borra");
        Series keptSeries = visibleSerie("Serie que se queda");
        Series doomedSeries = visibleSerie("Serie que se borra");
        for (User user : List.of(ana, bea)) {
            likeMovie(user, keptMovie, BASE);
            likeMovie(user, doomedMovie, BASE);
            likeSeries(user, keptSeries, BASE);
            likeSeries(user, doomedSeries, BASE);
        }

        movies().deleteById(doomedMovie.getId());
        series().deleteById(doomedSeries.getId());

        assertEquals(0, likeRows("user_liked_movies", "movie_id", doomedMovie.getId()));
        assertEquals(0, likeRows("user_liked_series", "series_id", doomedSeries.getId()));
        assertEquals(List.of(keptMovie.getId()),
                movies().findLikedByUserId(ana.getId()).stream().map(Movie::getId).toList());
        assertEquals(List.of(keptSeries.getId()),
                series().findVisibleLikedByUserId(ana.getId()).stream().map(Series::getId).toList());

        users().deleteById(ana.getId());

        assertEquals(0, likeRows("user_liked_movies", "user_id", ana.getId()));
        assertEquals(0, likeRows("user_liked_series", "user_id", ana.getId()));
        assertEquals(1, likeRows("user_liked_movies", "user_id", bea.getId()));
        assertEquals(1, likeRows("user_liked_series", "user_id", bea.getId()));
    }

    // ------------------------------------------------------------------
    // Recomendaciones
    // ------------------------------------------------------------------

    /** Sin ningún «me gusta» no hay recomendaciones (ni error), aunque haya catálogo. */
    @Test
    default void sinMeGustaNoHayRecomendaciones() {
        Genre drama = genre("Drama");
        movie("Pelicula", drama);
        visibleSerie("Serie", drama);
        User ana = user("ana");
        User bea = user("bea");
        likeMovie(bea, movie("Gusta a Bea", drama), BASE);

        assertEquals(List.of(), recommendedMovies(ana));
        assertEquals(List.of(), recommendedSeries(ana));
        assertEquals(List.of(), movies().findRecommendedMovieIds(Long.MAX_VALUE, 24));
    }

    /**
     * Puntuación = suma de los pesos de los géneros de la candidata, y el peso
     * de un género = cuántos de tus «me gusta» lo tienen. Te gustan
     * {@code (Drama, Comedia)} y {@code (Drama)}: Drama pesa 2 y Comedia 1. La
     * candidata con los dos géneros suma 3, la de Drama 2 y la de Comedia 1; la
     * de Acción no comparte nada y no sale, ni tampoco las que ya te gustan.
     * Las fechas de creación van al revés del resultado esperado para que el
     * desempate no pueda explicarlo.
     */
    @Test
    default void puntuaPorElPesoDeCadaGeneroYExcluyeLoQueYaTeGusta() {
        Genre drama = genre("Drama");
        Genre comedy = genre("Comedia");
        Genre action = genre("Accion");
        User ana = user("ana");
        likeMovie(ana, movie("Gusta 1", drama, comedy), BASE);
        likeMovie(ana, movie("Gusta 2", drama), BASE);
        Movie both = movie("Drama y comedia", drama, comedy);
        Movie dramaOnly = movie("Drama", drama);
        Movie comedyOnly = movie("Comedia", comedy);
        movie("Accion", action);
        setCreatedAt(both, BASE.plusSeconds(1));
        setCreatedAt(dramaOnly, BASE.plusSeconds(2));
        setCreatedAt(comedyOnly, BASE.plusSeconds(3));

        assertEquals(List.of(both.getId(), dramaOnly.getId(), comedyOnly.getId()), recommendedMovies(ana));
    }

    /**
     * Compartir dos géneros con una sola película que te gusta cuenta 2 (uno
     * por género compartido): con un único «me gusta» {@code (Drama, Comedia)},
     * la candidata con los dos géneros va antes que la de uno, aunque esta sea
     * más nueva.
     */
    @Test
    default void compartirDosGenerosConUnMismoMeGustaCuentaDos() {
        Genre drama = genre("Drama");
        Genre comedy = genre("Comedia");
        User ana = user("ana");
        likeMovie(ana, movie("Gusta", drama, comedy), BASE);
        Movie two = movie("Dos en comun", drama, comedy);
        Movie one = movie("Uno en comun", drama);
        setCreatedAt(two, BASE.plusSeconds(1));
        setCreatedAt(one, BASE.plusSeconds(2));

        assertEquals(List.of(two.getId(), one.getId()), recommendedMovies(ana));
    }

    /**
     * Los géneros son comunes: los «me gusta» de series cuentan para recomendar
     * películas y los de películas para recomendar series. Solo se recomiendan
     * series con episodios, y nunca la que ya te gusta.
     */
    @Test
    default void losMeGustaDePeliculasYSeriesSeCombinanYSoloSeRecomiendanSeriesVisibles() {
        Genre horror = genre("Terror");
        Genre drama = genre("Drama");
        User ana = user("ana");
        Series likedSeries = visibleSerie("Serie de terror que gusta", horror);
        likeSeries(ana, likedSeries, BASE);
        Movie horrorMovie = movie("Pelicula de terror", horror);
        movie("Pelicula de drama", drama);
        Series horrorSeries = visibleSerie("Otra serie de terror", horror);
        serie("Serie de terror vacia", horror);

        assertEquals(List.of(horrorMovie.getId()), recommendedMovies(ana));
        assertEquals(List.of(horrorSeries.getId()), recommendedSeries(ana));

        User bea = user("bea");
        likeMovie(bea, movie("Drama que gusta", drama), BASE);
        Series dramaSeries = visibleSerie("Serie de drama", drama);

        assertEquals(List.of(dramaSeries.getId()), recommendedSeries(bea));
    }

    /**
     * Un «me gusta» de una serie oculta (sin episodios) no cuenta: es coherente
     * con la sección «Te gusta», donde tampoco aparece.
     */
    @Test
    default void elMeGustaDeUnaSerieOcultaNoGeneraRecomendaciones() {
        Genre horror = genre("Terror");
        User ana = user("ana");
        likeSeries(ana, serie("Oculta", horror), BASE);
        movie("Pelicula de terror", horror);
        visibleSerie("Serie de terror", horror);

        assertEquals(List.of(), recommendedMovies(ana));
        assertEquals(List.of(), recommendedSeries(ana));
    }

    /**
     * Si ya te gusta todo el catálogo que comparte géneros contigo, no queda
     * nada que recomendar: las dos listas salen vacías aunque
     * {@code hasVisibleLikes} sea verdadero (es el caso «{@code basedOnLikes:
     * true} sin resultados», en el que el inicio no pinta la fila). Con un
     * solo «me gusta» y ninguna otra película, igual.
     */
    @Test
    default void siTeGustaTodoLoQueCompartesGenerosNoQuedaNadaQueRecomendar() {
        Genre drama = genre("Drama");
        Genre comedy = genre("Comedia");
        User ana = user("ana");
        Movie only = movie("La unica", drama);
        likeMovie(ana, only, BASE);

        assertTrue(users().hasVisibleLikes(ana.getId()));
        assertEquals(List.of(), recommendedMovies(ana), "un solo «me gusta» y nada más en el catálogo");
        assertEquals(List.of(), recommendedSeries(ana));

        Movie second = movie("Segunda", drama, comedy);
        Series serie = visibleSerie("Serie", comedy);
        likeMovie(ana, second, BASE);
        likeSeries(ana, serie, BASE);

        assertEquals(List.of(), recommendedMovies(ana));
        assertEquals(List.of(), recommendedSeries(ana));
        assertTrue(users().hasVisibleLikes(ana.getId()));
    }

    /**
     * Los títulos <strong>sin géneros</strong> nunca se recomiendan (no
     * comparten nada) y, si te gustan, no aportan peso: con solo un «me gusta»
     * de una película sin géneros no hay recomendaciones aunque
     * {@code hasVisibleLikes} sea verdadero; añadir uno con Drama recomienda
     * solo las de Drama, con la misma puntuación que si el primero no
     * existiera.
     */
    @Test
    default void losTitulosSinGenerosNiSeRecomiendanNiAportanPeso() {
        Genre drama = genre("Drama");
        User ana = user("ana");
        likeMovie(ana, movie("Gusta sin generos"), BASE);
        movie("Candidata sin generos");
        visibleSerie("Serie sin generos");
        Movie dramaMovie = movie("Candidata de drama", drama);
        Series dramaSeries = visibleSerie("Serie de drama", drama);

        assertTrue(users().hasVisibleLikes(ana.getId()));
        assertEquals(List.of(), recommendedMovies(ana));
        assertEquals(List.of(), recommendedSeries(ana));

        likeSeries(ana, visibleSerie("Serie que gusta", drama), BASE);

        assertEquals(List.of(dramaMovie.getId()), recommendedMovies(ana));
        assertEquals(List.of(dramaSeries.getId()), recommendedSeries(ana));
    }

    /**
     * Un mismo género gustado en una película <em>y</em> en una serie pesa 2:
     * gana a otro que solo aparece en un «me gusta», aunque la candidata de
     * ese otro género sea más nueva. Fija que el {@code UNION ALL} suma las
     * dos tablas (un {@code UNION} a secas deduplicaría y los empataría).
     */
    @Test
    default void unGeneroGustadoEnPeliculaYEnSeriePesaDos() {
        Genre drama = genre("Drama");
        Genre comedy = genre("Comedia");
        User ana = user("ana");
        likeMovie(ana, movie("Pelicula de drama que gusta", drama), BASE);
        likeSeries(ana, visibleSerie("Serie de drama que gusta", drama), BASE);
        likeMovie(ana, movie("Comedia que gusta", comedy), BASE);
        Movie dramaCandidate = movie("Drama", drama);
        Movie comedyCandidate = movie("Comedia", comedy);
        setCreatedAt(dramaCandidate, BASE.plusSeconds(1));
        setCreatedAt(comedyCandidate, BASE.plusSeconds(9));
        Series dramaSeries = visibleSerie("Serie de drama", drama);
        Series comedySeries = visibleSerie("Serie de comedia", comedy);
        setCreatedAt(dramaSeries, BASE.plusSeconds(1));
        setCreatedAt(comedySeries, BASE.plusSeconds(9));

        assertEquals(List.of(dramaCandidate.getId(), comedyCandidate.getId()), recommendedMovies(ana));
        assertEquals(List.of(dramaSeries.getId(), comedySeries.getId()), recommendedSeries(ana));
    }

    /**
     * Excluye solo lo que te gusta a ti: una película que le gusta a otro
     * usuario sí se te recomienda, y sus «me gusta» no cambian tus pesos.
     */
    @Test
    default void losMeGustaDeOtroUsuarioNoAfectanALosTuyos() {
        Genre drama = genre("Drama");
        Genre comedy = genre("Comedia");
        User ana = user("ana");
        User bea = user("bea");
        likeMovie(ana, movie("Gusta a Ana", drama), BASE);
        Movie dramaMovie = movie("Drama", drama);
        Movie comedyMovie = movie("Comedia", comedy);
        likeMovie(bea, dramaMovie, BASE);
        likeMovie(bea, comedyMovie, BASE);

        assertEquals(List.of(dramaMovie.getId()), recommendedMovies(ana));
    }

    /**
     * Desempate a igual puntuación: {@code created_at} descendente (lo más
     * nuevo primero) y, a igual fecha, {@code id} descendente. El orden es
     * total: dos llamadas devuelven lo mismo.
     */
    @Test
    default void aIgualPuntuacionDesempataPorNovedadYDespuesPorIdDescendente() {
        Genre drama = genre("Drama");
        User ana = user("ana");
        likeMovie(ana, movie("Gusta", drama), BASE);
        likeSeries(ana, visibleSerie("Serie que gusta", drama), BASE);
        Movie old = movie("Antigua", drama);
        Movie twinA = movie("Gemela A", drama);
        Movie twinB = movie("Gemela B", drama);
        Movie newest = movie("Nueva", drama);
        setCreatedAt(old, BASE.plusSeconds(1));
        setCreatedAt(twinA, BASE.plusSeconds(5));
        setCreatedAt(twinB, BASE.plusSeconds(5));
        setCreatedAt(newest, BASE.plusSeconds(9));
        Series oldSeries = visibleSerie("Serie antigua", drama);
        Series newSeries = visibleSerie("Serie nueva", drama);
        setCreatedAt(oldSeries, BASE.plusSeconds(1));
        setCreatedAt(newSeries, BASE.plusSeconds(9));

        List<Long> expected = List.of(newest.getId(), twinB.getId(), twinA.getId(), old.getId());
        assertEquals(expected, recommendedMovies(ana));
        assertEquals(expected, recommendedMovies(ana), "misma respuesta en cada llamada");
        assertEquals(List.of(newSeries.getId(), oldSeries.getId()), recommendedSeries(ana));
    }

    /**
     * El límite se aplica en la base de datos después de ordenar: devuelve el
     * principio exacto del orden completo.
     */
    @Test
    default void elLimiteDevuelveLasPrimerasDelOrdenCompleto() {
        Genre drama = genre("Drama");
        User ana = user("ana");
        likeMovie(ana, movie("Gusta", drama), BASE);
        for (int i = 0; i < 7; i++) {
            setCreatedAt(movie("Candidata " + i, drama), BASE.plusSeconds(i));
        }

        List<Long> all = movies().findRecommendedMovieIds(ana.getId(), 24);

        assertEquals(7, all.size());
        assertEquals(all.subList(0, 3), movies().findRecommendedMovieIds(ana.getId(), 3));
        assertEquals(all.subList(0, 1), movies().findRecommendedMovieIds(ana.getId(), 1));
    }

    /**
     * Oráculo independiente: catálogo pseudoaleatorio (semilla fija) con
     * géneros solapados, «me gusta» de películas y series, y pocas fechas
     * posibles (muchos empates); el orden esperado se calcula en Java con el
     * criterio documentado. Comprueba que la combinación de puntuación,
     * exclusión y desempate da el mismo orden total en H2 y en PostgreSQL.
     */
    @Test
    default void elOrdenCoincideConElCriterioCalculadoEnJava() {
        List<Genre> genres = List.of(genre("Accion"), genre("Drama"), genre("Comedia"), genre("Terror"));
        User ana = user("ana");
        java.util.Random random = new java.util.Random(20261017L);
        Map<Long, Integer> weights = new java.util.HashMap<>();
        record Candidate(long id, Set<Long> genreIds, Instant createdAt) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            List<Genre> chosen = genres.stream().filter(genre -> random.nextBoolean()).toList();
            Movie movie = movie("Pelicula " + i, chosen.toArray(Genre[]::new));
            Instant createdAt = BASE.plusSeconds(random.nextInt(3));
            setCreatedAt(movie, createdAt);
            Set<Long> genreIds = chosen.stream().map(Genre::getId).collect(Collectors.toSet());
            if (i % 5 == 0) {
                likeMovie(ana, movie, BASE);
                genreIds.forEach(id -> weights.merge(id, 1, Integer::sum));
            } else {
                candidates.add(new Candidate(movie.getId(), genreIds, createdAt));
            }
        }
        Series liked = visibleSerie("Serie que gusta", genres.get(3), genres.get(1));
        likeSeries(ana, liked, BASE);
        weights.merge(genres.get(3).getId(), 1, Integer::sum);
        weights.merge(genres.get(1).getId(), 1, Integer::sum);

        Function<Candidate, Integer> score = c -> c.genreIds().stream().mapToInt(id -> weights.getOrDefault(id, 0)).sum();
        List<Long> expected = candidates.stream()
                .filter(c -> score.apply(c) > 0)
                .sorted(java.util.Comparator.comparing(score).reversed()
                        .thenComparing(Candidate::createdAt, java.util.Comparator.reverseOrder())
                        .thenComparing(Candidate::id, java.util.Comparator.reverseOrder()))
                .map(Candidate::id)
                .toList();
        assertTrue(expected.size() > 15, "la semilla debe dar bastantes candidatas para que haya empates");

        assertEquals(expected, movies().findRecommendedMovieIds(ana.getId(), 1000));
        assertEquals(expected.subList(0, 5), movies().findRecommendedMovieIds(ana.getId(), 5));
    }

    /**
     * Sin N+1: ids recomendados + carga con géneros son exactamente
     * <strong>dos</strong> sentencias por tipo de título (cuatro en total para
     * películas y series), sean cuantos sean los títulos y sus géneros. Cada
     * título sale una sola vez aunque el {@code JOIN FETCH} repita su fila por
     * género, con los géneros ya inicializados.
     */
    @Test
    default void recomendarYCargarConGenerosSonDosSentenciasPorTipoSinDuplicados() {
        Genre drama = genre("Drama");
        Genre comedy = genre("Comedia");
        Genre horror = genre("Terror");
        User ana = user("ana");
        likeMovie(ana, movie("Gusta", drama, comedy), BASE);
        for (int i = 0; i < 12; i++) {
            movie("Pelicula " + i, drama, comedy, horror);
            visibleSerie("Serie " + i, drama, comedy);
        }

        long statements = statementsFor(() -> tx().executeWithoutResult(status -> {
            List<Long> movieIds = movies().findRecommendedMovieIds(ana.getId(), 24);
            List<Movie> loadedMovies = movies().findAllWithGenresByIdIn(movieIds);
            List<Long> seriesIds = series().findRecommendedSeriesIds(ana.getId(), 24);
            List<Series> loadedSeries = series().findAllWithGenresByIdIn(seriesIds);

            assertEquals(12, loadedMovies.size(), "sin filas duplicadas por el join con los géneros");
            assertEquals(12, loadedSeries.size(), "sin filas duplicadas por el join con los géneros");
            Map<Long, Movie> byId = loadedMovies.stream().collect(Collectors.toMap(Movie::getId, Function.identity()));
            for (Long id : movieIds) {
                assertTrue(Hibernate.isInitialized(byId.get(id).getGenres()));
                assertEquals(3, byId.get(id).getGenres().size());
            }
            for (Series series : loadedSeries) {
                assertTrue(Hibernate.isInitialized(series.getGenres()));
                assertEquals(2, series.getGenres().size());
            }
        }));

        assertEquals(4, statements, "ids + carga, para películas y para series");
    }
}
