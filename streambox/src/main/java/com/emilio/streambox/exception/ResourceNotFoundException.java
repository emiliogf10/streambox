package com.emilio.streambox.exception;

/**
 * Clase base de las excepciones que indican que un recurso solicitado
 * no existe (HTTP 404).
 *
 * <p>
 * Permite que {@link GlobalExceptionHandler} gestione todos los "no
 * encontrado" con un único handler en lugar de uno por cada recurso.
 * </p>
 */
public class ResourceNotFoundException extends RuntimeException {

    /**
     * Crea la excepción con un mensaje descriptivo.
     *
     * @param message mensaje que indica qué recurso no existe
     */
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
