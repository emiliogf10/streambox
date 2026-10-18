package com.emilio.streambox.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.User;

/**
 * Repositorio de acceso a datos de la entidad {@link User}.
 *
 * <p>
 * Incluye las operaciones de la lista de favoritos ("Mi lista"). Estas
 * operaciones trabajan <strong>directamente sobre la tabla de unión</strong>
 * {@code user_favorite_movies} con consultas explícitas, en lugar de cargar
 * la colección {@code User.favoriteMovies} completa (con los géneros de cada
 * película) solo para añadir o quitar un elemento.
 * </p>
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Busca un usuario por su correo electrónico (ya normalizado en minúsculas).
     *
     * @param email correo electrónico
     * @return el usuario, o vacío si no existe
     */
    Optional<User> findByEmail(String email);

    /**
     * Indica si ya existe un usuario con ese nombre de usuario.
     *
     * @param username nombre de usuario
     * @return {@code true} si está en uso
     */
    boolean existsByUsername(String username);

    /**
     * Indica si ya existe un usuario con ese correo electrónico.
     *
     * @param email correo electrónico (en minúsculas)
     * @return {@code true} si está en uso
     */
    boolean existsByEmail(String email);

    /**
     * Obtiene las películas de la lista de un usuario, ordenadas por título.
     *
     * <p>
     * Los géneros no se cargan aquí: se resolverán por lotes cuando el
     * servicio convierta las películas a DTO.
     * </p>
     *
     * @param userId identificador del usuario
     * @return películas favoritas del usuario
     */
    @Query("select m from User u join u.favoriteMovies m "
            + "where u.id = :userId order by m.title, m.id")
    List<Movie> findFavoriteMovies(@Param("userId") Long userId);

    /**
     * Comprueba si una película está en la lista de un usuario.
     *
     * @param userId  identificador del usuario
     * @param movieId identificador de la película
     * @return {@code true} si la película ya está en su lista
     */
    @Query("select count(m) > 0 from User u join u.favoriteMovies m "
            + "where u.id = :userId and m.id = :movieId")
    boolean isFavorite(@Param("userId") Long userId, @Param("movieId") Long movieId);

    /**
     * Añade una película a la lista de un usuario.
     *
     * <p>
     * Si la pareja ya existía, la base de datos lanza una violación de la
     * clave primaria (que el servicio convierte en "ya está en favoritos").
     * </p>
     *
     * @param userId  identificador del usuario
     * @param movieId identificador de la película
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO user_favorite_movies (user_id, movie_id) VALUES (:userId, :movieId)",
            nativeQuery = true)
    void addFavorite(@Param("userId") Long userId, @Param("movieId") Long movieId);

    /**
     * Quita una película de la lista de un usuario.
     *
     * @param userId  identificador del usuario
     * @param movieId identificador de la película
     * @return número de filas eliminadas (0 si la película no estaba en la lista)
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM user_favorite_movies WHERE user_id = :userId AND movie_id = :movieId",
            nativeQuery = true)
    int removeFavorite(@Param("userId") Long userId, @Param("movieId") Long movieId);

    /**
     * Vacía por completo la lista de un usuario.
     *
     * @param userId identificador del usuario
     * @return número de películas que se quitaron
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM user_favorite_movies WHERE user_id = :userId", nativeQuery = true)
    int clearFavorites(@Param("userId") Long userId);

    /**
     * Indica si el usuario tiene algún «me gusta» <strong>visible</strong>: de
     * una película, o de una serie con al menos un episodio.
     *
     * <p>
     * Es lo que decide {@code basedOnLikes} en las recomendaciones: exactamente
     * los mismos «me gusta» que alimentan los pesos de géneros
     * ({@link MovieRepository#LIKED_GENRE_WEIGHTS}) y que el usuario ve en su
     * sección «Te gusta». Un «me gusta» de una serie que ahora está oculta no
     * cuenta. Es una sola consulta con dos {@code EXISTS}, que paran en la
     * primera fila (clave primaria {@code (user_id, ...)} y el índice único de
     * {@code episodes}).
     * </p>
     *
     * @param userId identificador del usuario
     * @return {@code true} si tiene al menos un «me gusta» visible
     */
    @Query(value = "SELECT EXISTS (SELECT 1 FROM user_liked_movies WHERE user_id = :userId)"
            + " OR EXISTS (SELECT 1 FROM user_liked_series l WHERE l.user_id = :userId"
            + "   AND EXISTS (SELECT 1 FROM episodes e WHERE e.series_id = l.series_id))", nativeQuery = true)
    boolean hasVisibleLikes(@Param("userId") Long userId);
}
