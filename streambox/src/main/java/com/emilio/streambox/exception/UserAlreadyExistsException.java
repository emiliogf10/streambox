package com.emilio.streambox.exception;

/**
 * Excepción lanzada cuando se intenta crear un usuario utilizando
 * un nombre de usuario o correo electrónico que ya está registrado.
 */
public class UserAlreadyExistsException extends RuntimeException {

    /**
     * Crea una excepción indicando que los datos del usuario
     * ya están registrados.
     *
     * @param message mensaje descriptivo del error
     */
    public UserAlreadyExistsException(String message) {
        super(message);
    }
}