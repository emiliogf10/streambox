package com.emilio.streambox.dto;

import java.util.Set;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.validator.constraints.URL;

/**
 * DTO utilizado para recibir los datos necesarios para crear
 * una nueva película en Streambox.
 *
 * <p>
 * No contiene información generada internamente por la aplicación,
 * como el identificador o la fecha de creación.
 * </p>
 */
@Getter
@Setter
public class CreateMovieRequest {

    /**
     * Título de la película.
     */
    @NotBlank
    @Size(max = 150, message = "El título no puede superar los 150 caracteres")
    private String title;

    /**
     * Descripción o sinopsis de la película.
     */
    @NotBlank
    @Size(max = 1000, message = "La descripción no puede superar los 1000 caracteres")
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
     * URL de la imagen utilizada como portada de la película.
     */
    @NotBlank
    @URL(message = "La URL de la imagen no tiene un formato válido")
    private String imageUrl;

    /**
     * URL desde la que se puede acceder al vídeo de la película.
     */
    @NotBlank
    @URL(message = "La URL del vídeo no tiene un formato válido")
    private String videoUrl;

    /**
     * Identificadores de los géneros asociados a la película.
     *
     * <p>
     * Una película debe tener al menos un género.
     * </p>
     */
    @NotEmpty
    private Set<Long> genreIds;
}
