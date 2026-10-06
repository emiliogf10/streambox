package com.emilio.streambox.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import lombok.Getter;
import lombok.Setter;

/**
 * Entidad que representa un episodio de una {@link Series}.
 *
 * <p>
 * Se persiste en la tabla {@code episodes} (migración {@code V3}). La
 * temporada es solo un número ({@link #seasonNumber}); dentro de una serie no
 * puede repetirse la pareja temporada + número de episodio (restricción
 * {@code uk_episodes_series_season_episode}). Si se intenta, la base de datos
 * lanza una violación de unicidad; lo correcto es que el servicio lo compruebe
 * antes ({@code EpisodeRepository.existsBySeriesIdAndSeasonNumberAndEpisodeNumber})
 * para responder con un error claro.
 * </p>
 *
 * <p>
 * De momento el episodio no tiene imagen propia: se muestra la de la serie.
 * </p>
 */
@Entity
@Table(name = "episodes", uniqueConstraints = @UniqueConstraint(
        name = "uk_episodes_series_season_episode",
        columnNames = { "series_id", "season_number", "episode_number" }))
@Getter
@Setter
public class Episode {

    /**
     * Identificador único del episodio, generado por la base de datos.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Serie a la que pertenece el episodio.
     *
     * <p>
     * {@code LAZY}: al listar los episodios de una serie no hace falta volver
     * a cargar la serie (y si ya está en la sesión, Hibernate reutiliza esa
     * instancia sin consultar). Pedir solo su {@code id}
     * ({@code episode.getSeries().getId()}) no dispara ninguna consulta.
     * </p>
     *
     * <p>
     * {@code @OnDelete(CASCADE)} indica a Hibernate que es la base de datos
     * quien borra los episodios al borrar la serie ({@code ON DELETE CASCADE}
     * en {@code fk_episodes_series}). No se usa una cascada JPA
     * ({@code CascadeType.REMOVE}) porque obligaría a cargar todos los
     * episodios en memoria y borrarlos uno a uno.
     * </p>
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "series_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Series series;

    /**
     * Número de temporada (empieza en 1; {@code ck_episodes_season_number}).
     */
    @Column(nullable = false)
    private Integer seasonNumber;

    /**
     * Número del episodio dentro de su temporada (empieza en 1;
     * {@code ck_episodes_episode_number}).
     */
    @Column(nullable = false)
    private Integer episodeNumber;

    /**
     * Título del episodio.
     */
    @Column(nullable = false, length = 150)
    private String title;

    /**
     * Sinopsis del episodio. Es opcional ({@code null} si no se indica).
     */
    @Column(length = 1000)
    private String description;

    /**
     * Duración en minutos (mayor que cero; {@code ck_episodes_duration}).
     */
    @Column(nullable = false)
    private Integer duration;

    /**
     * URL desde la que se reproduce el episodio.
     */
    @Column(nullable = false, length = 500)
    private String videoUrl;

    /**
     * Instante en el que se creó el registro (lo asigna Hibernate al insertar).
     */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Dos episodios son iguales si tienen el mismo identificador (mismo
     * criterio que {@link Movie#equals(Object)}).
     *
     * @param other objeto con el que se compara
     * @return {@code true} si ambos representan la misma fila
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Episode that
                && id != null
                && id.equals(that.getId());
    }

    /**
     * Devuelve un valor constante por clase (ver {@link Movie#hashCode()}).
     *
     * @return código hash constante de la clase
     */
    @Override
    public int hashCode() {
        return Episode.class.hashCode();
    }
}
