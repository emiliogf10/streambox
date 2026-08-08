package com.emilio.streambox.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.emilio.streambox.entity.User;

/**
 * Repositorio para gestionar las operaciones de persistencia de la entidad
 * {@link User}.
 *
 * <p>Al extender {@link JpaRepository}, Spring Data JPA proporciona
 * automáticamente las operaciones CRUD básicas, como crear, consultar,
 * actualizar y eliminar usuarios.</p>
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Busca un usuario mediante su dirección de correo electrónico.
     *
     * <p>El resultado se devuelve mediante {@link Optional} para representar
     * explícitamente la posibilidad de que no exista ningún usuario con
     * el correo electrónico proporcionado.</p>
     *
     * @param email dirección de correo electrónico que se desea buscar
     * @return {@link Optional} que contiene el usuario encontrado o está
     *         vacío si no existe ningún usuario con ese correo electrónico
     */
    Optional<User> findByEmail(String email);
}