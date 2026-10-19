package com.emilio.streambox.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.emilio.streambox.entity.Movie;
import com.emilio.streambox.entity.User;

import jakarta.persistence.LockModeType;

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
     * Busca un usuario por su nombre de usuario (ya normalizado; distingue
     * mayúsculas, como la restricción {@code uk_users_username}).
     *
     * <p>
     * Lo usa el registro para saber si el nombre pertenece a una cuenta
     * pendiente cuyo enlace caducó (y entonces se libera).
     * </p>
     *
     * @param username nombre de usuario
     * @return el usuario, o vacío si no existe
     */
    Optional<User> findByUsername(String username);

    /**
     * Lee un usuario y <b>bloquea su fila</b> hasta el final de la transacción
     * ({@code SELECT ... FOR UPDATE}).
     *
     * <p>
     * <b>Por qué no {@code EntityManager.refresh(user, PESSIMISTIC_WRITE)}</b>,
     * que es lo que se usaba: si otra transacción borra la fila mientras esta
     * espera el bloqueo (un registro que sustituye una cuenta pendiente
     * caducada), {@code refresh} lanza {@code EntityNotFoundException} y la
     * petición acaba en un 500. Una consulta, en cambio, devuelve vacío, y
     * quien llama decide (reintentar el registro, o tratar el reenvío como
     * «sin cuenta»).
     * </p>
     *
     * <p>
     * Es una consulta derivada: lo que va entre {@code find} y {@code By}
     * ({@code ForUpdate}) Spring Data lo ignora y solo sirve para que el nombre
     * diga lo que hace; el bloqueo lo pone {@link Lock}. Si la entidad ya está
     * en la sesión de Hibernate, la consulta bloquea la fila pero devuelve esa
     * misma instancia <b>sin releer su estado</b>: quien necesite el estado
     * actual debe desacoplarla antes ({@code EmailVerificationService#lockForUpdate}).
     * </p>
     *
     * @param id id del usuario
     * @return el usuario bloqueado, o vacío si ya no existe
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findForUpdateById(Long id);

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
     * Marca como verificado el email de una cuenta <strong>pendiente</strong>
     * (tras consumir su token de verificación, en la misma transacción).
     *
     * <p>
     * Idempotente: si la cuenta ya estaba verificada no cambia nada (devuelve
     * {@code 0}) y conserva la fecha original. Es un {@code UPDATE} de una sola
     * columna, así que no pisa nada que otra petición haya cambiado en la fila.
     * No vacía la sesión de Hibernate: si el servicio tiene ese {@code User}
     * cargado, su {@code emailVerifiedAt} en memoria queda desfasado (y, por
     * {@code @DynamicUpdate}, guardar después otros cambios del usuario no lo
     * devuelve a {@code null}: el {@code UPDATE} solo lleva lo modificado).
     * </p>
     *
     * @param userId id de la cuenta
     * @param now    instante de la verificación (del {@code Clock} del servicio)
     * @return {@code 1} si estaba pendiente y queda verificada; {@code 0} si ya
     *         lo estaba o no existe
     */
    @Modifying(flushAutomatically = true)
    @Query("update User u set u.emailVerifiedAt = :now where u.id = :userId and u.emailVerifiedAt is null")
    int markEmailVerified(@Param("userId") Long userId, @Param("now") Instant now);

    /**
     * Borra una cuenta <strong>solo si sigue pendiente</strong> de verificar
     * (para que un registro nuevo sustituya una cuenta pendiente cuyo enlace ya
     * caducó).
     *
     * <p>
     * La condición {@code email_verified_at IS NULL} va en la propia sentencia:
     * si entre la comprobación del servicio y el borrado alguien verificara la
     * cuenta, no se borra (devuelve {@code 0}) y el servicio debe tratarla como
     * una cuenta existente. Sus tokens, sesiones y demás filas dependientes
     * caen en cascada ({@code ON DELETE CASCADE}). <b>Ojo:</b> las cuentas USER
     * anteriores a la V9 también están pendientes y pueden tener favoritos (cuya
     * clave foránea no cascada en las bases adoptadas). Por eso quien lo llame
     * solo debe usarlo con cuentas a las que se emitió algún token de
     * verificación y que nunca pudieron iniciar sesión; nunca con las heredadas.
     * </p>
     *
     * <p>
     * SQL nativo para que sea exactamente un {@code DELETE} con esa condición.
     * Vacía la sesión de Hibernate después ({@code clearAutomatically}): la
     * entidad de la cuenta borrada no debe seguir viva en la sesión mientras el
     * servicio crea la nueva con el mismo email. Lo cargado antes queda
     * desacoplado.
     * </p>
     *
     * @param userId id de la cuenta
     * @return {@code 1} si se ha borrado; {@code 0} si ya estaba verificada o no existe
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM users WHERE id = :userId AND email_verified_at IS NULL", nativeQuery = true)
    int deleteIfEmailNotVerified(@Param("userId") Long userId);

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
