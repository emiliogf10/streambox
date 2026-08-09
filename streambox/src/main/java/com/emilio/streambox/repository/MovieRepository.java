package com.emilio.streambox.repository;

import com.emilio.streambox.entity.Movie;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repositorio encargado de proporcionar acceso a los datos de las películas
 * almacenadas en la base de datos.
 *
 * <p>Al extender {@link JpaRepository}, Spring Data JPA proporciona
 * automáticamente las operaciones CRUD básicas para la entidad
 * {@link Movie}, como guardar, buscar, actualizar y eliminar películas.</p>
 */
public interface MovieRepository extends JpaRepository<Movie, Long> {
}
