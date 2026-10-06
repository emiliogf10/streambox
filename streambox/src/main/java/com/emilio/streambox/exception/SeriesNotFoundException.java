package com.emilio.streambox.exception;

/**
 * Excepción utilizada cuando no se encuentra una serie (HTTP 404,
 * {@code RESOURCE_NOT_FOUND}).
 *
 * <p>
 * Para los usuarios, una serie sin episodios también se trata como
 * inexistente: se lanza esta misma excepción con el mismo mensaje, de modo
 * que la respuesta no revela que la serie existe pero está vacía.
 * </p>
 */
public class SeriesNotFoundException extends ResourceNotFoundException {

    /**
     * Crea la excepción con un mensaje descriptivo.
     *
     * @param message mensaje descriptivo del error
     */
    public SeriesNotFoundException(String message) {
        super(message);
    }
}
