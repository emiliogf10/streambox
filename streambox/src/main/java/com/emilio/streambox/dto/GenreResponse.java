package com.emilio.streambox.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * DTO utilizado para representar un género cinematográfico
 * en las respuestas de la API.
 */
@Getter
@Setter
public class GenreResponse {

    /**
     * Identificador único del género.
     */
    private Long id;

    /**
     * Nombre del género.
     */
    private String name;
}