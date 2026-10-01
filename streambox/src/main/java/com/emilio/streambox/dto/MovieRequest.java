package com.emilio.streambox.dto;

import java.util.Set;

import org.hibernate.validator.constraints.URL;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * DTO con los datos de una película, utilizado tanto para crearla
 * ({@code POST /api/movies}) como para modificarla ({@code PUT /api/movies/{id}}).
 *
 * <p>
 * Antes existían dos clases idénticas ({@code CreateMovieRequest} y
 * {@code UpdateMovieRequest}). Como {@code PUT} sustituye la película completa,
 * ambas operaciones reciben exactamente los mismos campos y basta un solo DTO.
 * </p>
 *
 * <p>
 * No contiene datos generados por la aplicación, como el identificador o la
 * fecha de creación.
 * </p>
 *
 * @param title       título de la película (obligatorio, máximo 150 caracteres)
 * @param description sinopsis (obligatoria, máximo 1000 caracteres)
 * @param duration    duración en minutos (obligatoria, al menos 1)
 * @param releaseYear año de estreno (obligatorio, entre 1888 y 2100)
 * @param imageUrl    URL de la portada (obligatoria, formato de URL válido)
 * @param videoUrl    URL del vídeo (obligatoria, formato de URL válido)
 * @param genreIds    identificadores de los géneros de la película (al menos uno)
 */
public record MovieRequest(

        @NotBlank
        @Size(max = 150, message = "El título no puede superar los 150 caracteres")
        String title,

        @NotBlank
        @Size(max = 1000, message = "La descripción no puede superar los 1000 caracteres")
        String description,

        @NotNull
        @Min(1)
        Integer duration,

        @NotNull
        @Min(1888)
        @Max(2100)
        Integer releaseYear,

        @NotBlank
        @URL(message = "La URL de la imagen no tiene un formato válido")
        String imageUrl,

        @NotBlank
        @URL(message = "La URL del vídeo no tiene un formato válido")
        String videoUrl,

        @NotEmpty
        Set<Long> genreIds) {
}
