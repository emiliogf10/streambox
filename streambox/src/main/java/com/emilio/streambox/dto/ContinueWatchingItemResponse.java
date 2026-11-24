package com.emilio.streambox.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Una tarjeta de la fila «Seguir viendo» ({@code GET /api/users/me/continue-watching}):
 * una película a medio ver o la tarjeta de una serie (el episodio por el que va
 * el usuario o el siguiente que le toca), con lo justo para pintarla y para su
 * barra de progreso.
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
 * <b>La tarjeta de una serie</b> (criterio de QA-4, ver
 * {@code WatchProgressRepository.findContinueWatching}) es el <b>último episodio
 * que tocó</b> el usuario si lo tiene a medias ({@code upNext = false}) o, si lo
 * terminó, el <b>siguiente con vídeo</b> ({@code upNext = true}). Ese siguiente
 * puede no haberse empezado nunca (o haberse visto ya entero, y se vuelve a ver
 * desde el principio): entonces no hay posición ni duración que pintar y {@code positionSeconds}/{@code durationSeconds} van a {@code null}
 * (presentes en el JSON, no ausentes). Por eso son {@link Integer} y no
 * {@code int}; el cliente decide con {@code upNext} y con el nulo si pinta la
 * barra. Si el siguiente sí tiene progreso propio a medias, viene con él para
 * reanudarlo.
 * </p>
 *
 * <p>
 * No lleva {@code finished}: ninguna tarjeta lleva un progreso terminado
 * (≥ 95 %). Una película terminada no sale; un episodio terminado da paso a su
 * siguiente (y, si no hay siguiente, la serie no sale).
 * </p>
 *
 * @param type            {@code MOVIE} o {@code EPISODE}
 * @param id              id de la película o del episodio de la tarjeta, según {@code type}
 * @param title           título de la película o del episodio
 * @param imageUrl        portada de la película o, en un episodio, la de su serie
 *                        (los episodios no tienen imagen propia)
 * @param seasonNumber    temporada del episodio ({@code null} en películas)
 * @param episodeNumber   número del episodio en su temporada ({@code null} en películas)
 * @param seriesId        id de la serie del episodio ({@code null} en películas)
 * @param seriesTitle     título de la serie del episodio ({@code null} en películas)
 * @param positionSeconds segundo por el que va; {@code null} solo si {@code upNext}
 *                        y el siguiente no tiene progreso propio a medias
 * @param durationSeconds duración del vídeo reproducido, en segundos; nulo en el
 *                        mismo caso que {@code positionSeconds}
 * @param upNext          {@code true} si la tarjeta es el siguiente episodio a uno
 *                        terminado; {@code false} en películas y en el episodio a medias
 * @param updatedAt       instante que ordena la fila: el último guardado de la
 *                        película o, en una serie, el del último episodio tocado
 *                        (aunque la tarjeta muestre el siguiente)
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
        @Schema(types = { "integer", "null" }, format = "int32",
                description = "Segundo del vídeo por el que va el usuario. Es null solo cuando upNext es true y "
                        + "ese siguiente episodio no tiene progreso propio a medias (no lo ha empezado, o ya lo "
                        + "había visto entero): no hay barra que pintar y se reproduce desde el principio",
                example = "1325")
        Integer positionSeconds,
        @Schema(types = { "integer", "null" }, format = "int32",
                description = "Duración del vídeo reproducido, en segundos. Es null en el mismo caso que "
                        + "positionSeconds (upNext true sin progreso propio a medias)",
                example = "8160")
        Integer durationSeconds,
        @Schema(description = "true si la tarjeta es el siguiente episodio a uno que el usuario ha terminado "
                + "(«Siguiente episodio»); false en las películas y en el episodio que dejó a medias",
                example = "false")
        boolean upNext,
        @Schema(description = "Instante que ordena la fila (UTC, ISO-8601): el último guardado de la película "
                + "o, en una serie, el del último episodio que tocó el usuario, aunque la tarjeta muestre el "
                + "siguiente", example = "2026-11-21T18:30:00Z")
        Instant updatedAt) {
}
