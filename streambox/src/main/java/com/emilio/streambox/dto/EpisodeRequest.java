package com.emilio.streambox.dto;

import com.emilio.streambox.validation.ControlCharacters;
import com.emilio.streambox.validation.HttpsUrl;
import com.emilio.streambox.validation.SubtitlesRequireVideo;
import com.emilio.streambox.validation.VideoWithSubtitles;
import com.emilio.streambox.validation.WebVttUrl;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
 * <p>
 * <b>Vídeo opcional</b> (desde la migración {@code V6}): StreamBox no aloja
 * vídeos. Omitido, {@code null}, vacío o en blanco = sin vídeo, y el mapper lo
 * guarda como {@code null} (como la sinopsis). El tráiler no va en el episodio
 * sino en la serie ({@link SeriesRequest#trailerUrl()}). Un {@code PUT} sin
 * {@code videoUrl} lo borra.
 * </p>
 *
 * <p>
 * <b>Subtítulos</b> (migración {@code V15}): {@code subtitlesUrl}, opcional,
 * con las mismas constantes, mensajes y reglas que en {@link MovieRequest}
 * (longitud, archivo {@code .vtt} por {@code https://} y, si eso está bien,
 * que el episodio tenga vídeo). Un {@code PUT} sin {@code subtitlesUrl} los
 * borra.
 * </p>
 *
 * <p>
 * <b>Imagen propia</b> (migración {@code V16}): {@code imageUrl}, opcional,
 * con la regla de la portada ({@link HttpsUrl} con {@code allowLocalCovers};
 * primero la longitud y luego el formato) pero mensajes propios que nombran
 * "la imagen del episodio". A diferencia de la portada de películas y series
 * no es obligatoria: sin ella el cliente usa la portada de la serie. En blanco
 * = no tiene, y un {@code PUT} sin ella la borra.
 * </p>
 *
 * <p>
 * <b>Caracteres de control.</b> Las mismas reglas que en {@link MovieRequest}:
 * ninguno en el título y solo tabulador y saltos de línea en la descripción
 * (ver {@link ControlCharacters}).
 * </p>
 *
 * @param seasonNumber  número de temporada (obligatorio, 1 a 100)
 * @param episodeNumber número del episodio en su temporada (obligatorio, 1 a 1000)
 * @param title         título (obligatorio, máximo 150 caracteres)
 * @param description   sinopsis (opcional, máximo 1000 caracteres; en blanco se guarda como {@code null})
 * @param duration      duración en minutos (obligatoria, 1 a 600)
 * @param videoUrl      URL del vídeo (opcional; si viene, máximo 500 caracteres y
 *                      {@code https://}; en blanco = sin vídeo)
 * @param subtitlesUrl  URL de los subtítulos WebVTT (opcional; si viene, máximo
 *                      500 caracteres, {@code https://}, archivo {@code .vtt} y
 *                      con vídeo; en blanco = sin subtítulos)
 * @param imageUrl      URL de la imagen propia del episodio (opcional; si viene,
 *                      máximo 500 caracteres, {@code https://} o
 *                      {@code /covers/<archivo>}; en blanco = sin imagen, y se
 *                      usa la portada de la serie)
 */
@SubtitlesRequireVideo(maxLength = MovieRequest.URL_MAX_LENGTH,
        message = MovieRequest.SUBTITLES_REQUIRE_VIDEO_MESSAGE)
public record EpisodeRequest(

        @NotNull(message = "La temporada es obligatoria")
        @Min(value = 1, message = EpisodeRequest.SEASON_RANGE_MESSAGE)
        @Max(value = 100, message = EpisodeRequest.SEASON_RANGE_MESSAGE)
        Integer seasonNumber,

        @NotNull(message = "El número de episodio es obligatorio")
        @Min(value = 1, message = EpisodeRequest.EPISODE_RANGE_MESSAGE)
        @Max(value = 1000, message = EpisodeRequest.EPISODE_RANGE_MESSAGE)
        Integer episodeNumber,

        @Schema(description = MovieRequest.TITLE_DESCRIPTION)
        @NotBlank(message = "El título es obligatorio")
        @Size(max = 150, message = "El título no puede superar los 150 caracteres")
        @Pattern(regexp = ControlCharacters.SINGLE_LINE_TEXT,
                message = MovieRequest.TITLE_CONTROL_CHARACTERS_MESSAGE)
        String title,

        @Schema(description = "Sinopsis opcional. Si se omite o va en blanco se guarda sin sinopsis "
                + "(null en la respuesta). " + MovieRequest.DESCRIPTION_CONTROL_CHARACTERS_RULE)
        @Size(max = 1000, message = "La descripción no puede superar los 1000 caracteres")
        @Pattern(regexp = ControlCharacters.MULTILINE_TEXT,
                message = SeriesRequest.DESCRIPTION_CONTROL_CHARACTERS_MESSAGE)
        String description,

        @NotNull(message = "La duración es obligatoria")
        @Min(value = 1, message = EpisodeRequest.DURATION_RANGE_MESSAGE)
        @Max(value = 600, message = EpisodeRequest.DURATION_RANGE_MESSAGE)
        Integer duration,

        @Schema(description = "URL del vídeo del episodio. Opcional: si se omite, es null o va en "
                + "blanco, el episodio queda sin vídeo (null en la respuesta). Si se indica, debe ser una "
                + "URL https:// absoluta, con host y sin credenciales (usuario:clave@). Solo caracteres "
                + "ASCII visibles (sin espacios); máximo 500 caracteres. En un PUT, omitirlo borra el vídeo.",
                example = "https://videos.streambox.example/watch/serie-1x01")
        @Size(max = MovieRequest.URL_MAX_LENGTH, message = MovieRequest.VIDEO_URL_SIZE_MESSAGE)
        @HttpsUrl(maxLength = MovieRequest.URL_MAX_LENGTH, message = MovieRequest.VIDEO_URL_FORMAT_MESSAGE)
        String videoUrl,

        @Schema(description = MovieRequest.SUBTITLES_URL_DESCRIPTION,
                example = "https://videos.streambox.example/subtitulos/serie-1x01.es.vtt")
        @Size(max = MovieRequest.URL_MAX_LENGTH, message = MovieRequest.SUBTITLES_URL_SIZE_MESSAGE)
        @WebVttUrl(maxLength = MovieRequest.URL_MAX_LENGTH, message = MovieRequest.SUBTITLES_URL_FORMAT_MESSAGE)
        String subtitlesUrl,

        @Schema(description = EpisodeRequest.IMAGE_URL_DESCRIPTION,
                example = "https://image.tmdb.org/t/p/w780/serie-1x01.jpg")
        @Size(max = MovieRequest.URL_MAX_LENGTH, message = EpisodeRequest.IMAGE_URL_SIZE_MESSAGE)
        @HttpsUrl(allowLocalCovers = true, maxLength = MovieRequest.URL_MAX_LENGTH,
                message = EpisodeRequest.IMAGE_URL_FORMAT_MESSAGE)
        String imageUrl) implements VideoWithSubtitles {

    /** Error de rango de {@code seasonNumber}. */
    public static final String SEASON_RANGE_MESSAGE = "La temporada debe estar entre 1 y 100";

    /** Error de rango de {@code episodeNumber}. */
    public static final String EPISODE_RANGE_MESSAGE = "El número de episodio debe estar entre 1 y 1000";

    /** Error de rango de {@code duration}. */
    public static final String DURATION_RANGE_MESSAGE = "La duración debe estar entre 1 y 600 minutos";

    /**
     * Error de formato de {@code imageUrl} (contrato con el frontend). Misma
     * regla que la portada de {@link MovieRequest}, pero el texto dice "la
     * imagen del episodio" para que el administrador no la confunda con la
     * portada de la serie, que es la que se ve cuando falta.
     */
    public static final String IMAGE_URL_FORMAT_MESSAGE = "La URL de la imagen del episodio debe empezar por "
            + "https:// o ser una portada propia (/covers/archivo)";

    /** Error de longitud de {@code imageUrl} (contrato con el frontend). */
    public static final String IMAGE_URL_SIZE_MESSAGE =
            "La URL de la imagen del episodio no puede superar los " + MovieRequest.URL_MAX_LENGTH + " caracteres";

    /** Texto OpenAPI de {@code imageUrl} en la petición. */
    public static final String IMAGE_URL_DESCRIPTION = "Imagen propia del episodio (miniatura de la lista de "
            + "episodios y fotograma de espera del reproductor); proporción recomendada 16:9. Opcional: si se "
            + "omite, es null o va en blanco, el episodio queda sin imagen propia (null en la respuesta) y se "
            + "usa la portada de la serie. Si se indica, las mismas reglas que la portada de películas y "
            + "series: URL https:// absoluta, con host y sin credenciales, o una portada propia "
            + "/covers/<archivo>; solo caracteres ASCII visibles; máximo 500 caracteres. En un PUT, omitirlo "
            + "la borra. Errores, uno solo y por este orden: «" + IMAGE_URL_SIZE_MESSAGE + "» y «"
            + IMAGE_URL_FORMAT_MESSAGE + "».";
}
