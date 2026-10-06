package com.emilio.streambox.dto;

import java.time.Instant;
import java.util.List;

/**
 * DTO con el detalle completo de una serie: los mismos campos que
 * {@link SeriesResponse} más sus temporadas con los episodios.
 *
 * <p>
 * Es un {@code record} aparte (y no un {@code SeriesResponse} con una lista
 * opcional) para que el contrato sea explícito: los listados nunca llevan
 * episodios y el detalle siempre los lleva ({@code seasons} vacío si la serie
 * aún no tiene ninguno, cosa que solo ve el administrador).
 * </p>
 *
 * @param id           identificador de la serie
 * @param title        título
 * @param description  sinopsis
 * @param releaseYear  año de estreno
 * @param endYear      año de finalización, o {@code null} si sigue en emisión
 * @param imageUrl     URL de la portada
 * @param createdAt    instante en el que se registró la serie
 * @param genres       géneros de la serie, ordenados por nombre
 * @param seasonCount  número de temporadas con al menos un episodio
 * @param episodeCount número total de episodios
 * @param seasons      temporadas ordenadas por número, cada una con sus episodios
 */
public record SeriesDetailResponse(
        Long id,
        String title,
        String description,
        Integer releaseYear,
        Integer endYear,
        String imageUrl,
        Instant createdAt,
        List<GenreResponse> genres,
        long seasonCount,
        long episodeCount,
        List<SeasonResponse> seasons) {
}
