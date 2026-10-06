package com.emilio.streambox.specification;

import org.springframework.data.jpa.domain.Specification;

import com.emilio.streambox.entity.Episode;
import com.emilio.streambox.entity.Genre;
import com.emilio.streambox.entity.Series;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Filtros dinámicos reutilizables para consultar series.
 *
 * <p>
 * Mismos criterios que {@link MovieSpecification} (título, género, año) más
 * {@link #hasEpisodes()}, que oculta las series vacías a los usuarios. El
 * servicio las combina con {@code Specification.allOf}.
 * </p>
 */
public final class SeriesSpecification {

    private SeriesSpecification() {
        // Clase de utilidad: no se instancia
    }

    /**
     * Series con al menos un episodio, es decir, <strong>visibles para los
     * usuarios</strong>.
     *
     * <p>
     * Se usa en el catálogo y la búsqueda públicos; el panel de
     * administración no la aplica porque necesita ver las series vacías para
     * poder subirles episodios.
     * </p>
     *
     * <p>
     * Es un {@code EXISTS} correlacionado y no un {@code JOIN} con
     * {@code episodes}: un {@code JOIN} devolvería una fila por episodio
     * (habría que añadir {@code DISTINCT}, y el recuento de la paginación
     * también se complicaría), mientras que el {@code EXISTS} solo pregunta
     * "¿hay alguno?" y la base de datos lo resuelve con el índice único de
     * {@code episodes}, que empieza por {@code series_id}. Es la misma
     * condición que {@code SeriesRepository.findVisibleById}.
     * </p>
     *
     * @return especificación "tiene episodios"
     */
    public static Specification<Series> hasEpisodes() {

        return (root, query, criteriaBuilder) -> {

            Subquery<Long> subquery = query.subquery(Long.class);
            Root<Episode> episode = subquery.from(Episode.class);

            subquery.select(episode.get("id"))
                    .where(criteriaBuilder.equal(episode.get("series"), root));

            return criteriaBuilder.exists(subquery);
        };
    }

    /**
     * Series cuyo título contiene el texto indicado, sin distinguir
     * mayúsculas y con los comodines de {@code LIKE} tratados como texto
     * literal (ver {@link LikePatterns}).
     *
     * @param title texto que debe contener el título
     * @return especificación de filtro por título
     */
    public static Specification<Series> hasTitle(String title) {

        return (root, query, criteriaBuilder) ->
                LikePatterns.containsIgnoreCase(criteriaBuilder, root.get("title"), title);
    }

    /**
     * Series que pertenecen al género indicado.
     *
     * <p>
     * Con {@code EXISTS} y no con {@code JOIN}, por el mismo motivo que
     * {@link MovieSpecification#hasGenre(Long)}: no duplica filas y la
     * paginación se resuelve en la base de datos.
     * </p>
     *
     * @param genreId identificador del género
     * @return especificación de filtro por género
     */
    public static Specification<Series> hasGenre(Long genreId) {

        return (root, query, criteriaBuilder) -> {

            Subquery<Long> subquery = query.subquery(Long.class);
            Root<Series> series = subquery.from(Series.class);
            Join<Series, Genre> genre = series.join("genres");

            subquery.select(series.get("id"))
                    .where(
                            criteriaBuilder.equal(series.get("id"), root.get("id")),
                            criteriaBuilder.equal(genre.get("id"), genreId));

            return criteriaBuilder.exists(subquery);
        };
    }

    /**
     * Series estrenadas en el año indicado.
     *
     * @param releaseYear año de estreno
     * @return especificación de filtro por año
     */
    public static Specification<Series> hasReleaseYear(Integer releaseYear) {

        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(
                root.get("releaseYear"),
                releaseYear);
    }
}
