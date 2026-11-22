package com.emilio.streambox.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Un elemento de la fila «Seguir viendo» ({@code GET /api/users/me/continue-watching}):
 * una película o un episodio a medio ver, con lo justo para pintar su tarjeta y
 * su barra de progreso.
 *
 * <p>
 * <b>Por qué un solo DTO «plano» con {@code type}</b> y no dos clases con
 * herencia polimórfica de Jackson: la lista mezcla los dos tipos en el orden
 * en que se vieron, y un objeto con las mismas claves siempre es más sencillo
 * de documentar en OpenAPI y de tipar en el cliente (una unión discriminada por
 * {@code type}). Los campos de serie van a {@code null} en las películas (y
 * salen en el JSON, como el {@code videoUrl} nulo de {@link MovieResponse}).
 * </p>
 *
 * <p>
 * No lleva {@code finished}: lo terminado (≥ 95 %) nunca sale en esta fila.
 * </p>
 *
 * @param type            {@code MOVIE} o {@code EPISODE}
 * @param id              id de la película o del episodio, según {@code type}
 * @param title           título de la película o del episodio
 * @param imageUrl        portada de la película o, en un episodio, la de su serie
 *                        (los episodios no tienen imagen propia)
 * @param seasonNumber    temporada del episodio ({@code null} en películas)
 * @param episodeNumber   número del episodio en su temporada ({@code null} en películas)
 * @param seriesId        id de la serie del episodio ({@code null} en películas)
 * @param seriesTitle     título de la serie del episodio ({@code null} en películas)
 * @param positionSeconds segundo por el que va
 * @param durationSeconds duración del vídeo reproducido, en segundos
 * @param updatedAt       instante del último guardado
 */
public record ContinueWatchingItemResponse(
        @Schema(description = "MOVIE (película) o EPISODE (episodio de una serie)", example = "MOVIE")
        ContinueWatchingType type,
        @Schema(description = "Id de la película (MOVIE) o del episodio (EPISODE)", example = "7")
        long id,
        @Schema(description = "Título de la película o del episodio", example = "Dune")
        String title,
        @Schema(description = "Portada de la película o, en un episodio, la de su serie",
                example = "https://example.com/dune.jpg")
        String imageUrl,
        @Schema(types = { "integer", "null" }, format = "int32",
                description = "Temporada del episodio; null en una película", example = "1")
        Integer seasonNumber,
        @Schema(types = { "integer", "null" }, format = "int32",
                description = "Número del episodio en su temporada; null en una película", example = "3")
        Integer episodeNumber,
        @Schema(types = { "integer", "null" }, format = "int64",
                description = "Id de la serie del episodio; null en una película", example = "4")
        Long seriesId,
        @Schema(types = { "string", "null" },
                description = "Título de la serie del episodio; null en una película", example = "Dark")
        String seriesTitle,
        @Schema(description = "Segundo del vídeo por el que va el usuario", example = "1325")
        int positionSeconds,
        @Schema(description = "Duración del vídeo reproducido, en segundos", example = "8160")
        int durationSeconds,
        @Schema(description = "Instante del último guardado (UTC, ISO-8601)", example = "2026-11-21T18:30:00Z")
        Instant updatedAt) {
}
