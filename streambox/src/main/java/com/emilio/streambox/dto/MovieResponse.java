package com.emilio.streambox.dto;

import java.time.LocalDateTime;

import com.emilio.streambox.entity.Movie;

import lombok.Getter;
import lombok.Setter;

/**
 * DTO utilizado para representar una película en las respuestas
 * de la API de Streambox.
 *
 * <p>Este objeto evita exponer directamente la entidad JPA
 * {@link Movie} hacia el cliente.</p>
 */
@Getter
@Setter
public class MovieResponse {

    /**
     * Identificador único de la película.
     */
    private Long id;

    /**
     * Título de la película.
     */
    private String title;

    /**
     * Descripción o sinopsis de la película.
     */
    private String description;

    /**
     * Fecha de lanzamiento de la película.
     */
    private LocalDateTime releaseDate;

    /**
     * Fecha en la que la película fue añadida a Streambox.
     */
    private LocalDateTime createdAt;
}
