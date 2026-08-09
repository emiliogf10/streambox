package com.emilio.streambox.entity;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Entidad que representa una película o contenido audiovisual
 * disponible en Streambox.
 *
 * <p>La información de la película se almacena en la tabla
 * {@code movies} de la base de datos.</p>
 */
@Entity
@Table(name = "movies")
@Getter
@Setter
public class Movie {

    /**
     * Identificador único de la película.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Título de la película.
     */
    @Column(nullable = false, length = 150)
    private String title;

    /**
     * Descripción o sinopsis de la película.
     */
    @Column(nullable = false, length = 1000)
    private String description;

    /**
     * Fecha de lanzamiento de la película.
     */
    private LocalDateTime releaseDate;

    /**
     * Fecha en la que la película fue añadida a Streambox.
     */
    @Column(nullable = false)
    private LocalDateTime createdAt;
}
