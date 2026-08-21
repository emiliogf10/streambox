package com.emilio.streambox.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.emilio.streambox.entity.Movie;

/**
 * Repositorio encargado de proporcionar acceso a los datos de las películas
 * almacenadas en la base de datos.
 *
 * <p>
 * Proporciona las operaciones CRUD básicas para la entidad {@link Movie}
 * y permite cargar explícitamente los géneros asociados a las películas.
 * </p>
 */
public interface MovieRepository
        extends JpaRepository<Movie, Long>, JpaSpecificationExecutor<Movie> {

    /**
     * Obtiene una película junto con sus géneros asociados.
     *
     * @param id identificador de la película
     * @return película encontrada junto con sus géneros
     */
    @Override
    @EntityGraph(attributePaths = { "genres" })
    Optional<Movie> findById(Long id);

    /**
     * Obtiene todas las películas junto con sus géneros asociados.
     *
     * @return lista de películas con sus géneros cargados
     */
    @Override
    @EntityGraph(attributePaths = { "genres" })
    List<Movie> findAll();

    /**
     * Busca películas cuyo título contenga el texto indicado,
     * ignorando diferencias entre mayúsculas y minúsculas.
     *
     * @param title texto que debe contener el título
     * @return películas que coinciden con el título indicado
     */
    @EntityGraph(attributePaths = { "genres" })
    List<Movie> findByTitleContainingIgnoreCase(String title);
}
