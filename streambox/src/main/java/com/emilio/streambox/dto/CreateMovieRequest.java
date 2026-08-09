package com.emilio.streambox.dto;

import java.time.LocalDateTime;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/**
 * DTO utilizado para recibir los datos necesarios para crear
 * una nueva película en Streambox.
 *
 * <p>Este objeto representa los datos que el cliente puede enviar
 * a la API. No contiene información generada internamente por
 * la aplicación, como el identificador o la fecha de creación.</p>
 */
@Getter
@Setter
public class CreateMovieRequest {

    /**
     * Título de la película.
     *
     * <p>No puede estar vacío.</p>
     */
    @NotBlank
    private String title;

    /**
     * Descripción o sinopsis de la película.
     *
     * <p>No puede estar vacía.</p>
     */
    @NotBlank
    private String description;

    /**
     * Fecha de lanzamiento de la película.
     */
    private LocalDateTime releaseDate;
}
