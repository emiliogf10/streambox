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

    // ------------------------------------------------------------------
    // «Me gusta» de películas (tabla user_liked_movies, V8)
    //
    // Mismo patrón que los favoritos de series (SeriesRepository): consultas
    // nativas sobre la tabla de unión, sin colección en User ni entidad propia.
    // Viven aquí porque el listado devuelve Movie (Spring Data solo convierte
    // una consulta nativa en entidades del tipo del propio repositorio). No
    // hace falta limpiar los «me gusta» antes de borrar una película: la clave
    // foránea tiene ON DELETE CASCADE en todas las bases (la tabla es nueva).
    // ------------------------------------------------------------------

    /**
     * Devuelve las películas que le gustan a un usuario, de la que le gustó más
     * recientemente a la más antigua.
     *
     * <p>
     * El orden es el de {@code user_liked_movies.created_at} (cuándo dio el «me
     * gusta», no cuándo se creó la película) y, a igual fecha, {@code id}
     * descendente, como el catálogo cuando se pide {@code createdAt desc}. Lo
     * resuelve el índice {@code idx_liked_movies_user_created_at}.
     * </p>
     *
     * <p>
     * Los géneros no se cargan aquí: se resuelven por lotes
     * ({@code default_batch_fetch_size}) al convertir a DTO dentro de la
     * transacción, como en «Mi lista».
     * </p>
     *
     * @param userId identificador del usuario
     * @return películas que le gustan, la más reciente primero
     */
    @Query(value = "SELECT m.* FROM movies m"
            + " JOIN user_liked_movies l ON l.movie_id = m.id"
            + " WHERE l.user_id = :userId"
            + " ORDER BY l.created_at DESC, m.id DESC", nativeQuery = true)
    List<Movie> findLikedByUserId(@Param("userId") Long userId);

    /**
     * Da «me gusta» a una película. <strong>Idempotente</strong>.
     *
     * <p>
     * {@code ON CONFLICT DO NOTHING}: si el usuario ya le había dado «me gusta»,
     * no hace nada (devuelve 0) y <em>conserva la fecha original</em>, así que
     * repetir la acción no la sube al principio de la lista. Además es seguro
     * ante dos peticiones simultáneas: en PostgreSQL la segunda espera a la
     * primera y no inserta, sin provocar una violación de la clave primaria
     * (que abortaría la transacción). La sintaxis sin columnas de conflicto es
     * la que también acepta H2 en modo PostgreSQL.
     * </p>
     *
     * <p>
     * Solo ignora el duplicado: si la película o el usuario no existen (p. ej.
     * se borró la película entre la comprobación del servicio y este
     * {@code INSERT}), la base de datos sigue lanzando la violación de clave
     * foránea ({@code 23503}; {@code 23506} en H2), que el servicio debe
     * traducir a 404.
     * </p>
     *
     * @param userId  identificador del usuario
     * @param movieId identificador de la película
     * @param likedAt instante del «me gusta» (el servicio lo toma de su {@code Clock})
     * @return filas insertadas: 1 si es nuevo, 0 si ya le gustaba
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "INSERT INTO user_liked_movies (user_id, movie_id, created_at)"
            + " VALUES (:userId, :movieId, :likedAt) ON CONFLICT DO NOTHING", nativeQuery = true)
    int addLike(@Param("userId") Long userId, @Param("movieId") Long movieId,
            @Param("likedAt") Instant likedAt);

    /**
     * Quita el «me gusta» de una película. Idempotente: si no le gustaba (o la
     * película no existe) no borra nada y no falla.
     *
     * @param userId  identificador del usuario
     * @param movieId identificador de la película
     * @return filas eliminadas: 1 si le gustaba, 0 si no
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM user_liked_movies WHERE user_id = :userId AND movie_id = :movieId",
            nativeQuery = true)
    int removeLike(@Param("userId") Long userId, @Param("movieId") Long movieId);

    /**
     * Devuelve los ids de las películas <strong>recomendadas</strong> a un
     * usuario según los géneros de lo que le gusta, ya ordenados de más a menos
     * recomendable.
     *
     * <p>
     * <strong>Criterio</strong> (primera versión, sin IA):
     * </p>
     * <ol>
     * <li>Cada género recibe un <em>peso</em>: cuántos de los títulos que le
     * gustan al usuario lo tienen. Cuentan sus «me gusta» de películas
     * <em>y</em> de series, porque el catálogo de géneros es común y un
     * usuario al que le gustan series de terror querrá ver películas de
     * terror. De las series solo cuentan las visibles (con episodios), para
     * que la recomendación se base exactamente en lo que el usuario ve en su
     * sección «Te gusta».</li>
     * <li>La puntuación de cada película candidata es la <strong>suma de los
     * pesos de sus géneros</strong>. Por tanto, una película que comparte dos
     * géneros con un título que te gusta suma por los dos (cuenta 2): cuantos
     * más géneros en común, más parecida, el mismo criterio que
     * {@link #findSimilarMovieIds(Long, int)}. Y un género presente en muchos
     * de tus «me gusta» pesa más que uno que solo aparece en uno.</li>
     * <li>Se excluyen las películas que ya le gustan. Las que solo tienen
     * géneros que no aparecen en sus «me gusta» no son candidatas.</li>
     * <li>Desempate: {@code created_at} descendente (lo más nuevo primero) y
     * {@code id} descendente, como el catálogo con {@code createdAt desc}; el
     * orden es total, así que la respuesta no cambia entre llamadas.</li>
     * </ol>
     *
     * <p>
     * <strong>Cómo funciona la consulta.</strong> La subconsulta {@code pesos}
     * reúne con {@code UNION ALL} una fila por cada pareja («me gusta»,
     * género) de películas y de series (clave primaria de las tablas de unión)
     * y la agrupa por género. Después se une con {@code movie_genres} por
     * {@code genre_id} (índice {@code idx_movie_genres_genre_id}): cada fila del
     * join es un género que la candidata comparte, así que {@code SUM(peso)}
     * por película es la puntuación. El {@code NOT EXISTS} usa la clave primaria
     * {@code (user_id, movie_id)} de {@code user_liked_movies}. Sin ningún «me
     * gusta», {@code pesos} está vacía y el resultado también.
     * </p>
     *
     * <p>
     * SQL nativo y estándar (tabla derivada, {@code UNION ALL},
     * {@code GROUP BY}, {@code FETCH FIRST}) por lo mismo que
     * {@link #findSimilarMovieIds(Long, int)}: es una agregación sobre tablas de
     * unión que en JPQL no se expresa sin entidades para ellas. Devuelve solo
     * ids para que el {@code LIMIT} se aplique en la base de datos; las
     * películas se cargan después con
     * {@link #findAllWithGenresByIdIn(Collection)} (que no conserva el orden:
     * hay que reconstruirlo con esta lista). En total, dos consultas.
     * </p>
     *
     * @param userId identificador del usuario
     * @param limit  número máximo de ids devueltos (lo valida el servicio)
     * @return ids de las películas recomendadas, en orden; vacío si no tiene
     *         «me gusta» o no hay candidatas
     */
    @Query(value = "SELECT mg.movie_id"
            + " FROM (" + LIKED_GENRE_WEIGHTS + ") pesos"
            + " JOIN movie_genres mg ON mg.genre_id = pesos.genre_id"
            + " JOIN movies m ON m.id = mg.movie_id"
            + " WHERE NOT EXISTS (SELECT 1 FROM user_liked_movies ya"
            + "   WHERE ya.user_id = :userId AND ya.movie_id = mg.movie_id)"
            + " GROUP BY mg.movie_id, m.created_at"
            + " ORDER BY SUM(pesos.peso) DESC, m.created_at DESC, mg.movie_id DESC"
            + " FETCH FIRST :limit ROWS ONLY", nativeQuery = true)
    List<Long> findRecommendedMovieIds(@Param("userId") Long userId, @Param("limit") int limit);

    /**
     * Subconsulta común a las recomendaciones de películas y de series
     * ({@code SeriesRepository.findRecommendedSeriesIds}): una fila por género
     * de los títulos que le gustan al usuario {@code :userId}, con su
     * {@code peso} (cuántos de esos títulos lo tienen). Las series que le
     * gustan pero están ocultas (sin episodios) no cuentan: el {@code EXISTS}
     * se resuelve con el índice único de {@code episodes}, que empieza por
     * {@code series_id}.
     *
     * <p>
     * Es una constante para que las dos recomendaciones no puedan divergir en
     * cómo pesan los géneros.
     * </p>
     */
    String LIKED_GENRE_WEIGHTS = "SELECT gustos.genre_id, COUNT(*) AS peso FROM ("
            + " SELECT g.genre_id FROM user_liked_movies l"
            + "   JOIN movie_genres g ON g.movie_id = l.movie_id"
            + "  WHERE l.user_id = :userId"
            + " UNION ALL"
            + " SELECT g.genre_id FROM user_liked_series l"
            + "   JOIN series_genres g ON g.series_id = l.series_id"
            + "  WHERE l.user_id = :userId"
            + "    AND EXISTS (SELECT 1 FROM episodes e WHERE e.series_id = l.series_id)"
            + " ) gustos GROUP BY gustos.genre_id";
}
