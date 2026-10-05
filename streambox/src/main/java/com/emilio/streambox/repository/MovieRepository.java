package com.emilio.streambox.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.emilio.streambox.entity.Movie;

/**
 * Repositorio de acceso a datos de la entidad {@link Movie}.
 *
 * <p>
 * Hereda de {@link JpaSpecificationExecutor} para poder aplicar filtros
 * dinámicos mediante {@code Specification} (ver
 * {@link com.emilio.streambox.specification.MovieSpecification}).
 * </p>
 *
 * <p>
 * <strong>Carga de géneros:</strong> solo {@link #findById(Long)} trae los
 * géneros con un {@code JOIN FETCH}, porque devuelve una única película. Las
 * consultas que devuelven varias películas (y sobre todo las paginadas) NO
 * hacen fetch de la colección: Hibernate no puede aplicar {@code LIMIT} sobre
 * un join de colección y paginaría en memoria, cargando todo el catálogo.
 * En su lugar los géneros se cargan de forma diferida y por lotes
 * ({@code hibernate.default_batch_fetch_size}), con una consulta extra por
 * página en lugar de una por película.
 * </p>
 */
public interface MovieRepository
        extends JpaRepository<Movie, Long>, JpaSpecificationExecutor<Movie> {

    /**
     * Busca una película por su identificador cargando también sus géneros.
     *
     * @param id identificador de la película
     * @return la película con sus géneros, o vacío si no existe
     */
    @Override
    @EntityGraph(attributePaths = { "genres" })
    Optional<Movie> findById(Long id);

    /**
     * Busca todas las películas cuyo título coincide exactamente, sin
     * distinguir mayúsculas de minúsculas.
     *
     * @param title título buscado
     * @return películas con ese título (puede haber más de una)
     */
    List<Movie> findAllByTitleIgnoreCase(String title);

    /**
     * Cuenta cuántas películas tienen asignado un género.
     *
     * <p>
     * Se usa antes de borrar un género para explicar por qué no se puede y a
     * cuántas películas afecta. Es una consulta {@code COUNT} con un
     * {@code JOIN} sobre {@code movie_genres} (que aprovecha el índice
     * {@code idx_movie_genres_genre_id}): no carga ninguna película ni su
     * colección de géneros. No hace falta {@code DISTINCT}, porque la clave
     * primaria de {@code movie_genres} impide que una película tenga el mismo
     * género dos veces.
     * </p>
     *
     * @param genreId identificador del género
     * @return número de películas que lo usan
     */
    long countByGenres_Id(Long genreId);

    /**
     * Elimina una película de las listas de favoritos de todos los usuarios.
     *
     * <p>
     * Las bases de datos creadas antes de Flyway conservan claves foráneas
     * sin {@code ON DELETE CASCADE}, por lo que antes de borrar una película
     * hay que limpiar esta tabla a mano. En bases nuevas el borrado en cascada
     * lo haría la propia base de datos, y esta consulta simplemente no
     * encuentra filas.
     * </p>
     *
     * @param movieId identificador de la película
     */
    @Modifying
    @Query(value = "DELETE FROM user_favorite_movies WHERE movie_id = :movieId", nativeQuery = true)
    void deleteFromAllFavorites(@Param("movieId") Long movieId);
}
