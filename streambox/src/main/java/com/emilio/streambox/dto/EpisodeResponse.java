package com.emilio.streambox.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DTO con los datos de un episodio que se devuelven al cliente.
 *
 * <p>
 * No incluye la serie: el episodio siempre se devuelve dentro de su serie
 * ({@link SeasonResponse}) o como respuesta a una operación sobre
 * {@code /api/series/{id}/episodes}, donde la serie ya es conocida. Su imagen
 * propia ({@code imageUrl}, migración {@code V16}) es opcional: si es
 * {@code null}, el cliente usa la portada de la serie.
 * </p>
 *
 * @param id            identificador del episodio
 * @param seasonNumber  número de temporada (empieza en 1)
 * @param episodeNumber número del episodio dentro de su temporada (empieza en 1)
 * @param title         título del episodio
 * @param description   sinopsis, o {@code null} si no tiene
 * @param duration      duración en minutos
 * @param videoUrl      URL del vídeo, o {@code null} si el episodio no tiene
 *                      (el tráiler está en la serie)
 * @param subtitlesUrl  URL de los subtítulos WebVTT en español, o {@code null}
 *                      si no tiene (nunca sin {@code videoUrl})
 * @param imageUrl      URL de la imagen propia del episodio, o {@code null} si
 *                      no tiene (se usa entonces la portada de la serie)
 */
public record EpisodeResponse(
        Long id,
        Integer seasonNumber,
        Integer episodeNumber,
        String title,
        String description,
        Integer duration,
        @Schema(types = { "string", "null" },
                description = "URL del vídeo del episodio, o null si no tiene (el tráiler va en la serie).")
        String videoUrl,
        @Schema(types = { "string", "null" }, description = MovieResponse.SUBTITLES_URL_DESCRIPTION,
                example = "https://videos.streambox.example/subtitulos/serie-1x01.es.vtt")
        String subtitlesUrl,
        @Schema(types = { "string", "null" }, description = "Imagen propia del episodio (proporción "
                + "recomendada 16:9) para la miniatura de la lista y el fotograma de espera del reproductor: "
                + "URL https:// o portada propia /covers/<archivo>. Es opcional: null si no tiene, y entonces "
                + "se usa la portada de la serie.",
                example = "https://image.tmdb.org/t/p/w780/serie-1x01.jpg")
        String imageUrl) {
}
