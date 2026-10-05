package com.emilio.streambox.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.emilio.streambox.entity.Genre;

/**
 * Repositorio de acceso a datos de la entidad {@link Genre}.
 *
 * <p>
 * Las operaciones habituales ({@code findAll}, {@code findAllById},
 * {@code save}...) las proporciona {@link JpaRepository}.
 * </p>
 *
 * <p>
 * <strong>Unicidad del nombre:</strong> el servicio la comprueba antes de
 * guardar con las consultas de abajo para responder con un error claro, pero
 * la garantía real es la restricción {@code UNIQUE} de la base de datos: dos
 * peticiones simultáneas pueden pasar la comprobación a la vez y solo la
 * restricción impide que ambas se guarden.
 * </p>
 */
public interface GenreRepository extends JpaRepository<Genre, Long> {

    /**
     * Indica si existe un género con ese nombre, sin distinguir mayúsculas.
     *
     * <p>
     * Se ignoran mayúsculas aunque los nombres nuevos se guarden ya
     * normalizados, porque puede haber filas anteriores a la normalización
     * (p. ej. {@code "Ciencia Ficción"}) que la restricción {@code UNIQUE},
     * que sí distingue mayúsculas, no detectaría como duplicadas de
     * {@code "Ciencia ficción"}.
     * </p>
     *
     * @param name nombre buscado
     * @return {@code true} si algún género tiene ese nombre
     */
    boolean existsByNameIgnoreCase(String name);

    /**
     * Indica si un género <em>distinto</em> del indicado tiene ese nombre, sin
     * distinguir mayúsculas.
     *
     * <p>
     * Lo usa la edición: renombrar un género a su propio nombre (o cambiar
     * solo sus mayúsculas) no es un duplicado.
     * </p>
     *
     * @param name nombre buscado
     * @param id   identificador del género que se está editando
     * @return {@code true} si otro género tiene ese nombre
     */
    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
