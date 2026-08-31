package com.emilio.streambox.exception;

/**
 * Excepción lanzada cuando las credenciales proporcionadas
 * durante la autenticación no son válidas.
 */
public class InvalidCredentialsException extends RuntimeException {

    /**
     * Crea una excepción indicando que las credenciales
     * proporcionadas no son correctas.
     *
     * @param message mensaje descriptivo del error
     */
    public InvalidCredentialsException(String message) {
        super(message);
    }
}