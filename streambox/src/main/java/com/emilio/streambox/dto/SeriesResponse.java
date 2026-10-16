package com.emilio.streambox.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DTO con los datos de una serie que se devuelven en los listados (catálogo,
 * búsqueda, panel de administración y "Mi lista").
 *
 * <p>
 * No lleva los episodios (para eso está {@link SeriesDetailResponse}), pero sí
 * cuántos tiene y en cuántas temporadas, para que el cliente pueda mostrarlo
 * sin pedir el detalle. Esos recuentos se calculan para toda la página con una
 * única consulta agrupada, no serie a serie.
 * </p>
 *
 * @param id           identificador de la serie
 * @param title        título
 * @param description  sinopsis
 * @param releaseYear  año de estreno
 * @param endYear      año de finalización, o {@code null} si sigue en emisión
 * @param imageUrl     URL de la portada
 * @param trailerUrl   tráiler de YouTube normalizado, o {@code null} si no tiene
 * @param createdAt    instante en el que se registró la serie
 * @param genres       géneros de la serie, ordenados por nombre
 * @param seasonCount  número de temporadas con al menos un episodio
 * @param episodeCount número total de episodios
 */
public record SeriesResponse(
        Long id,
        String title,
        String description,
        Integer releaseYear,
        Integer endYear,
        String imageUrl,
        @Schema(types = { "string", "null" }, description = MovieResponse.TRAILER_URL_DESCRIPTION,
                example = "https://www.youtube.com/watch?v=aBcDeFgHiJk")
        String trailerUrl,
        Instant createdAt,
        List<GenreResponse> genres,
        long seasonCount,
        long episodeCount) {
}
