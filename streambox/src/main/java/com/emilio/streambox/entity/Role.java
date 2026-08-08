package com.emilio.streambox.entity;

/**
 * Define los roles disponibles para los usuarios de Streambox.
 *
 * <p>El rol de un usuario determina el nivel de permisos que tendrá
 * dentro de la aplicación.</p>
 */
public enum Role {

    /**
     * Rol correspondiente a un usuario normal de Streambox.
     */
    USER,

    /**
     * Rol correspondiente a un administrador de Streambox.
     */
    ADMIN
}