package com.emilio.streambox.repository;

/**
 * Proyección con el número de episodios y de temporadas de una serie.
 *
 * <p>
 * La devuelve {@link EpisodeRepository#countBySeriesIds(java.util.Collection)}
 * para una página entera de series con una sola consulta agrupada
 * ({@code GROUP BY}), en lugar de contar serie a serie (N+1). Es una interfaz
 * de Spring Data: cada getter se rellena con la columna del mismo alias de la
 * consulta. No es un DTO de la API; el servicio la usa para construir los suyos.
 * </p>
 */
public interface SeriesEpisodeStats {

    /**
     * @return identificador de la serie
     */
    Long getSeriesId();

    /**
     * @return número de episodios de la serie
     */
    long getEpisodeCount();

    /**
     * @return número de temporadas distintas con al menos un episodio
     */
    long getSeasonCount();
}
