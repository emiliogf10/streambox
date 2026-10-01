package com.emilio.streambox.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.emilio.streambox.entity.Genre;

/**
 * Repositorio de acceso a datos de la entidad {@link Genre}.
 *
 * <p>
 * Las operaciones habituales ({@code findAll}, {@code findAllById},
 * {@code save}...) las proporciona {@link JpaRepository}. La unicidad del
 * nombre la garantiza la restricción {@code UNIQUE} de la base de datos.
 * </p>
 */
public interface GenreRepository extends JpaRepository<Genre, Long> {
}
