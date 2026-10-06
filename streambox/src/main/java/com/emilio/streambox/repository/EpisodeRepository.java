package com.emilio.streambox.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.emilio.streambox.entity.Episode;

/**
 * Repositorio de acceso a datos de la entidad {@link Episode}.
 *
 * <p>
 * Todas las consultas filtran por {@code series_id}, la primera columna del
 * índice único {@code uk_episodes_series_season_episode}
 * {@code (series_id, season_number, episode_number)}: ese mismo índice sirve
 * para localizar los episodios de una serie y devolverlos ya ordenados.
 * </p>
 *
 * <p>
 * Ninguna consulta carga la serie de cada episodio: {@code Episode.series} es
 * {@code LAZY} y basta con {@code episode.getSeries().getId()}, que no consulta
 * la base de datos.
 * </p>
 */
public interface EpisodeRepository extends JpaRepository<Episode, Long> {

    /**
     * Devuelve todos los episodios de una serie ordenados por temporada y
     * número de episodio, en una sola consulta.
     *
     * @param seriesId identificador de la serie
     * @return episodios ordenados (vacío si la serie no tiene o no existe)
     */
    @Query("select e from Episode e where e.series.id = :seriesId "
            + "order by e.seasonNumber, e.episodeNumber")
    List<Episode> findAllBySeriesIdOrdered(@Param("seriesId") Long seriesId);

    /**
     * Busca un episodio comprobando que pertenece a la serie indicada.
     *
     * <p>
     * Para rutas anidadas ({@code /series/{id}/episodes/{episodeId}}): evita
     * actuar sobre un episodio de otra serie si los dos identificadores no
     * casan.
     * </p>
     *
     * @param id       identificador del episodio
     * @param seriesId identificador de la serie
     * @return el episodio, o vacío si no existe o es de otra serie
     */
    Optional<Episode> findByIdAndSeriesId(Long id, Long seriesId);

    /**
     * Indica si una serie tiene al menos un episodio (es decir, si es visible
     * para los usuarios).
     *
     * @param seriesId identificador de la serie
     * @return {@code true} si tiene algún episodio
     */
    boolean existsBySeriesId(Long seriesId);

    /**
     * Indica si ya existe en la serie un episodio con esa temporada y número.
     *
     * <p>
     * El servicio debe comprobarlo antes de crear un episodio para responder
     * con un error claro en lugar de la violación de
     * {@code uk_episodes_series_season_episode}.
     * </p>
     *
     * @param seriesId      identificador de la serie
     * @param seasonNumber  número de temporada
     * @param episodeNumber número de episodio
     * @return {@code true} si la posición ya está ocupada
     */
    boolean existsBySeriesIdAndSeasonNumberAndEpisodeNumber(
            Long seriesId, Integer seasonNumber, Integer episodeNumber);

    /**
     * Igual que
     * {@link #existsBySeriesIdAndSeasonNumberAndEpisodeNumber(Long, Integer, Integer)}
     * pero sin contar un episodio concreto: para editar un episodio, que puede
     * conservar su propia temporada y número.
     *
     * @param seriesId      identificador de la serie
     * @param seasonNumber  número de temporada
     * @param episodeNumber número de episodio
     * @param id            episodio que se está editando (se excluye)
     * @return {@code true} si otro episodio ocupa ya esa posición
     */
    boolean existsBySeriesIdAndSeasonNumberAndEpisodeNumberAndIdNot(
            Long seriesId, Integer seasonNumber, Integer episodeNumber, Long id);

    /**
     * Número de episodios y de temporadas de varias series en una sola
     * consulta agrupada.
     *
     * <p>
     * Pensado para un listado (p. ej. el panel de administración, que muestra
     * qué series están vacías y por tanto ocultas): se le pasan los ids de la
     * página y devuelve una fila por serie <strong>con</strong> episodios. Las
     * series sin episodios no aparecen en el resultado (cuentan como 0).
     * </p>
     *
     * @param seriesIds identificadores de las series (no vacío)
     * @return recuentos por serie
     */
    @Query("select e.series.id as seriesId, count(e) as episodeCount, "
            + "count(distinct e.seasonNumber) as seasonCount "
            + "from Episode e where e.series.id in :seriesIds group by e.series.id")
    List<SeriesEpisodeStats> countBySeriesIds(@Param("seriesIds") Collection<Long> seriesIds);
}
