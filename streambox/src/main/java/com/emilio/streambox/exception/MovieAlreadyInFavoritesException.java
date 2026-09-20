package com.emilio.streambox.exception;

/**
 * Excepción utilizada cuando se intenta añadir a favoritos una película que
 * ya pertenece a la lista del usuario.
 */
public class MovieAlreadyInFavoritesException extends RuntimeException {

    /**
     * Crea una excepción con información sobre la película duplicada.
     *
     * @param message mensaje descriptivo del error
     */
    public MovieAlreadyInFavoritesException(String message) {
        super(message);
    }
}
