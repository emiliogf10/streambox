package com.emilio.streambox.entity;

import java.time.Instant;
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

import org.hibernate.annotations.CreationTimestamp;

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
     * Instante en el que se creó el registro.
     *
     * <p>
     * Lo asigna Hibernate automáticamente al insertar ({@code @CreationTimestamp})
     * y no se modifica después. Se guarda como {@link Instant} (un momento
     * absoluto, independiente de la zona horaria del servidor) en una columna
     * {@code TIMESTAMP WITH TIME ZONE}.
     * </p>
     */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

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

    /**
     * Dos películas son iguales si tienen el mismo identificador.
     *
     * <p>
     * La igualdad se basa solo en el {@code id} asignado por la base de
     * datos, como recomienda Hibernate: así una entidad sigue siendo la misma
     * aunque se cargue en sesiones distintas, y una entidad aún no guardada
     * (sin {@code id}) solo es igual a sí misma. Se compara con
     * {@code instanceof} y con el getter para funcionar también con los
     * proxies de carga diferida.
     * </p>
     *
     * @param other objeto con el que se compara
     * @return {@code true} si ambos representan la misma fila
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Movie that
                && id != null
                && id.equals(that.getId());
    }

    /**
     * Devuelve un valor constante por clase.
     *
     * <p>
     * El {@code id} cambia al guardar la entidad por primera vez, y un
     * {@code hashCode} que cambiara rompería las colecciones {@code Set}
     * que la contienen. Un valor constante es correcto (aunque agrupa todas
     * las instancias en un mismo bucket, algo irrelevante con las pocas
     * entidades que maneja una colección aquí).
     * </p>
     *
     * @return código hash constante de la clase
     */
    @Override
    public int hashCode() {
        return Movie.class.hashCode();
    }
}
