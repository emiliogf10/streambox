package com.emilio.streambox.exception;

/**
 * Excepción utilizada cuando no se encuentra un usuario con el
 * identificador solicitado.
 */
public class UserNotFoundException extends RuntimeException {

    /**
     * Crea una excepción indicando que el usuario no ha sido encontrado.
     *
     * @param message mensaje descriptivo del error
     */
    public UserNotFoundException(String message) {
        super(message);
    }
}
