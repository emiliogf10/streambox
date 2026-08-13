package com.emilio.streambox.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/**
 * Entidad que representa un género cinematográfico dentro de Streambox.
 *
 * <p>
 * Los géneros permiten clasificar las películas y posteriormente
 * facilitar su búsqueda y filtrado.
 * </p>
 */
@Entity
@Table(name = "genres")
@Getter
@Setter
public class Genre {

    /**
     * Identificador único del género.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Nombre del género cinematográfico.
     *
     * <p>
     * El nombre es obligatorio y no puede repetirse dentro
     * de la base de datos.
     * </p>
     */
    @Column(nullable = false, unique = true, length = 50)
    private String name;
}
