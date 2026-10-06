package com.emilio.streambox.dto;

/**
 * DTO con los datos de un episodio que se devuelven al cliente.
 *
 * <p>
 * No incluye la serie: el episodio siempre se devuelve dentro de su serie
 * ({@link SeasonResponse}) o como respuesta a una operación sobre
 * {@code /api/series/{id}/episodes}, donde la serie ya es conocida. Tampoco
 * tiene imagen propia todavía: el cliente usa la de la serie.
 * </p>
 *
 * @param id            identificador del episodio
 * @param seasonNumber  número de temporada (empieza en 1)
 * @param episodeNumber número del episodio dentro de su temporada (empieza en 1)
 * @param title         título del episodio
 * @param description   sinopsis, o {@code null} si no tiene
 * @param duration      duración en minutos
 * @param videoUrl      URL del vídeo
 */
public record EpisodeResponse(
        Long id,
        Integer seasonNumber,
        Integer episodeNumber,
        String title,
        String description,
        Integer duration,
        String videoUrl) {
}
