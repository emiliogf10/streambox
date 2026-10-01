package com.emilio.streambox.specification;

import java.util.Locale;

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

    /** Carácter de escape usado en las búsquedas {@code LIKE}. */
    private static final char LIKE_ESCAPE = '\\';

    private MovieSpecification() {
        // Clase de utilidad: no se instancia
    }

    /**
     * Películas cuyo título contiene el texto indicado, sin distinguir
     * mayúsculas de minúsculas.
     *
     * <p>
     * Los comodines de {@code LIKE} ({@code %} y {@code _}) que escriba el
     * usuario se tratan como texto literal: buscar {@code "100%"} encuentra
     * títulos que contienen exactamente {@code 100%}, no cualquier título.
     * </p>
     *
     * @param title texto que debe contener el título
     * @return especificación de filtro por título
     */
    public static Specification<Movie> hasTitle(String title) {

        String pattern = "%" + escapeLike(title.toLowerCase(Locale.ROOT)) + "%";

        return (root, query, criteriaBuilder) -> criteriaBuilder.like(
                criteriaBuilder.lower(root.get("title")),
                pattern,
                LIKE_ESCAPE);
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

    /**
     * Escapa los caracteres con significado especial en {@code LIKE}.
     *
     * @param text texto introducido por el usuario
     * @return texto con {@code \}, {@code %} y {@code _} escapados
     */
    private static String escapeLike(String text) {

        return text
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
