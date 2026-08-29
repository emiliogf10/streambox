package com.emilio.streambox.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.emilio.streambox.entity.Genre;

/**
 * Repositorio encargado de proporcionar acceso a los datos de los géneros
 * almacenados en la base de datos.
 *
 * <p>
 * Extiende {@link JpaRepository}, por lo que Spring Data JPA proporciona
 * automáticamente las operaciones CRUD básicas para la entidad
 * {@link Genre}.
 * </p>
 */
public interface GenreRepository extends JpaRepository<Genre, Long> {

    /**
     * Busca un género mediante su nombre.
     *
     * <p>
     * Spring Data JPA genera automáticamente la consulta correspondiente
     * a partir del nombre del método.
     * </p>
     *
     * @param name nombre del género que se desea buscar
     * @return {@link Optional} que contiene el género encontrado o vacío
     *         si no existe ningún género con el nombre indicado
     */
    Optional<Genre> findByName(String name);
}