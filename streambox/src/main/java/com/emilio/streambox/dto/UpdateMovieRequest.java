package com.emilio.streambox.dto;

import java.util.Set;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO utilizado para recibir los datos necesarios para modificar
 * una película existente en Streambox.
 *
 * <p>
 * El identificador de la película no se incluye en este DTO,
 * ya que se obtiene directamente de la URL de la petición.
 * </p>
 */
@Getter
@Setter
public class UpdateMovieRequest {

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
     * URL de la imagen utilizada como portada de la película.
     */
    @NotBlank
    private String imageUrl;

    /**
     * URL desde la que se puede acceder al vídeo de la película.
     */
    @NotBlank
    private String videoUrl;

    /**
     * Identificadores de los géneros asociados a la película.
     */
    @NotEmpty
    private Set<Long> genreIds;
}