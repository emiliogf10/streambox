package com.emilio.streambox.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.emilio.streambox.entity.Movie;

/**
 * Repositorio encargado de proporcionar acceso a los datos de las películas
 * almacenadas en la base de datos.
 *
 * <p>
 * Extiende {@link JpaRepository} para disponer de las operaciones CRUD
 * proporcionadas por Spring Data JPA y {@link JpaSpecificationExecutor}
 * para permitir búsquedas mediante especificaciones dinámicas.
 * </p>
 *
 * <p>
 * Algunos métodos utilizan {@link EntityGraph} para cargar explícitamente
 * los géneros asociados a las películas y evitar problemas relacionados
 * con la carga diferida de la colección {@code genres}.
 * </p>
 */
public interface MovieRepository
                extends JpaRepository<Movie, Long>, JpaSpecificationExecutor<Movie> {

        /**
         * Obtiene una película mediante su identificador junto con sus
         * géneros asociados.
         *
         * <p>
         * La anotación {@link EntityGraph} fuerza la carga de la relación
         * {@code genres} durante la consulta.
         * </p>
         *
         * @param id identificador de la película que se desea obtener
         * @return {@link Optional} que contiene la película encontrada junto
         *         con sus géneros, o vacío si no existe
         */
        @Override
        @EntityGraph(attributePaths = { "genres" })
        Optional<Movie> findById(Long id);

        /**
         * Obtiene todas las películas almacenadas junto con sus géneros asociados.
         *
         * <p>
         * La relación {@code genres} se carga explícitamente mediante
         * {@link EntityGraph} para que pueda utilizarse posteriormente
         * fuera del contexto de persistencia.
         * </p>
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
         * <p>
         * Los géneros asociados a las películas encontradas se cargan
         * explícitamente mediante {@link EntityGraph}.
         * </p>
         *
         * @param title texto que debe estar contenido en el título
         * @return lista de películas cuyo título coincide con el criterio
         *         de búsqueda
         */
        @EntityGraph(attributePaths = { "genres" })
        List<Movie> findByTitleContainingIgnoreCase(String title);

        /**
         * Busca películas aplicando una especificación y devuelve los resultados
         * de forma paginada.
         *
         * <p>
         * La especificación permite construir consultas dinámicas combinando
         * diferentes criterios de búsqueda. La paginación y ordenación se
         * controlan mediante el objeto {@link Pageable}.
         * </p>
         *
         * <p>
         * Los géneros asociados se cargan explícitamente mediante
         * {@link EntityGraph} para evitar problemas de inicialización diferida
         * al convertir las películas en objetos DTO.
         * </p>
         *
         * @param specification criterios dinámicos utilizados para filtrar
         *                      las películas
         * @param pageable      configuración de paginación y ordenación
         * @return página de películas que cumplen los criterios indicados
         */
        @EntityGraph(attributePaths = { "genres" })
        Page<Movie> findAll(
                        Specification<Movie> specification,
                        Pageable pageable);
}
