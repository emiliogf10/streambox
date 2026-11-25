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
 * <p>
 * <b>{@code finished} y {@code watched} son cosas distintas</b> (migración
 * {@code V15}): {@code finished} mira la posición <b>actual</b> y se pierde al
 * volver a verlo y parar antes; {@code watched} dice si se terminó <b>alguna
 * vez</b> y no se pierde. Todo lo {@code finished} es {@code watched}; al
 * revés no (visto y ahora a medias = volviéndolo a ver).
 * </p>
 *
 * @param positionSeconds segundo por el que va
 * @param durationSeconds duración del vídeo reproducido, en segundos
 * @param finished        si la posición actual llega al 95 % de la duración
 * @param watched         si lo ha terminado alguna vez (no se pierde al volver a verlo)
 * @param updatedAt       instante del último guardado
 */
public record WatchProgressResponse(
        @Schema(description = "Segundo del vídeo por el que va el usuario", example = "1325")
        int positionSeconds,
        @Schema(description = "Duración del vídeo reproducido, en segundos", example = "8160")
        int durationSeconds,
        @Schema(description = "true si la posición ACTUAL alcanza el 95 % de la duración: ya no sale en "
                + "«Seguir viendo» y el reproductor lo empieza desde el principio. Se pierde si se vuelve a ver "
                + "y se para antes; para «lo ha visto alguna vez», ver watched", example = "false")
        boolean finished,
        @Schema(description = "true si lo ha terminado alguna vez (llegó al 95 % de la duración), aunque ahora lo esté "
                + "volviendo a ver y la posición actual esté por debajo: volver a verlo no lo pierde. Solo "
                + "desaparece si se quita de «Seguir viendo» (se borra su progreso)",
                example = "true")
        boolean watched,
        @Schema(description = "Instante del último guardado (UTC, ISO-8601)", example = "2026-11-21T18:30:00Z")
        Instant updatedAt) {
}
