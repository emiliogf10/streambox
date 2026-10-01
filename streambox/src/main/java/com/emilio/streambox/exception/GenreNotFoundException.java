package com.emilio.streambox.exception;

/**
 * Excepción lanzada cuando no se encuentra un género
 * mediante el identificador solicitado.
 */
public class GenreNotFoundException extends ResourceNotFoundException {

    /**
     * Crea una excepción indicando que el género no existe.
     *
     * @param message mensaje descriptivo del error
     */
    public GenreNotFoundException(String message) {
        super(message);
    }
}