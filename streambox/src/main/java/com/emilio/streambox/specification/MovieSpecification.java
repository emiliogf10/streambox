package com.emilio.streambox.specification;

import org.springframework.data.jpa.domain.Specification;

import com.emilio.streambox.entity.Movie;

/**
 * Clase encargada de construir las especificaciones utilizadas
 * para realizar búsquedas dinámicas de películas.
 *
 * <p>
 * Permite combinar diferentes filtros de forma opcional,
 * evitando tener que crear un método de repositorio para
 * cada combinación posible de criterios de búsqueda.
 * </p>
 */
public class MovieSpecification {

    /**
     * Constructor privado para evitar la creación de instancias.
     */
    private MovieSpecification() {
        // Evita instanciar la clase
    }

    /**
     * Crea una especificación para buscar películas cuyo título
     * contenga el texto indicado, ignorando mayúsculas y minúsculas.
     *
     * @param title texto que debe contener el título de la película
     * @return especificación para filtrar por título
     */
    public static Specification<Movie> hasTitle(String title) {

        return (root, query, criteriaBuilder) -> criteriaBuilder.like(
                criteriaBuilder.lower(root.get("title")),
                "%" + title.toLowerCase() + "%");
    }

    /**
     * Crea una especificación para buscar películas asociadas
     * a un género determinado.
     *
     * @param genreId identificador del género
     * @return especificación para filtrar por género
     */
    public static Specification<Movie> hasGenre(Long genreId) {

        return (root, query, criteriaBuilder) -> {

            query.distinct(true);

            return criteriaBuilder.equal(
                    root.join("genres").get("id"),
                    genreId);
        };
    }

    /**
     * Crea una especificación para buscar películas estrenadas
     * en un año determinado.
     *
     * @param releaseYear año de lanzamiento
     * @return especificación para filtrar por año
     */
    public static Specification<Movie> hasReleaseYear(Integer releaseYear) {

        return (root, query, criteriaBuilder) -> criteriaBuilder.equal(
                root.get("releaseYear"),
                releaseYear);
    }

    /**
     * Especificación que fuerza la carga de los géneros asociados
     * a cada película.
     *
     * <p>
     * Es necesaria para las consultas realizadas mediante
     * {@link org.springframework.data.jpa.repository.JpaSpecificationExecutor},
     * ya que dichas consultas no utilizan automáticamente el
     * {@code @EntityGraph} definido en los métodos estándar
     * del repositorio.
     * </p>
     *
     * @return especificación que realiza un fetch de los géneros
     */
    public static Specification<Movie> fetchGenres() {

        return (root, query, criteriaBuilder) -> {

            /*
             * Evitamos realizar el fetch cuando Hibernate está
             * construyendo una consulta de conteo.
             */
            if (query.getResultType() != Long.class
                    && query.getResultType() != long.class) {

                root.fetch("genres");
                query.distinct(true);
            }

            return criteriaBuilder.conjunction();
        };
    }
}