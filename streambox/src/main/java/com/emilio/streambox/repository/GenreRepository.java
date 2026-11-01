package com.emilio.streambox.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.emilio.streambox.entity.Genre;

/**
 * Repositorio de acceso a datos de la entidad {@link Genre}.
 *
 * <p>
 * Las operaciones habituales ({@code findAll}, {@code findAllById},
 * {@code save}...) las proporciona {@link JpaRepository}.
 * </p>
 *
 * <p>
 * <strong>Unicidad del nombre:</strong> el servicio la comprueba antes de
 * guardar con las consultas de abajo para responder con un error claro, pero
 * la garantía real es la restricción {@code UNIQUE} de la base de datos: dos
 * peticiones simultáneas pueden pasar la comprobación a la vez y solo la
 * restricción impide que ambas se guarden.
 * </p>
 */
public interface GenreRepository extends JpaRepository<Genre, Long> {

    /**
     * Indica si existe un género con ese nombre, sin distinguir mayúsculas.
     *
     * <p>
     * Se ignoran mayúsculas aunque los nombres nuevos se guarden ya
     * normalizados, porque puede haber filas anteriores a la normalización
     * (p. ej. {@code "Ciencia Ficción"}) que la restricción {@code UNIQUE},
     * que sí distingue mayúsculas, no detectaría como duplicadas de
     * {@code "Ciencia ficción"}.
     * </p>
     *
     * @param name nombre buscado
     * @return {@code true} si algún género tiene ese nombre
     */
    boolean existsByNameIgnoreCase(String name);

    /**
     * Indica si un género <em>distinto</em> del indicado tiene ese nombre, sin
     * distinguir mayúsculas.
     *
     * <p>
     * Lo usa la edición: renombrar un género a su propio nombre (o cambiar
     * solo sus mayúsculas) no es un duplicado.
     * </p>
     *
     * @param name nombre buscado
     * @param id   identificador del género que se está editando
     * @return {@code true} si otro género tiene ese nombre
     */
    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);

    // ------------------------------------------------------------------
    // Géneros favoritos («Tus gustos», tabla user_favorite_genres, V11)
    //
    // Mismo patrón que los «me gusta» (V8): consultas nativas sobre la tabla de
    // unión, sin colección en User ni entidad propia. Viven aquí porque los
    // listados devuelven Genre (Spring Data solo convierte una consulta nativa
    // en entidades del tipo del propio repositorio). No hace falta limpiar
    // nada antes de borrar un género o un usuario: ON DELETE CASCADE.
    // ------------------------------------------------------------------

    /**
     * Condición «el género {@code g} tiene algún título <strong>visible</strong>»:
     * una película (todas son visibles) o una serie con al menos un episodio
     * (la misma regla de visibilidad que el resto de rutas públicas; un
     * episodio implica su serie por la clave foránea).
     *
     * <p>
     * Es una constante para que el listado de opciones y la validación de los
     * ids no puedan divergir. Cada {@code EXISTS} para en la primera fila:
     * {@code idx_movie_genres_genre_id}, {@code idx_series_genres_genre_id} y el
     * índice único de {@code episodes} (empieza por {@code series_id}).
     * </p>
     */
    String HAS_VISIBLE_TITLES = "(EXISTS (SELECT 1 FROM movie_genres mg WHERE mg.genre_id = g.id)"
            + " OR EXISTS (SELECT 1 FROM series_genres sg WHERE sg.genre_id = g.id"
            + "   AND EXISTS (SELECT 1 FROM episodes e WHERE e.series_id = sg.series_id)))";

    /**
     * Devuelve los géneros favoritos de un usuario, por nombre (y por
     * {@code id} para desempatar de forma estable).
     *
     * <p>
     * Incluye los que se hayan quedado sin títulos visibles después de
     * elegirlos: siguen siendo sus gustos (y no aportan candidatas a las
     * recomendaciones hasta que vuelvan a tener títulos). El orden alfabético
     * es el de la <em>collation</em> de la base de datos (en PostgreSQL, la del
     * servidor; H2 puede ordenar distinto las mayúsculas y los acentos).
     * </p>
     *
     * @param userId identificador del usuario
     * @return sus géneros favoritos; vacío si no tiene ninguno
     */
    @Query(value = "SELECT g.* FROM genres g"
            + " JOIN user_favorite_genres f ON f.genre_id = g.id"
            + " WHERE f.user_id = :userId"
            + " ORDER BY g.name, g.id", nativeQuery = true)
    List<Genre> findFavoritesByUserId(@Param("userId") Long userId);

    /**
     * Quita todos los géneros favoritos de un usuario. Primera mitad de
     * «sustituir la lista entera» ({@code PUT /api/users/me/genres}); la
     * segunda es {@link #addFavorites(Long, Collection, Instant)}.
     *
     * <p>
     * El servicio debe llamar a los dos en la <strong>misma
     * transacción</strong> (si el {@code INSERT} falla, el borrado se deshace y
     * la lista anterior sigue intacta) y, antes que a ninguno,
     * {@code UserRepository.markGenresOnboarded}, que bloquea la fila del
     * usuario para que dos sustituciones simultáneas no se mezclen.
     * </p>
     *
     * @param userId identificador del usuario
     * @return número de géneros que se quitaron
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM user_favorite_genres WHERE user_id = :userId", nativeQuery = true)
    int clearFavorites(@Param("userId") Long userId);

    /**
     * Añade varios géneros a los favoritos de un usuario en <strong>una sola
     * sentencia</strong> ({@code INSERT ... SELECT} sobre {@code genres}).
     *
     * <p>
     * Se usa justo después de {@link #clearFavorites(Long)}. Al seleccionar de
     * {@code genres}, los ids repetidos de la lista se insertan una sola vez
     * ({@code IN} no duplica filas) y un id que no existe simplemente no se
     * inserta, sin violar la clave foránea; por eso devuelve cuántos entraron:
     * si es menor que el número de ids distintos validados, un género se borró
     * entre la validación y este {@code INSERT} y el servicio debe rechazar la
     * petición (lanzando una excepción, para que se deshaga también el
     * borrado). No comprueba que tengan títulos visibles: eso lo valida antes
     * {@link #findIdsWithVisibleTitles(Collection)}.
     * </p>
     *
     * <p>
     * <b>No lo llames con una colección vacía</b>: «quitar los gustos» es solo
     * {@link #clearFavorites(Long)}. Si un usuario ya tuviera alguno de esos
     * géneros (porque no se llamó antes a {@code clearFavorites}), la clave
     * primaria lanzaría una violación.
     * </p>
     *
     * @param userId   identificador del usuario
     * @param genreIds ids de los géneros (no vacía; ya validados)
     * @param chosenAt instante de la elección (del {@code Clock} del servicio)
     * @return filas insertadas
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO user_favorite_genres (user_id, genre_id, created_at)"
            + " SELECT :userId, g.id, :chosenAt FROM genres g WHERE g.id IN (:genreIds)", nativeQuery = true)
    int addFavorites(@Param("userId") Long userId, @Param("genreIds") Collection<Long> genreIds,
            @Param("chosenAt") Instant chosenAt);

    /**
     * Devuelve los géneros que se pueden elegir como favoritos: los que tienen
     * algún título visible (ver {@link #HAS_VISIBLE_TITLES}), por nombre y
     * {@code id}.
     *
     * <p>
     * Un género sin títulos no se ofrece: elegirlo no daría ninguna
     * recomendación. Es la lista de {@code GET /api/users/me/genres/options}.
     * </p>
     *
     * @return géneros con algún título visible
     */
    @Query(value = "SELECT g.* FROM genres g WHERE " + HAS_VISIBLE_TITLES
            + " ORDER BY g.name, g.id", nativeQuery = true)
    List<Genre> findWithVisibleTitles();

    /**
     * De los ids dados, devuelve los que son géneros existentes <strong>con
     * algún título visible</strong> (los que se pueden elegir), cada uno una
     * vez y sin orden garantizado.
     *
     * <p>
     * Para validar {@code PUT /api/users/me/genres}: los ids pedidos que no
     * estén en el resultado no existen o no tienen títulos visibles (para
     * distinguir los dos casos en el mensaje basta con {@code findAllById}).
     * Una sola consulta, con la misma condición que
     * {@link #findWithVisibleTitles()}. <b>No lo llames con una colección
     * vacía</b> (no hay nada que validar).
     * </p>
     *
     * @param ids ids pedidos (no vacía)
     * @return los que se pueden elegir
     */
    @Query(value = "SELECT g.id FROM genres g WHERE g.id IN (:ids) AND " + HAS_VISIBLE_TITLES, nativeQuery = true)
    List<Long> findIdsWithVisibleTitles(@Param("ids") Collection<Long> ids);
}
