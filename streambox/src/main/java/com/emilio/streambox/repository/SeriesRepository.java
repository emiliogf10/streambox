package com.emilio.streambox.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.emilio.streambox.entity.Series;

/**
 * Repositorio de acceso a datos de la entidad {@link Series}.
 *
 * <p>
 * Hereda de {@link JpaSpecificationExecutor} para que el catálogo de series
 * se pueda filtrar con {@code Specification}, igual que el de películas.
 * </p>
 *
 * <p>
 * <strong>Carga de géneros:</strong> igual que en {@link MovieRepository},
 * solo las consultas de una única serie ({@link #findById(Long)} y
 * {@link #findVisibleById(Long)}) traen los géneros en la misma consulta. Los
 * listados (paginados o no) no hacen fetch de la colección: los géneros se
 * cargan después por lotes ({@code hibernate.default_batch_fetch_size}).
 * </p>
 *
 * <p>
 * <strong>Favoritos de series ("Mi lista").</strong> Se gestionan aquí, con
 * consultas nativas sobre la tabla de unión {@code user_favorite_series}, y no
 * con una colección en {@code User}: la entidad {@code User} la carga el filtro
 * JWT en cada petición y no necesita saber nada de series, y una colección que
 * no se debe recorrer nunca (se escribe con {@code INSERT}/{@code DELETE}
 * directos) sería una trampa para quien la usara. Viven en este repositorio y
 * no en {@code UserRepository} porque la consulta de listado es nativa y
 * devuelve {@link Series}: Spring Data solo la convierte en entidades cuando
 * el tipo devuelto es el del propio repositorio.
 * </p>
 *
 * <p>
 * No hace falta un método para "quitar una serie de todas las listas" antes de
 * borrarla (como {@code MovieRepository.deleteFromAllFavorites}): la tabla
 * {@code user_favorite_series} es nueva en {@code V3} y tiene
 * {@code ON DELETE CASCADE} en todas las bases, también en las adoptadas.
 * </p>
 */
public interface SeriesRepository
        extends JpaRepository<Series, Long>, JpaSpecificationExecutor<Series> {

    /**
     * Busca una serie por su identificador cargando también sus géneros.
     *
     * <p>
     * No comprueba si tiene episodios: es la consulta del panel de
     * administración, donde las series vacías sí son visibles.
     * </p>
     *
     * @param id identificador de la serie
     * @return la serie con sus géneros, o vacío si no existe
     */
    @Override
    @EntityGraph(attributePaths = { "genres" })
    Optional<Series> findById(Long id);

    /**
     * Busca una serie <strong>visible para los usuarios</strong> (con al menos
     * un episodio), cargando sus géneros en la misma consulta.
     *
     * <p>
     * Es la consulta del detalle público: una serie sin episodios se trata como
     * si no existiera (404). El {@code EXISTS} se resuelve con el índice único
     * de {@code episodes}, que empieza por {@code series_id}. Junto con
     * {@link EpisodeRepository#findAllBySeriesIdOrdered(Long)}, el detalle
     * completo cuesta dos consultas, sin depender del número de episodios ni
     * multiplicar filas (traer géneros y episodios en un único {@code JOIN}
     * devolvería géneros × episodios filas).
     * </p>
     *
     * @param id identificador de la serie
     * @return la serie con sus géneros, o vacío si no existe o no tiene episodios
     */
    @EntityGraph(attributePaths = { "genres" })
    @Query("select s from Series s where s.id = :id "
            + "and exists (select e.id from Episode e where e.series = s)")
    Optional<Series> findVisibleById(@Param("id") Long id);

    /**
     * Cuenta cuántas series tienen asignado un género.
     *
     * <p>
     * Se usa antes de borrar un género (junto con
     * {@link MovieRepository#countByGenres_Id(Long)}) para explicar por qué no
     * se puede: {@code fk_series_genres_genre} no tiene cascada. Es un
     * {@code COUNT} que aprovecha {@code idx_series_genres_genre_id} sin cargar
     * ninguna serie; no necesita {@code DISTINCT} porque la clave primaria de
     * {@code series_genres} impide repetir la pareja.
     * </p>
     *
     * @param genreId identificador del género
     * @return número de series que lo usan
     */
    long countByGenres_Id(Long genreId);

    // ------------------------------------------------------------------
    // Favoritos de series ("Mi lista")
    // ------------------------------------------------------------------

    /**
     * Obtiene las series de la lista de un usuario, ordenadas por título (y por
     * {@code id} para desempatar de forma estable).
     *
     * <p>
     * No filtra las series sin episodios: si el administrador vacía una serie
     * que alguien tenía en su lista, decidir si se muestra es cosa del
     * servicio. Los géneros no se cargan aquí; se resuelven por lotes al
     * convertir a DTO (dentro de la transacción).
     * </p>
     *
     * @param userId identificador del usuario
     * @return series favoritas del usuario
     */
    @Query(value = "SELECT s.* FROM series s"
            + " JOIN user_favorite_series f ON f.series_id = s.id"
            + " WHERE f.user_id = :userId"
            + " ORDER BY s.title, s.id", nativeQuery = true)
    List<Series> findFavoritesByUserId(@Param("userId") Long userId);

    /**
     * Comprueba si una serie está en la lista de un usuario.
     *
     * <p>
     * El servicio debe llamarlo antes de {@link #addFavorite(Long, Long)}: en
     * PostgreSQL una violación de la clave primaria deja la transacción
     * abortada, así que es mejor no provocarla para detectar duplicados.
     * </p>
     *
     * @param userId   identificador del usuario
     * @param seriesId identificador de la serie
     * @return {@code true} si la serie ya está en su lista
     */
    @Query(value = "SELECT COUNT(*) > 0 FROM user_favorite_series"
            + " WHERE user_id = :userId AND series_id = :seriesId", nativeQuery = true)
    boolean isFavorite(@Param("userId") Long userId, @Param("seriesId") Long seriesId);

    /**
     * Añade una serie a la lista de un usuario.
     *
     * <p>
     * Si la pareja ya existía, la base de datos lanza una violación de la clave
     * primaria {@code pk_user_favorite_series}; si el usuario o la serie no
     * existen, una violación de clave foránea.
     * </p>
     *
     * @param userId   identificador del usuario
     * @param seriesId identificador de la serie
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO user_favorite_series (user_id, series_id) VALUES (:userId, :seriesId)",
            nativeQuery = true)
    void addFavorite(@Param("userId") Long userId, @Param("seriesId") Long seriesId);

    /**
     * Quita una serie de la lista de un usuario.
     *
     * @param userId   identificador del usuario
     * @param seriesId identificador de la serie
     * @return filas eliminadas (0 si la serie no estaba en la lista)
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM user_favorite_series WHERE user_id = :userId AND series_id = :seriesId",
            nativeQuery = true)
    int removeFavorite(@Param("userId") Long userId, @Param("seriesId") Long seriesId);

    /**
     * Quita todas las series de la lista de un usuario (no toca sus películas).
     *
     * @param userId identificador del usuario
     * @return número de series que se quitaron
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM user_favorite_series WHERE user_id = :userId", nativeQuery = true)
    int clearFavorites(@Param("userId") Long userId);

    /**
     * Carga varias series por id <strong>con sus géneros</strong> en una sola
     * consulta.
     *
     * <p>
     * Es la gemela de {@link MovieRepository#findAllWithGenresByIdIn(Collection)}
     * para completar {@link #findRecommendedSeriesIds(Long, int)}: la lista de
     * ids ya viene limitada, así que el {@code JOIN FETCH} de la colección es
     * seguro. No filtra las series sin episodios (los ids recomendados ya
     * salen solo de series con episodios) y <strong>no conserva el orden de
     * {@code ids}</strong>: hay que reconstruirlo.
     * </p>
     *
     * @param ids identificadores de las series a cargar
     * @return las series encontradas, con sus géneros ya inicializados
     */
    @EntityGraph(attributePaths = { "genres" })
    @Query("select s from Series s where s.id in :ids")
    List<Series> findAllWithGenresByIdIn(@Param("ids") Collection<Long> ids);

    // ------------------------------------------------------------------
    // «Me gusta» de series (tabla user_liked_series, V8)
    //
    // Mismo patrón que los favoritos de series y que los «me gusta» de
    // películas (MovieRepository): consultas nativas sobre la tabla de unión.
    // La existencia de una serie visible se comprueba con
    // EpisodeRepository.existsBySeriesId (si tiene algún episodio, existe).
    // No hace falta limpiar nada antes de borrar una serie: ON DELETE CASCADE.
    // ------------------------------------------------------------------

    /**
     * Devuelve las series <strong>visibles</strong> (con al menos un episodio)
     * que le gustan a un usuario, de la que le gustó más recientemente a la más
     * antigua ({@code user_liked_series.created_at} descendente y, a igual
     * fecha, {@code id} descendente).
     *
     * <p>
     * Una serie que le gustó y que después el administrador dejó sin episodios
     * no sale (como en «Mi lista»: una serie oculta es indistinguible de una
     * inexistente), pero su «me gusta» se conserva y reaparece si vuelve a
     * tener episodios. El {@code EXISTS} usa el índice único de
     * {@code episodes}; los géneros se cargan por lotes al convertir a DTO.
     * </p>
     *
     * @param userId identificador del usuario
     * @return series visibles que le gustan, la más reciente primero
     */
    @Query(value = "SELECT s.* FROM series s"
            + " JOIN user_liked_series l ON l.series_id = s.id"
            + " WHERE l.user_id = :userId"
            + "   AND EXISTS (SELECT 1 FROM episodes e WHERE e.series_id = s.id)"
            + " ORDER BY l.created_at DESC, s.id DESC", nativeQuery = true)
    List<Series> findVisibleLikedByUserId(@Param("userId") Long userId);

    /**
     * Da «me gusta» a una serie. <strong>Idempotente</strong>
     * ({@code ON CONFLICT DO NOTHING}): si ya le gustaba no hace nada, conserva
     * la fecha original y no provoca una violación de la clave primaria, ni
     * siquiera con dos peticiones simultáneas.
     *
     * <p>
     * No comprueba que la serie tenga episodios: eso lo hace el servicio antes
     * ({@code EpisodeRepository.existsBySeriesId}). Si la serie o el usuario no
     * existen, la base de datos lanza la violación de clave foránea
     * ({@code 23503}; {@code 23506} en H2), que el servicio traduce a 404. Ver
     * {@link MovieRepository#addLike(Long, Long, Instant)}.
     * </p>
     *
     * @param userId   identificador del usuario
     * @param seriesId identificador de la serie
     * @param likedAt  instante del «me gusta» (el servicio lo toma de su {@code Clock})
     * @return filas insertadas: 1 si es nuevo, 0 si ya le gustaba
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO user_liked_series (user_id, series_id, created_at)"
            + " VALUES (:userId, :seriesId, :likedAt) ON CONFLICT DO NOTHING", nativeQuery = true)
    int addLike(@Param("userId") Long userId, @Param("seriesId") Long seriesId,
            @Param("likedAt") Instant likedAt);

    /**
     * Quita el «me gusta» de una serie. Idempotente: si no le gustaba (o la
     * serie no existe) no borra nada y no falla.
     *
     * @param userId   identificador del usuario
     * @param seriesId identificador de la serie
     * @return filas eliminadas: 1 si le gustaba, 0 si no
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM user_liked_series WHERE user_id = :userId AND series_id = :seriesId",
            nativeQuery = true)
    int removeLike(@Param("userId") Long userId, @Param("seriesId") Long seriesId);

    /**
     * Devuelve los ids de las series <strong>recomendadas</strong> a un usuario
     * según los géneros de lo que le gusta, ya ordenados.
     *
     * <p>
     * Mismo criterio que {@link MovieRepository#findRecommendedMovieIds(Long, int)}
     * y la misma subconsulta de pesos ({@link MovieRepository#LIKED_GENRE_WEIGHTS}:
     * cuentan los «me gusta» de películas y de series visibles): puntuación =
     * suma de los pesos de los géneros de la serie, excluidas las que ya le
     * gustan, desempate por {@code created_at} e {@code id} descendentes. Solo
     * son candidatas las series <strong>con episodios</strong> (las ocultas no
     * se recomiendan nunca). Las series se cargan después con
     * {@link #findAllWithGenresByIdIn(Collection)}.
     * </p>
     *
     * @param userId identificador del usuario
     * @param limit  número máximo de ids devueltos (lo valida el servicio)
     * @return ids de las series recomendadas, en orden; vacío si no tiene «me
     *         gusta» o no hay candidatas
     */
    @Query(value = "SELECT sg.series_id"
            + " FROM (" + MovieRepository.LIKED_GENRE_WEIGHTS + ") pesos"
            + " JOIN series_genres sg ON sg.genre_id = pesos.genre_id"
            + " JOIN series s ON s.id = sg.series_id"
            + " WHERE NOT EXISTS (SELECT 1 FROM user_liked_series ya"
            + "   WHERE ya.user_id = :userId AND ya.series_id = sg.series_id)"
            + "   AND EXISTS (SELECT 1 FROM episodes e WHERE e.series_id = sg.series_id)"
            + " GROUP BY sg.series_id, s.created_at"
            + " ORDER BY SUM(pesos.peso) DESC, s.created_at DESC, sg.series_id DESC"
            + " FETCH FIRST :limit ROWS ONLY", nativeQuery = true)
    List<Long> findRecommendedSeriesIds(@Param("userId") Long userId, @Param("limit") int limit);
}
