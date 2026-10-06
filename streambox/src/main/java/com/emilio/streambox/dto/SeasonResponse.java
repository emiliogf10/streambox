package com.emilio.streambox.dto;

import java.util.List;

/**
 * DTO con una temporada de una serie y sus episodios.
 *
 * <p>
 * La temporada no existe en la base de datos (es solo el número
 * {@code season_number} de cada episodio): este DTO se construye agrupando los
 * episodios en {@link com.emilio.streambox.mapper.SeriesMapper}. Solo aparecen
 * las temporadas que tienen algún episodio, así que los números pueden no ser
 * consecutivos (p. ej. 1 y 3).
 * </p>
 *
 * @param seasonNumber número de la temporada
 * @param episodes     episodios de la temporada, ordenados por número
 */
public record SeasonResponse(
        Integer seasonNumber,
        List<EpisodeResponse> episodes) {
}
