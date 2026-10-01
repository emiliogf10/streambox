package com.emilio.streambox.dto;

import java.time.Instant;
import java.util.Set;

/**
 * DTO con los datos de una película que se devuelven al cliente.
 *
 * <p>
 * Es un {@code record}: inmutable y sin código repetitivo. Se construye
 * dentro de los servicios, mientras la transacción sigue abierta, con
 * {@link com.emilio.streambox.mapper.MovieMapper#toResponse}.
 * </p>
 *
 * @param id          identificador de la película
 * @param title       título
 * @param description sinopsis
 * @param duration    duración en minutos
 * @param releaseYear año de estreno
 * @param imageUrl    URL de la portada
 * @param videoUrl    URL del vídeo
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
        String videoUrl,
        Instant createdAt,
        Set<GenreResponse> genres) {
}
