package com.emilio.streambox.service.recommendation;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.dto.RecommendationBasis;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.repository.GenreRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;

/**
 * Recomendador por <strong>géneros</strong>, sin IA: recomienda los títulos que
 * comparten géneros con lo que le gusta al usuario y con sus géneros favoritos.
 *
 * <p>
 * <b>Criterio</b> (la consulta está en
 * {@link MovieRepository#findRecommendedMovieIds(Long, int)} y
 * {@link SeriesRepository#findRecommendedSeriesIds(Long, int)}, con la
 * subconsulta común {@link MovieRepository#GENRE_WEIGHTS}; explicado en el cap.
 * 14.8 del manual):
 * </p>
 * <pre>
 * peso(género)    = nº de «me gusta» visibles con ese género
 *                   + {@value MovieRepository#FAVORITE_GENRE_WEIGHT} si es uno de sus géneros favoritos
 * puntuación(t)   = suma de los pesos de los géneros del título t
 * </pre>
 * <p>
 * Los «me gusta» cuentan películas y series juntas (los géneros son comunes) y
 * solo los visibles (de series con episodios). Se excluye lo que ya le gusta;
 * las series sin episodios nunca salen; a igual puntuación, primero lo más nuevo
 * y después el id mayor.
 * </p>
 *
 * <p>
 * <b>Por qué un favorito vale 2</b> (decisión del autor): elegir un género a
 * propósito dice más que un solo «me gusta» (que suma 1 a cada género del
 * título, aunque sea secundario), pero no debe tapar lo que el usuario demuestra
 * con el uso: en cuanto tres títulos que le gustaron comparten un género que no
 * eligió, ese género ya pesa más que uno elegido. Para una cuenta nueva, sin «me
 * gusta», el valor da igual: solo cuentan los favoritos y pesan lo mismo entre
 * ellos. Así se resuelve el «arranque en frío»: quien contesta la pregunta de
 * bienvenida tiene recomendaciones desde el primer minuto.
 * </p>
 *
 * <p>
 * <b>Coste fijo.</b> Sin géneros favoritos ni «me gusta» visibles, dos
 * {@code EXISTS} ({@link UserRepository#hasVisibleLikes(Long)} y
 * {@link UserRepository#hasFavoriteGenres(Long)}) y ninguna consulta más: es el
 * caso de todo usuario nuevo que aún no ha elegido nada, y así abrir el inicio
 * no le cuesta las dos agregaciones. Con favoritos, dos consultas más para
 * comprobar que alguno conserva títulos visibles (ver
 * {@link #hasFavoriteGenresWithVisibleTitles(Long)}); si hay base, una por tipo.
 * Como mucho seis, tenga los que tenga; la carga de los títulos la hace después
 * {@code RecommendationService}.
 * </p>
 *
 * <p>
 * Es la implementación que se inyecta hoy en {@link Recommender}. Para cambiarla
 * por otra (p. ej. una IA), basta con otra clase que implemente la interfaz
 * marcada con {@code @Primary} (o quitar el {@code @Component} de esta).
 * </p>
 */
@Component
public class GenreBasedRecommender implements Recommender {

    private final UserRepository userRepository;
    private final GenreRepository genreRepository;
    private final MovieRepository movieRepository;
    private final SeriesRepository seriesRepository;

    /**
     * Crea el recomendador por géneros.
     *
     * @param userRepository   repositorio de usuarios (¿tiene «me gusta» visibles o géneros favoritos?)
     * @param genreRepository  repositorio de géneros (¿le queda algún favorito con títulos?)
     * @param movieRepository  repositorio de películas (ids de películas recomendadas)
     * @param seriesRepository repositorio de series (ids de series recomendadas)
     */
    public GenreBasedRecommender(
            UserRepository userRepository,
            GenreRepository genreRepository,
            MovieRepository movieRepository,
            SeriesRepository seriesRepository) {

        this.userRepository = userRepository;
        this.genreRepository = genreRepository;
        this.movieRepository = movieRepository;
        this.seriesRepository = seriesRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * {@code basis} se decide con las mismas señales que alimentan los pesos y
     * que el usuario ve: «me gusta» visibles (un «me gusta» de una serie ahora
     * oculta no cuenta) y géneros favoritos que aún tienen algún título visible.
     * Por eso, si hay base pero ningún título candidato (p. ej. le gusta todo lo
     * de sus géneros), las listas vienen vacías con un {@code basis} distinto de
     * {@code NONE}: tiene en qué basarse, solo que no queda nada nuevo que
     * proponerle.
     * </p>
     *
     * <p>
     * Es de solo lectura; cuando la llama {@code RecommendationService} se une
     * a su transacción.
     * </p>
     */
    @Override
    @Transactional(readOnly = true)
    public Recommendations recommend(Long userId, int limit) {

        boolean hasLikes = userRepository.hasVisibleLikes(userId);
        boolean hasGenres = hasFavoriteGenresWithVisibleTitles(userId);
        RecommendationBasis basis = RecommendationBasis.of(hasGenres, hasLikes);
        if (basis == RecommendationBasis.NONE) {
            return Recommendations.none();
        }

        List<Long> movieIds = movieRepository.findRecommendedMovieIds(userId, limit);
        List<Long> seriesIds = seriesRepository.findRecommendedSeriesIds(userId, limit);
        return new Recommendations(basis, movieIds, seriesIds);
    }

    /**
     * Indica si alguno de los géneros favoritos del usuario tiene todavía algún
     * título visible.
     *
     * <p>
     * <b>Por qué no basta con {@code hasFavoriteGenres}.</b> Un favorito puede
     * quedarse sin títulos después de elegirlo (se borran sus películas o sus
     * series pierden los episodios). Sigue en la base de datos, pero no aporta
     * ninguna candidata y {@code GET /api/users/me/genres} ya no lo muestra. Si
     * contara, un usuario cuyos favoritos se hubieran quedado todos vacíos
     * recibiría {@code basis = GENRES} sin ninguna recomendación posible, y el
     * inicio diría «Porque te gustan…» sin poder nombrar ningún género, en vez
     * de invitarle a elegir otros. La regla de «visible» es la misma constante
     * que usa la lista de opciones ({@code GenreRepository.HAS_VISIBLE_TITLES}).
     * </p>
     *
     * <p>
     * El {@code EXISTS} va primero porque es el caso de casi todos los usuarios
     * (sin favoritos) y no materializa nada; las otras dos consultas solo
     * corren si tiene alguno y leen como mucho unos pocos géneros (el
     * {@code PUT} admite cinco).
     * </p>
     */
    private boolean hasFavoriteGenresWithVisibleTitles(Long userId) {

        if (!userRepository.hasFavoriteGenres(userId)) {
            return false;
        }
        List<Long> favoriteIds = genreRepository.findFavoritesByUserId(userId).stream()
                .map(Genre::getId)
                .toList();
        return !favoriteIds.isEmpty() && !genreRepository.findIdsWithVisibleTitles(favoriteIds).isEmpty();
    }
}
