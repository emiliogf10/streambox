package com.emilio.streambox.dto;

import java.time.LocalDateTime;

import com.emilio.streambox.entity.Role;

import lombok.Getter;
import lombok.Setter;

/**
 * DTO utilizado para representar la información de un usuario
 * que se devuelve al cliente.
 *
 * <p>Este DTO contiene únicamente la información que puede ser
 * expuesta mediante la API REST. La contraseña del usuario no se
 * incluye por motivos de seguridad.</p>
 */
@Getter
@Setter
public class UserResponse {

    /**
     * Identificador único del usuario.
     */
    private Long id;

    /**
     * Nombre de usuario.
     */
    private String username;

    /**
     * Dirección de correo electrónico del usuario.
     */
    private String email;

    /**
     * Rol asignado al usuario.
     *
     * <p>Determina el nivel de permisos que tiene el usuario
     * dentro de la aplicación.</p>
     */
    private Role role;

    /**
     * Fecha y hora en la que se creó el usuario.
     */
    private LocalDateTime createdAt;
}