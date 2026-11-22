package com.emilio.streambox.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Progreso del usuario autenticado en un episodio de una serie, como elemento
 * de {@code GET /api/users/me/progress/series/{id}}: la página de la serie lo
 * usa para pintar la barra de cada episodio con una sola petición.
 *
 * <p>
 * Es {@link WatchProgressResponse} más el id del episodio. Se devuelve una
 * <b>lista</b> y no un objeto {@code {"<id>": ...}} porque las claves de un
 * objeto JSON son siempre texto: el cliente tendría que convertir los ids.
 * </p>
 *
 * @param episodeId       identificador del episodio
 * @param positionSeconds segundo por el que va
 * @param durationSeconds duración del vídeo reproducido, en segundos
 * @param finished        si cuenta como visto entero (≥ 95 %)
 * @param updatedAt       instante del último guardado
 */
public record EpisodeProgressResponse(
        @Schema(description = "Identificador del episodio", example = "42")
        long episodeId,
        @Schema(description = "Segundo del vídeo por el que va el usuario", example = "610")
        int positionSeconds,
        @Schema(description = "Duración del vídeo reproducido, en segundos", example = "2700")
        int durationSeconds,
        @Schema(description = "true si la posición alcanza el 95 % de la duración (visto entero)",
                example = "false")
        boolean finished,
        @Schema(description = "Instante del último guardado (UTC, ISO-8601)", example = "2026-11-21T18:30:00Z")
        Instant updatedAt) {
}
