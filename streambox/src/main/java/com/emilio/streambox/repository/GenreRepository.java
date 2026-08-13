package com.emilio.streambox.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.emilio.streambox.entity.Genre;

/**
 * Repositorio encargado de proporcionar acceso a los datos de los géneros
 * almacenados en la base de datos.
 *
 * <p>
 * Spring Data JPA proporciona automáticamente las operaciones CRUD
 * básicas para la entidad {@link Genre}.
 * </p>
 */
public interface GenreRepository extends JpaRepository<Genre, Long> {

    /**
     * Busca un género por su nombre.
     *
     * @param name nombre del género que se desea buscar
     * @return {@link Optional} que contiene el género si existe
     */
    Optional<Genre> findByName(String name);
}