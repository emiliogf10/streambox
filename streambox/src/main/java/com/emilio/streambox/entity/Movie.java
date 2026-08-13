package com.emilio.streambox.entity;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.Setter;

/**
 * Entidad que representa una película almacenada en Streambox.
 *
 * <p>
 * La entidad se persiste en la tabla {@code movies} de la base de
 * datos y contiene la información necesaria para identificar y
 * reproducir una película.
 * </p>
 *
 * <p>
 * Las propiedades {@code imageUrl} y {@code videoUrl} almacenan
 * las direcciones donde se encuentran, respectivamente, la imagen
 * asociada a la película y el contenido de vídeo.
 * </p>
 */
@Entity
@Table(name = "movies")
@Getter
@Setter
public class Movie {

    /**
     * Identificador único de la película.
     *
     * <p>
     * Su valor es generado automáticamente por la base de datos.
     * </p>
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
     * Duración de la película expresada en minutos.
     */
    @Column(nullable = false)
    private Integer duration;

    /**
     * Año en el que se estrenó la película.
     */
    @Column(nullable = false)
    private Integer releaseYear;

    /**
     * URL de la imagen utilizada como portada de la película.
     */
    @Column(nullable = false, length = 500)
    private String imageUrl;

    /**
     * URL desde la que se puede acceder al vídeo de la película.
     */
    @Column(nullable = false, length = 500)
    private String videoUrl;

    /**
     * Fecha y hora en la que la película fue registrada en Streambox.
     */
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /**
     * Géneros asociados a la película.
     *
     * <p>
     * Una película puede pertenecer a varios géneros y un género
     * puede estar asociado a varias películas.
     * </p>
     */
    @ManyToMany
    @JoinTable(name = "movie_genres", joinColumns = @JoinColumn(name = "movie_id"), inverseJoinColumns = @JoinColumn(name = "genre_id"))
    private Set<Genre> genres = new HashSet<>();
}