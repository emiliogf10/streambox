package com.emilio.streambox.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.emilio.streambox.entity.User;

/**
 * Repositorio encargado de proporcionar acceso a los datos de los usuarios
 * almacenados en la base de datos.
 *
 * <p>
 * Extiende {@link JpaRepository}, por lo que Spring Data JPA proporciona
 * automáticamente las operaciones CRUD básicas para la entidad
 * {@link User}.
 * </p>
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Busca un usuario mediante su dirección de correo electrónico.
     *
     * <p>
     * El resultado se devuelve mediante {@link Optional} para representar
     * explícitamente la posibilidad de que no exista ningún usuario con
     * el correo electrónico proporcionado.
     * </p>
     *
     * @param email dirección de correo electrónico que se desea buscar
     * @return {@link Optional} que contiene el usuario encontrado o vacío
     *         si no existe ningún usuario con ese correo electrónico
     */
    Optional<User> findByEmail(String email);

    /**
     * Comprueba si ya existe un usuario con el nombre de usuario indicado.
     *
     * <p>
     * Spring Data JPA genera automáticamente la consulta necesaria para
     * comprobar la existencia de un usuario cuyo nombre coincida con
     * el proporcionado.
     * </p>
     *
     * @param username nombre de usuario que se desea comprobar
     * @return {@code true} si existe un usuario con ese nombre de usuario,
     *         {@code false} en caso contrario
     */
    boolean existsByUsername(String username);

    /**
     * Comprueba si ya existe un usuario con la dirección de correo
     * electrónico indicada.
     *
     * <p>
     * Spring Data JPA genera automáticamente la consulta necesaria para
     * comprobar la existencia de un usuario cuyo correo electrónico
     * coincida con el proporcionado.
     * </p>
     *
     * @param email dirección de correo electrónico que se desea comprobar
     * @return {@code true} si existe un usuario con ese correo electrónico,
     *         {@code false} en caso contrario
     */
    boolean existsByEmail(String email);
}