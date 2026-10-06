package com.emilio.streambox.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.emilio.streambox.dto.SeriesResponse;
import com.emilio.streambox.entity.Series;
import com.emilio.streambox.mapper.SeriesMapper;
import com.emilio.streambox.repository.EpisodeRepository;
import com.emilio.streambox.repository.SeriesEpisodeStats;

/**
 * Convierte listas de series en {@link SeriesResponse} con sus recuentos de
 * temporadas y episodios, usando <strong>una sola consulta</strong> agrupada
 * para toda la lista.
 *
 * <p>
 * <b>Por qué existe.</b> Cada {@code SeriesResponse} lleva cuántas temporadas y
 * episodios tiene la serie. Calcularlo serie a serie
 * ({@code countBySeriesId} dentro de un bucle) sería el problema N+1: 25
 * series, 25 consultas más. En su lugar se piden los recuentos de todos los
 * ids de la lista de una vez ({@link EpisodeRepository#countBySeriesIds}) y se
 * cruzan en memoria. La usan el catálogo ({@link SeriesService}) y "Mi lista"
 * ({@link SeriesFavoriteService}); por eso está aparte y no dentro de uno de
 * ellos.
 * </p>
 *
 * <p>
 * Es de paquete y sin estado: solo la usan los servicios, siempre dentro de su
 * transacción (los géneros se cargan por lotes al convertir).
 * </p>
 */
final class SeriesEpisodeCounts {

    private SeriesEpisodeCounts() {
        // Clase de utilidad: no se instancia
    }

    /**
     * Obtiene los recuentos de las series indicadas.
     *
     * <p>
     * Si no hay ids no se consulta nada: {@code IN ()} vacío no es SQL válido
     * en todos los motores, y una página vacía no necesita recuentos. Las
     * series sin episodios no aparecen en el mapa (cuentan como 0).
     * </p>
     *
     * @param episodeRepository repositorio de episodios
     * @param seriesIds         identificadores de las series
     * @return recuentos por id de serie (solo las que tienen episodios)
     */
    static Map<Long, SeriesEpisodeStats> load(EpisodeRepository episodeRepository, Collection<Long> seriesIds) {

        if (seriesIds.isEmpty()) {
            return Map.of();
        }
        return episodeRepository.countBySeriesIds(seriesIds).stream()
                .collect(Collectors.toMap(SeriesEpisodeStats::getSeriesId, Function.identity()));
    }

    /**
     * Convierte una serie en su DTO de listado con los recuentos ya cargados.
     *
     * @param series serie a convertir
     * @param stats  recuentos obtenidos con {@link #load}
     * @return DTO de listado (0 temporadas y 0 episodios si no aparece en {@code stats})
     */
    static SeriesResponse toResponse(Series series, Map<Long, SeriesEpisodeStats> stats) {

        SeriesEpisodeStats counts = stats.get(series.getId());
        return counts == null
                ? SeriesMapper.toResponse(series, 0, 0)
                : SeriesMapper.toResponse(series, counts.getSeasonCount(), counts.getEpisodeCount());
    }

    /**
     * Ids de una lista de series, en el mismo orden.
     *
     * @param series series de la página o lista
     * @return sus identificadores
     */
    static List<Long> ids(List<Series> series) {

        return series.stream().map(Series::getId).toList();
    }
}
