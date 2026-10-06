package com.emilio.streambox.entity;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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
 * Entidad que representa una serie del catálogo de StreamBox.
 *
 * <p>
 * Se persiste en la tabla {@code series} (migración {@code V3}). Una serie
 * agrupa episodios ({@link Episode}); las temporadas no son una entidad, sino
 * el número {@link Episode#getSeasonNumber()} de cada episodio.
 * </p>
 *
 * <p>
 * <strong>Sin colección de episodios.</strong> La relación serie–episodio solo
 * se mapea en el lado del episodio ({@link Episode#getSeries()}). Los
 * episodios se piden siempre con una consulta ordenada
 * ({@code EpisodeRepository.findAllBySeriesIdOrdered}), así que una colección
 * {@code Series.episodes} no aportaría nada y sí dos riesgos: recorrerla en un
 * listado provocaría una consulta por serie (N+1), y habría que mantener
 * sincronizados los dos lados de la relación. Las series sin episodios se
 * filtran con un {@code EXISTS} sobre {@code episodes}, que tampoco necesita
 * la colección.
 * </p>
 *
 * <p>
 * Tampoco hay colección de "usuarios que la tienen en favoritos": esa tabla
 * ({@code user_favorite_series}) se maneja con consultas nativas en
 * {@code SeriesRepository}.
 * </p>
 */
@Entity
@Table(name = "series")
@Getter
@Setter
public class Series {

    /**
     * Identificador único de la serie, generado por la base de datos.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Título de la serie.
     */
    @Column(nullable = false, length = 150)
    private String title;

    /**
     * Sinopsis de la serie.
     */
    @Column(nullable = false, length = 1000)
    private String description;

    /**
     * Año en el que se estrenó la serie (entre 1888 y 2100, lo comprueba
     * también la base de datos con {@code ck_series_release_year}).
     */
    @Column(nullable = false)
    private Integer releaseYear;

    /**
     * Año en el que terminó la serie, o {@code null} si sigue en emisión.
     *
     * <p>
     * Si tiene valor, la base de datos exige que no sea anterior a
     * {@link #releaseYear} ni posterior a 2100 ({@code ck_series_end_year}).
     * </p>
     */
    @Column
    private Integer endYear;

    /**
     * URL de la imagen de portada. Los episodios, de momento, no tienen
     * imagen propia y usan esta.
     */
    @Column(nullable = false, length = 500)
    private String imageUrl;

    /**
     * Instante en el que se creó el registro.
     *
     * <p>
     * Lo asigna Hibernate al insertar ({@code @CreationTimestamp}) y no se
     * modifica después. Se guarda en una columna
     * {@code TIMESTAMP WITH TIME ZONE}, como en {@link Movie}.
     * </p>
     */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Géneros de la serie (el mismo catálogo que usan las películas).
     *
     * <p>
     * Carga diferida explícita: los listados paginados no hacen fetch de esta
     * colección y se cargan por lotes ({@code default_batch_fetch_size});
     * solo el detalle la trae en la misma consulta con un {@code @EntityGraph}.
     * </p>
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "series_genres", joinColumns = @JoinColumn(name = "series_id"), inverseJoinColumns = @JoinColumn(name = "genre_id"))
    private Set<Genre> genres = new HashSet<>();

    /**
     * Dos series son iguales si tienen el mismo identificador.
     *
     * <p>
     * Mismo criterio que {@link Movie#equals(Object)}: igualdad por el
     * {@code id} de la base de datos, comparando con {@code instanceof} y el
     * getter para que funcione con los proxies de carga diferida.
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
        return other instanceof Series that
                && id != null
                && id.equals(that.getId());
    }

    /**
     * Devuelve un valor constante por clase, para que el {@code hashCode} no
     * cambie cuando la entidad recibe su {@code id} al guardarse (ver
     * {@link Movie#hashCode()}).
     *
     * @return código hash constante de la clase
     */
    @Override
    public int hashCode() {
        return Series.class.hashCode();
    }
}
