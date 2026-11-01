package com.emilio.streambox.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.entity.User;

/**
 * Casos de prueba de los <strong>géneros favoritos</strong> ({@code V11}:
 * {@code user_favorite_genres} y {@code users.genres_onboarded_at}) y de su
 * peso en las recomendaciones, escritos <strong>una sola vez</strong> y
 * ejecutados en los dos motores: H2 ({@link FavoriteGenresRepositoryIntegrationTest})
 * y PostgreSQL real ({@code PostgresFavoriteGenresIntegrationTest}). Mismo
 * patrón que {@link LikesQueryContract}.
 *
 * <p>
 * Qué se prueba: sustituir la lista entera (borrar e insertar en una
 * transacción, y que un fallo deje la anterior intacta), el
 * {@code INSERT ... SELECT} con ids repetidos o inexistentes, los géneros que
 * se pueden elegir (con algún título visible) y la validación de ids, el
 * cierre de la pregunta de bienvenida sin pisar la primera fecha, las cascadas,
 * y la fórmula de los pesos de {@link MovieRepository#GENRE_WEIGHTS}: cada
 * género favorito vale exactamente {@value MovieRepository#FAVORITE_GENRE_WEIGHT}
 * «me gusta», se suma a los «me gusta» de ese género y basta por sí solo para
 * recibir recomendaciones.
 * </p>
 *
 * <p>
 * Los nombres de los géneros son ASCII y sin mayúsculas mezcladas para que el
 * orden alfabético no dependa de la <em>collation</em> de cada motor.
 * </p>
 */
public interface FavoriteGenresQueryContract {

    /** Instante base de las fechas fijadas a mano. */
    Instant BASE = Instant.parse("2024-01-01T00:00:00Z");

    // ------------------------------------------------------------------
    // Lo que aporta cada motor
    // ------------------------------------------------------------------

    /** @return repositorio de géneros */
    GenreRepository genres();

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

    /** Plantilla de transacción, como la que abrirá el servicio. */
    TransactionTemplate tx();

    // ------------------------------------------------------------------
    // Utilidades comunes
    // ------------------------------------------------------------------

    /**
     * Sustituye los géneros favoritos como lo hará el servicio: en una
     * transacción, cierra la pregunta (y bloquea la fila del usuario), borra
     * la lista y, si la nueva no está vacía, la inserta.
     *
     * @return filas insertadas
     */
    default int replaceFavorites(User user, Collection<Long> genreIds, Instant at) {
        return tx().execute(status -> {
            assertEquals(1, users().markGenresOnboarded(user.getId(), at));
            genres().clearFavorites(user.getId());
            return genreIds.isEmpty() ? 0 : genres().addFavorites(user.getId(), genreIds, at);
        });
    }

    /** Ids de los géneros favoritos del usuario, en el orden del repositorio. */
    default List<Long> favoriteIds(User user) {
        return genres().findFavoritesByUserId(user.getId()).stream().map(Genre::getId).toList();
    }

    /** Da «me gusta» a una película en su propia transacción. */
    default void likeMovie(User user, Movie movie) {
        tx().executeWithoutResult(status -> movies().addLike(user.getId(), movie.getId(), BASE));
    }

    /** Da «me gusta» a una serie en su propia transacción. */
    default void likeSeries(User user, Series series) {
        tx().executeWithoutResult(status -> series().addLike(user.getId(), series.getId(), BASE));
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

    /** {@code genres_onboarded_at} del usuario, o {@code null}. */
    default Instant onboardedAt(User user) {
        OffsetDateTime value = jdbc().queryForObject("SELECT genres_onboarded_at FROM users WHERE id = ?",
                OffsetDateTime.class, user.getId());
        return value == null ? null : value.toInstant();
    }

    /** Recomendaciones de películas con un límite amplio. */
    default List<Long> recommendedMovies(User user) {
        return movies().findRecommendedMovieIds(user.getId(), 24);
    }

    /** Recomendaciones de series con un límite amplio. */
    default List<Long> recommendedSeries(User user) {
        return series().findRecommendedSeriesIds(user.getId(), 24);
    }

    /** Ids de los géneros, en el orden dado. */
    private static List<Long> ids(Genre... genres) {
        return java.util.Arrays.stream(genres).map(Genre::getId).toList();
    }

    // ------------------------------------------------------------------
    // Sustituir la lista
    // ------------------------------------------------------------------

    /**
     * Sustituir la lista borra la anterior e inserta la nueva: tras elegir
     * {@code (a, b, c)} y después {@code (c, d, e)}, quedan exactamente las
     * tres nuevas, por nombre, con la fecha de la segunda elección (también la
     * que se repite: se borró y se volvió a insertar). Los gustos de otro
     * usuario no se tocan. Una lista vacía los quita todos.
     */
    @Test
    default void sustituirLaListaBorraLaAnteriorEInsertaLaNueva() {
        Genre a = genre("aventura");
        Genre b = genre("belico");
        Genre c = genre("comedia");
        Genre d = genre("drama");
        Genre e = genre("espionaje");
        User ana = user("ana");
        User bea = user("bea");
        replaceFavorites(bea, ids(a, b), BASE);

        assertEquals(3, replaceFavorites(ana, ids(c, a, b), BASE));
        assertEquals(ids(a, b, c), favoriteIds(ana));
        assertEquals(3, replaceFavorites(ana, ids(e, d, c), BASE.plusSeconds(60)));

        assertEquals(ids(c, d, e), favoriteIds(ana), "por nombre, no por orden de elección");
        assertEquals(List.of(BASE.plusSeconds(60)), jdbc().queryForList(
                "SELECT DISTINCT created_at FROM user_favorite_genres WHERE user_id = ?",
                OffsetDateTime.class, ana.getId()).stream().map(OffsetDateTime::toInstant).toList());
        assertEquals(ids(a, b), favoriteIds(bea), "los gustos de otro usuario no cambian");

        assertEquals(0, replaceFavorites(ana, List.of(), BASE.plusSeconds(120)));
        assertEquals(List.of(), favoriteIds(ana));
        assertEquals(ids(a, b), favoriteIds(bea));
    }

    /**
     * Borrar e insertar van en la misma transacción: si algo falla después del
     * borrado (aquí, una excepción tras insertar), se deshace todo y la lista
     * anterior sigue intacta, igual que la pregunta sin cerrar.
     */
    @Test
    default void siLaSustitucionFallaLaListaAnteriorSeConserva() {
        Genre a = genre("accion");
        Genre b = genre("belico");
        Genre c = genre("comedia");
        User ana = user("ana");
        tx().executeWithoutResult(status -> genres().addFavorites(ana.getId(), ids(a, b), BASE));

        assertThrows(IllegalStateException.class, () -> tx().executeWithoutResult(status -> {
            users().markGenresOnboarded(ana.getId(), BASE);
            genres().clearFavorites(ana.getId());
            genres().addFavorites(ana.getId(), ids(c), BASE);
            throw new IllegalStateException("fallo simulado después del INSERT");
        }));

        assertEquals(ids(a, b), favoriteIds(ana));
        assertEquals(null, onboardedAt(ana), "la pregunta también se deshace");
    }

    /**
     * {@code addFavorites} es un {@code INSERT ... SELECT} sobre {@code genres}:
     * un id repetido entra una vez y uno inexistente no entra (sin violar la
     * clave foránea). Devuelve las filas insertadas, que es como el servicio
     * detecta que un género se borró entre la validación y el {@code INSERT}.
     */
    @Test
    default void anadirIgnoraIdsRepetidosEInexistentesYDevuelveLasFilasInsertadas() {
        Genre a = genre("accion");
        Genre b = genre("belico");
        User ana = user("ana");

        int inserted = tx().execute(status -> genres().addFavorites(ana.getId(),
                List.of(a.getId(), a.getId(), Long.MAX_VALUE, b.getId()), BASE));

        assertEquals(2, inserted);
        assertEquals(ids(a, b), favoriteIds(ana));
    }

    /**
     * Los favoritos de un usuario incluyen los que se han quedado sin títulos
     * visibles: siguen siendo sus gustos. Sin favoritos, lista vacía (también
     * para un usuario inexistente).
     */
    @Test
    default void losFavoritosIncluyenLosGenerosQueSeQuedaronSinTitulos() {
        Genre withMovie = genre("con pelicula");
        Genre empty = genre("vacio");
        movie("Pelicula", withMovie);
        User ana = user("ana");
        replaceFavorites(ana, ids(withMovie, empty), BASE);

        assertEquals(ids(withMovie, empty), favoriteIds(ana));
        assertEquals(List.of(), favoriteIds(user("singustos")));
        assertEquals(List.of(), genres().findFavoritesByUserId(Long.MAX_VALUE));
    }

    // ------------------------------------------------------------------
    // Géneros que se pueden elegir
    // ------------------------------------------------------------------

    /**
     * Solo se ofrecen los géneros con algún título <strong>visible</strong>:
     * una película, o una serie con al menos un episodio; por nombre. Un género
     * cuya única serie no tiene episodios no se ofrece hasta que lo tenga, y uno
     * sin títulos nunca.
     */
    @Test
    default void soloSeOfrecenLosGenerosConAlgunTituloVisible() {
        Genre movieGenre = genre("drama");
        Genre seriesGenre = genre("anime");
        Genre hiddenGenre = genre("terror");
        Genre empty = genre("vacio");
        Genre both = genre("comedia");
        movie("Pelicula", movieGenre, both);
        visibleSerie("Serie", seriesGenre, both);
        Series hidden = serie("Serie oculta", hiddenGenre);

        assertEquals(ids(seriesGenre, both, movieGenre),
                genres().findWithVisibleTitles().stream().map(Genre::getId).toList());

        episode(hidden, 1);

        assertEquals(ids(seriesGenre, both, movieGenre, hiddenGenre),
                genres().findWithVisibleTitles().stream().map(Genre::getId).toList());
        assertFalse(genres().findWithVisibleTitles().contains(empty));
    }

    /**
     * De los ids pedidos, {@code findIdsWithVisibleTitles} devuelve los que se
     * pueden elegir, cada uno una vez: no los inexistentes, ni los géneros sin
     * títulos, ni los que solo tienen series sin episodios.
     */
    @Test
    default void validarIdsDevuelveSoloLosGenerosExistentesConTitulosVisibles() {
        Genre movieGenre = genre("drama");
        Genre seriesGenre = genre("anime");
        Genre hiddenGenre = genre("terror");
        Genre empty = genre("vacio");
        movie("Pelicula", movieGenre);
        visibleSerie("Serie", seriesGenre);
        serie("Serie oculta", hiddenGenre);

        List<Long> valid = genres().findIdsWithVisibleTitles(List.of(movieGenre.getId(), movieGenre.getId(),
                seriesGenre.getId(), hiddenGenre.getId(), empty.getId(), Long.MAX_VALUE));

        assertEquals(Set.of(movieGenre.getId(), seriesGenre.getId()), Set.copyOf(valid));
        assertEquals(2, valid.size(), "cada id una sola vez");
        assertEquals(List.of(), genres().findIdsWithVisibleTitles(List.of(empty.getId(), Long.MAX_VALUE)));
    }

    // ------------------------------------------------------------------
    // Pregunta de bienvenida
    // ------------------------------------------------------------------

    /**
     * Una cuenta nueva nace con la pregunta pendiente ({@code null}).
     * {@code markGenresOnboarded} la cierra con la fecha dada y devuelve 1;
     * repetirlo con otra fecha devuelve 1 (la cuenta existe y la fila queda
     * bloqueada) pero <b>conserva la primera</b>. Con un id inexistente, 0. No
     * toca a los demás usuarios.
     */
    @Test
    default void cerrarLaPreguntaRellenaLaFechaUnaVezYNoLaPisa() {
        User ana = user("ana");
        User bea = user("bea");
        assertEquals(null, onboardedAt(ana), "una cuenta nueva nace con la pregunta pendiente");

        assertEquals(1, (int) tx().<Integer>execute(status -> users().markGenresOnboarded(ana.getId(), BASE)));
        assertEquals(1, (int) tx().<Integer>execute(status -> users().markGenresOnboarded(ana.getId(), BASE.plusSeconds(60))));

        assertEquals(BASE, onboardedAt(ana));
        assertEquals(null, onboardedAt(bea));
        assertEquals(0, (int) tx().<Integer>execute(status -> users().markGenresOnboarded(Long.MAX_VALUE, BASE)));
        assertEquals(BASE, users().findById(ana.getId()).orElseThrow().getGenresOnboardedAt(),
                "la entidad lee la columna");
    }

    /** {@code hasFavoriteGenres}: falso sin gustos, verdadero con alguno, falso al quitarlos. */
    @Test
    default void tieneGenerosFavoritosSoloConAlgunGusto() {
        Genre drama = genre("drama");
        User ana = user("ana");

        assertFalse(users().hasFavoriteGenres(ana.getId()));
        replaceFavorites(ana, ids(drama), BASE);
        assertTrue(users().hasFavoriteGenres(ana.getId()));
        replaceFavorites(ana, List.of(), BASE);
        assertFalse(users().hasFavoriteGenres(ana.getId()));
        assertFalse(users().hasFavoriteGenres(Long.MAX_VALUE));
    }

    // ------------------------------------------------------------------
    // Borrado en cascada
    // ------------------------------------------------------------------

    /**
     * Borrar un género con el repositorio (como hace el panel con uno que no
     * usa ninguna película ni serie) <b>no falla</b> aunque esté en los gustos
     * de alguien: la cascada se lo quita a todos. Si era el único, el usuario
     * deja de tener gustos (y {@code hasFavoriteGenres} pasa a falso). Borrar un
     * usuario borra los suyos y ninguno de otro.
     */
    @Test
    default void borrarUnGeneroOUnUsuarioBorraSusGustosSinFallar() {
        Genre drama = genre("drama");
        Genre empty = genre("vacio");
        movie("Pelicula", drama);
        User ana = user("ana");
        User bea = user("bea");
        replaceFavorites(ana, ids(drama, empty), BASE);
        replaceFavorites(bea, ids(empty), BASE);

        genres().deleteById(empty.getId());

        assertEquals(ids(drama), favoriteIds(ana));
        assertEquals(List.of(), favoriteIds(bea));
        assertFalse(users().hasFavoriteGenres(bea.getId()), "su único gusto se borró");

        users().deleteById(ana.getId());

        assertEquals(0, jdbc().queryForObject("SELECT COUNT(*) FROM user_favorite_genres", Integer.class));
        assertTrue(genres().existsById(drama.getId()));
    }

    // ------------------------------------------------------------------
    // Peso en las recomendaciones
    // ------------------------------------------------------------------

    /**
     * <b>Solo géneros favoritos, sin ningún «me gusta»</b> (la cuenta nueva):
     * hay recomendaciones. Con Drama y Comedia como gustos, la candidata con
     * los dos géneros suma 4 y va antes que las de uno (2), aunque sea la más
     * antigua; la de Acción no comparte nada y no sale. En series, solo las
     * visibles.
     */
    @Test
    default void soloConGenerosFavoritosHayRecomendaciones() {
        Genre drama = genre("drama");
        Genre comedy = genre("comedia");
        Genre action = genre("accion");
        User ana = user("ana");
        replaceFavorites(ana, ids(drama, comedy), BASE);
        Movie both = movie("Drama y comedia", drama, comedy);
        Movie dramaOnly = movie("Drama", drama);
        Movie comedyOnly = movie("Comedia", comedy);
        movie("Accion", action);
        setCreatedAt(both, BASE.plusSeconds(1));
        setCreatedAt(dramaOnly, BASE.plusSeconds(2));
        setCreatedAt(comedyOnly, BASE.plusSeconds(3));
        Series dramaSeries = visibleSerie("Serie de drama", drama);
        serie("Serie de drama vacia", drama);

        assertFalse(users().hasVisibleLikes(ana.getId()), "precondición: ningún «me gusta»");
        assertEquals(List.of(both.getId(), comedyOnly.getId(), dramaOnly.getId()), recommendedMovies(ana));
        assertEquals(List.of(dramaSeries.getId()), recommendedSeries(ana));
    }

    /**
     * <b>Un género favorito vale exactamente 2 «me gusta».</b> Ana tiene 3 «me
     * gusta» de Terror, 2 de Acción y 1 de Comedia, y Drama como favorito (sin
     * «me gusta»). Las candidatas se crean de la más antigua a la más nueva en
     * el orden Terror, Acción, Drama, Comedia, así que solo un peso de 2 para
     * Drama da {@code Terror, Drama, Acción, Comedia}: con 1 Drama quedaría
     * detrás de Acción; con 3 empataría con Terror y, por ser más nueva, iría
     * primera. Comedia (1) va la última aunque sea la más nueva.
     */
    @Test
    default void unGeneroFavoritoValeExactamenteDosMeGusta() {
        Genre horror = genre("terror");
        Genre action = genre("accion");
        Genre drama = genre("drama");
        Genre comedy = genre("comedia");
        User ana = user("ana");
        for (int i = 0; i < 3; i++) {
            likeMovie(ana, movie("Terror que gusta " + i, horror));
        }
        likeMovie(ana, movie("Accion que gusta 1", action));
        likeSeries(ana, visibleSerie("Accion que gusta 2", action));
        likeMovie(ana, movie("Comedia que gusta", comedy));
        replaceFavorites(ana, ids(drama), BASE);
        Movie horrorCandidate = movie("Terror", horror);
        Movie actionCandidate = movie("Accion", action);
        Movie dramaCandidate = movie("Drama", drama);
        Movie comedyCandidate = movie("Comedia", comedy);
        setCreatedAt(horrorCandidate, BASE.plusSeconds(1));
        setCreatedAt(actionCandidate, BASE.plusSeconds(2));
        setCreatedAt(dramaCandidate, BASE.plusSeconds(3));
        setCreatedAt(comedyCandidate, BASE.plusSeconds(4));

        assertEquals(2, MovieRepository.FAVORITE_GENRE_WEIGHT);
        assertEquals(List.of(horrorCandidate.getId(), dramaCandidate.getId(), actionCandidate.getId(),
                comedyCandidate.getId()), recommendedMovies(ana));
    }

    /**
     * <b>Favorito y «me gusta» del mismo género se suman.</b> Drama es favorito
     * y además tiene un «me gusta» (2 + 1 = 3); Terror tiene dos «me gusta»
     * (2). La candidata de Drama va primera aunque sea más antigua: si el
     * favorito sustituyera a los «me gusta» en vez de sumarse, empatarían y
     * ganaría Terror por ser más nueva. Lo mismo en series. Y la película de
     * Drama que ya le gusta no se recomienda aunque Drama sea favorito.
     */
    @Test
    default void elFavoritoSeSumaALosMeGustaDelMismoGeneroYNoRecomiendaLoQueYaTeGusta() {
        Genre drama = genre("drama");
        Genre horror = genre("terror");
        User ana = user("ana");
        Movie likedDrama = movie("Drama que gusta", drama);
        likeMovie(ana, likedDrama);
        likeMovie(ana, movie("Terror que gusta 1", horror));
        likeSeries(ana, visibleSerie("Terror que gusta 2", horror));
        replaceFavorites(ana, ids(drama), BASE);
        Movie dramaCandidate = movie("Drama", drama);
        Movie horrorCandidate = movie("Terror", horror);
        setCreatedAt(dramaCandidate, BASE.plusSeconds(1));
        setCreatedAt(horrorCandidate, BASE.plusSeconds(9));
        Series dramaSeries = visibleSerie("Serie de drama", drama);
        Series horrorSeries = visibleSerie("Serie de terror", horror);
        setCreatedAt(dramaSeries, BASE.plusSeconds(1));
        setCreatedAt(horrorSeries, BASE.plusSeconds(9));

        assertEquals(List.of(dramaCandidate.getId(), horrorCandidate.getId()), recommendedMovies(ana));
        assertEquals(List.of(dramaSeries.getId(), horrorSeries.getId()), recommendedSeries(ana));
        assertFalse(recommendedMovies(ana).contains(likedDrama.getId()));
    }

    /**
     * Solo «me gusta», sin favoritos: la fórmula no cambia respecto a la V8
     * (cada «me gusta» suma 1 a sus géneros). Y los gustos de otro usuario no
     * afectan a los tuyos.
     */
    @Test
    default void soloConMeGustaYConGustosDeOtroUsuarioLaFormulaNoCambia() {
        Genre drama = genre("drama");
        Genre comedy = genre("comedia");
        User ana = user("ana");
        User bea = user("bea");
        likeMovie(ana, movie("Drama que gusta", drama));
        replaceFavorites(bea, ids(comedy), BASE);
        Movie dramaCandidate = movie("Drama", drama);
        movie("Comedia", comedy);

        assertEquals(List.of(dramaCandidate.getId()), recommendedMovies(ana));
    }

    /**
     * <b>Un género borrado deja de contar.</b> Ana solo tiene favoritos; al
     * quitarle uno con la cascada (se borra el género), ya no aporta peso.
     * Como un género que usa algún título no se puede borrar, se comprueba con
     * la otra mitad: quitar un favorito (sustituir la lista) cambia las
     * recomendaciones al momento, y sin ningún favorito ni «me gusta» no hay.
     */
    @Test
    default void quitarUnFavoritoOBorrarSuGeneroLoSacaDeLosPesos() {
        Genre drama = genre("drama");
        Genre comedy = genre("comedia");
        Genre empty = genre("vacio");
        User ana = user("ana");
        Movie dramaMovie = movie("Drama", drama);
        Movie comedyMovie = movie("Comedia", comedy);
        replaceFavorites(ana, ids(drama, comedy, empty), BASE);
        assertEquals(Set.of(dramaMovie.getId(), comedyMovie.getId()), Set.copyOf(recommendedMovies(ana)));

        genres().deleteById(empty.getId());
        replaceFavorites(ana, ids(drama), BASE);

        assertEquals(List.of(dramaMovie.getId()), recommendedMovies(ana));

        replaceFavorites(ana, List.of(), BASE);

        assertEquals(List.of(), recommendedMovies(ana));
        assertEquals(List.of(), recommendedSeries(ana));
    }

    /**
     * Oráculo independiente: catálogo pseudoaleatorio (semilla fija) con
     * géneros solapados, «me gusta» de películas y series, dos géneros
     * favoritos y pocas fechas posibles (muchos empates); el orden esperado se
     * calcula en Java con la fórmula documentada (1 por «me gusta» visible y
     * género, {@value MovieRepository#FAVORITE_GENRE_WEIGHT} por favorito).
     * Comprueba que H2 y PostgreSQL dan el mismo orden total.
     */
    @Test
    default void elOrdenConFavoritosCoincideConLaFormulaCalculadaEnJava() {
        List<Genre> genres = List.of(genre("accion"), genre("drama"), genre("comedia"), genre("terror"),
                genre("musical"));
        User ana = user("ana");
        Random random = new Random(20261025L);
        Map<Long, Integer> weights = new HashMap<>();
        record Candidate(long id, Set<Long> genreIds, Instant createdAt) {
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            List<Genre> chosen = genres.stream().filter(genre -> random.nextInt(3) == 0).toList();
            Movie movie = movie("Pelicula " + i, chosen.toArray(Genre[]::new));
            Instant createdAt = BASE.plusSeconds(random.nextInt(3));
            setCreatedAt(movie, createdAt);
            Set<Long> genreIds = chosen.stream().map(Genre::getId).collect(Collectors.toSet());
            if (i % 7 == 0) {
                likeMovie(ana, movie);
                genreIds.forEach(id -> weights.merge(id, 1, Integer::sum));
            } else {
                candidates.add(new Candidate(movie.getId(), genreIds, createdAt));
            }
        }
        likeSeries(ana, visibleSerie("Serie que gusta", genres.get(0)));
        weights.merge(genres.get(0).getId(), 1, Integer::sum);
        likeSeries(ana, serie("Serie oculta que gusta", genres.get(2))); // oculta: no cuenta
        replaceFavorites(ana, ids(genres.get(1), genres.get(4)), BASE);
        weights.merge(genres.get(1).getId(), MovieRepository.FAVORITE_GENRE_WEIGHT, Integer::sum);
        weights.merge(genres.get(4).getId(), MovieRepository.FAVORITE_GENRE_WEIGHT, Integer::sum);

        Function<Candidate, Integer> score = c -> c.genreIds().stream().mapToInt(id -> weights.getOrDefault(id, 0)).sum();
        List<Long> expected = candidates.stream()
                .filter(c -> score.apply(c) > 0)
                .sorted(Comparator.comparing(score).reversed()
                        .thenComparing(Candidate::createdAt, Comparator.reverseOrder())
                        .thenComparing(Candidate::id, Comparator.reverseOrder()))
                .map(Candidate::id)
                .toList();
        assertTrue(expected.size() > 15, "la semilla debe dar bastantes candidatas para que haya empates");

        assertEquals(expected, movies().findRecommendedMovieIds(ana.getId(), 1000));
        assertEquals(expected.subList(0, 5), movies().findRecommendedMovieIds(ana.getId(), 5));
    }
}
