package com.emilio.streambox.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Una entrada del <b>historial de visionado</b> del perfil
 * ({@code GET /api/users/me/watch-history}): una película con progreso o, por
 * cada serie, el <b>último episodio que tocó</b> el usuario, terminados o no y
 * tengan hoy vídeo o no.
 *
 * <p>
 * Tiene la misma forma «plana» con {@code type} que
 * {@link ContinueWatchingItemResponse} (y el mismo {@link ContinueWatchingType}),
 * por la misma razón: la lista mezcla películas y episodios en el orden en que
 * se vieron, y un objeto con las mismas claves es más sencillo de documentar y
 * de tipar en el cliente. Los campos de serie y temporada van a {@code null} en
 * las películas (presentes en el JSON, no ausentes).
 * </p>
 *
 * <p>
 * <b>Diferencias con «Seguir viendo»</b>, que explican por qué es otro DTO y no
 * el mismo:
 * </p>
 * <ul>
 *   <li>Lleva {@code finished} y {@code watched}: aquí salen también los
 *       títulos terminados, y el cliente los pinta distinto. La etiqueta
 *       «Vista» sale de {@code watched} (terminada alguna vez, migración
 *       {@code V15}), no de {@code finished} (posición actual).</li>
 *   <li>No lleva {@code upNext}: el historial cuenta lo que se vio, nunca
 *       propone el siguiente episodio.</li>
 *   <li>{@code positionSeconds} y {@code durationSeconds} son {@code int} (nunca
 *       nulos): toda entrada sale de una fila de progreso real.</li>
 * </ul>
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
 * @param positionSeconds segundo hasta el que llegó el usuario
 * @param durationSeconds duración del vídeo reproducido, en segundos
 * @param finished        si la posición actual llega al 95 % de la duración
 * @param watched         si lo ha terminado alguna vez (no se pierde al volver a verlo)
 * @param updatedAt       instante del último guardado de la entrada: ordena el historial
 */
public record WatchHistoryItemResponse(
        @Schema(description = "MOVIE (película) o EPISODE (episodio de una serie)", example = "EPISODE")
        ContinueWatchingType type,
        @Schema(description = "Id de la película (MOVIE) o del episodio (EPISODE), nunca el de la serie",
                example = "12")
        long id,
        @Schema(description = "Título de la película o del episodio", example = "Secretos")
        String title,
        @Schema(description = "Portada de la película o, en un episodio, la de su serie",
                example = "https://example.com/dark.jpg")
        String imageUrl,
        @Schema(types = { "integer", "null" }, format = "int32",
                description = "Temporada del episodio; null en una película", example = "1")
        Integer seasonNumber,
        @Schema(types = { "integer", "null" }, format = "int32",
                description = "Número del episodio en su temporada; null en una película", example = "1")
        Integer episodeNumber,
        @Schema(types = { "integer", "null" }, format = "int64",
                description = "Id de la serie del episodio; null en una película", example = "4")
        Long seriesId,
        @Schema(types = { "string", "null" },
                description = "Título de la serie del episodio; null en una película", example = "Dark")
        String seriesTitle,
        @Schema(description = "Segundo del vídeo hasta el que llegó el usuario", example = "2600")
        int positionSeconds,
        @Schema(description = "Duración del vídeo reproducido, en segundos", example = "2700")
        int durationSeconds,
        @Schema(description = "true si la posición ACTUAL alcanza el 95 % de la duración. Se pierde si se "
                + "vuelve a ver y se para antes; para mostrar «Vista», usa watched", example = "true")
        boolean finished,
        @Schema(description = "true si lo ha terminado alguna vez (llegó al 95 % de la duración), aunque ahora lo esté "
                + "volviendo a ver y la posición actual esté por debajo: volver a verlo no lo pierde. Solo "
                + "desaparece si se quita de «Seguir viendo» (se borra su progreso)",
                example = "true")
        boolean watched,
        @Schema(description = "Instante del último guardado de la película o del episodio (UTC, ISO-8601): "
                + "ordena el historial", example = "2026-11-21T18:30:00Z")
        Instant updatedAt) {
}
