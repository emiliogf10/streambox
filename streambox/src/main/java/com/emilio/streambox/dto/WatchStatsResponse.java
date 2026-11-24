package com.emilio.streambox.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Cifras de visionado del usuario autenticado para su perfil
 * ({@code GET /api/users/me/watch-stats}).
 *
 * <p>
 * Son {@code long} porque {@code watchedSeconds} puede superar
 * {@link Integer#MAX_VALUE} (cada título admite hasta 24 h) y, por coherencia,
 * los contadores usan el mismo tipo; en JSON son enteros sin más.
 * </p>
 *
 * <p>
 * <b>{@code watchedSeconds} es una estimación:</b> solo se guarda el último
 * punto de cada título, así que suma la duración de lo terminado y la posición
 * de lo que está a medias; no cuenta lo vuelto a ver ni descuenta lo saltado.
 * </p>
 *
 * @param finishedMovies   películas terminadas (≥ 95 %)
 * @param finishedEpisodes episodios terminados (≥ 95 %), cada uno por separado
 * @param watchedSeconds   tiempo visto estimado, en segundos
 */
public record WatchStatsResponse(
        @Schema(description = "Películas que el usuario ha visto enteras (95 % o más de la duración)",
                example = "7")
        long finishedMovies,
        @Schema(description = "Episodios que el usuario ha visto enteros (95 % o más); cada episodio cuenta "
                + "por separado, aunque sean de la misma serie", example = "23")
        long finishedEpisodes,
        @Schema(description = "Tiempo visto aproximado, en segundos: la duración de cada título terminado más "
                + "la posición de cada uno a medias. No cuenta lo vuelto a ver ni descuenta lo saltado",
                example = "98400")
        long watchedSeconds) {
}
