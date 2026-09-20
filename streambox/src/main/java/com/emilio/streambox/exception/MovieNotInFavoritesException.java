package com.emilio.streambox.exception;

/**
 * Excepción utilizada cuando se intenta eliminar de favoritos una película
 * que no pertenece a la lista del usuario.
 */
public class MovieNotInFavoritesException extends RuntimeException {

    /**
     * Crea una excepción con información sobre la película ausente de la lista.
     *
     * @param message mensaje descriptivo del error
     */
    public MovieNotInFavoritesException(String message) {
        super(message);
    }
}
