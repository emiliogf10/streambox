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
 * <b>«Terminadas» = vistas alguna vez</b> (migración {@code V15}): cuentan los
 * títulos con {@code watched} (llegaron al 95 % al menos una vez), no los que
 * tienen hoy la posición al final. Volver a ver una película y parar en el
 * minuto 2 ya no la resta. Los nombres de los campos no cambian.
 * </p>
 *
 * <p>
 * <b>{@code watchedSeconds} es una estimación:</b> solo se guarda el último
 * punto de cada título, así que suma la duración de lo visto alguna vez y la
 * posición de lo que nunca se ha terminado; no cuenta lo vuelto a ver ni
 * descuenta lo saltado.
 * </p>
 *
 * @param finishedMovies   películas vistas alguna vez (≥ 95 % al menos una vez)
 * @param finishedEpisodes episodios vistos alguna vez, cada uno por separado
 * @param watchedSeconds   tiempo visto estimado, en segundos
 */
public record WatchStatsResponse(
        @Schema(description = "Películas que el usuario ha visto enteras alguna vez (llegó al 95 % o más de "
                + "la duración al menos una vez); volver a verlas no las resta", example = "7")
        long finishedMovies,
        @Schema(description = "Episodios que el usuario ha visto enteros alguna vez (95 % o más al menos una "
                + "vez); cada episodio cuenta por separado, aunque sean de la misma serie, y volver a verlos no "
                + "los resta", example = "23")
        long finishedEpisodes,
        @Schema(description = "Tiempo visto aproximado, en segundos: la duración de cada título visto alguna "
                + "vez más la posición de cada uno que nunca se ha terminado. No cuenta lo vuelto a ver ni "
                + "descuenta lo saltado",
                example = "98400")
        long watchedSeconds) {
}
