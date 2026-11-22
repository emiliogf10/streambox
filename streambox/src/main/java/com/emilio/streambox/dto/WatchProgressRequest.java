package com.emilio.streambox.dto;

import com.emilio.streambox.repository.WatchProgress;
import com.emilio.streambox.validation.PositionWithinDuration;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Progreso de visionado que envía el reproductor
 * ({@code PUT /api/users/me/progress/movies/{id}} y
 * {@code PUT /api/users/me/progress/episodes/{id}}).
 *
 * <p>
 * <b>Segundos enteros.</b> El cliente redondea hacia abajo
 * ({@code Math.floor(video.currentTime)}): un decimal se rechaza con 400
 * {@code MALFORMED_REQUEST} (ver {@code StrictJsonScalarsConfig}), igual que un
 * texto. {@code durationSeconds} es la duración del <b>vídeo reproducido</b>
 * que conoce el reproductor, no la de la ficha (que va en minutos y la escribe
 * el administrador).
 * </p>
 *
 * <p>
 * <b>Por qué {@code Integer} y no {@code int}.</b> Con un primitivo, Jackson
 * convertiría un campo ausente en {@code 0} sin avisar y se guardaría «al
 * principio» un progreso que el cliente no envió; con {@code Integer} el
 * {@code @NotNull} lo rechaza con 400.
 * </p>
 *
 * <p>
 * Las reglas son las mismas que los {@code CHECK} de la {@code V14}: la
 * posición entre 0 y la duración ({@link PositionWithinDuration}) y la
 * duración entre 1 y {@value WatchProgress#MAX_DURATION_SECONDS} (24 h, para
 * cazar a un cliente que mande milisegundos).
 * </p>
 *
 * @param positionSeconds segundo por el que va el usuario (0..{@code durationSeconds})
 * @param durationSeconds duración del vídeo en segundos (1..86400)
 */
@PositionWithinDuration
public record WatchProgressRequest(

        @Schema(description = "Segundo del vídeo por el que va el usuario, entero (redondeado hacia abajo). "
                + "Entre 0 y durationSeconds.", example = "1325", minimum = "0",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "La posición es obligatoria")
        @Min(value = 0, message = "La posición no puede ser negativa")
        Integer positionSeconds,

        @Schema(description = "Duración del vídeo reproducido en segundos, entera (la que conoce el "
                + "reproductor, no la de la ficha, que va en minutos). Entre 1 y 86400 (24 horas).",
                example = "8160", minimum = "1", maximum = "86400", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "La duración es obligatoria")
        @Min(value = 1, message = "La duración debe ser de al menos 1 segundo")
        @Max(value = WatchProgress.MAX_DURATION_SECONDS, message = "La duración no puede superar las 24 horas")
        Integer durationSeconds) {
}
