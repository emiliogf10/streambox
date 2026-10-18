package com.emilio.streambox.service.recommendation;

import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.repository.UserRepository;

/**
 * Recomendador por <strong>géneros</strong>, sin IA: recomienda los títulos que
 * comparten géneros con lo que le gusta al usuario.
 *
 * <p>
 * <b>Criterio</b> (la consulta está en
 * {@link MovieRepository#findRecommendedMovieIds(Long, int)} y
 * {@link SeriesRepository#findRecommendedSeriesIds(Long, int)}, explicada en el
 * cap. 14.8 del manual): cada género pesa tantos puntos como «me gusta»
 * visibles del usuario lo tienen (películas y series juntas, porque los
 * géneros son comunes); la puntuación de un título es la suma de los pesos de
 * sus géneros; se excluye lo que ya le gusta; las series sin episodios nunca
 * salen; a igual puntuación, primero lo más nuevo y después el id mayor.
 * </p>
 *
 * <p>
 * <b>Coste fijo.</b> Sin «me gusta» visibles, una sola consulta
 * ({@link UserRepository#hasVisibleLikes(Long)}) y ninguna más: es el caso de
 * todo usuario nuevo, y así abrir el inicio no le cuesta las dos agregaciones.
 * Con «me gusta», tres consultas en total (esa y una por tipo), tenga los que
 * tenga; la carga de los títulos la hace después {@code RecommendationService}.
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
    private final MovieRepository movieRepository;
    private final SeriesRepository seriesRepository;

    /**
     * Crea el recomendador por géneros.
     *
     * @param userRepository   repositorio de usuarios (¿tiene «me gusta» visibles?)
     * @param movieRepository  repositorio de películas (ids de películas recomendadas)
     * @param seriesRepository repositorio de series (ids de series recomendadas)
     */
    public GenreBasedRecommender(
            UserRepository userRepository,
            MovieRepository movieRepository,
            SeriesRepository seriesRepository) {

        this.userRepository = userRepository;
        this.movieRepository = movieRepository;
        this.seriesRepository = seriesRepository;
    }

    /**
     * {@inheritDoc}
     *
     * <p>
     * {@code basedOnLikes} se decide con {@code hasVisibleLikes}, que cuenta
     * exactamente los mismos «me gusta» que alimentan los pesos (un «me gusta»
     * de una serie ahora oculta no cuenta). Por eso, si es {@code true} pero no
     * hay ningún título candidato (p. ej. le gusta todo lo de sus géneros), las
     * listas vienen vacías con {@code basedOnLikes = true}: tiene «me gusta»,
     * solo que no queda nada nuevo que proponerle.
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

        if (!userRepository.hasVisibleLikes(userId)) {
            return Recommendations.withoutLikes();
        }

        List<Long> movieIds = movieRepository.findRecommendedMovieIds(userId, limit);
        List<Long> seriesIds = seriesRepository.findRecommendedSeriesIds(userId, limit);
        return new Recommendations(true, movieIds, seriesIds);
    }
}
