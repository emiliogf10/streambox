package com.emilio.streambox.dto;

import java.time.Instant;
import java.util.Set;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DTO con los datos de una película que se devuelven al cliente.
 *
 * <p>
 * Es un {@code record}: inmutable y sin código repetitivo. Se construye
 * dentro de los servicios, mientras la transacción sigue abierta, con
 * {@link com.emilio.streambox.mapper.MovieMapper#toResponse}.
 * </p>
 *
 * <p>
 * {@code videoUrl} y {@code trailerUrl} pueden ser {@code null} (y salen en
 * el JSON como {@code null}, no se omiten): una película puede no tener vídeo
 * del contenido, tráiler, o ninguno de los dos. En OpenAPI 3.1 eso se documenta
 * con el tipo {@code ["string", "null"]}.
 * </p>
 *
 * @param id          identificador de la película
 * @param title       título
 * @param description sinopsis
 * @param duration    duración en minutos
 * @param releaseYear año de estreno
 * @param imageUrl    URL de la portada
 * @param videoUrl    URL del vídeo de la película, o {@code null} si no tiene
 * @param trailerUrl  tráiler de YouTube normalizado
 *                    ({@code https://www.youtube.com/watch?v=<id>}), o
 *                    {@code null} si no tiene
 * @param createdAt   instante en el que se registró la película
 * @param genres      géneros de la película, ordenados por nombre
 */
public record MovieResponse(
        Long id,
        String title,
        String description,
        Integer duration,
        Integer releaseYear,
        String imageUrl,
        @Schema(types = { "string", "null" },
                description = "URL del vídeo de la película, o null si no tiene vídeo del contenido.")
        String videoUrl,
        @Schema(types = { "string", "null" }, description = MovieResponse.TRAILER_URL_DESCRIPTION,
                example = "https://www.youtube.com/watch?v=aBcDeFgHiJk")
        String trailerUrl,
        Instant createdAt,
        Set<GenreResponse> genres) {

    /**
     * Texto OpenAPI del {@code trailerUrl} de las respuestas, compartido con
     * las de series para que las tres documenten lo mismo.
     */
    public static final String TRAILER_URL_DESCRIPTION = "Tráiler en YouTube, siempre en la forma "
            + "normalizada https://www.youtube.com/watch?v=<id> (id de 11 caracteres de letras, números, "
            + "guion o guion bajo), o null si no tiene.";
}
