package com.emilio.streambox.repository;

import java.util.Collection;
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
 * <strong>Carga de géneros:</strong> solo {@link #findById(Long)} (una única
 * película) y {@link #findAllWithGenresByIdIn(Collection)} (una lista corta de
 * ids ya limitada, sin paginación) traen los géneros con un {@code JOIN FETCH}.
 * Las
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
     * Devuelve los ids de las películas <strong>similares</strong> a una dada,
     * ya ordenados de la más parecida a la menos parecida.
     *
     * <p>
     * <strong>Criterio</strong> (primera versión, sin IA): son candidatas las
     * películas que comparten <em>al menos un género</em> con la película dada,
     * excluida ella misma. El orden es total y determinista:
     * </p>
     * <ol>
     * <li>número de géneros en común, de más a menos;</li>
     * <li>distancia en años de estreno ({@code ABS(año candidata - año
     * origen)}), de menos a más;</li>
     * <li>{@code created_at}, de la más reciente a la más antigua;</li>
     * <li>{@code id} ascendente, para que dos películas idénticas en todo lo
     * anterior no cambien de posición entre llamadas.</li>
     * </ol>
     *
     * <p>
     * <strong>Cómo funciona la consulta.</strong> Es un <em>auto-join</em> de
     * {@code movie_genres}: {@code origen} son las filas de la película dada
     * (se localizan con la clave primaria, que empieza por {@code movie_id}) y
     * {@code candidata} las de cualquier otra película con el mismo
     * {@code genre_id} (índice {@code idx_movie_genres_genre_id}). Cada fila del
     * join es un género compartido, así que {@code COUNT(*)} por película es
     * exactamente el número de géneros en común: la clave primaria
     * {@code (movie_id, genre_id)} impide contar un género dos veces. Las
     * películas sin ningún género en común ni siquiera aparecen en el join.
     * </p>
     *
     * <p>
     * <strong>Por qué SQL nativo:</strong> es una agregación sobre la tabla de
     * unión, que en JPQL obliga a pasar por la entidad {@code Genre} y a repetir
     * la película de origen en subconsultas; en SQL se lee tal cual y su plan
     * ({@code EXPLAIN}) es el que se ha revisado. Solo usa SQL estándar
     * ({@code GROUP BY}, {@code ABS}, {@code FETCH FIRST ... ROWS ONLY}), así que
     * funciona igual en PostgreSQL y en H2. Las columnas de {@code movies} que
     * se usan en el {@code ORDER BY} se repiten en el {@code GROUP BY} porque
     * H2 no deduce que dependen del {@code id}.
     * </p>
     *
     * <p>
     * <strong>Nulos:</strong> {@code release_year} y {@code created_at} son
     * {@code NOT NULL} (V1, y también en las bases anteriores a Flyway), así que
     * {@code ABS} nunca recibe un nulo ni hace falta decidir dónde ordenarlos.
     * </p>
     *
     * <p>
     * Devuelve solo ids (no entidades) para que el {@code LIMIT} se aplique en
     * la base de datos sin interferir con la carga de géneros; las películas se
     * cargan después con {@link #findAllWithGenresByIdIn(Collection)}, que no
     * conserva el orden: hay que reconstruirlo a partir de esta lista.
     * </p>
     *
     * @param movieId identificador de la película de referencia (si no existe
     *                o no tiene géneros, el resultado es vacío)
     * @param limit   número máximo de ids devueltos (lo valida el servicio)
     * @return ids de las películas similares, en orden de relevancia
     */
    @Query(value = "SELECT candidata.movie_id"
            + " FROM movie_genres origen"
            + " JOIN movie_genres candidata"
            + "   ON candidata.genre_id = origen.genre_id"
            + "  AND candidata.movie_id <> origen.movie_id"
            + " JOIN movies pelicula_origen ON pelicula_origen.id = origen.movie_id"
            + " JOIN movies m ON m.id = candidata.movie_id"
            + " WHERE origen.movie_id = :movieId"
            + " GROUP BY candidata.movie_id, m.release_year, m.created_at, pelicula_origen.release_year"
            + " ORDER BY COUNT(*) DESC,"
            + "   ABS(m.release_year - pelicula_origen.release_year) ASC,"
            + "   m.created_at DESC,"
            + "   candidata.movie_id ASC"
            + " FETCH FIRST :limit ROWS ONLY", nativeQuery = true)
    List<Long> findSimilarMovieIds(@Param("movieId") Long movieId, @Param("limit") int limit);

    /**
     * Carga varias películas por id <strong>con sus géneros</strong> en una sola
     * consulta ({@code LEFT JOIN} a {@code movie_genres} y {@code genres}).
     *
     * <p>
     * Pensado para completar {@link #findSimilarMovieIds(Long, int)}: aquí no
     * hay paginación (el límite ya se aplicó al elegir los ids, como mucho unas
     * pocas decenas), así que el {@code JOIN FETCH} de la colección es seguro y
     * ahorra la segunda consulta que haría la carga por lotes
     * ({@code default_batch_fetch_size}). Hibernate 6 elimina solo los
     * duplicados de la entidad raíz que produce el join.
     * </p>
     *
     * <p>
     * <strong>No conserva el orden de {@code ids}</strong> ({@code IN} no
     * garantiza ninguno); los ids que no existan simplemente no aparecen.
     * </p>
     *
     * @param ids identificadores de las películas a cargar
     * @return las películas encontradas, con sus géneros ya inicializados
     */
    @EntityGraph(attributePaths = { "genres" })
    @Query("select m from Movie m where m.id in :ids")
    List<Movie> findAllWithGenresByIdIn(@Param("ids") Collection<Long> ids);

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
