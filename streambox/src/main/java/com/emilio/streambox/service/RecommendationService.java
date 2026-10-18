package com.emilio.streambox.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.emilio.streambox.dto.MovieResponse;
import com.emilio.streambox.dto.RecommendationsResponse;
import com.emilio.streambox.dto.SeriesResponse;
import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.exception.InvalidParameterException;
import com.emilio.streambox.mapper.MovieMapper;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.MovieRepository;
import com.emilio.streambox.repository.SeriesEpisodeStats;
import com.emilio.streambox.repository.SeriesRepository;
import com.emilio.streambox.service.recommendation.Recommendations;
import com.emilio.streambox.service.recommendation.Recommender;

/**
 * Servicio de las recomendaciones personales
 * ({@code GET /api/users/me/recommendations}).
 *
 * <p>
 * <b>Reparto del trabajo.</b> Qué recomendar lo decide el {@link Recommender}
 * inyectado (hoy {@code GenreBasedRecommender}), que devuelve solo ids. Este
 * servicio hace lo que no debe depender de la implementación:
 * </p>
 * <ol>
 * <li>Valida el límite (también lo hace el controlador: aquí es defensa en
 * profundidad, porque acaba en un {@code FETCH FIRST}).</li>
 * <li>Por si una implementación futura no cumple su contrato, quita ids
 * repetidos y recorta al límite: el contrato de la API no depende de ella.</li>
 * <li>Carga los títulos con sus géneros (una consulta por tipo; las series,
 * además, sus recuentos con una consulta agrupada), reconstruye el orden de
 * los ids ({@code IN} no lo conserva) y descarta lo que ya no se puede mostrar:
 * un título borrado entre las dos consultas o una serie sin episodios (las
 * series ocultas son indistinguibles de las inexistentes).</li>
 * <li>Convierte a DTO dentro de la transacción ({@code open-in-view=false}).</li>
 * </ol>
 *
 * <p>
 * <b>Coste fijo:</b> una sentencia si el usuario no tiene «me gusta» y, con
 * ellos, seis como mucho (las tres del recomendador, películas, series y
 * recuentos), sea cual sea el límite. Una lista vacía no lanza su carga.
 * </p>
 */
@Service
public class RecommendationService {

    /** Mínimo de títulos por tipo que se pueden pedir. */
    public static final int MIN_LIMIT = 1;

    /**
     * Máximo de títulos por tipo que se pueden pedir. Bajo a propósito, como en
     * las películas similares: es una fila del inicio, no un listado, y la
     * carga de esos títulos va en un {@code IN} sin paginar.
     */
    public static final int MAX_LIMIT = 24;

    /** Títulos por tipo si el cliente no indica {@code limit}. */
    public static final int DEFAULT_LIMIT = 12;

    private final Recommender recommender;
    private final MovieRepository movieRepository;
    private final SeriesRepository seriesRepository;
    private final EpisodeRepository episodeRepository;

    /**
     * Crea el servicio de recomendaciones.
     *
     * @param recommender       estrategia que decide qué recomendar
     * @param movieRepository   repositorio de películas (carga con géneros)
     * @param seriesRepository  repositorio de series (carga con géneros)
     * @param episodeRepository repositorio de episodios (recuentos y visibilidad)
     */
    public RecommendationService(
            Recommender recommender,
            MovieRepository movieRepository,
            SeriesRepository seriesRepository,
            EpisodeRepository episodeRepository) {

        this.recommender = recommender;
        this.movieRepository = movieRepository;
        this.seriesRepository = seriesRepository;
        this.episodeRepository = episodeRepository;
    }

    /**
     * Devuelve las recomendaciones del usuario.
     *
     * @param userId identificador del usuario autenticado (del token)
     * @param limit  máximo de películas y, por separado, de series
     *               ({@value #MIN_LIMIT} a {@value #MAX_LIMIT})
     * @return recomendaciones; con {@code basedOnLikes = false} y listas vacías
     *         si no tiene ningún «me gusta» visible
     * @throws InvalidParameterException si el límite está fuera de rango
     */
    @Transactional(readOnly = true)
    public RecommendationsResponse getRecommendations(Long userId, int limit) {

        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new InvalidParameterException("limit",
                    "El límite debe estar entre " + MIN_LIMIT + " y " + MAX_LIMIT);
        }

        Recommendations recommendations = recommender.recommend(userId, limit);

        return new RecommendationsResponse(
                recommendations.basedOnLikes(),
                loadMovies(normalize(recommendations.movieIds(), limit)),
                loadSeries(normalize(recommendations.seriesIds(), limit)));
    }

    /** Quita ids repetidos (conserva la primera aparición) y recorta al límite. */
    private static List<Long> normalize(List<Long> ids, int limit) {

        return ids.stream().distinct().limit(limit).toList();
    }

    /** Carga las películas en una consulta y las devuelve en el orden de {@code ids}. */
    private List<MovieResponse> loadMovies(List<Long> ids) {

        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Movie> byId = movieRepository.findAllWithGenresByIdIn(ids).stream()
                .collect(Collectors.toMap(Movie::getId, Function.identity()));

        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(MovieMapper::toResponse)
                .toList();
    }

    /**
     * Carga las series y sus recuentos (dos consultas) y las devuelve en el
     * orden de {@code ids}, sin las que no tienen episodios: no aparecen en los
     * recuentos, así que filtrarlas no cuesta ninguna consulta más.
     */
    private List<SeriesResponse> loadSeries(List<Long> ids) {

        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Series> byId = seriesRepository.findAllWithGenresByIdIn(ids).stream()
                .collect(Collectors.toMap(Series::getId, Function.identity()));
        Map<Long, SeriesEpisodeStats> stats = SeriesEpisodeCounts.load(episodeRepository, ids);

        return ids.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .filter(series -> stats.containsKey(series.getId()))
                .map(series -> SeriesEpisodeCounts.toResponse(series, stats))
                .toList();
    }
}
