package com.emilio.streambox.dto;

import com.emilio.streambox.validation.HttpsUrl;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * DTO con los datos de un episodio, utilizado para crearlo
 * ({@code POST /api/series/{id}/episodes}) y para modificarlo
 * ({@code PUT /api/series/{id}/episodes/{episodeId}}).
 *
 * <p>
 * No lleva la serie: la indica la ruta. Así un episodio no se puede "mover" a
 * otra serie por error desde el cuerpo de la petición.
 * </p>
 *
 * <p>
 * <b>Límites.</b> Los máximos de temporada (100), número de episodio (1000) y
 * duración (600 minutos) no los exige la base de datos (solo exige que sean
 * positivos); son topes de sentido común para que una errata (p. ej. 4500 en
 * lugar de 45 minutos) no llegue al catálogo. La URL del vídeo usa las mismas
 * constantes y mensajes que {@link MovieRequest}.
 * </p>
 *
 * @param seasonNumber  número de temporada (obligatorio, 1 a 100)
 * @param episodeNumber número del episodio en su temporada (obligatorio, 1 a 1000)
 * @param title         título (obligatorio, máximo 150 caracteres)
 * @param description   sinopsis (opcional, máximo 1000 caracteres; en blanco se guarda como {@code null})
 * @param duration      duración en minutos (obligatoria, 1 a 600)
 * @param videoUrl      URL del vídeo (obligatoria, máximo 500 caracteres, {@code https://})
 */
public record EpisodeRequest(

        @NotNull(message = "La temporada es obligatoria")
        @Min(value = 1, message = EpisodeRequest.SEASON_RANGE_MESSAGE)
        @Max(value = 100, message = EpisodeRequest.SEASON_RANGE_MESSAGE)
        Integer seasonNumber,

        @NotNull(message = "El número de episodio es obligatorio")
        @Min(value = 1, message = EpisodeRequest.EPISODE_RANGE_MESSAGE)
        @Max(value = 1000, message = EpisodeRequest.EPISODE_RANGE_MESSAGE)
        Integer episodeNumber,

        @NotBlank(message = "El título es obligatorio")
        @Size(max = 150, message = "El título no puede superar los 150 caracteres")
        String title,

        @Schema(description = "Sinopsis opcional. Si se omite o va en blanco se guarda sin sinopsis "
                + "(null en la respuesta).")
        @Size(max = 1000, message = "La descripción no puede superar los 1000 caracteres")
        String description,

        @NotNull(message = "La duración es obligatoria")
        @Min(value = 1, message = EpisodeRequest.DURATION_RANGE_MESSAGE)
        @Max(value = 600, message = EpisodeRequest.DURATION_RANGE_MESSAGE)
        Integer duration,

        @Schema(description = "URL del vídeo. Debe ser una URL https:// absoluta, con host y sin "
                + "credenciales (usuario:clave@). Solo caracteres ASCII visibles (sin espacios); "
                + "máximo 500 caracteres.",
                example = "https://videos.streambox.example/watch/serie-1x01")
        @NotBlank(message = "La URL del vídeo es obligatoria")
        @Size(max = MovieRequest.URL_MAX_LENGTH, message = MovieRequest.VIDEO_URL_SIZE_MESSAGE)
        @HttpsUrl(maxLength = MovieRequest.URL_MAX_LENGTH, message = MovieRequest.VIDEO_URL_FORMAT_MESSAGE)
        String videoUrl) {

    /** Error de rango de {@code seasonNumber}. */
    public static final String SEASON_RANGE_MESSAGE = "La temporada debe estar entre 1 y 100";

    /** Error de rango de {@code episodeNumber}. */
    public static final String EPISODE_RANGE_MESSAGE = "El número de episodio debe estar entre 1 y 1000";

    /** Error de rango de {@code duration}. */
    public static final String DURATION_RANGE_MESSAGE = "La duración debe estar entre 1 y 600 minutos";
}
