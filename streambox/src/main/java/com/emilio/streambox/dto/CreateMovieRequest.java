package com.emilio.streambox.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO utilizado para recibir los datos necesarios para crear
 * una nueva película en Streambox.
 *
 * <p>No contiene información generada internamente por la aplicación,
 * como el identificador o la fecha de creación.</p>
 */
@Getter
@Setter
public class CreateMovieRequest {

    /**
     * Título de la película.
     */
    @NotBlank
    private String title;

    /**
     * Descripción o sinopsis de la película.
     */
    @NotBlank
    private String description;

    /**
     * Duración de la película expresada en minutos.
     */
    @NotNull
    @Min(1)
    private Integer duration;

    /**
     * Año de lanzamiento de la película.
     */
    @NotNull
    @Min(1888)
    @Max(2100)
    private Integer releaseYear;

    /**
     * Género principal de la película.
     */
    @NotBlank
    private String genre;

    /**
     * URL de la imagen utilizada como portada de la película.
     */
    @NotBlank
    private String imageUrl;

    /**
     * URL desde la que se puede acceder al vídeo de la película.
     */
    @NotBlank
    private String videoUrl;
}
