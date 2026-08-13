package com.emilio.streambox.dto;

import java.time.LocalDateTime;
import java.util.Set;

import com.emilio.streambox.entity.Movie;

import lombok.Getter;
import lombok.Setter;

/**
 * DTO utilizado para representar una película en las respuestas
 * de la API de Streambox.
 *
 * <p>
 * Permite controlar qué información de la entidad {@link Movie}
 * se expone al cliente de la API.
 * </p>
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
     * Duración de la película expresada en minutos.
     */
    private Integer duration;

    /**
     * Año en el que se estrenó la película.
     */
    private Integer releaseYear;

    /**
     * URL de la imagen utilizada como portada de la película.
     */
    private String imageUrl;

    /**
     * URL desde la que se puede acceder al vídeo de la película.
     */
    private String videoUrl;

    /**
     * Fecha y hora en la que la película fue registrada en Streambox.
     */
    private LocalDateTime createdAt;

    /**
     * Géneros asociados a la película.
     */
    private Set<GenreResponse> genres;
}