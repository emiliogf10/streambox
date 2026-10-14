package com.emilio.streambox.specification;

import org.springframework.data.jpa.domain.Specification;

import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Movie;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Filtros dinámicos reutilizables para consultar películas.
 *
 * <p>
 * Cada método devuelve una {@link Specification} que representa una
 * condición. El servicio las combina con {@code and} según los filtros
 * que el cliente haya indicado.
 * </p>
 */
public final class MovieSpecification {

    private MovieSpecification() {
        // Clase de utilidad: no se instancia
    }

    /**
     * Películas cuyo título contiene el texto indicado, sin distinguir
     * mayúsculas ni acentos: "manana" encuentra "Mañana".
     *
     * <p>
     * Los comodines de {@code LIKE} ({@code %} y {@code _}) que escriba el
     * usuario se tratan como texto literal: buscar {@code "100%"} encuentra
     * títulos que contienen exactamente {@code 100%}, no cualquier título.
     * La normalización ({@code lower()} y quitar acentos) se hace en la base de
     * datos a los dos lados y coincide con la expresión del índice
     * {@code idx_movies_title_search}; los detalles y el porqué están en
     * {@link TitleSearch}, compartida con las series.
     * </p>
     *
     * @param title texto que debe contener el título
     * @return especificación de filtro por título
     */
    public static Specification<Movie> hasTitle(String title) {

        return (root, query, criteriaBuilder) ->
                TitleSearch.contains(criteriaBuilder, root.get("title"), title);
    }

    /**
     * Películas cuyo título <b>se parece</b> al texto indicado, tolerando
     * erratas pequeñas: "interstelar" encuentra "Interstellar". Solo en
     * PostgreSQL ({@code pg_trgm}); en H2 no encuentra nada.
     *
     * <p>
     * Pensada como <b>segundo intento</b>, cuando {@link #hasTitle(String)} no
     * encuentra nada: sumada siempre a la búsqueda exacta traería títulos
     * parecidos a una búsqueda bien escrita. Con menos de
     * {@value TitleSearch#TYPO_TOLERANCE_MIN_CHARACTERS} letras o dígitos no
     * encuentra nada (ver {@link TitleSearch#toleratesTypos(String)}).
     * </p>
     *
     * <p>
     * <b>Orden:</b> si la página se pide <b>sin</b> orden
     * ({@code PageRequest.of(page, size)}), ordena por parecido (el más parecido
     * primero) y desempata por {@code id}. Si la página trae orden propio,
     * Spring Data lo sustituye por ese.
     * </p>
     *
     * @param title texto buscado, posiblemente con erratas
     * @return especificación de filtro (y orden) por parecido del título
     */
    public static Specification<Movie> hasSimilarTitle(String title) {

        return (root, query, criteriaBuilder) -> {
            TitleSearch.orderBySimilarity(query, criteriaBuilder, root.get("title"), root.get("id"), title);
            return TitleSearch.resembles(criteriaBuilder, root.get("title"), title);
        };
    }

    /**
     * Películas que pertenecen al género indicado.
     *
     * <p>
     * Se implementa con una subconsulta {@code EXISTS} en lugar de un
     * {@code JOIN}: así la consulta principal no duplica filas (no hace falta
     * {@code DISTINCT}), no interfiere con la carga de géneros de la película
     * y la paginación se resuelve en la base de datos.
     * </p>
     *
     * @param genreId identificador del género
     * @return especificación de filtro por género
     */
    public static Specification<Movie> hasGenre(Long genreId) {

        return (root, query, criteriaBuilder) -> {

            Subquery<Long> subquery = query.subquery(Long.class);
            Root<Movie> movie = subquery.from(Movie.class);
            Join<Movie, Genre> genre = movie.join("genres");

            subquery.select(movie.get("id"))
                    .where(
                            criteriaBuilder.equal(movie.get("id"), root.get("id")),
                            criteriaBuilder.equal(genre.get("id"), genreId));

            return criteriaBuilder.exists(subquery);
        };
    }

    /**
     * Películas estrenadas en el año indicado.
     *
     * @param releaseYear año de estreno
     * @return especificación de filtro por año
     */
    public static Specification<Movie> hasReleaseYear(Integer releaseYear) {

        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(
                root.get("releaseYear"),
                releaseYear);
    }
}
