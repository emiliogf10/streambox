package com.emilio.streambox.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Progreso de visionado del usuario autenticado en una película o un episodio
 * ({@code GET /api/users/me/progress/movies/{id}} y
 * {@code GET /api/users/me/progress/episodes/{id}}): lo que necesita el
 * reproductor para ofrecer «Reanudar desde X».
 *
 * <p>
 * {@code finished} lo calcula el servidor (posición ≥ 95 % de la duración, ver
 * {@code WatchProgress#FINISHED_PERCENT}) para que el cliente no tenga que
 * copiar el criterio de «Seguir viendo»: un título terminado ya no sale ahí, y
 * lo lógico es que el reproductor lo empiece desde el principio.
 * </p>
 *
 * @param positionSeconds segundo por el que va
 * @param durationSeconds duración del vídeo reproducido, en segundos
 * @param finished        si cuenta como visto entero (≥ 95 %)
 * @param updatedAt       instante del último guardado
 */
public record WatchProgressResponse(
        @Schema(description = "Segundo del vídeo por el que va el usuario", example = "1325")
        int positionSeconds,
        @Schema(description = "Duración del vídeo reproducido, en segundos", example = "8160")
        int durationSeconds,
        @Schema(description = "true si la posición alcanza el 95 % de la duración (visto entero: ya no sale "
                + "en «Seguir viendo»)", example = "false")
        boolean finished,
        @Schema(description = "Instante del último guardado (UTC, ISO-8601)", example = "2026-11-21T18:30:00Z")
        Instant updatedAt) {
}
